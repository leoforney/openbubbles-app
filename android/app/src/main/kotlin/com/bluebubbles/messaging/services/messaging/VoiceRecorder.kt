package com.bluebubbles.messaging.services.messaging

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Records voice notes as AAC in an .m4a container, the same format the Flutter app sends (Apple
 * clients play it as an audio message). The file is named "Audio Message.m4a", which
 * OutgoingMessageQueue uses to mark the message as a voice note.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(File(context.cacheDir, "voice"), UUID.randomUUID().toString()).apply { mkdirs() }
        val out = File(dir, "${OutgoingMessageQueue.VOICE_PREFIX}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Could not start recording", e)
            out.parentFile?.deleteRecursively()
            false
        }
    }

    /** Stops and returns the recording, or null if it was too short / failed. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val out = file
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            false // stop() throws if nothing was recorded
        } finally {
            r.release()
        }
        if (!ok || out == null || System.currentTimeMillis() - startedAt < 700) {
            out?.parentFile?.deleteRecursively()
            return null
        }
        return out
    }

    fun cancel() {
        stop()?.parentFile?.deleteRecursively()
    }
}
