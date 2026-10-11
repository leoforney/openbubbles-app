package com.bluebubbles.messaging.objectbox

import android.util.Log
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.objectbox.BoxStore
import io.objectbox.reactive.DataSubscription
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Reports every commit to the store, no matter which runtime made it.
 *
 * ObjectBox Java observers (`store.subscribe()`) only fire for commits made through Java. Messages
 * written by Dart (incoming messages, delivery receipts, ...) would never reach Compose. This class
 * also registers an observer with the ObjectBox C API (`obx_observe`), which runs inside the
 * shared native store and so sees commits from Java and Dart alike. This is the same mechanism
 * Dart's `query.watch()` uses.
 */
object StoreChanges {
    private const val TAG = "StoreChanges"

    /** Entity type ids (`<Entity>_.__ENTITY_ID`) touched by a commit. */
    private val changes = MutableSharedFlow<Set<Int>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val all: Flow<Set<Int>> = changes.asSharedFlow()

    var nativeObserverActive = false
        private set

    fun of(vararg entityIds: Int): Flow<Unit> {
        val wanted = entityIds.toSet()
        return all.filter { changed -> changed.any { it in wanted } }.map { }
    }

    @Suppress("FunctionName")
    private interface ObxC : Library {
        fun obx_store_wrap(coreStore: Pointer): Pointer?
        fun obx_observe(store: Pointer, callback: ObxObserver, userData: Pointer?): Pointer?
    }

    /** `void obx_observer(const obx_schema_id* type_ids, size_t type_ids_count, void* user_data)` */
    private fun interface ObxObserver : Callback {
        fun invoke(typeIds: Pointer?, count: NativeLong, userData: Pointer?)
    }

    // Strong references: JNA callbacks and native handles must outlive the process-wide store.
    private var obxStore: Pointer? = null
    private var observerHandle: Pointer? = null
    private var observerCallback: ObxObserver? = null
    private var javaSubscription: DataSubscription? = null

    @Synchronized
    fun start(store: BoxStore) {
        if (javaSubscription != null) return

        // Java commits: always available.
        javaSubscription = store.subscribe().onlyChanges().observer { entityClass ->
            val id = entityIdOf(entityClass) ?: return@observer
            changes.tryEmit(setOf(id))
        }

        // Dart (and Java) commits via the C API. Must not start transactions inside the callback.
        try {
            val lib = Native.load("objectbox-jni", ObxC::class.java)
            val wrapped = lib.obx_store_wrap(Pointer(store.nativeStore))
                ?: throw IllegalStateException("obx_store_wrap returned null")
            val callback = ObxObserver { typeIds, count, _ ->
                val n = count.toInt()
                if (typeIds != null && n > 0) {
                    changes.tryEmit(typeIds.getIntArray(0, n).toSet())
                }
            }
            val handle = lib.obx_observe(wrapped, callback, null)
                ?: throw IllegalStateException("obx_observe returned null")
            obxStore = wrapped
            observerCallback = callback
            observerHandle = handle
            nativeObserverActive = true
            Log.i(TAG, "Native store observer registered")
        } catch (t: Throwable) {
            // Not fatal: Compose still refreshes on Kotlin commits and when screens reload.
            Log.e(TAG, "Native store observer unavailable; Dart writes will not live-update Compose", t)
        }
    }

    private fun entityIdOf(entityClass: Class<*>): Int? = when (entityClass) {
        ChatEntity::class.java -> ChatEntity_.__ENTITY_ID
        MessageEntity::class.java -> MessageEntity_.__ENTITY_ID
        AttachmentEntity::class.java -> AttachmentEntity_.__ENTITY_ID
        HandleEntity::class.java -> HandleEntity_.__ENTITY_ID
        ContactEntity::class.java -> ContactEntity_.__ENTITY_ID
        else -> null
    }
}
