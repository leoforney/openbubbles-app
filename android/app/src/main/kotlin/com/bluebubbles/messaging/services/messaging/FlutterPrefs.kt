package com.bluebubbles.messaging.services.messaging

import android.content.Context

/** Read-only access to the settings Flutter stores via shared_preferences ("flutter." prefix). */
internal object FlutterPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean): Boolean =
        runCatching { prefs(context).getBoolean("flutter.$key", default) }.getOrDefault(default)

    fun string(context: Context, key: String, default: String?): String? =
        runCatching { prefs(context).getString("flutter.$key", default) }.getOrDefault(default)
}
