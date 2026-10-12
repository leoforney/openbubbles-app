package com.bluebubbles.messaging.objectbox

import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.Message
import com.bluebubbles.messaging.socialite.model.ParticipantInfo
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import io.objectbox.kotlin.equal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

object ChatRepository {
    private val chatBox: Box<ChatEntity> get() = ObjectBox.store.boxFor()
    private val messageBox: Box<MessageEntity> get() = ObjectBox.store.boxFor()
    private val attachmentBox: Box<AttachmentEntity> get() = ObjectBox.store.boxFor()

    private var flutterDirPath: String = ""
    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        flutterDirPath = context.getDir("flutter", android.content.Context.MODE_PRIVATE).absolutePath
    }

    fun getChatById(chatId: Long): Chat? {
        val chatEntity = chatBox.get(chatId) ?: return null
        return buildChat(chatEntity)
    }

    fun getChats(): List<Chat> {
        val chatEntities = chatBox.query().orderDesc(ChatEntity_.dbOnlyLatestMessageDate).build().find()
        return chatEntities.map { buildChat(it) }
    }

    private fun buildChat(chatEntity: ChatEntity): Chat {
        val participantInfoList = chatEntity.handles.map { handle ->
            val address = handle.address ?: handle.uniqueAddressAndService ?: "Unknown"
            val contactInfo = if (::appContext.isInitialized) {
                com.bluebubbles.messaging.utils.ContactUtils.getContactInfo(appContext, address)
            } else Pair(null, null)

            ParticipantInfo(
                address = address,
                name = contactInfo.first ?: handle.contactRelationId.target?.displayName ?: address,
                avatarBytes = contactInfo.second ?: handle.contactRelationId.target?.avatar
            )
        }

        // Latest message -> preview text + whether it's incoming
        val latest = messageBox.query {
            apply(MessageEntity_.chatIdId.equal(chatEntity.id))
            orderDesc(MessageEntity_.dateCreated)
        }.findFirst()

        val unreadCount = if (chatEntity.hasUnreadMessage != true) {
            0L
        } else {
            var lastReadTime = chatEntity.lastReadMessageGuid?.let { guid ->
                messageBox.query {
                    apply(MessageEntity_.guid.equal(guid))
                }.findFirst()?.dateCreated?.time
            } ?: 0L

            if (lastReadTime == 0L) {
                // Fallback to the latest message sent by me
                lastReadTime = messageBox.query {
                    apply(MessageEntity_.chatIdId.equal(chatEntity.id))
                    apply(MessageEntity_.isFromMe.equal(true))
                    orderDesc(MessageEntity_.dateCreated)
                }.findFirst()?.dateCreated?.time ?: 0L
            }

            val count = messageBox.query {
                apply(MessageEntity_.chatIdId.equal(chatEntity.id))
                apply(MessageEntity_.isFromMe.equal(false))
                apply(MessageEntity_.dateCreated.greater(java.util.Date(lastReadTime)))
            }.count()
            
            if (count == 0L) 1L else count
        }

        return Chat(
            id = chatEntity.id,
            name = chatEntity.displayName ?: chatEntity.title ?: participantInfoList.joinToString(", ") { it.name },
            avatarPath = chatEntity.customAvatarPath,
            avatarBytes = if (participantInfoList.size == 1) participantInfoList.first().avatarBytes else null,
            date = chatEntity.dbOnlyLatestMessageDate?.time ?: 0L,
            lastMessage = latest?.text ?: "",
            lastMessageIsFromMe = latest?.isFromMe == true,
            unreadCount = unreadCount.toInt(),
            isGroup = chatEntity.chatIdentifier?.contains(";") == true,
            participantInfo = participantInfoList
        )
    }

    /**
     * Live chat list. Re-queries whenever chats, messages, handles or contacts change, including
     * changes committed by Dart (see [StoreChanges]).
     */
    fun observeChats(): Flow<List<Chat>> =
        StoreChanges.of(ChatEntity_.__ENTITY_ID, MessageEntity_.__ENTITY_ID, HandleEntity_.__ENTITY_ID, ContactEntity_.__ENTITY_ID)
            .onStart { emit(Unit) }
            .conflate()
            .map { getChats() }
            .flowOn(Dispatchers.IO)

    /**
     * Live view of the newest [limit] messages of a chat. To page, collect again with a larger limit;
     * only that many rows are loaded, never the whole history.
     */
    fun observeMessages(chatId: Long, limit: Long): Flow<List<Message>> =
        StoreChanges.of(MessageEntity_.__ENTITY_ID, AttachmentEntity_.__ENTITY_ID)
            .onStart { emit(Unit) }
            .conflate()
            .map { getMessages(chatId, limit, 0) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    fun getMessages(chatId: Long, limit: Long = 50, offset: Long = 0): List<Message> {
        val chatEntity = chatBox.get(chatId) ?: return emptyList()
        // Wait, the message box has a chat relation? Actually we can query messageBox where chatId == chatId
        val messages = messageBox.query {
            apply(MessageEntity_.chatIdId.equal(chatId)) // Relation adds "Id" suffix to the name
            apply(MessageEntity_.associatedMessageGuid.isNull()) // Only normal messages
            orderDesc(MessageEntity_.dateCreated)
        }.find(offset, limit)
            
        return messages.map { msg ->
            // In ObjectBox, relations need to be queried or accessed if mapped.
            // Since we didn't map a ToMany for attachments in MessageEntity, we can query AttachmentEntity by messageId
            val attachmentsList = attachmentBox.query {
                apply(AttachmentEntity_.messageIdId.equal(msg.id))
            }.find()
                .map { a ->
                    val guidDir = java.io.File("$flutterDirPath/attachments/${a.guid ?: ""}")
                    var file = java.io.File(guidDir, a.transferName?.replace("/", "_") ?: "")
                    if (!file.isFile) {
                        file = guidDir.listFiles()?.firstOrNull { it.isFile } ?: file
                    }
                    val filePath = file.absolutePath
                    Attachment(
                        guid = a.guid ?: "",
                        mimeType = a.mimeType,
                        path = filePath
                    )
                }

            val reactionsList = messageBox.query {
                apply(MessageEntity_.associatedMessageGuid.equal(msg.guid ?: ""))
            }.find().map { r ->
                com.bluebubbles.messaging.socialite.model.Reaction(
                    type = r.associatedMessageType ?: "",
                    isIncoming = r.isFromMe == false,
                    senderName = null
                )
            }

            Message(
                id = msg.id,
                guid = msg.guid ?: "",
                text = msg.text ?: "",
                isIncoming = msg.isFromMe == false,
                date = msg.dateCreated?.time ?: 0L,
                dateDelivered = msg.dateDelivered?.time ?: 0L,
                dateRead = msg.dateRead?.time ?: 0L,
                isError = (msg.errorVal ?: 0L) > 0L || msg.guid?.startsWith("error") == true,
                isSending = msg.guid?.startsWith("temp") == true,
                senderName = null, // Can map from msg.handleId if we want
                attachments = attachmentsList,
                reactions = reactionsList
            )
        }
    }
}
