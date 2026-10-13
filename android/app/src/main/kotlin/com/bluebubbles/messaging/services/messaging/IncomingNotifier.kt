package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.objectbox.AttachmentEntity
import com.bluebubbles.messaging.objectbox.AttachmentEntity_
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.HandleEntity
import com.bluebubbles.messaging.objectbox.HandleEntity_
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.services.notifications.CreateIncomingMessageNotification
import com.bluebubbles.messaging.services.notifications.DeleteNotificationHandler
import com.bluebubbles.messaging.utils.ContactUtils
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import java.io.File
import java.time.LocalDateTime

/**
 * Notifications for natively handled messages. Port of `MessageHelper.handleNotification`,
 * `Chat.shouldMuteNotification` and `NotificationsService.createNotification`; the notification
 * itself is still built by the existing [CreateIncomingMessageNotification].
 */
internal object IncomingNotifier {
    private const val TAG = "IncomingNotifier"
    private const val CHANNEL_ID = "com.bluebubbles.new_messages"
    private val REACTIONS = setOf("love", "like", "dislike", "laugh", "emphasize", "question")

    fun onNewMessage(context: Context, chat: ChatEntity, msg: MessageEntity, notifyAnyways: Boolean) {
        try {
            if (msg.isFromMe == true) return
            if ((msg.itemType ?: 0L) != 0L) return // group events (renames, participants) are not notified natively
            if (msg.itemType == 5L && msg.subject != null) return // kept audio
            val handle = handleOf(msg) ?: return
            if (handle.blocked == true) return
            if (!notifyAnyways && shouldMute(context, chat, msg, handle)) return
            if (!notifyAnyways && ActiveChatTracker.isActive(chat.id)) return
            post(context, chat, msg, handle, notifyAnyways)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification for ${msg.guid}", e)
        }
    }

    /** Re-posts an already shown message notification with its (now downloaded) image. */
    fun addImageToPosted(context: Context, chat: ChatEntity, msg: MessageEntity) {
        try {
            val handle = handleOf(msg) ?: return
            post(context, chat, msg, handle, notifyAnyways = false, updateExisting = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add image to notification for ${msg.guid}", e)
        }
    }

    fun clearChatNotification(context: Context, chat: ChatEntity) {
        DeleteNotificationHandler().deleteNotification(context, chat.id.toInt(), Constants.newMessageNotificationTag)
    }

    private fun handleOf(msg: MessageEntity): HandleEntity? {
        val rowId = msg.handleId ?: return null
        if (rowId == 0L) return null
        return ObjectBox.store.boxFor<HandleEntity>().query { apply(HandleEntity_.originalROWID.equal(rowId)) }.findFirst()
    }

    /** Port of `Chat.shouldMuteNotification`. */
    private fun shouldMute(context: Context, chat: ChatEntity, msg: MessageEntity, handle: HandleEntity): Boolean {
        val text = msg.text?.lowercase() ?: ""
        val global = FlutterPrefs.string(context, "globalTextDetection", "") ?: ""
        val args = chat.muteArgs
        return when {
            FlutterPrefs.bool(context, "filterUnknownSenders", false) && chat.handles.size == 1 &&
                !ChatResolver.isKnownContact(context, chat.handles.first()) -> true
            global.isNotEmpty() -> global.split(",").none { it.isNotEmpty() && text.contains(it.lowercase()) }
            chat.muteType == "mute" -> true
            chat.muteType == "mute_individuals" -> args?.split(",")?.contains(handle.address ?: "") == true
            chat.muteType == "temporary_mute" -> args != null &&
                runCatching { LocalDateTime.now().isBefore(LocalDateTime.parse(args.replace(' ', 'T'))) }.getOrDefault(false)
            chat.muteType == "text_detection" -> args?.split(",")?.none { it.isNotEmpty() && text.contains(it.lowercase()) } ?: true
            else -> !FlutterPrefs.bool(context, "notifyReactions", true) && msg.associatedMessageType in REACTIONS
        }
    }

    private fun post(context: Context, chat: ChatEntity, msg: MessageEntity, handle: HandleEntity, notifyAnyways: Boolean, updateExisting: Boolean = false) {
        val address = handle.address ?: ""
        val (contactName, contactAvatar) = ContactUtils.getContactInfo(context, address)
        val senderName = contactName ?: address
        val isGroup = ChatResolver.isGroup(chat)
        val title = if (isGroup) groupTitle(context, chat) else senderName
        val chatIcon = if (isGroup) chat.customAvatarPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes() else contactAvatar
        val text = if (FlutterPrefs.bool(context, "hideTextPreviews", false)) "iMessage" else
            com.bluebubbles.messaging.objectbox.MessagePreview.text(msg) { rowId -> nameForHandle(context, rowId) }
        val image = attachmentsOf(msg).firstOrNull { it.mimeType?.startsWith("image/") == true }
            ?.let { AttachmentDownloader.fileFor(context, it) }?.takeIf { it.isFile }

        val args = hashMapOf<String, Any?>(
            "channel_id" to CHANNEL_ID,
            "chat_id" to chat.id.toInt(),
            "chat_guid" to (chat.guid ?: return),
            "chat_is_group" to isGroup,
            "chat_title" to title,
            "chat_icon" to chatIcon,
            "contact_name" to senderName,
            "contact_uri" to (if (isGroup) null else ChatResolver.participantUris(chat).firstOrNull()),
            "notify_anyways" to notifyAnyways,
            "contact_avatar" to contactAvatar,
            "message_guid" to (msg.guid ?: return),
            "message_text" to text,
            "message_date" to (msg.dateCreated?.time ?: System.currentTimeMillis()),
            "message_is_from_me" to false,
            "attachment_path" to image?.absolutePath,
            "attachment_type" to image?.let { attachmentsOf(msg).first { a -> a.mimeType?.startsWith("image/") == true }.mimeType },
            "update_existing" to updateExisting,
        )
        CreateIncomingMessageNotification().handleMethodCall(MethodCall(CreateIncomingMessageNotification.tag, args), object : MethodChannel.Result {
            override fun success(result: Any?) {}
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(TAG, "Notification failed: $errorCode $errorMessage")
            }
            override fun notImplemented() {}
        }, context)
    }

    private fun groupTitle(context: Context, chat: ChatEntity): String {
        chat.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        return chat.handles.mapNotNull { h -> h.address?.let { ContactUtils.getContactInfo(context, it).first ?: it } }.joinToString(", ")
    }

    private fun attachmentsOf(msg: MessageEntity): List<AttachmentEntity> =
        ObjectBox.store.boxFor<AttachmentEntity>().query { apply(AttachmentEntity_.messageIdId.equal(msg.id)) }.find()

    private fun nameForHandle(context: Context, rowId: Long?): String? {
        if (rowId == null || rowId == 0L) return null
        val h = ObjectBox.store.boxFor<HandleEntity>().query { apply(HandleEntity_.originalROWID.equal(rowId)) }.findFirst() ?: return null
        val address = h.address ?: return null
        return ContactUtils.getContactInfo(context, address).first ?: h.contactRelationId.target?.displayName ?: address
    }
}
