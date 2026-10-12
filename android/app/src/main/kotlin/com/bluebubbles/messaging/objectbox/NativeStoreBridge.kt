package com.bluebubbles.messaging.objectbox

import android.content.Context
import android.os.Process
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.objectbox.BoxStore
import org.json.JSONObject
import java.io.File

/**
 * Makes Dart use the *same* native store instance as Kotlin.
 *
 * ObjectBox Java's BoxStore is not registered in the ObjectBox C registry, so Dart's
 * `Store.isOpen(path)` returns false and Dart would open a second, independent store on the same
 * files. Two instances in one process each keep their own in-memory ID counter. That is how
 * natively sent messages got IDs that Dart later reused, overwriting existing rows.
 *
 * Kotlin wraps its store for the C API (`obx_store_wrap`) and publishes the pointer together with
 * the current process id in [referenceFile]. Dart (lib/database/database.dart) reads that file and
 * attaches with `Store.fromReference`.
 */
object NativeStoreBridge {
    private const val TAG = "NativeStoreBridge"

    @Suppress("FunctionName")
    private interface ObxC : Library {
        fun obx_store_wrap(coreStore: Pointer): Pointer?
        fun obx_store_is_open(path: String): Boolean
    }

    private val lib: ObxC by lazy { Native.load("objectbox-jni", ObxC::class.java) }

    /** Wrapped C store (OBX_store*) for Kotlin's BoxStore. Never closed: lives as long as the process. */
    @Volatile
    var cStore: Pointer? = null
        private set

    /** Read by Dart. Kept outside the objectbox directory so ObjectBox never sees it. */
    fun referenceFile(context: Context): File = File(context.getDir("flutter", Context.MODE_PRIVATE), "objectbox-native-store.json")

    /** True if a store for [dir] was already opened through the C API (i.e. by Dart) in this process. */
    fun isOpenedByDart(dir: File): Boolean = try {
        lib.obx_store_is_open(dir.absolutePath)
    } catch (t: Throwable) {
        Log.e(TAG, "obx_store_is_open failed", t)
        false
    }

    /** Wraps [store] and publishes it for Dart. Throws if that is not possible. */
    fun publish(context: Context, store: BoxStore, dir: File) {
        val wrapped = lib.obx_store_wrap(Pointer(store.nativeStore))
            ?: throw IllegalStateException("obx_store_wrap returned null")
        cStore = wrapped
        val json = JSONObject()
            .put("pid", Process.myPid())
            .put("pointer", Pointer.nativeValue(wrapped).toString())
            .put("directory", dir.absolutePath)
        val file = referenceFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) throw IllegalStateException("could not write ${file.absolutePath}")
        Log.i(TAG, "Published native store for Dart: $json")
    }

    fun clear(context: Context) {
        referenceFile(context).delete()
    }
}
