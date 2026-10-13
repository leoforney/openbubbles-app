package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.AttachmentEntity
import com.bluebubbles.messaging.objectbox.AttachmentEntity_
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.ChatEntity_
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.services.rustpush.APNClient
import com.bluebubbles.messaging.services.rustpush.APNService
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import uniffi.rust_lib_bluebubbles.NativeAttachment
import uniffi.rust_lib_bluebubbles.NativeConversationData
import uniffi.rust_lib_bluebubbles.NativeIndexedMessagePart
import uniffi.rust_lib_bluebubbles.NativeMessage
import uniffi.rust_lib_bluebubbles.NativeMessagePart
import uniffi.rust_lib_bluebubbles.NativeMessageType
import uniffi.rust_lib_bluebubbles.NativeMsgException
import uniffi.rust_lib_bluebubbles.NativeNormalMessage
import uniffi.rust_lib_bluebubbles.NativeProgressCallback
import uniffi.rust_lib_bluebubbles.NativeReactMessageType
import uniffi.rust_lib_bluebubbles.NativeReactMessage
import uniffi.rust_lib_bluebubbles.NativeReaction
import uniffi.rust_lib_bluebubbles.NativePushState
import uniffi.rust_lib_bluebubbles.NativeTextFormat
import uniffi.rust_lib_bluebubbles.attachmentFromPlist
import uniffi.rust_lib_bluebubbles.attachmentToPlist
import uniffi.rust_lib_bluebubbles.newMessageInst
import java.io.File
import java.net.URLConnection
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Native outgoing message pipeline (SERVICE_TRANSITION.md §7).
 *
 * The database is the queue. A message is written immediately in Dart's "pending" form, and
 * [SendMessageWorker] sends it. Each chat has its own ordered WorkManager chain, so sends survive
 * process death and stay in order.
 *
 * Message states follow Dart's conventions exactly, because the Flutter UI renders the same rows:
 * - pending: guid = "temp-xxxxxxxx", stagingGuid = the final rustpush message id, which is generated
 *   up front. Dart's `Message.findOne(guid)` also matches stagingGuid, so a delivery receipt that
 *   arrives before the send finishes still finds the message.
 * - sent:    guid = rustpush id, stagingGuid = null.
 * - failed:  guid = "error-<reason>…", errorVal = 1001 (MessageError.BAD_REQUEST); stagingGuid is kept
 *   so a retry reuses the same rustpush id.
 *
 * Retries always reuse the same rustpush id. If a send reached Apple but the result was never
 * recorded (crash), the resend has the same id and recipients deduplicate it.
 */
object OutgoingMessageQueue {
    private const val TAG = "OutgoingMessageQueue"

    /** `MessageError.BAD_REQUEST.code` in lib/helpers/types/extensions/extensions.dart */
    const val ERROR_BAD_REQUEST = 1001L

    /** Retryable failures (network, timeouts, service not ready) before a message is marked failed. */
    const val MAX_ATTEMPTS = 8

    private const val SERVICE_WAIT_MS = 30_000L

    /** Max wait for Apple to confirm a send before retrying (uploads happen before this starts). */
    private const val SEND_TIMEOUT_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatLocks = ConcurrentHashMap<Long, Mutex>()

    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()
    private val attachmentBox get() = ObjectBox.store.boxFor<AttachmentEntity>()
    private val chatBox get() = ObjectBox.store.boxFor<ChatEntity>()

    enum class Outcome { SENT, FAILED, RETRY, NOTHING_TO_DO }

    /** What a message being sent replies to (inline reply / thread). */
    data class ReplyTarget(val guid: String, val part: Int = 0, val textLength: Int = 0)

    /**
     * Queues a message from the UI. All attachments and the text go into ONE iMessage, which
     * iPhones show as a stack/grid with the caption below (Flutter used to send one message per
     * attachment). Returns immediately; the row appears in the database (and UI) right away.
     */
    fun send(context: Context, chatId: Long, text: String, attachmentPaths: List<String>,
             replyTo: ReplyTarget? = null, voice: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                enqueue(appContext, chatId, text, attachmentPaths, replyTo, voice)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue message for chat $chatId", e)
            }
        }
    }

    suspend fun enqueue(context: Context, chatId: Long, text: String, attachmentPaths: List<String>,
                        replyTo: ReplyTarget? = null, voice: Boolean = false): List<Long> = withContext(Dispatchers.IO) {
        val chat = chatBox.get(chatId) ?: run {
            Log.e(TAG, "Cannot send: chat $chatId not found")
            return@withContext emptyList()
        }
        val smsChat = chat.isRpSms == true // SMS relay was removed; these sends fail visibly below

        val attachmentsDir = File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments")
        val attachments = attachmentPaths.mapNotNull { path ->
            val source = File(path)
            if (!source.isFile) {
                Log.e(TAG, "Skipping missing attachment $path")
                return@mapNotNull null
            }
            val attGuid = newTempGuid()
            val name = source.name.replace("/", "_").replace("\\", "_")
            val dest = File(File(attachmentsDir, attGuid), name)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            val mime = mimeFor(name)
            AttachmentEntity(
                guid = attGuid,
                transferName = name,
                mimeType = mime,
                uti = utiFor(mime),
                totalBytes = dest.length(),
                isOutgoing = true,
                hasLivePhoto = false,
            )
        }
        if (attachments.isEmpty() && text.isBlank()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val id = insertPending(chat, now, attachments) { msg ->
            msg.text = text.takeIf { it.isNotBlank() }
            if (replyTo != null) {
                msg.threadOriginatorGuid = replyTo.guid
                msg.threadOriginatorPart = "${replyTo.part}:0:${replyTo.textLength}"
            }
            if (voice) msg.itemType = 0 // voice flag is derived from the attachment when sending
        }
        if (smsChat) {
            markFailed(id, "SMS forwarding is no longer supported")
            return@withContext listOf(id)
        }
        if (voice) voiceMessages.add(id)
        // Like Dart's Chat.addMessage for a message from me: the chat is no longer unread.
        ObjectBox.update<ChatEntity>(chat.id) {
            it.dbOnlyLatestMessageDate = Date(now)
            it.hasUnreadMessage = false
        }
        SendMessageWorker.enqueue(context, chat.id, id)
        ShareTargets.reportUsed(context, chat.id)
        listOf(id)
    }

    /**
     * Sends (or removes) a tapback. [reaction] is a ReactionTypes value ("love", "like", ...) or
     * any emoji. Stored like Dart's reflected reaction so both UIs render it right away.
     */
    fun react(context: Context, chatId: Long, targetGuid: String, reaction: String, remove: Boolean, targetPart: Int = 0) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val chat = chatBox.get(chatId) ?: return@launch
                if (chat.isRpSms == true) return@launch // SMS relay was removed
                val standard = reaction in STANDARD_REACTIONS
                val type = if (standard) reaction else "emoji"
                val now = System.currentTimeMillis()
                val id = insertPending(chat, now, emptyList()) { msg ->
                    msg.associatedMessageGuid = targetGuid
                    msg.associatedMessagePart = targetPart.toLong()
                    msg.associatedMessageType = if (remove) "-$type" else type
                    msg.associatedMessageEmoji = if (standard) null else reaction
                }
                SendMessageWorker.enqueue(appContext, chat.id, id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue reaction for $targetGuid", e)
            }
        }
    }

    private val STANDARD_REACTIONS = setOf("love", "like", "dislike", "laugh", "emphasize", "question")
    private val voiceMessages = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    private fun insertPending(chat: ChatEntity, dateMs: Long, attachments: List<AttachmentEntity>, configure: (MessageEntity) -> Unit): Long {
        return ObjectBox.store.callInTx {
            val msg = MessageEntity(
                guid = newTempGuid(),
                stagingGuid = UUID.randomUUID().toString().uppercase(),
                isFromMe = true,
                handleId = 0,
                dateCreated = Date(dateMs),
                itemType = 0,
                hasDdResults = false,
                hasAttachments = attachments.isNotEmpty(),
                hasReactions = false,
                hasApplePayloadData = false,
                wasDeliveredQuietly = false,
                didNotifyRecipient = false,
                isBookmarked = false,
                hasBeenForwarded = false,
                verificationFailed = false,
                isDelivered = false,
                errorVal = 0,
                groupActionType = 0,
                ckSyncState = false,
            )
            configure(msg)
            msg.chatId.target = chat
            val id = messageBox.put(msg)
            for (att in attachments) {
                att.messageId.targetId = id
                attachmentBox.put(att)
            }
            id
        }
    }

    /** Sends one pending message. Called by [SendMessageWorker]; safe to call more than once. */
    suspend fun sendPending(context: Context, messageId: Long, attempt: Int): Outcome = withContext(Dispatchers.IO) {
        val initial = messageBox.get(messageId) ?: return@withContext Outcome.NOTHING_TO_DO
        val chatId = initial.chatId.targetId
        chatLocks.getOrPut(chatId) { Mutex() }.withLock {
            // Re-read under the lock; another run may have finished it.
            val msg = messageBox.get(messageId) ?: return@withLock Outcome.NOTHING_TO_DO
            val guid = msg.guid ?: return@withLock Outcome.NOTHING_TO_DO
            if (!guid.startsWith("temp")) return@withLock Outcome.NOTHING_TO_DO
            val rustId = msg.stagingGuid ?: return@withLock markFailed(messageId, "Missing message id")
            val chat = chatBox.get(chatId) ?: return@withLock markFailed(messageId, "Chat not found")

            val state = awaitPushState(context)
                ?: return@withLock retryOrFail(messageId, attempt, "Messaging service not ready")

            try {
                val handles = state.getHandles()
                if (handles.isEmpty()) return@withLock retryOrFail(messageId, attempt, "No registered handles")
                val sender = ChatResolver.ensureHandle(context, chat, handles)
                val conversation = ChatResolver.conversationData(context, chat, handles, before = msg.dateCreated ?: java.util.Date(), excludeMessageId = msg.id)
                val profile = ProfileSharing.forChat(context, chat)

                val message: NativeMessage
                var sentParts: List<NativeIndexedMessagePart> = emptyList()
                var linkPayload: String? = null
                val reactionType = msg.associatedMessageType
                val reactionTarget = msg.associatedMessageGuid
                if (reactionType != null && reactionTarget != null) {
                    val remove = reactionType.startsWith("-")
                    val base = reactionType.removePrefix("-")
                    val reaction = when (base) {
                        "love" -> NativeReaction.Heart
                        "like" -> NativeReaction.Like
                        "dislike" -> NativeReaction.Dislike
                        "laugh" -> NativeReaction.Laugh
                        "emphasize" -> NativeReaction.Emphasize
                        "question" -> NativeReaction.Question
                        else -> NativeReaction.Emoji(msg.associatedMessageEmoji ?: return@withLock markFailed(messageId, "Missing emoji"))
                    }
                    val targetText = messageBox.query { apply(MessageEntity_.guid.equal(reactionTarget)) }.findFirst()?.text ?: ""
                    message = NativeMessage.React(NativeReactMessage(
                        toUuid = reactionTarget,
                        toPart = (msg.associatedMessagePart ?: 0L).toULong(),
                        reaction = NativeReactMessageType.React(reaction, !remove),
                        toText = targetText,
                        embeddedProfile = profile,
                    ))
                } else {
                    val parts = mutableListOf<NativeIndexedMessagePart>()
                    val attachments = attachmentBox.query {
                        apply(AttachmentEntity_.messageIdId.equal(messageId))
                        order(AttachmentEntity_.id)
                    }.find()
                    for (att in attachments) {
                        val uploaded = uploadedAttachment(att) ?: upload(context, state, att)
                        parts += NativeIndexedMessagePart(NativeMessagePart.Attachment(uploaded), null, null)
                    }
                    msg.text?.takeIf { it.isNotEmpty() }?.let {
                        parts += NativeIndexedMessagePart(NativeMessagePart.Text(it, NativeTextFormat.Flags(false, false, false, false)), null, null)
                    }
                    if (parts.isEmpty()) return@withLock markFailed(messageId, "Empty message")
                    val link = if (attachments.isEmpty()) msg.text?.let { LinkPreviews.build(it) } else null
                    linkPayload = link?.let { MessageFormats.linkPayload(it) }
                    sentParts = parts
                    message = NativeMessage.Message(NativeNormalMessage(
                        parts = parts,
                        effect = msg.expressiveSendStyleId,
                        replyGuid = msg.threadOriginatorGuid,
                        replyPart = if (msg.threadOriginatorGuid != null) msg.threadOriginatorPart else null,
                        service = NativeMessageType.IMessage,
                        subject = msg.subject?.takeIf { it.isNotEmpty() },
                        app = null,
                        linkMeta = link,
                        voice = messageId in voiceMessages || attachments.singleOrNull()?.transferName?.startsWith(VOICE_PREFIX) == true,
                        scheduled = null,
                        embeddedProfile = profile,
                    ))
                }
                val inst = newMessageInst(conversation, sender, message)
                inst.id = rustId
                // Never let one unacknowledged send block the chat's queue: give up after
                // SEND_TIMEOUT_MS and retry with the same id (recipients deduplicate by id). The
                // abandoned attempt may still complete in Rust; that is harmless for the same reason.
                val sent = withTimeoutOrNull(SEND_TIMEOUT_MS) { state.send(inst) }
                    ?: return@withLock retryOrFail(messageId, attempt, "Apple did not confirm the send in time")
                voiceMessages.remove(messageId)
                markSent(context, messageId, rustId, sent.sentTimestamp.toLong(), sentParts, linkPayload)
                Outcome.SENT
            } catch (e: NativeMsgException.Retryable) {
                Log.w(TAG, "Retryable send failure for $rustId (attempt $attempt)", e)
                retryOrFail(messageId, attempt, e.msg)
            } catch (e: NativeMsgException.Permanent) {
                Log.e(TAG, "Send failed for $rustId", e)
                markFailed(messageId, e.msg)
            } catch (e: NativeMsgException.Invalid) {
                Log.e(TAG, "Invalid message $rustId", e)
                markFailed(messageId, e.msg)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected send failure for $rustId", e)
                markFailed(messageId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Voice notes are recorded under this file name prefix (see VoiceRecorder). */
    const val VOICE_PREFIX = "Audio Message"

    private fun mimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".heif") -> "image/heif"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".caf") -> "audio/x-caf"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }
    }

    /** Apple UTIs for common types; Apple clients use these to decide how to render attachments. */
    private fun utiFor(mime: String): String = when {
        mime == "image/jpeg" -> "public.jpeg"
        mime == "image/png" -> "public.png"
        mime == "image/gif" -> "com.compuserve.gif"
        mime == "image/heic" -> "public.heic"
        mime == "image/heif" -> "public.heif"
        mime == "image/webp" -> "org.webmproject.webp"
        mime == "video/mp4" -> "public.mpeg-4"
        mime == "video/quicktime" -> "com.apple.quicktime-movie"
        mime == "audio/mp4" -> "com.apple.m4a-audio"
        mime == "audio/x-caf" -> "com.apple.coreaudio-format"
        mime == "audio/mpeg" -> "public.mp3"
        mime == "application/pdf" -> "com.adobe.pdf"
        mime.startsWith("text/") -> "public.plain-text"
        else -> "public.data"
    }

    // --- attachments -----------------------------------------------------------------------------

    private fun attachmentFile(context: Context, att: AttachmentEntity): File {
        val dir = File(File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments"), att.guid ?: "")
        val named = File(dir, att.transferName ?: "")
        return if (named.isFile) named else dir.listFiles()?.firstOrNull { it.isFile } ?: named
    }

    /** Reuses an upload from a previous attempt (stored in metadata like Dart does). */
    private fun uploadedAttachment(att: AttachmentEntity): NativeAttachment? {
        val plist = att.dbMetadata?.let { runCatching { JSONObject(it).optString("rustpush", "") }.getOrNull() }
        if (plist.isNullOrEmpty()) return null
        return runCatching { attachmentFromPlist(plist) }.getOrNull()
    }

    private suspend fun upload(context: Context, state: NativePushState, att: AttachmentEntity): NativeAttachment {
        val file = attachmentFile(context, att)
        val uploaded = state.uploadAttachment(
            file.absolutePath,
            att.mimeType ?: "application/octet-stream",
            att.uti ?: "public.data", // same fallback as Dart's sendAttachment
            att.transferName ?: file.name,
            object : NativeProgressCallback {
                override fun onProgress(progress: ULong, total: ULong) {}
            },
        )
        val plist = attachmentToPlist(uploaded)
        ObjectBox.update<AttachmentEntity>(att.id) { a ->
            val meta = a.dbMetadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            meta.put("rustpush", plist)
            a.dbMetadata = meta.toString()
        }
        return uploaded
    }

    // --- state transitions -------------------------------------------------------------------

    private fun markSent(context: Context, messageId: Long, rustId: String, sentTimestampMs: Long,
                         parts: List<NativeIndexedMessagePart>, linkPayload: String?) {
        // Like Dart's reflectMessageDyn/replaceAttachment: attachment i gets guid "<id>_<i>" and its
        // folder is renamed to match. Renames happen before the transaction (filesystem first).
        val root = File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments")
        val attachments = attachmentBox.query {
            apply(AttachmentEntity_.messageIdId.equal(messageId))
            order(AttachmentEntity_.id)
        }.find()
        val newGuids = HashMap<Long, String>()
        attachments.forEachIndexed { i, att ->
            val old = att.guid ?: return@forEachIndexed
            if (!old.startsWith("temp")) return@forEachIndexed
            val target = "${rustId}_$i"
            val from = File(root, old)
            val to = File(root, target)
            if (from.isDirectory && !to.exists() && !from.renameTo(to)) {
                Log.w(TAG, "Could not rename attachment folder ${from.name} -> ${to.name}")
                return@forEachIndexed
            }
            newGuids[att.id] = target
        }
        val body = if (parts.isNotEmpty()) MessageFormats.partsToBody(parts, rustId, null) else null
        ObjectBox.store.runInTx {
            val msg = messageBox.get(messageId) ?: return@runInTx
            msg.guid = rustId
            msg.stagingGuid = null
            msg.errorVal = 0
            if (sentTimestampMs > 0) msg.dateCreated = Date(sentTimestampMs)
            if (body != null) msg.dbAttributedBody = MessageFormats.bodyList(body.attributedBody)
            if (linkPayload != null) {
                msg.balloonBundleId = MessageFormats.URL_BALLOON
                msg.dbPayloadData = linkPayload
            }
            messageBox.put(msg)
            for ((attId, newGuid) in newGuids) {
                attachmentBox.get(attId)?.let { a ->
                    a.guid = newGuid
                    attachmentBox.put(a)
                }
            }
        }
        Log.i(TAG, "Sent $rustId (${attachments.size} attachment(s))")
        guardAgainstStaleOverwrite(messageId, rustId)
    }

    /**
     * Dart's Message.save() is a read-then-put outside a transaction. A delivery receipt handled
     * by Dart in the same instant can write back the old "temp" row and undo [markSent]. Check
     * again shortly after and re-apply if that happened.
     */
    private fun guardAgainstStaleOverwrite(messageId: Long, rustId: String) {
        scope.launch {
            delay(3_000)
            ObjectBox.store.runInTx {
                val msg = messageBox.get(messageId) ?: return@runInTx
                if (msg.guid?.startsWith("temp") == true && msg.stagingGuid == rustId) {
                    Log.w(TAG, "Re-applying sent state for $rustId after a concurrent overwrite")
                    msg.guid = rustId
                    msg.stagingGuid = null
                    msg.errorVal = 0
                    messageBox.put(msg)
                }
            }
        }
    }

    private fun retryOrFail(messageId: Long, attempt: Int, reason: String): Outcome {
        if (attempt + 1 >= MAX_ATTEMPTS) return markFailed(messageId, reason)
        Log.w(TAG, "Message $messageId not sent yet (attempt ${attempt + 1}/$MAX_ATTEMPTS): $reason; retrying")
        return Outcome.RETRY
    }

    /** Same shape as Dart's handleSendError: "temp" in the guid becomes "error-<reason>". */
    private fun markFailed(messageId: Long, reason: String): Outcome {
        val cleanReason = reason.replace(Regex("\\s+"), " ").take(200)
        ObjectBox.store.runInTx {
            val msg = messageBox.get(messageId) ?: return@runInTx
            val guid = msg.guid ?: return@runInTx
            if (!guid.startsWith("temp")) return@runInTx
            msg.guid = guid.replaceFirst("temp", "error-$cleanReason")
            msg.errorVal = ERROR_BAD_REQUEST
            messageBox.put(msg)
        }
        Log.e(TAG, "Message $messageId failed: $cleanReason")
        return Outcome.FAILED
    }

    // --- misc --------------------------------------------------------------------------------

    private suspend fun awaitPushState(context: Context): NativePushState? {
        APNService.instance?.pushState?.let { return it }
        if (APNService.instance == null) {
            try {
                withContext(Dispatchers.Main) {
                    val client = APNClient(context.applicationContext)
                    client.bind { client.destroy() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the messaging service", e)
            }
        }
        return withTimeoutOrNull(SERVICE_WAIT_MS) {
            var state = APNService.instance?.pushState
            while (state == null) {
                delay(250)
                state = APNService.instance?.pushState
            }
            state
        }
    }

    fun findChatIdByGuid(chatGuid: String): Long? =
        chatBox.query { apply(ChatEntity_.guid.equal(chatGuid)) }.findFirst()?.id

    private fun newTempGuid(): String = "temp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
}
