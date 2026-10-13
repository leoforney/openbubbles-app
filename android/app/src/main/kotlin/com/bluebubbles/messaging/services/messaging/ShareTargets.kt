package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutManagerCompat
import com.bluebubbles.messaging.objectbox.ChatEntity
import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.MessageEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.services.system.PushShareTargetsHandler
import com.bluebubbles.messaging.utils.ContactUtils
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/**
 * Direct-share targets ("people" row of the Android share sheet).
 *
 * The chats you message most (messages exchanged in the last [WINDOW_DAYS] days) are published as
 * ranked conversation shortcuts; every real send/receive is also reported to Android
 * (reportShortcutUsed), which the share sheet uses for its own ranking.
 */
object ShareTargets {
    private const val TAG = "ShareTargets"
    private const val WINDOW_DAYS = 30L
    private const val MAX_TARGETS = 8
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Recomputes and publishes the most frequent chats. Cheap enough to run on app start. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (!ObjectBox.isOpen) return@launch
                val since = Date(System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000)
                val counts = HashMap<Long, Int>()
                ObjectBox.store.boxFor<MessageEntity>().query {
                    apply(MessageEntity_.dateCreated.greater(since))
                    apply(MessageEntity_.dateDeleted.isNull())
                }.find().forEach { m ->
                    // Reactions and group events don't count as "talking to" someone.
                    if (m.associatedMessageGuid != null || (m.itemType ?: 0L) != 0L) return@forEach
                    val chatId = m.chatId.targetId
                    if (chatId != 0L) counts[chatId] = (counts[chatId] ?: 0) + 1
                }
                val limit = minOf(MAX_TARGETS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext).coerceAtLeast(1))
                val chatBox = ObjectBox.store.boxFor<ChatEntity>()
                val top = counts.entries.sortedByDescending { it.value }
                    .mapNotNull { (id, _) -> chatBox.get(id) }
                    .filter { it.dateDeleted == null && it.isRoutingStub != true && it.guid != null }
                    .take(limit)
                top.forEachIndexed { rank, chat -> publish(appContext, chat, rank, reportUsage = false) }
                Log.i(TAG, "Published ${top.size} share targets")
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh share targets", e)
            }
        }
    }

    /** The user just messaged in this chat: refresh its shortcut and report the usage. */
    fun reportUsed(context: Context, chatId: Long) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val chat = ObjectBox.store.boxFor<ChatEntity>().get(chatId) ?: return@launch
                publish(appContext, chat, rank = null, reportUsage = true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not report share target usage", e)
            }
        }
    }

    private fun publish(context: Context, chat: ChatEntity, rank: Int?, reportUsage: Boolean) {
        val guid = chat.guid ?: return
        val handles = chat.handles
        val isGroup = ChatResolver.isGroup(chat)
        val names = handles.mapNotNull { h -> h.address?.let { ContactUtils.getContactInfo(context, it).first ?: h.contactRelationId.target?.displayName ?: it } }
        val title = chat.displayName?.takeIf { it.isNotBlank() } ?: names.joinToString(", ").ifEmpty { return }
        val icon = if (isGroup) {
            chat.customAvatarPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
        } else {
            handles.firstOrNull()?.address?.let { ContactUtils.getContactInfo(context, it).second }
                ?: handles.firstOrNull()?.contactRelationId?.target?.avatar
        }
        val personUri = if (isGroup) null else handles.firstOrNull()?.address?.let { ChatResolver.withPrefix(it) }
        PushShareTargetsHandler().pushShareTarget(context, title, guid, icon, personUri, rank, reportUsage)
    }
}
