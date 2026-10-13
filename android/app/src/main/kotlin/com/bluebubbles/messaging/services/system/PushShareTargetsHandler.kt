package com.bluebubbles.messaging.services.system

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.models.MethodCallHandlerImpl
import com.bluebubbles.messaging.utils.Utils
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Create android share sheet targets
class PushShareTargetsHandler: MethodCallHandlerImpl() {
    companion object {
        const val tag = "push-share-targets"
    }

    override fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context
    ) {
        val name: String = call.argument("title")!!
        val guid: String = call.argument("guid")!!
        val icon: ByteArray? = call.argument("icon")
        pushShareTarget(context, name, guid, icon)
        result.success(null)
    }

    /**
     * Pushes (or refreshes) the conversation shortcut for a chat. Message notifications reference it via
     * setShortcutId/setLocusId, which is what makes Android treat the chat as a conversation (priority
     * conversations, bubbles, share-sheet ranking).
     *
     * @param personUri the contact's tel:/mailto: URI for 1:1 chats (lets Android match contacts / priority).
     * @param rank 0 = most important; used to order chats in the share sheet.
     * @param reportUsage true when the user actually messaged with the chat (feeds Android's ranking).
     */
    fun pushShareTarget(context: Context, name: String, guid: String, icon: ByteArray?,
                        personUri: String? = null, rank: Int? = null, reportUsage: Boolean = true) {
        val adaptiveIcon = if ((icon?.size ?: 0) == 0) null else Utils.getAdaptiveIconFromByteArray(icon!!)

        Log.d(Constants.logTag, "Creating intent for shortcut with name $name")
        val contactCategories = setOf(Constants.categoryTextShareTarget)
        // Tapping the conversation shortcut opens the chat in the Compose UI.
        val launcherIntent = Intent(context, com.bluebubbles.messaging.ComposeHomeActivity::class.java)
            .putExtra(com.bluebubbles.messaging.ComposeHomeActivity.EXTRA_CHAT_GUID, guid)
            .setAction(Intent.ACTION_VIEW)
        val person = Person.Builder().setName(name).setKey(guid).setImportant(true)
        if (personUri != null) person.setUri(personUri)
        if (adaptiveIcon != null) {
            person.setIcon(adaptiveIcon)
        }

        Log.d(Constants.logTag, "Creating and pushing shortcut for $name")
        val shortcut = ShortcutInfoCompat.Builder(context, guid)
            .setShortLabel(name)
            .setIntent(launcherIntent)
            .setCategories(contactCategories)
            .setLongLived(true)
            .setIsConversation()
            .setPerson(person.build())
            .addCapabilityBinding("actions.intent.CREATE_MESSAGE")
            .setLocusId(androidx.core.content.LocusIdCompat(guid))
        if (rank != null) shortcut.setRank(rank)
        if (adaptiveIcon != null) {
            shortcut.setIcon(adaptiveIcon)
        }

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut.build())
        if (reportUsage) ShortcutManagerCompat.reportShortcutUsed(context, guid)
    }
}