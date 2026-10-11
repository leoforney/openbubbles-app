package com.bluebubbles.messaging

import android.app.Application
import android.util.Log
import com.bluebubbles.messaging.objectbox.ObjectBox

class BlueBubblesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Open the shared ObjectBox store before any FlutterEngine starts, so Kotlin's open options
        // apply and Dart attaches to this store instead of opening its own.
        try {
            ObjectBox.init(this)
        } catch (e: Exception) {
            // Never crash at startup over this: Dart opens the store itself, as it always has.
            Log.e("BlueBubblesApplication", "Native ObjectBox init failed; Flutter will open the store", e)
        }
    }
}
