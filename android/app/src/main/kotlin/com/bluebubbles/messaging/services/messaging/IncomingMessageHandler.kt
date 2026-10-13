package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.AttachmentEntity
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.ChatEntity_
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_lib_bluebubbles.NativeConversationData
import uniffi.rust_lib_bluebubbles.NativeMessage
import uniffi.rust_lib_bluebubbles.NativeMessageInst
import uniffi.rust_lib_bluebubbles.NativeMessageType
import uniffi.rust_lib_bluebubbles.NativePushState
import uniffi.rust_lib_bluebubbles.NativeReactMessageType
import uniffi.rust_lib_bluebubbles.newMessageInst
import java.util.Date
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Native handling of incoming iMessages (SERVICE_TRANSITION.md §6, inventory §1). Each handler is a
 * port of the matching branch of `RustPushService.handleMsgInner` / `reflectMessageDyn` and the
 * incoming queue (`ActionHandler.handleNewMessage`, `Chat.addMessage`, `Message.save`).
 *
 * Messages are processed one at a time by [IncomingPipeline]; all database work for a message is
 * done in write transactions so a crash never leaves half a message behind.
 */
internal class IncomingMessageHandler(private val context: Context, private val state: NativePushState) {
    companion object {
        private const val TAG = "IncomingMessages"
        private const val IMAGE_WAIT_MS = 20_000L
        private val notifyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        /**
         * Whether this message is handled natively. Everything else is forwarded to Dart unchanged,
         * which shares the same native store, so nothing is dropped while the port is incomplete.
         */
        fun supports(inst: NativeMessageInst): Boolean {
            // Receipts, typing, errors etc. are handled without a chat and usually carry no
            // conversation data; everything else needs it for chat resolution.
            val noChatNeeded = when (inst.message) {
                is NativeMessage.Delivered, is NativeMessage.Read, is NativeMessage.Typing,
                is NativeMessage.MessageReadOnDevice, is NativeMessage.Unschedule,
                is NativeMessage.Error, is NativeMessage.NotifyAnyways, is NativeMessage.PeerCacheInvalidate -> true
                else -> false
            }
            if (inst.conversation == null && !noChatNeeded) return false
            return when (val m = inst.message) {
                is NativeMessage.Message -> m.msg.service is NativeMessageType.IMessage && m.msg.app == null &&
                    m.msg.embeddedProfile == null && m.msg.linkMeta?.isPasswordsInvite() != true
                is NativeMessage.React -> m.msg.embeddedProfile == null &&
                    (m.msg.reaction as? NativeReactMessageType.React)?.let { MessageFormats.reactionType(it.reaction) != null } == true
                is NativeMessage.Delivered, is NativeMessage.Read,
                is NativeMessage.Edit, is NativeMessage.Unsend,
                is NativeMessage.RenameMessage, is NativeMessage.ChangeParticipants,
                is NativeMessage.MarkUnread, is NativeMessage.Error, is NativeMessage.NotifyAnyways,
                is NativeMessage.Typing, is NativeMessage.MessageReadOnDevice, is NativeMessage.Unschedule,
                is NativeMessage.PeerCacheInvalidate -> true
                else -> false
            }
        }
    }

    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()
    private val attachmentBox get() = ObjectBox.store.boxFor<AttachmentEntity>()
    private val chatBox get() = ObjectBox.store.boxFor<ChatEntity>()

    private lateinit var myHandles: List<String>

    suspend fun handle(inst: NativeMessageInst) {
        myHandles = state.getHandles()
        if (myHandles.isEmpty()) throw IllegalStateException("no registered handles yet")
        when (val m = inst.message) {
            is NativeMessage.Delivered, is NativeMessage.Read -> handleReceipt(inst, m is NativeMessage.Read)
            is NativeMessage.Typing, is NativeMessage.MessageReadOnDevice, is NativeMessage.Unschedule -> Unit // no stored state
            is NativeMessage.Error -> handleError(inst, m)
            is NativeMessage.PeerCacheInvalidate -> PeerCaches.invalidate(context, state, myHandles)
            is NativeMessage.NotifyAnyways -> handleNotifyAnyways(inst)
            else -> handleWithChat(inst)
        }
    }

    // --- receipts (handled before chat resolution, like Dart) --------------------------------

    private fun handleReceipt(inst: NativeMessageInst, isRead: Boolean) {
        if (inst.verificationFailed) return
        var markChatRead: ChatEntity? = null
        ObjectBox.store.runInTx {
            val msg = findMessage(inst.id) ?: return@runInTx
            val chat = msg.chatId.target
            if (inst.sender in myHandles && chat?.isRpSms != true) {
                // Read on one of my other devices: clear unread. Delivered to my devices is ignored.
                if (isRead && chat != null && chat.hasUnreadMessage == true) {
                    chat.hasUnreadMessage = false
                    chatBox.put(chat)
                    markChatRead = chat
                }
                return@runInTx
            }
            val at = Date(inst.sentTimestamp.toLong())
            if (isRead) msg.dateRead = at else msg.dateDelivered = at
            if (chat?.notifsSilenced == true) {
                val last = chat.dateNotifiedAnyways
                msg.wasDeliveredQuietly = last == null || System.currentTimeMillis() - last.time > 5 * 60_000
            }
            messageBox.put(msg)
        }
        markChatRead?.let { IncomingNotifier.clearChatNotification(context, it) }
    }

    // --- everything that needs the chat ------------------------------------------------------

    private suspend fun handleWithChat(inst: NativeMessageInst) {
        val saved = ObjectBox.store.callInTx {
            val chat = chatForMessage(inst)
            when (val m = inst.message) {
                is NativeMessage.RenameMessage -> {
                    if (inst.verificationFailed) return@callInTx null
                    if (chat.lockChatName != true) chat.displayName = m.newName
                    chat.apnTitle = m.newName
                    chat.ckSyncState = false
                    chatBox.put(chat)
                    save(chat, groupEvent(inst, itemType = 2, groupActionType = 2).apply { groupTitle = m.newName }, emptyList())
                }
                is NativeMessage.ChangeParticipants -> {
                    if (inst.verificationFailed) return@callInTx null
                    updateParticipants(chat, inst, inst.conversation!!.participants, m.newParticipants)
                    chat.groupVersion = m.groupVersion.toLong()
                    chat.ckSyncState = false
                    chatBox.put(chat)
                    null
                }
                is NativeMessage.MarkUnread -> {
                    chat.hasUnreadMessage = true
                    chatBox.put(chat)
                    null
                }
                is NativeMessage.Message -> {
                    val parts = m.msg.parts
                    val hasAttachment = parts.any { it.part is uniffi.rust_lib_bluebubbles.NativeMessagePart.Attachment }
                    val rawText = parts.joinToString("") { p ->
                        when (val x = p.part) {
                            is uniffi.rust_lib_bluebubbles.NativeMessagePart.Text -> x.text
                            is uniffi.rust_lib_bluebubbles.NativeMessagePart.Mention -> x.text
                            else -> ""
                        }
                    }
                    if (rawText.isEmpty() && !hasAttachment) return@callInTx null // Dart drops these too
                    val (entity, attachments) = reflectNormal(inst, m)
                    save(chat, entity, attachments)
                }
                is NativeMessage.React -> save(chat, reflectReaction(inst, m), emptyList())
                is NativeMessage.Edit -> { applyEdit(inst, m); null }
                is NativeMessage.Unsend -> { applyUnsend(inst, m); null }
                else -> null
            }
        }
        if (saved != null && !saved.isNew) {
            // Re-delivered message: resume any attachment download that did not finish.
            AttachmentDownloader.downloadIfEnabled(context, state, saved.message.id)
        }
        if (saved != null && saved.isNew) {
            notifyWithImage(saved)
            if (saved.message.isFromMe != true && ActiveChatTracker.isActive(saved.chat.id)) {
                ReadReceipts.onMessageWhileActive(context, saved.chat)
            }
        }
    }

    class Saved(val chat: ChatEntity, val message: MessageEntity, val isNew: Boolean)

    /**
     * Posts the notification for a new message. If it has an image and auto-download is allowed,
     * the image is downloaded first (max [IMAGE_WAIT_MS]) so the notification can show it inline.
     * Runs outside the pipeline so a slow download never delays other messages.
     */
    private fun notifyWithImage(saved: Saved) {
        val image = if (saved.message.isFromMe == true || !AttachmentDownloader.autoDownloadAllowed(context)) null else
            attachmentBox.query { apply(com.bluebubbles.messaging.objectbox.AttachmentEntity_.messageIdId.equal(saved.message.id)) }
                .find().firstOrNull { it.mimeType?.startsWith("image/") == true }
        if (image == null) {
            AttachmentDownloader.downloadIfEnabled(context, state, saved.message.id)
            IncomingNotifier.onNewMessage(context, saved.chat, saved.message, notifyAnyways = false)
            return
        }
        notifyScope.launch {
            val ready = kotlinx.coroutines.withTimeoutOrNull(IMAGE_WAIT_MS) { AttachmentDownloader.downloadNow(context, state, image) } == true
            IncomingNotifier.onNewMessage(context, saved.chat, saved.message, notifyAnyways = false)
            AttachmentDownloader.downloadIfEnabled(context, state, saved.message.id, skipId = image.id)
            if (!ready) {
                // Too slow for the first post: keep downloading and add the image to the posted
                // notification once it arrives (silently, no second alert).
                if (AttachmentDownloader.downloadNow(context, state, image)) {
                    IncomingNotifier.addImageToPosted(context, saved.chat, saved.message)
                }
            }
        }
    }

    /** Port of `chatForMessageInner` + `chatForMessage` (conformance updates). */
    private fun chatForMessage(inst: NativeMessageInst): ChatEntity {
        val conv = inst.conversation!!
        val replayGuid = when (val m = inst.message) {
            is NativeMessage.Edit -> m.tuuid
            is NativeMessage.Unsend -> m.tuuid
            else -> inst.id
        }
        var chat = findMessage(replayGuid)?.chatId?.target
        if (chat == null && conv.afterGuid != null) {
            val after = findMessage(conv.afterGuid!!)?.chatId?.target
            if (after != null && (inst.sender == null || after.handles.any { it.address == ChatResolver.stripPrefix(inst.sender!!) })) {
                chat = after
            }
        }
        if (chat == null) {
            chat = when (val m = inst.message) {
                is NativeMessage.RenameMessage ->
                    ChatResolver.findByRust(context, conv, myHandles, soft = true)
                        ?: ChatResolver.findByRust(context, conv.copy(cvName = m.newName), myHandles, soft = false)!!
                is NativeMessage.ChangeParticipants ->
                    ChatResolver.findByRust(context, conv, myHandles, soft = true)
                        ?: ChatResolver.findByRust(context, conv.copy(participants = m.newParticipants), myHandles, soft = false)!!
                else -> ChatResolver.findByRust(context, conv, myHandles, soft = false)!!
            }
        }

        // conformance stuff
        var changed = false
        conv.senderGuid?.let { sg ->
            val refs = chat.guidRefs?.toList() ?: emptyList()
            if (sg !in refs) { chat.guidRefs = (refs + sg).toTypedArray(); changed = true }
        }
        val mine = myHandles.filter { it in conv.participants }
        if (mine.isNotEmpty() && chat.usingHandle !in mine) { chat.usingHandle = mine[0]; changed = true }
        if (changed) chatBox.put(chat)

        if (inst.message !is NativeMessage.ChangeParticipants) {
            val current = ChatResolver.conversationData(context, chat, myHandles).participants
            updateParticipants(chat, inst, current, conv.participants)
        }
        if (inst.message !is NativeMessage.RenameMessage && conv.cvName != null && conv.cvName != chat.apnTitle) {
            if (chat.lockChatName != true) chat.displayName = conv.cvName
            chat.apnTitle = conv.cvName
            chatBox.put(chat)
            save(chat, groupEvent(inst, itemType = 2, groupActionType = 2, guid = UUID.randomUUID().toString()).apply { groupTitle = conv.cvName }, emptyList())
        }
        if (chat.dateDeleted != null) {
            chat.dateDeleted = null
            chatBox.put(chat)
        }
        return chat
    }

    /** Port of `updateChatParticipants`. */
    private fun updateParticipants(chat: ChatEntity, inst: NativeMessageInst, old: List<String>, new: List<String>) {
        val added = new.filter { it !in old && it !in myHandles }
        val removed = old.filter { it !in new }
        if (added.isEmpty() && removed.isEmpty()) return
        val (_, handles) = ChatResolver.splitParticipants(new, myHandles)
        chat.handles.clear()
        chat.handles.addAll(handles)
        chat.handles.applyChangesToDb()

        val useId = inst.message is NativeMessage.ChangeParticipants
        for (p in added) {
            val other = ChatResolver.handleFor(p)
            save(chat, groupEvent(inst, 1, 0, if (useId) inst.id else UUID.randomUUID().toString()).apply { otherHandle = other.originalROWID }, emptyList())
        }
        for (p in removed) {
            val other = ChatResolver.handleFor(p)
            val left = p == inst.sender
            save(chat, groupEvent(inst, if (left) 3 else 1, if (left) 0 else 1, if (useId) inst.id else UUID.randomUUID().toString()).apply { otherHandle = other.originalROWID }, emptyList())
        }
    }

    private fun groupEvent(inst: NativeMessageInst, itemType: Long, groupActionType: Long, guid: String = inst.id) = baseMessage(inst, guid).apply {
        this.itemType = itemType
        this.groupActionType = groupActionType
    }

    private fun baseMessage(inst: NativeMessageInst, guid: String = inst.id) = MessageEntity(
        guid = guid,
        isFromMe = inst.sender in myHandles,
        handleId = inst.sender?.let { ChatResolver.handleFor(it).originalROWID } ?: 0,
        dateCreated = Date(inst.sentTimestamp.toLong()),
        itemType = 0,
        hasDdResults = false,
        hasAttachments = false,
        hasReactions = false,
        hasApplePayloadData = false,
        wasDeliveredQuietly = false,
        didNotifyRecipient = false,
        isBookmarked = false,
        hasBeenForwarded = false,
        verificationFailed = inst.verificationFailed,
        isDelivered = false,
        errorVal = 0,
        groupActionType = 0,
        ckSyncState = false,
    )

    /** Port of the `Message_Message` branch of `reflectMessageDyn` (iMessage only). */
    private fun reflectNormal(inst: NativeMessageInst, m: NativeMessage.Message): Pair<MessageEntity, List<AttachmentEntity>> {
        val n = m.msg
        val body = MessageFormats.partsToBody(n.parts, inst.id, null)
        val link = n.linkMeta
        val entity = baseMessage(inst).apply {
            text = body.text
            dbAttributedBody = MessageFormats.bodyList(body.attributedBody)
            subject = n.subject
            threadOriginatorPart = n.replyPart
            threadOriginatorGuid = n.replyGuid
            expressiveSendStyleId = n.effect
            dateScheduled = n.scheduled?.let { Date(it.ms.toLong()) }
            hasAttachments = body.attachments.isNotEmpty()
            if (link != null) {
                balloonBundleId = MessageFormats.URL_BALLOON
                dbPayloadData = MessageFormats.linkPayload(link)
            }
        }
        return entity to body.attachments
    }

    /** Port of the `Message_React` branch (standard and emoji tapbacks). */
    private fun reflectReaction(inst: NativeMessageInst, m: NativeMessage.React): MessageEntity {
        val react = m.msg.reaction as NativeReactMessageType.React
        val type = MessageFormats.reactionType(react.reaction)!!
        return baseMessage(inst).apply {
            associatedMessagePart = m.msg.toPart?.toLong()
            associatedMessageGuid = m.msg.toUuid
            associatedMessageType = if (react.enable) type else "-$type"
            associatedMessageEmoji = (react.reaction as? uniffi.rust_lib_bluebubbles.NativeReaction.Emoji)?.emoji
            dbAttributedBody = "[]"
        }
    }

    /** Port of the `Message_Edit` branch. Throws if the target is unknown so the push is retried. */
    private fun applyEdit(inst: NativeMessageInst, m: NativeMessage.Edit) {
        val target = findMessage(m.tuuid) ?: throw IllegalStateException("edit target ${m.tuuid} not found")
        MessageMutations.applyEdit(target, m.newParts, m.editPart.toInt(), inst.id, inst.sentTimestamp.toLong(), inst.verificationFailed)
    }

    /** Port of the `Message_Unsend` branch (without Dart's force-unwrap crash). */
    private fun applyUnsend(inst: NativeMessageInst, m: NativeMessage.Unsend) {
        val target = findMessage(m.tuuid) ?: throw IllegalStateException("unsend target ${m.tuuid} not found")
        MessageMutations.applyUnsend(target, listOf(m.editPart.toInt()), inst.verificationFailed)
    }

    private fun handleError(inst: NativeMessageInst, m: NativeMessage.Error) {
        if (inst.sender !in myHandles) return // only errors reported by my own devices
        ObjectBox.store.runInTx {
            val target = findMessage(m.forUuid) ?: return@runInTx
            if (target.isDelivered == true || target.dateDelivered != null) return@runInTx
            val guid = target.guid ?: return@runInTx
            if (!guid.contains("temp") && !guid.contains("error")) target.stagingGuid = guid
            val temp = "temp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
            target.guid = temp.replace("temp", "error-protocol: ${m.statusStr}")
            target.errorVal = OutgoingMessageQueue.ERROR_BAD_REQUEST
            messageBox.put(target)
        }
    }

    private fun handleNotifyAnyways(inst: NativeMessageInst) {
        val msg = ObjectBox.store.callInTx {
            val msg = findMessage(inst.id) ?: return@callInTx null
            msg.wasDeliveredQuietly = false
            messageBox.put(msg)
            msg
        } ?: return
        val chat = msg.chatId.target ?: return
        IncomingNotifier.onNewMessage(context, chat, msg, notifyAnyways = true)
    }

    // --- persistence -----------------------------------------------------------------------

    /** Dart `Message.findOne(guid:)`: by guid, then by stagingGuid. */
    private fun findMessage(guid: String): MessageEntity? =
        messageBox.query { apply(MessageEntity_.guid.equal(guid)) }.findFirst()
            ?: messageBox.query { apply(MessageEntity_.stagingGuid.equal(guid)) }.findFirst()

    /**
     * Port of `ActionHandler.handleNewMessage` → `Chat.addMessage` / `Message.save`, or
     * `Message.replaceMessage` when the message already exists (replays, own sends).
     */
    private fun save(chat: ChatEntity, msg: MessageEntity, attachments: List<AttachmentEntity>): Saved {
        val existing = msg.guid?.let { findMessage(it) }
        if (existing != null) {
            // replaceMessage merge rules (lib/database/io/message.dart)
            existing.guid = msg.guid
            msg.text?.let { existing.text = it }
            existing.dateDelivered = msg.dateDelivered ?: existing.dateDelivered
            existing.dateRead = msg.dateRead ?: existing.dateRead
            existing.dateEdited = msg.dateEdited ?: existing.dateEdited
            if (msg.dbAttributedBody != null && msg.dbAttributedBody != "[]") existing.dbAttributedBody = msg.dbAttributedBody
            if (msg.dbMessageSummaryInfo != null && msg.dbMessageSummaryInfo != "[]") existing.dbMessageSummaryInfo = msg.dbMessageSummaryInfo
            existing.dbPayloadData = msg.dbPayloadData ?: existing.dbPayloadData
            existing.errorVal = msg.errorVal
            existing.stagingGuid = msg.stagingGuid
            existing.verificationFailed = msg.verificationFailed
            existing.amkSessionId = msg.amkSessionId
            if (existing.dateScheduled != null && msg.dateScheduled == null) existing.dateCreated = msg.dateCreated
            existing.dateScheduled = msg.dateScheduled
            messageBox.put(existing)
            return Saved(chat, existing, isNew = false)
        }

        // Message.save: reactions flag on the target, or on this message if reactions exist already
        if (msg.associatedMessageType != null && msg.associatedMessageGuid != null) {
            findMessage(msg.associatedMessageGuid!!)?.let { target ->
                if (target.hasReactions != true) { target.hasReactions = true; messageBox.put(target) }
            }
        } else if (msg.hasReactions != true && msg.guid != null &&
            messageBox.query { apply(MessageEntity_.associatedMessageGuid.equal(msg.guid!!)) }.count() > 0) {
            msg.hasReactions = true
        }
        msg.chatId.target = chat
        messageBox.put(msg)
        for (att in attachments) {
            att.messageId.target = msg
            attachmentBox.put(att)
        }

        // Chat.addMessage: latest message, undelete, unarchive, sender known, unread
        val date = msg.dateCreated
        val latest = chat.dbOnlyLatestMessageDate
        val isNewer = date != null && (latest == null || date.after(latest) || date == latest)
        if (isNewer) {
            chat.dbOnlyLatestMessageDate = date
            if (chat.dateDeleted != null) chat.dateDeleted = null
            if (chat.isArchived == true && msg.isFromMe != true && FlutterPrefs.bool(context, "unarchiveOnNewMessage", false) &&
                chat.handles.firstOrNull()?.blocked != true) {
                chat.isArchived = false
            }
        }
        if (chat.senderIsKnown == false && msg.isFromMe == true) chat.senderIsKnown = true
        if (isNewer) {
            when {
                msg.isFromMe == true -> chat.hasUnreadMessage = false
                ActiveChatTracker.isActive(chat.id) -> chat.hasUnreadMessage = false
                else -> chat.hasUnreadMessage = true
            }
        }
        chatBox.put(chat)
        return Saved(chat, msg, isNew = true)
    }

    // --- delivery certification ------------------------------------------------------------

    /** Port of `markCertified`. Never creates a chat. */
    suspend fun certify(inst: NativeMessageInst) {
        var sendDelivered = inst.sendDelivered
        val conv = inst.conversation
        val handles = state.getHandles()
        val chat = try {
            if (conv != null && handles.isNotEmpty()) ObjectBox.store.callInTx { ChatResolver.findByRust(context, conv, handles, soft = true) } else null
        } catch (e: Exception) { null }
        if (chat != null) {
            if (!ChatResolver.isGroup(chat) && chat.handles.size == 1 && chat.handles.first().blocked == true) sendDelivered = false
            if (chat.isRpSms == true) sendDelivered = false
        }
        val context2 = inst.certifiedContext
        if (context2 == null) {
            if (!sendDelivered || conv == null) return
            if (inst.id.contains("temp") || inst.id.contains("error") || handles.isEmpty()) return
            val sender = chat?.let { ChatResolver.ensureHandle(context, it, handles) } ?: ChatResolver.defaultHandle(context, handles)
            val receipt = newMessageInst(
                NativeConversationData(participants = listOf(inst.sender ?: return), cvName = conv.cvName, senderGuid = conv.senderGuid, afterGuid = null),
                sender,
                NativeMessage.Delivered,
            )
            receipt.id = inst.id
            receipt.target = inst.target // only the device that sent it
            state.send(receipt)
            return
        }
        state.certifyDelivery(context2, sendDelivered)
    }
}
