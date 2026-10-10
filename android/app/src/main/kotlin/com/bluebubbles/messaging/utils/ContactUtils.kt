package com.bluebubbles.messaging.utils

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads contact names/photos directly from the system Contacts provider instead of
 * OpenBubbles' own (stale) contact cache. Results are memoized in-memory, and the memo
 * is cleared immediately whenever the system contacts database changes.
 */
object ContactUtils {
    private const val TAG = "ContactUtils"

    private data class Entry(val name: String?, val avatar: ByteArray?)

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var observerRegistered = false

    /** Incremented whenever contacts change so UIs can re-query. */
    @Volatile var version: Long = 0
        private set

    private fun ensureObserver(context: Context) {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            try {
                context.applicationContext.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            Log.d(TAG, "System contacts changed, clearing cache")
                            cache.clear()
                            version++
                        }
                    }
                )
                observerRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register contacts observer", e)
            }
        }
    }

    private fun normalize(address: String): String =
        address.removePrefix("tel:").removePrefix("mailto:").trim()

    fun getContactInfo(context: Context, rawAddress: String): Pair<String?, ByteArray?> {
        ensureObserver(context)
        val address = normalize(rawAddress)
        if (address.isEmpty()) return Pair(null, null)
        cache[address]?.let { return Pair(it.name, it.avatar) }

        val result = lookup(context, address)
        Log.d(TAG, "lookup '$address' -> name=${result.name} photo=${result.avatar?.size ?: 0}B")
        cache[address] = result
        return Pair(result.name, result.avatar)
    }

    private fun lookup(context: Context, address: String): Entry {
        val isEmail = address.contains("@")
        val uri: Uri = if (isEmail) {
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(address))
        } else {
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        }
        val projection = if (isEmail) {
            arrayOf(
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.PHOTO_URI
            )
        } else {
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
        }

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val photoUriStr = cursor.getString(1)
                    var avatar: ByteArray? = null
                    if (photoUriStr != null) {
                        try {
                            context.contentResolver.openInputStream(Uri.parse(photoUriStr))?.use {
                                avatar = it.readBytes()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read photo for $address", e)
                        }
                    }
                    return Entry(name, avatar)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission missing", e)
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $address", e)
        }
        return Entry(null, null)
    }
}
