package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.util.Log
import com.bluebubbles.messaging.objectbox.ChatEntity
import org.json.JSONArray
import uniffi.rust_lib_bluebubbles.NativeShareProfile
import uniffi.rust_lib_bluebubbles.decodeProfileMessage

/**
 * Name & Photo sharing on outgoing messages, port of `getShareProfileMessageFor`
 * (rustpush_service.dart): only in 1:1 chats, only if the user enabled sharing and automatic
 * sharing, only with saved (non-shared) contacts, and only once per contact (`sharedContacts`).
 */
internal object ProfileSharing {
    private const val TAG = "ProfileSharing"

    @Synchronized
    fun forChat(context: Context, chat: ChatEntity): NativeShareProfile? {
        try {
            val handles = chat.handles
            if (handles.size != 1) return null
            val encoded = FlutterPrefs.string(context, "shareProfileMessage", null) ?: return null
            if (!FlutterPrefs.bool(context, "shareContactAutomatically", true)) return null
            if (!FlutterPrefs.bool(context, "nameAndPhotoSharing", false)) return null
            val handle = handles.first()
            val address = handle.address ?: return null
            // Dart: !(t.contact?.isShared ?? true) – the handle must have a real (non-shared) contact
            if (handle.contactRelationId.target?.isShared != false) return null
            val shared = sharedContacts(context)
            if (address in shared) return null
            FlutterPrefs.putString(context, "sharedContacts", JSONArray(shared + address).toString())
            return decodeProfileMessage(encoded)
        } catch (e: Exception) {
            Log.w(TAG, "Not sharing profile with ${chat.guid}", e)
            return null
        }
    }

    private fun sharedContacts(context: Context): List<String> {
        val raw = FlutterPrefs.string(context, "sharedContacts", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
