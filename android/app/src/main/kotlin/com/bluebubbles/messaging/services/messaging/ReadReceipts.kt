package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.services.rustpush.APNService
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.rust_lib_bluebubbles.NativeMessage
import uniffi.rust_lib_bluebubbles.newMessageInst

/**
 * Marking chats read, port of `Chat.toggleHasUnread(false)` + `RustPushBackend.markRead`.
 *
 * Whether the other person sees "Read" follows the user's preferences exactly like Dart:
 * `enablePrivateAPI && (chat.autoSendReadReceipts ?? privateMarkChatAsRead)`, never for SMS
 * forwarding chats or conversations with more than 2 participants. When others are not notified the
 * receipt is still sent to my own handle, so my other devices mark the chat read too.
 */
object ReadReceipts {
    private const val TAG = "ReadReceipts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Clears the unread state of a chat (opening it, "Mark as read"). Sends the receipt only if the
     * chat was unread, like Dart.
     */
    fun markChatRead(context: Context, chatId: Long, clearNotification: Boolean = true) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (!ObjectBox.isOpen) return@launch
                val chat = ObjectBox.store.callInTx {
                    val box = ObjectBox.store.boxFor<ChatEntity>()
                    val chat = box.get(chatId) ?: return@callInTx null
                    if (chat.hasUnreadMessage != true) return@callInTx null
                    chat.hasUnreadMessage = false
                    box.put(chat)
                    chat
                }
                if (clearNotification) ObjectBox.store.boxFor<ChatEntity>().get(chatId)?.let { IncomingNotifier.clearChatNotification(appContext, it) }
                if (chat != null) sendRead(appContext, chat)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark chat $chatId read", e)
            }
        }
    }

    /** A new message arrived while the chat is open: Dart marks it read again (newOnMessage). */
    fun onMessageWhileActive(context: Context, chat: ChatEntity) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                sendRead(appContext, chat)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt for ${chat.guid}", e)
            }
        }
    }

    private suspend fun sendRead(context: Context, chat: ChatEntity) {
        val state = APNService.instance?.pushState ?: run {
            Log.w(TAG, "Messaging service not ready; read receipt for ${chat.guid} not sent")
            return
        }
        val handles = state.getHandles()
        if (handles.isEmpty()) return

        val latest = ObjectBox.store.boxFor<MessageEntity>().query {
            apply(MessageEntity_.chatIdId.equal(chat.id))
            apply(MessageEntity_.dateDeleted.isNull())
            orderDesc(MessageEntity_.dateCreated)
        }.findFirst()?.guid ?: return
        if (latest.contains("temp") || latest.contains("error")) return

        val data = ObjectBox.store.callInTx { ChatResolver.conversationData(context, chat, handles) }
        val sender = data.participants.last() // conversationData appends ensureHandle()

        var notifyOthers = FlutterPrefs.bool(context, "enablePrivateAPI", true) &&
            (chat.autoSendReadReceipts ?: FlutterPrefs.bool(context, "privateMarkChatAsRead", false))
        if (chat.isRpSms == true) notifyOthers = false
        if (data.participants.size > 2) notifyOthers = false

        val conversation = if (notifyOthers) data else data.copy(participants = listOf(sender))
        val msg = newMessageInst(conversation, sender, NativeMessage.Read)
        msg.id = latest
        state.send(msg)
        Log.i(TAG, "Read receipt for ${chat.guid} sent (${if (notifyOthers) "to participants" else "to my devices only"})")
    }
}
