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
        return chatEntities.filter { chatEntity: ChatEntity ->
            val count = messageBox.query {
                apply(MessageEntity_.chatIdId.equal(chatEntity.id))
                apply(MessageEntity_.dateDeleted.isNull())
            }.count()
            if (count == 0L && chatEntity.dbOnlyLatestMessageDate != null) {
                // Clear any erroneous latest message date on empty chats so they never sort to top
                ObjectBox.update<ChatEntity>(chatEntity.id) { it.dbOnlyLatestMessageDate = null }
            }
            count > 0L
        }.map { buildChat(it) }
    }

    /** Handle.displayName: contact name, else the address. Keyed by originalROWID. */
    private fun displayNameFor(handleRowId: Long?): String? {
        if (handleRowId == null || handleRowId == 0L) return null
        val handle = handleBox.query { apply(HandleEntity_.originalROWID.equal(handleRowId)) }.findFirst() ?: return null
        val address = handle.address ?: return null
        val contact = if (::appContext.isInitialized) com.bluebubbles.messaging.utils.ContactUtils.getContactInfo(appContext, address).first else null
        return contact ?: handle.contactRelationId.target?.displayName ?: address
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
        // Like Dart's chat.latestMessage: newest non-deleted row, reactions and group events included,
        // so the preview can say e.g. "Grace liked “See you at 8”".
        val latest = messageBox.query {
            apply(MessageEntity_.chatIdId.equal(chatEntity.id))
            apply(MessageEntity_.dateDeleted.isNull())
            apply(MessageEntity_.dateCreated.notNull())
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
            lastMessage = latest?.let { MessagePreview.text(it, ::displayNameFor) } ?: "",
            lastMessageIsFromMe = latest?.isFromMe == true,
            unreadCount = unreadCount.toInt(),
            isGroup = chatEntity.handles.size > 1 || chatEntity.chatIdentifier?.contains(";") == true || chatEntity.style == 43L,
            participantInfo = participantInfoList
        )
    }

    /**
     * Normalizes an address (phone number or email) for robust matching.
     */
    fun normalizeAddress(raw: String): String {
        val clean = raw.trim().removePrefix("tel:").removePrefix("mailto:")
        if (clean.contains("@")) return clean.lowercase()
        val digits = clean.filter { it.isDigit() }
        return when {
            clean.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> if (digits.isNotEmpty()) "+$digits" else clean
        }
    }

    /**
     * Checks if two addresses refer to the same recipient (handles country code prefixes and formatting differences).
     */
    fun matchesAddress(a: String, b: String): Boolean {
        val cleanA = a.trim().removePrefix("tel:").removePrefix("mailto:")
        val cleanB = b.trim().removePrefix("tel:").removePrefix("mailto:")
        if (cleanA.equals(cleanB, ignoreCase = true)) return true
        if (cleanA.contains("@") || cleanB.contains("@")) {
            return cleanA.equals(cleanB, ignoreCase = true)
        }
        val digA = cleanA.filter { it.isDigit() }
        val digB = cleanB.filter { it.isDigit() }
        if (digA.isEmpty() || digB.isEmpty()) return false
        if (digA == digB) return true
        if (digA.length >= 7 && digB.length >= 7 && (digA.endsWith(digB) || digB.endsWith(digA))) return true
        return false
    }

    /**
     * Finds an existing chat in ObjectBox matching the provided participant addresses.
     */
    fun findExistingChat(addresses: List<String>): ChatEntity? {
        if (addresses.isEmpty()) return null
        val allChats = chatBox.query().build().find()

        if (addresses.size == 1) {
            val target = addresses.first()
            return allChats.firstOrNull { chat ->
                // Check direct handles
                if (chat.handles.size == 1 && matchesAddress(chat.handles.first().address ?: "", target)) {
                    return@firstOrNull true
                }
                // Check chatIdentifier
                if (chat.handles.size <= 1 && chat.chatIdentifier != null && matchesAddress(chat.chatIdentifier!!, target)) {
                    return@firstOrNull true
                }
                // Check guid
                if (chat.handles.size <= 1 && chat.guid != null && (matchesAddress(chat.guid!!, target) || chat.guid!!.contains(target))) {
                    return@firstOrNull true
                }
                false
            }
        } else {
            // Group chat: handles must match all requested addresses
            return allChats.firstOrNull { chat ->
                if (chat.handles.size != addresses.size) return@firstOrNull false
                val remaining = addresses.toMutableList()
                for (handle in chat.handles) {
                    val addr = handle.address ?: return@firstOrNull false
                    val idx = remaining.indexOfFirst { matchesAddress(it, addr) }
                    if (idx < 0) return@firstOrNull false
                    remaining.removeAt(idx)
                }
                remaining.isEmpty()
            }
        }
    }

    /**
     * Resolves an existing chat matching [addresses], or creates a new one in ObjectBox.
     * When creating a new chat, dbOnlyLatestMessageDate is left null so an empty draft
     * never appears in the conversation list before a message is actually sent.
     */
    fun getOrCreateChat(context: android.content.Context, addresses: List<String>): Chat {
        require(addresses.isNotEmpty()) { "Addresses must not be empty" }
        return ObjectBox.store.callInTx {
            // Check for existing chat with these contact(s) first
            val existing = findExistingChat(addresses)
            if (existing != null) {
                return@callInTx buildChat(existing)
            }

            // Only create if no existing conversation exists
            val handles = addresses.map { addr ->
                val clean = addr.trim().removePrefix("tel:").removePrefix("mailto:")
                com.bluebubbles.messaging.services.messaging.ChatResolver.handleFor(clean)
            }

            val isGroup = handles.size > 1
            val guid = if (isGroup) {
                "chat_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            } else {
                "iMessage;-;${handles.first().address}"
            }

            val chatEntity = ChatEntity(
                guid = guid,
                chatIdentifier = if (isGroup) guid else handles.first().address,
                style = if (isGroup) 43L else 45L,
                isArchived = false,
                isPinned = false,
                hasUnreadMessage = false,
                displayName = null,
                isRpSms = false,
                lockChatName = false,
                lockChatIcon = false,
                notifsSilenced = false,
                senderIsKnown = handles.any { com.bluebubbles.messaging.services.messaging.ChatResolver.isKnownContact(context, it) },
                isRoutingStub = false,
                guidRefs = arrayOf(guid),
                ckSyncState = false,
                textFieldAttachments = arrayOf(),
                dbOnlyLatestMessageDate = null // Null until a message is sent!
            )
            chatBox.put(chatEntity)
            chatEntity.handles.addAll(handles)
            chatEntity.handles.applyChangesToDb()

            buildChat(chatEntity)
        }
    }

    /**
     * Cleans up an unrouted/draft chat if no messages were ever sent or received in it.
     */
    fun cleanupIfEmpty(chatId: Long) {
        try {
            ObjectBox.store.callInTx {
                val count = messageBox.query {
                    apply(MessageEntity_.chatIdId.equal(chatId))
                }.count()
                if (count == 0L) {
                    val chat = chatBox.get(chatId) ?: return@callInTx
                    chat.handles.clear()
                    chat.handles.applyChangesToDb()
                    chatBox.remove(chat)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "Failed to cleanup empty chat $chatId", e)
        }
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

            // Unsent parts (messageSummaryInfo.retractedParts): hide them; attachment i has guid "<id>_<i>".
            val summary = msg.dbMessageSummaryInfo?.let { runCatching { org.json.JSONArray(it).optJSONObject(0) }.getOrNull() }
            val retracted = summary?.optJSONArray("retractedParts")?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toSet() } ?: emptySet()
            val textPart = com.bluebubbles.messaging.services.messaging.MessageMutations.textPartOf(msg)
            val visibleAttachments = if (retracted.isEmpty()) attachmentsList else
                attachmentsList.filter { a -> a.guid.substringAfterLast('_').toIntOrNull()?.let { it !in retracted } ?: true }
            val textRetracted = retracted.isNotEmpty() && textPart in retracted
            val isUnsent = retracted.isNotEmpty() && visibleAttachments.isEmpty() && (textRetracted || msg.text.isNullOrBlank())
            val editCount = summary?.optJSONObject("editedContent")?.optJSONArray(textPart.toString())?.length()?.minus(1)?.coerceAtLeast(0) ?: 0

            Message(
                id = msg.id,
                guid = msg.guid ?: "",
                text = when {
                    (msg.itemType ?: 0L) != 0L -> groupEventText(msg, ::senderName)
                    isUnsent -> "${if (msg.isFromMe == true) "You" else senderName(msg.handleId) ?: "Someone"} unsent a message"
                    textRetracted -> ""
                    else -> msg.text ?: ""
                },
                isIncoming = msg.isFromMe == false,
                date = msg.dateCreated?.time ?: 0L,
                dateDelivered = msg.dateDelivered?.time ?: 0L,
                dateRead = msg.dateRead?.time ?: 0L,
                isError = (msg.errorVal ?: 0L) > 0L || msg.guid?.startsWith("error") == true,
                isSending = msg.guid?.startsWith("temp") == true,
                senderName = if (msg.isFromMe == true) null else senderName(msg.handleId),
                attachments = if (isUnsent) emptyList() else visibleAttachments,
                reactions = if (isUnsent) emptyList() else reactionsList,
                replyTo = replyTo,
                linkPreview = linkPreview(msg),
                isEdited = msg.dateEdited != null,
                isGroupEvent = (msg.itemType ?: 0L) != 0L,
                isUnsent = isUnsent,
                editCount = editCount,
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
