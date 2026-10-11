package com.bluebubbles.messaging.services.backend_ui_interop

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.objectbox.*
import com.bluebubbles.messaging.socialite.model.*
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.net.URLConnection

object MessageSender {
    fun sendMessage(context: Context, chatId: Long, text: String, attachmentPaths: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Save to ObjectBox immediately
                val chatBox = ObjectBox.store.boxFor(ChatEntity::class.java)
                val messageBox = ObjectBox.store.boxFor(MessageEntity::class.java)
                val attachmentBox = ObjectBox.store.boxFor(AttachmentEntity::class.java)

                val chatEntity = chatBox.get(chatId)
                if (chatEntity == null) {
                    Log.e(Constants.logTag, "Cannot send message: Chat $chatId not found")
                    return@launch
                }

                val msgGuid = "temp-${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"
                
                val msgEntity = MessageEntity(
                    guid = msgGuid,
                    text = if (text.isEmpty()) null else text,
                    isFromMe = true,
                    dateCreated = java.util.Date(),
                    itemType = 0,
                    hasDdResults = false,
                    hasAttachments = attachmentPaths.isNotEmpty(),
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
                    ckSyncState = false
                )
                msgEntity.chatId.target = chatEntity
                val msgId = messageBox.put(msgEntity)

                val flutterDirPath = context.getDir("flutter", Context.MODE_PRIVATE).absolutePath
                val attachmentsList = mutableListOf<String>()

                for (path in attachmentPaths) {
                    val file = File(path)
                    val attGuid = "temp-${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"
                    val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                    
                    val attEntity = AttachmentEntity(
                        guid = attGuid,
                        transferName = file.name,
                        mimeType = mime,
                        totalBytes = file.length(),
                        isOutgoing = true,
                        hasLivePhoto = false
                    )
                    attEntity.messageId.targetId = msgId
                    attachmentBox.put(attEntity)
                    attachmentsList.add(attGuid)

                    // Copy file to flutter attachments directory so it loads instantly in UI
                    val guidDir = File("$flutterDirPath/attachments/$attGuid")
                    guidDir.mkdirs()
                    val destFile = File(guidDir, file.name)
                    file.copyTo(destFile, overwrite = true)
                }

                Log.d(Constants.logTag, "Message $msgGuid saved to ObjectBox natively.")

                // 2. Queue to Dart via Headless Worker for execution (Path 2)
                val payload = mapOf(
                    "chatGuid" to (chatEntity.guid ?: ""),
                    "msgGuid" to msgGuid,
                    "text" to text,
                    "attachmentGuids" to attachmentsList // pass GUIDs since dart can load them from DB
                )

                DartWorker.callMethod(context, "execute-rust-send", payload)

            } catch (e: Exception) {
                Log.e(Constants.logTag, "Failed to send message: ${e.message}", e)
            }
        }
    }
}
