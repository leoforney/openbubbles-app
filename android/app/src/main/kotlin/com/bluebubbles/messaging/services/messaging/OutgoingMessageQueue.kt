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
import com.bluebubbles.messaging.services.backend_ui_interop.MessageSender
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatLocks = ConcurrentHashMap<Long, Mutex>()

    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()
    private val attachmentBox get() = ObjectBox.store.boxFor<AttachmentEntity>()
    private val chatBox get() = ObjectBox.store.boxFor<ChatEntity>()

    enum class Outcome { SENT, FAILED, RETRY, NOTHING_TO_DO }

    /**
     * Queues a message from the UI. Like Dart, each attachment is sent as its own message, followed
     * by the text. Returns immediately; the rows appear in the database (and UI) right away.
     */
    fun send(context: Context, chatId: Long, text: String, attachmentPaths: List<String>) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                enqueue(appContext, chatId, text, attachmentPaths)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue message for chat $chatId", e)
            }
        }
    }

    suspend fun enqueue(context: Context, chatId: Long, text: String, attachmentPaths: List<String>): List<Long> = withContext(Dispatchers.IO) {
        val chat = chatBox.get(chatId) ?: run {
            Log.e(TAG, "Cannot send: chat $chatId not found")
            return@withContext emptyList()
        }
        if (chat.isRpSms == true) {
            // SMS forwarding is not ported yet; keep using the Dart pipeline for these chats.
            MessageSender.sendMessage(context, chatId, text, attachmentPaths)
            return@withContext emptyList()
        }

        val attachmentsDir = File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments")
        val now = System.currentTimeMillis()
        val created = mutableListOf<Long>()

        attachmentPaths.forEachIndexed { i, path ->
            val source = File(path)
            if (!source.isFile) {
                Log.e(TAG, "Skipping missing attachment $path")
                return@forEachIndexed
            }
            val attGuid = newTempGuid()
            val name = source.name.replace("/", "_").replace("\\", "_")
            val dest = File(File(attachmentsDir, attGuid), name)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)

            created += insertPending(chat, text = null, dateMs = now + i, attachment = AttachmentEntity(
                guid = attGuid,
                transferName = name,
                mimeType = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                totalBytes = dest.length(),
                isOutgoing = true,
                hasLivePhoto = false,
            ))
        }
        if (text.isNotBlank()) {
            created += insertPending(chat, text = text, dateMs = now + attachmentPaths.size, attachment = null)
        }

        ObjectBox.update<ChatEntity>(chat.id) { it.dbOnlyLatestMessageDate = Date(now + attachmentPaths.size) }

        for (id in created) SendMessageWorker.enqueue(context, chat.id, id)
        created
    }

    private fun insertPending(chat: ChatEntity, text: String?, dateMs: Long, attachment: AttachmentEntity?): Long {
        return ObjectBox.store.callInTx {
            val msg = MessageEntity(
                guid = newTempGuid(),
                stagingGuid = UUID.randomUUID().toString().uppercase(),
                text = text,
                isFromMe = true,
                handleId = 0,
                dateCreated = Date(dateMs),
                itemType = 0,
                hasDdResults = false,
                hasAttachments = attachment != null,
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
            msg.chatId.target = chat
            val id = messageBox.put(msg)
            if (attachment != null) {
                attachment.messageId.targetId = id
                attachmentBox.put(attachment)
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

                val parts = mutableListOf<NativeIndexedMessagePart>()
                val attachment = attachmentBox.query { apply(AttachmentEntity_.messageIdId.equal(messageId)) }.findFirst()
                if (attachment != null) {
                    val uploaded = uploadedAttachment(attachment) ?: upload(context, state, attachment)
                    parts += NativeIndexedMessagePart(NativeMessagePart.Attachment(uploaded), null, null)
                }
                msg.text?.takeIf { it.isNotEmpty() }?.let {
                    parts += NativeIndexedMessagePart(NativeMessagePart.Text(it, NativeTextFormat.Flags(false, false, false, false)), null, null)
                }
                if (parts.isEmpty()) return@withLock markFailed(messageId, "Empty message")

                val normal = NativeNormalMessage(
                    parts = parts,
                    effect = msg.expressiveSendStyleId,
                    replyGuid = msg.threadOriginatorGuid,
                    replyPart = if (msg.threadOriginatorGuid != null) msg.threadOriginatorPart else null,
                    service = NativeMessageType.IMessage,
                    subject = msg.subject?.takeIf { it.isNotEmpty() },
                    app = null,
                    linkMeta = null,
                    voice = false,
                    scheduled = null,
                    embeddedProfile = null,
                )
                val inst = newMessageInst(conversation, sender, NativeMessage.Message(normal))
                inst.id = rustId
                val sent = state.send(inst)
                markSent(context, messageId, rustId, sent.sentTimestamp.toLong())
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

    private fun markSent(context: Context, messageId: Long, rustId: String, sentTimestampMs: Long) {
        // Like Dart's replaceAttachment: rename the attachment folder to the final guid first.
        val att = attachmentBox.query { apply(AttachmentEntity_.messageIdId.equal(messageId)) }.findFirst()
        var newAttGuid: String? = null
        if (att != null && att.guid?.startsWith("temp") == true) {
            newAttGuid = "${rustId}_0" // reflectMessageDyn: "${msgId}_$fieldIdx"
            val root = File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments")
            val from = File(root, att.guid!!)
            val to = File(root, newAttGuid)
            if (from.isDirectory && !to.exists() && !from.renameTo(to)) {
                Log.w(TAG, "Could not rename attachment folder ${from.name} -> ${to.name}")
                newAttGuid = null
            }
        }
        ObjectBox.store.runInTx {
            val msg = messageBox.get(messageId) ?: return@runInTx
            msg.guid = rustId
            msg.stagingGuid = null
            msg.errorVal = 0
            if (sentTimestampMs > 0) msg.dateCreated = Date(sentTimestampMs)
            messageBox.put(msg)
            if (att != null && newAttGuid != null) {
                attachmentBox.get(att.id)?.let { a ->
                    a.guid = newAttGuid
                    attachmentBox.put(a)
                }
            }
        }
        Log.i(TAG, "Sent $rustId")
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

    private fun retryOrFail(messageId: Long, attempt: Int, reason: String): Outcome =
        if (attempt + 1 >= MAX_ATTEMPTS) markFailed(messageId, reason) else Outcome.RETRY

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
