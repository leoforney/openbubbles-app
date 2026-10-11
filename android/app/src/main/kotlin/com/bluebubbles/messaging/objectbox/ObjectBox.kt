package com.bluebubbles.messaging.objectbox

import android.content.Context
import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore
import java.io.File

/**
 * Owner of the single ObjectBox store shared by Kotlin and Flutter.
 *
 * Data-safety rules (see SERVICE_TRANSITION.md §5):
 * - Kotlin opens the store first, from [com.bluebubbles.messaging.BlueBubblesApplication], before any
 *   FlutterEngine exists. Dart then sees the store as open and uses `Store.attach` (lib/database/database.dart).
 * - Options that are fixed at open time must match what Dart passes to `openStore`, because whoever
 *   opens first decides them for both runtimes.
 * - The store is never closed: Dart may still be attached to it.
 * - The Kotlin and Dart models must be identical; the `verifyObjectBoxModels` Gradle task enforces this.
 */
object ObjectBox {
    private const val TAG = "ObjectBox"

    /** Same as `maxDBSizeInKB: 5 * 1024 * 1024` in lib/database/database.dart. The Java default is only 1 GB. */
    const val MAX_DB_SIZE_KB: Long = 5L * 1024 * 1024

    @Volatile
    private var _store: BoxStore? = null

    val store: BoxStore
        get() = _store ?: throw IllegalStateException("ObjectBox store is not open; call ObjectBox.init() first")

    val isOpen: Boolean
        get() = _store != null

    /** Same directory as Dart's `join(getApplicationDocumentsDirectory(), 'objectbox')`, i.e. app_flutter/objectbox. */
    fun directory(context: Context): File = File(context.getDir("flutter", Context.MODE_PRIVATE), "objectbox")

    /**
     * Opens the store. Idempotent and thread-safe.
     *
     * If opening fails the exception is logged and rethrown; nothing is deleted or recreated. Dart will
     * then open the store itself exactly like it did before the native data layer existed.
     */
    fun init(context: Context) {
        if (_store != null) return
        synchronized(this) {
            if (_store != null) return
            val appContext = context.applicationContext
            val dir = directory(appContext)
            try {
                val opened = MyObjectBox.builder()
                    .androidContext(appContext)
                    .directory(dir)
                    .maxSizeInKByte(MAX_DB_SIZE_KB)
                    .build()
                _store = opened
                Log.i(TAG, "Opened ObjectBox store at ${dir.absolutePath} (size on disk ${opened.sizeOnDisk() / 1024} KB)")
                StoreChanges.start(opened)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ObjectBox store at ${dir.absolutePath}", e)
                throw e
            }
            ChatRepository.init(appContext)
        }
    }

    /**
     * Read-modify-write of a single object inside one write transaction.
     *
     * Dart writes to the same store, so reading an object, changing it and putting it back outside a
     * transaction can silently overwrite a change Dart committed in between. Always use this (or
     * [BoxStore.runInTx]) when updating existing objects.
     *
     * @return the updated object, or null if it no longer exists.
     */
    inline fun <reified T> update(id: Long, crossinline modify: (T) -> Unit): T? {
        return store.callInTx {
            val box: Box<T> = store.boxFor(T::class.java)
            val obj = box.get(id) ?: return@callInTx null
            modify(obj)
            box.put(obj)
            obj
        }
    }
}
