package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.ChatEntity_
import com.bluebubbles.messaging.objectbox.HandleEntity
import com.bluebubbles.messaging.objectbox.HandleEntity_
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.utils.ContactUtils
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder.StringOrder
import uniffi.rust_lib_bluebubbles.NativeConversationData
import java.util.UUID

/**
 * Chat and handle lookup for native message handling. Ports of RustPushBBUtils.rustHandleToBB,
 * Chat.findByRust, RustPushBackend.createChat, Chat.ensureHandle and Chat.getConversationData.
 * Must be called inside a write transaction when it may create objects.
 */
internal object ChatResolver {
    private const val TAG = "ChatResolver"

    private val chatBox get() = ObjectBox.store.boxFor<ChatEntity>()
    private val handleBox get() = ObjectBox.store.boxFor<HandleEntity>()
    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()

    fun stripPrefix(handle: String) = handle.replace("tel:", "").replace("mailto:", "")

    fun withPrefix(address: String) = if (address.contains("@")) "mailto:$address" else "tel:$address"

    /** Port of `rustHandleToBB`: find or create the iMessage handle for a rustpush address. */
    fun handleFor(rustHandle: String): HandleEntity {
        val address = stripPrefix(rustHandle)
        var handle = handleBox.query {
            apply(HandleEntity_.address.equal(address).and(HandleEntity_.service.equal("iMessage")))
        }.findFirst()
        if (handle == null) {
            handle = HandleEntity(
                address = address,
                service = "iMessage",
                uniqueAddressAndService = "$address/iMessage",
                blocked = false,
            )
            handleBox.put(handle)
        }
        if (handle.originalROWID == null) {
            handle.originalROWID = handle.id
            handleBox.put(handle)
        }
        return handle
    }

    /** Port of `rustParticipantsToBB`: (my handles in the list, handles of everyone else). */
    fun splitParticipants(participants: List<String>, myHandles: List<String>): Pair<List<String>, List<HandleEntity>> {
        val mine = myHandles.filter { it in participants }
        val others = participants.filter { it !in myHandles }.map { handleFor(it) }
        return mine to others
    }

    /** Port of `Chat.findByRust`. With [soft] = true, returns null instead of creating a chat. */
    fun findByRust(context: Context, data: NativeConversationData, myHandles: List<String>, soft: Boolean, routingStub: Boolean = false): ChatEntity? {
        require(data.participants.isNotEmpty()) { "empty participants" }
        data.senderGuid?.let { senderGuid ->
            chatBox.query { apply(ChatEntity_.guid.equal(senderGuid)) }.findFirst()?.let { return it }
            chatBox.query { apply(ChatEntity_.guidRefs.containsElement(senderGuid, StringOrder.CASE_SENSITIVE)) }.findFirst()?.let { return it }
        }

        val (mine, others) = splitParticipants(data.participants, myHandles)
        val otherIds = others.map { it.id }

        var cond: io.objectbox.query.QueryCondition<ChatEntity> = ChatEntity_.isRoutingStub.equal(routingStub)
        if (!routingStub) cond = cond.or(ChatEntity_.isRoutingStub.isNull) // Dart reads null as false
        data.cvName?.let { cond = cond.and(ChatEntity_.apnTitle.equal(it, StringOrder.CASE_SENSITIVE)) }
        val builder = chatBox.query(cond)
        builder.link(ChatEntity_.handles).apply(HandleEntity_.address.oneOf(others.mapNotNull { it.address }.toTypedArray(), StringOrder.CASE_SENSITIVE))
        val candidates = builder.build().use { it.find() }

        val match = candidates.firstOrNull { chat ->
            val remaining = otherIds.toMutableList()
            chat.handles.all { remaining.remove(it.id) } && remaining.isEmpty()
        }
        if (match != null || soft) return match

        // Port of RustPushBackend.createChat + the follow-up in findByRust
        val guid = data.senderGuid ?: UUID.randomUUID().toString()
        val chat = ChatEntity(
            guid = guid,
            isArchived = false,
            isPinned = false,
            hasUnreadMessage = false,
            displayName = data.cvName,
            apnTitle = data.cvName,
            usingHandle = mine.firstOrNull() ?: defaultHandle(context, myHandles),
            isRpSms = false,
            lockChatName = false,
            lockChatIcon = false,
            notifsSilenced = false,
            senderIsKnown = others.any { isKnownContact(context, it) },
            isRoutingStub = routingStub,
            guidRefs = arrayOf(guid),
            ckSyncState = false,
            textFieldAttachments = arrayOf(),
        )
        chatBox.put(chat)
        chat.handles.addAll(others)
        chat.handles.applyChangesToDb()
        Log.i(TAG, "Created chat $guid with ${others.size} participant(s)")
        return chat
    }

    fun isKnownContact(context: Context, handle: HandleEntity): Boolean {
        if (handle.contactRelationId.target?.isShared == false) return true
        val address = handle.address ?: return false
        return ContactUtils.getContactInfo(context, address).first != null
    }

    /** Port of `RustPushBackend.getDefaultHandle`. */
    fun defaultHandle(context: Context, myHandles: List<String>): String {
        val configured = FlutterPrefs.string(context, "defaultHandle", null)
        return if (configured != null && configured in myHandles) configured else myHandles.first()
    }

    /** Port of `Chat.ensureHandle()` for iMessage chats. */
    fun ensureHandle(context: Context, chat: ChatEntity, myHandles: List<String>): String {
        chat.usingHandle?.let { return it }
        val chosen = defaultHandle(context, myHandles)
        ObjectBox.update<ChatEntity>(chat.id) { it.usingHandle = chosen }
        chat.usingHandle = chosen
        return chosen
    }

    /** Port of `Chat.getRustHandlesExcludingMine()`. */
    fun participantUris(chat: ChatEntity): List<String> = chat.handles.mapNotNull { it.address }.map { withPrefix(it) }

    /**
     * Port of `Chat.getConversationData()` + `Chat.sendLastMessage`. Messages at or after [before]
     * are ignored (used for messages that are still queued).
     */
    fun conversationData(context: Context, chat: ChatEntity, myHandles: List<String>, before: java.util.Date? = null, excludeMessageId: Long = 0): NativeConversationData {
        val participants = participantUris(chat).toMutableList()
        participants += ensureHandle(context, chat, myHandles)
        val recent = messageBox.query {
            apply(MessageEntity_.chatIdId.equal(chat.id))
            apply(MessageEntity_.dateDeleted.isNull())
            apply(MessageEntity_.dateCreated.notNull())
            if (before != null) apply(MessageEntity_.dateCreated.less(before))
            orderDesc(MessageEntity_.dateCreated)
        }.find(0, 10)
        val last = recent.firstOrNull { m ->
            m.id != excludeMessageId && (m.stagingGuid != null || (m.guid != null && !m.guid!!.contains("temp") && !m.guid!!.contains("error")))
        }
        return NativeConversationData(
            participants = participants,
            cvName = chat.apnTitle,
            senderGuid = chat.guid,
            afterGuid = last?.let { it.stagingGuid ?: it.guid } ?: chat.guid,
        )
    }

    fun isGroup(chat: ChatEntity) = chat.handles.size > 1 || chat.style == 43L
}
