package com.bluebubbles.messaging.services.messaging

import android.util.Log
import com.bluebubbles.messaging.objectbox.ObjectBox
import com.bluebubbles.messaging.services.rustpush.APNService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.rust_lib_bluebubbles.NativePushMessage

/**
 * Entry point for every push rustpush emits (`MsgReceiver.receieved_msg`).
 *
 * Pushes are processed strictly one at a time, in arrival order, so edits/reactions never overtake
 * the message they refer to. A push is completed (removed from rustpush's queue) only after it has
 * been saved. Until then rustpush re-emits it every 30 s (up to 5 times).
 *
 * Push types that are not ported yet go to Dart exactly as before; so does a supported push that
 * keeps failing natively. Dart attaches to the same native store, so this is safe and nothing is
 * dropped while the port is incomplete.
 */
object IncomingPipeline {
    private const val TAG = "IncomingPipeline"

    /** Native attempts before a failing push is handed to Dart instead (rustpush retry counter). */
    private const val NATIVE_ATTEMPTS = 2uL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<ULong, ULong>>(Channel.UNLIMITED)
    @Volatile private var started = false

    fun submit(service: APNService, key: ULong, retry: ULong) {
        ensureStarted(service)
        queue.trySend(key to retry)
    }

    @Synchronized
    private fun ensureStarted(service: APNService) {
        if (started) return
        started = true
        scope.launch {
            for ((key, retry) in queue) {
                val svc = APNService.instance ?: service
                try {
                    process(svc, key, retry)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected failure for push $key", e)
                }
            }
        }
    }

    private suspend fun process(service: APNService, key: ULong, retry: ULong) {
        val state = service.pushState ?: return // not ready; rustpush re-emits later
        if (!ObjectBox.isOpen) {
            // Native store unavailable: Dart owns the database, keep the old path.
            service.forwardToDart(key, retry)
            return
        }
        val payload = state.takePushMessage(key) ?: return // already completed
        val inst = (payload as? NativePushMessage.IMessage)?.msg
        if (inst == null || !IncomingMessageHandler.supports(inst)) {
            service.forwardToDart(key, retry)
            return
        }

        val handler = IncomingMessageHandler(service, state)
        try {
            handler.handle(inst)
        } catch (e: Exception) {
            if (retry >= NATIVE_ATTEMPTS) {
                Log.e(TAG, "Native handling of ${inst.id} failed $retry times; handing it to Dart", e)
                service.forwardToDart(key, retry)
            } else {
                Log.w(TAG, "Native handling of ${inst.id} failed (attempt $retry); rustpush will retry", e)
            }
            return
        }
        try {
            handler.certify(inst)
        } catch (e: Exception) {
            Log.w(TAG, "Delivery certification failed for ${inst.id}", e)
        }
        state.completeMsg(key)
        Log.i(TAG, "Handled ${inst.message.javaClass.simpleName} ${inst.id} natively")
    }
}
