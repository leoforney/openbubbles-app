package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_lib_bluebubbles.NativeConversationData
import uniffi.rust_lib_bluebubbles.NativeDeleteTarget
import uniffi.rust_lib_bluebubbles.NativeIndexedMessagePart
import uniffi.rust_lib_bluebubbles.NativeMessage
import uniffi.rust_lib_bluebubbles.NativeMessagePart
import uniffi.rust_lib_bluebubbles.NativeTextFormat
import uniffi.rust_lib_bluebubbles.newMessageInst

/**
 * Edit, unsend and delete for the user's own messages (ports of RustPushBackend.edit / unsend /
 * moveToRecycleBin + Message.softDelete). The change is sent first and stored once Apple confirms it.
 */
object MessageActions {
    private const val TAG = "MessageActions"

    /** Apple's limits: edits within 15 minutes (max 5 per part), unsend within 2 minutes. */
    const val EDIT_WINDOW_MS = 15 * 60_000L
    const val MAX_EDITS = 5
    const val UNSEND_WINDOW_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()

    private fun isFinal(msg: MessageEntity) = msg.guid?.let { !it.startsWith("temp") && !it.startsWith("error") } == true
    private fun age(msg: MessageEntity) = System.currentTimeMillis() - (msg.dateCreated?.time ?: 0L)

    fun canEdit(msg: MessageEntity) = msg.isFromMe == true && isFinal(msg) && !msg.text.isNullOrBlank() &&
        age(msg) < EDIT_WINDOW_MS && MessageMutations.editCount(msg, MessageMutations.textPartOf(msg)) < MAX_EDITS

    fun canUnsend(msg: MessageEntity) = msg.isFromMe == true && isFinal(msg) && age(msg) < UNSEND_WINDOW_MS

    fun findByGuid(guid: String): MessageEntity? = messageBox.query { apply(MessageEntity_.guid.equal(guid)) }.findFirst()

    fun edit(context: Context, guid: String, newText: String) {
        val appContext = context.applicationContext
        scope.launch {
            val msg = findByGuid(guid) ?: return@launch
            if (!canEdit(msg)) return@launch toast(appContext, "This message can no longer be edited")
            val chat = msg.chatId.target ?: return@launch
            val part = MessageMutations.textPartOf(msg)
            val parts = listOf(NativeIndexedMessagePart(NativeMessagePart.Text(newText, NativeTextFormat.Flags(false, false, false, false)), part.toULong(), null))
            val sent = send(appContext, chat) { conversation, sender ->
                newMessageInst(conversation, sender, NativeMessage.Edit(guid, part.toULong(), parts))
            } ?: return@launch toast(appContext, "Couldn't edit the message")
            ObjectBox.store.runInTx {
                val fresh = messageBox.get(msg.id) ?: return@runInTx
                MessageMutations.applyEdit(fresh, parts, part, sent, System.currentTimeMillis(), false)
            }
        }
    }

    fun unsend(context: Context, guid: String) {
        val appContext = context.applicationContext
        scope.launch {
            val msg = findByGuid(guid) ?: return@launch
            if (!canUnsend(msg)) return@launch toast(appContext, "Messages can only be unsent within 2 minutes")
            val chat = msg.chatId.target ?: return@launch
            val parts = MessageMutations.partsOf(msg)
            for (part in parts) {
                send(appContext, chat) { conversation, sender ->
                    newMessageInst(conversation, sender, NativeMessage.Unsend(guid, part.toULong()))
                } ?: return@launch toast(appContext, "Couldn't unsend the message")
            }
            ObjectBox.store.runInTx {
                val fresh = messageBox.get(msg.id) ?: return@runInTx
                MessageMutations.applyUnsend(fresh, parts, false)
            }
        }
    }

    /**
     * Deletes messages locally (dateDeleted, like Message.softDelete) and moves them to the recycle bin
     * on my other Apple devices. Works for any message, including received ones.
     */
    fun delete(context: Context, chatId: Long, guids: List<String>) {
        val appContext = context.applicationContext
        scope.launch {
            val now = java.util.Date()
            val deleted = ObjectBox.store.callInTx {
                guids.mapNotNull { g ->
                    val m = findByGuid(g) ?: return@mapNotNull null
                    m.dateDeleted = now
                    messageBox.put(m)
                    m
                }
            }
            val chat = ObjectBox.store.boxFor<ChatEntity>().get(chatId) ?: return@launch
            val syncable = deleted.filter { isFinal(it) }.mapNotNull { it.guid }
            if (syncable.isEmpty()) return@launch
            try {
                val state = APNService.instance?.pushState ?: return@launch
                val handles = state.getHandles().ifEmpty { return@launch }
                val me = ChatResolver.ensureHandle(appContext, chat, handles)
                val msg = newMessageInst(
                    NativeConversationData(participants = listOf(me), cvName = null, senderGuid = null, afterGuid = null),
                    me,
                    NativeMessage.MoveToRecycleBin(NativeDeleteTarget.Messages(syncable), now.time.toULong()),
                )
                withTimeoutOrNull(45_000) { state.send(msg) }
            } catch (e: Exception) {
                Log.w(TAG, "Deleted locally, but syncing the delete to other devices failed", e)
            }
        }
    }

    /** Sends a control message in [chat]; returns its id on success, null on failure. */
    private suspend fun send(context: Context, chat: ChatEntity,
                             build: (NativeConversationData, String) -> uniffi.rust_lib_bluebubbles.NativeMessageInst): String? {
        return try {
            val state = APNService.instance?.pushState ?: return null
            val handles = state.getHandles().ifEmpty { return null }
            val sender = ChatResolver.ensureHandle(context, chat, handles)
            val conversation = ChatResolver.conversationData(context, chat, handles)
            val inst = build(conversation, sender)
            withTimeoutOrNull(45_000) { state.send(inst) }?.id
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            null
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
