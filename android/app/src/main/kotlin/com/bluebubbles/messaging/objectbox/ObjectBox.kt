package com.bluebubbles.messaging.objectbox

import android.content.Context
import io.objectbox.BoxStore
import java.io.File

object ObjectBox {
    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) {
            val dbDir = File(context.getDir("flutter", Context.MODE_PRIVATE), "objectbox")
            
            store = MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .directory(dbDir)
                .build()
            ChatRepository.init(context)
        }
    }
}
