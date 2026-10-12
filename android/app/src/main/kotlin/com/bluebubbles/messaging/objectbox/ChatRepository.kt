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
    private val handleBox: Box<HandleEntity> get() = ObjectBox.store.boxFor()

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
        chatBox.get(chatId) ?: return emptyList()
        val messages = messageBox.query {
            apply(MessageEntity_.chatIdId.equal(chatId)) // Relation adds "Id" suffix to the name
            apply(MessageEntity_.associatedMessageGuid.isNull()) // Only normal messages
            apply(MessageEntity_.dateDeleted.isNull())
            orderDesc(MessageEntity_.dateCreated)
        }.find(offset, limit)

        val names = HashMap<Long, String?>()
        fun senderName(handleRowId: Long?): String? {
            if (handleRowId == null || handleRowId == 0L) return null
            return names.getOrPut(handleRowId) {
                val handle = handleBox.query { apply(HandleEntity_.originalROWID.equal(handleRowId)) }.findFirst()
                val address = handle?.address ?: return@getOrPut null
                val contact = if (::appContext.isInitialized) com.bluebubbles.messaging.utils.ContactUtils.getContactInfo(appContext, address).first else null
                contact ?: handle.contactRelationId.target?.displayName ?: address
            }
        }

        return messages.map { msg ->
            val attachmentsList = attachmentBox.query {
                apply(AttachmentEntity_.messageIdId.equal(msg.id))
                order(AttachmentEntity_.id)
            }.find().map { a ->
                val guidDir = java.io.File("$flutterDirPath/attachments/${a.guid ?: ""}")
                var file = java.io.File(guidDir, a.transferName?.replace("/", "_") ?: "")
                if (!file.isFile) {
                    file = guidDir.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") } ?: file
                }
                Attachment(
                    guid = a.guid ?: "",
                    mimeType = a.mimeType,
                    path = file.absolutePath,
                    id = a.id,
                    transferName = a.transferName,
                    isDownloaded = file.isFile,
                    totalBytes = a.totalBytes ?: 0L,
                )
            }

            // Net tapbacks: one per sender (latest wins), removals ("-type") cancel it.
            val reactionsList = if (msg.guid.isNullOrEmpty()) emptyList() else messageBox.query {
                apply(MessageEntity_.associatedMessageGuid.equal(msg.guid!!))
                apply(MessageEntity_.associatedMessageType.notNull())
                order(MessageEntity_.dateCreated)
            }.find()
                .groupBy { if (it.isFromMe == true) -1L else (it.handleId ?: 0L) }
                .mapNotNull { (_, rows) -> rows.lastOrNull() }
                .filter { it.associatedMessageType?.startsWith("-") == false }
                .map { r ->
                    com.bluebubbles.messaging.socialite.model.Reaction(
                        type = r.associatedMessageType ?: "",
                        isIncoming = r.isFromMe != true,
                        senderName = if (r.isFromMe == true) "You" else senderName(r.handleId),
                        emoji = r.associatedMessageEmoji,
                    )
                }

            val replyTo = msg.threadOriginatorGuid?.let { g ->
                messageBox.query { apply(MessageEntity_.guid.equal(g)) }.findFirst()?.let { o ->
                    com.bluebubbles.messaging.socialite.model.ReplyPreview(
                        guid = g,
                        text = o.text?.takeIf { it.isNotBlank() } ?: if (o.hasAttachments == true) "Attachment" else "Message",
                        senderName = if (o.isFromMe == true) "You" else senderName(o.handleId),
                    )
                }
            }

            Message(
                id = msg.id,
                guid = msg.guid ?: "",
                text = if ((msg.itemType ?: 0L) != 0L) groupEventText(msg, ::senderName) else msg.text ?: "",
                isIncoming = msg.isFromMe == false,
                date = msg.dateCreated?.time ?: 0L,
                dateDelivered = msg.dateDelivered?.time ?: 0L,
                dateRead = msg.dateRead?.time ?: 0L,
                isError = (msg.errorVal ?: 0L) > 0L || msg.guid?.startsWith("error") == true,
                isSending = msg.guid?.startsWith("temp") == true,
                senderName = if (msg.isFromMe == true) null else senderName(msg.handleId),
                attachments = attachmentsList,
                reactions = reactionsList,
                replyTo = replyTo,
                linkPreview = linkPreview(msg),
                isEdited = msg.dateEdited != null,
                isGroupEvent = (msg.itemType ?: 0L) != 0L,
            )
        }
    }

    /** Port of Message.groupEventText for the item types written by the incoming pipeline. */
    private fun groupEventText(msg: MessageEntity, name: (Long?) -> String?): String {
        val actor = if (msg.isFromMe == true) "You" else name(msg.handleId) ?: "Someone"
        val other = name(msg.otherHandle) ?: "someone"
        return when (msg.itemType) {
            1L -> if (msg.groupActionType == 1L) "$actor removed $other from the conversation" else "$actor added $other to the conversation"
            2L -> "$actor named the conversation \"${msg.groupTitle ?: ""}\""
            3L -> if (msg.groupActionType == 1L) "$actor changed the group photo" else "$actor left the conversation"
            7L -> "$actor changed the chat background"
            else -> "Conversation updated"
        }
    }

    /** Reads the URL preview Dart/Kotlin store in dbPayloadData (PayloadData.toJson, type 0 = url). */
    private fun linkPreview(msg: MessageEntity): com.bluebubbles.messaging.socialite.model.LinkPreview? {
        if (msg.balloonBundleId != "com.apple.messages.URLBalloonProvider") return null
        val json = msg.dbPayloadData?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return null
        val data = json.optJSONArray("urlData")?.optJSONObject(0) ?: return null
        fun nsUrl(key: String) = data.optJSONObject(key)?.optString("NS.relative")?.takeIf { it.isNotEmpty() && it != "null" }
        val url = nsUrl("URL") ?: nsUrl("originalURL") ?: return null
        return com.bluebubbles.messaging.socialite.model.LinkPreview(
            url = url,
            title = data.optString("title").takeIf { it.isNotEmpty() && it != "null" },
            summary = data.optString("summary").takeIf { it.isNotEmpty() && it != "null" },
            imageUrl = data.optJSONObject("imageMetadata")?.optJSONObject("URL")?.optString("NS.relative")?.takeIf { it.startsWith("http") },
        )
    }
}
