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

    fun downloadIfEnabled(context: Context, state: NativePushState, messageId: Long) {
        if (!FlutterPrefs.bool(context, "autoDownload", true)) return
        if (FlutterPrefs.bool(context, "onlyWifiDownload", false)) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm.isActiveNetworkMetered) return
        }
        val attachments = ObjectBox.store.boxFor<AttachmentEntity>()
            .query { apply(AttachmentEntity_.messageIdId.equal(messageId)) }.find()
        for (att in attachments) {
            scope.launch { parallel.withPermit { download(context, state, att) } }
        }
    }

    private suspend fun download(context: Context, state: NativePushState, att: AttachmentEntity) {
        val file = fileFor(context, att)
        if (file.isFile) return
        val plist = att.dbMetadata?.let { runCatching { JSONObject(it).optString("rustpush", "") }.getOrNull() }
        if (plist.isNullOrEmpty()) return
        try {
            val native = attachmentFromPlist(plist)
            state.downloadAttachment(native, file.absolutePath, object : NativeProgressCallback {
                override fun onProgress(progress: ULong, total: ULong) {}
            })
            // touch the row so observers (Compose/Flutter) re-render with the file present
            ObjectBox.update<AttachmentEntity>(att.id) { it.totalBytes = it.totalBytes ?: file.length() }
            Log.i(TAG, "Downloaded ${att.guid} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${att.guid}; it can be downloaded later", e)
        }
    }
}
