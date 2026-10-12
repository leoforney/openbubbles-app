package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.ChatEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.rust_lib_bluebubbles.NativeConversationData
import uniffi.rust_lib_bluebubbles.NativeMessage
import uniffi.rust_lib_bluebubbles.NativePushState
import uniffi.rust_lib_bluebubbles.newMessageInst
import java.util.Date

/** Port of `invalidatePeerCaches`: tell recent conversation partners that my keys changed. */
internal object PeerCaches {
    private const val TAG = "PeerCaches"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun invalidate(context: Context, state: NativePushState, myHandles: List<String>) {
        scope.launch {
            try {
                val since = Date(System.currentTimeMillis() - 12 * 60 * 60 * 1000L)
                val chats = ObjectBox.store.boxFor<ChatEntity>().query {
                    apply(ChatEntity_.dateDeleted.isNull())
                    apply(ChatEntity_.dbOnlyLatestMessageDate.greater(since))
                }.find()
                val byHandle = myHandles.associateWith { mutableSetOf(it) }
                for (chat in chats) {
                    if (chat.isRpSms == true) continue
                    val data = ChatResolver.conversationData(context, chat, myHandles)
                    val sender = ChatResolver.ensureHandle(context, chat, myHandles)
                    byHandle[sender]?.addAll(data.participants)
                }
                for ((handle, participants) in byHandle) {
                    if (participants.size == 1) continue
                    val msg = newMessageInst(
                        NativeConversationData(participants = participants.toList(), cvName = null, senderGuid = null, afterGuid = null),
                        handle,
                        NativeMessage.PeerCacheInvalidate,
                    )
                    state.send(msg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Peer cache invalidation failed", e)
            }
        }
    }
}
