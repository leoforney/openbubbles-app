package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.bluebubbles.messaging.objectbox.AttachmentEntity
import com.bluebubbles.messaging.objectbox.AttachmentEntity_
import com.bluebubbles.messaging.objectbox.ObjectBox
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import uniffi.rust_lib_bluebubbles.NativeProgressCallback
import uniffi.rust_lib_bluebubbles.NativePushState
import uniffi.rust_lib_bluebubbles.attachmentFromPlist
import java.io.File

/**
 * Downloads incoming attachments after the message is saved, honouring Flutter's `autoDownload` and
 * `onlyWifiDownload` settings. Files go where Dart and Compose look for them:
 * app_flutter/attachments/<guid>/<transferName>. Runs outside the incoming pipeline, so a slow
 * download never delays saving messages or posting notifications.
 */
internal object AttachmentDownloader {
    private const val TAG = "AttachmentDownloader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parallel = Semaphore(2)

    fun fileFor(context: Context, att: AttachmentEntity): File {
        val dir = File(File(context.getDir("flutter", Context.MODE_PRIVATE), "attachments"), att.guid ?: "")
        return File(dir, att.transferName ?: "attachment")
    }

    /** Download on demand (tap in the UI), regardless of auto-download settings. */
    fun downloadOnDemand(context: Context, attachmentId: Long) {
        val state = com.bluebubbles.messaging.services.rustpush.APNService.instance?.pushState ?: return
        val att = ObjectBox.store.boxFor<AttachmentEntity>().get(attachmentId) ?: return
        scope.launch { parallel.withPermit { download(context.applicationContext, state, att) } }
    }

    fun autoDownloadAllowed(context: Context): Boolean {
        if (!FlutterPrefs.bool(context, "autoDownload", true)) return false
        if (FlutterPrefs.bool(context, "onlyWifiDownload", false)) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm.isActiveNetworkMetered) return false
        }
        return true
    }

    /** Downloads all attachments of a message in the background, except [skipId] (already handled). */
    fun downloadIfEnabled(context: Context, state: NativePushState, messageId: Long, skipId: Long = 0) {
        if (!autoDownloadAllowed(context)) return
        val attachments = ObjectBox.store.boxFor<AttachmentEntity>()
            .query { apply(AttachmentEntity_.messageIdId.equal(messageId)) }.find()
        for (att in attachments) {
            if (att.id == skipId) continue
            scope.launch { parallel.withPermit { download(context, state, att) } }
        }
    }

    /** Downloads one attachment now (e.g. the image shown in a notification). True if the file exists afterwards. */
    suspend fun downloadNow(context: Context, state: NativePushState, att: AttachmentEntity): Boolean {
        download(context, state, att)
        return fileFor(context, att).isFile
    }

    /**
     * Android cannot play Apple's CAF voice notes. Like Dart, convert in place to AAC (m4a) while
     * keeping the original file name, so every reader finds the file where it expects it.
     */
    private fun convertCaf(file: File) {
        val dir = file.parentFile ?: return
        val src = File(dir, "encode.caf")
        val out = File(dir, "encode.m4a")
        if (!file.renameTo(src)) return
        val session = com.antonkarpenko.ffmpegkit.FFmpegKit.execute("-y -i \"${src.absolutePath}\" -c:a aac \"${out.absolutePath}\"")
        if (com.antonkarpenko.ffmpegkit.ReturnCode.isSuccess(session.returnCode) && out.isFile) {
            out.renameTo(file)
            src.delete()
        } else {
            Log.w(TAG, "CAF conversion failed: ${session.failStackTrace ?: session.output?.takeLast(300)}")
            out.delete()
            src.renameTo(file) // keep the original rather than losing it
        }
    }

    suspend fun download(context: Context, state: NativePushState, att: AttachmentEntity) {
        val file = fileFor(context, att)
        if (file.isFile) return
        val plist = att.dbMetadata?.let { runCatching { JSONObject(it).optString("rustpush", "") }.getOrNull() }
        if (plist.isNullOrEmpty()) return
        try {
            val native = attachmentFromPlist(plist)
            // Retry transient failures (e.g. "Send timeout" when the Apple connection blips).
            var attempt = 0
            while (true) {
                try {
                    state.downloadAttachment(native, file.absolutePath, object : NativeProgressCallback {
                        override fun onProgress(progress: ULong, total: ULong) {}
                    })
                    break
                } catch (e: uniffi.rust_lib_bluebubbles.NativeMsgException.Retryable) {
                    if (++attempt >= 3) throw e
                    Log.w(TAG, "Download of ${att.guid} failed (attempt $attempt): ${e.msg}; retrying")
                    kotlinx.coroutines.delay(2_000L * attempt)
                }
            }
            if (att.uti == "com.apple.coreaudio-format" || file.name.endsWith(".caf", ignoreCase = true)) convertCaf(file)
            // touch the row so observers (Compose/Flutter) re-render with the file present
            ObjectBox.update<AttachmentEntity>(att.id) { it.totalBytes = it.totalBytes ?: file.length() }
            Log.i(TAG, "Downloaded ${att.guid} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${att.guid}; it can be downloaded later", e)
        }
    }
}
