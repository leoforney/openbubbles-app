package com.bluebubbles.messaging.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.bluebubbles.messaging.socialite.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaSaver {
    private const val TAG = "MediaSaver"

    /**
     * Saves an [attachment] to the public device media/downloads folder and shows a Toast.
     */
    suspend fun saveAttachment(context: Context, attachment: Attachment): Boolean = withContext(Dispatchers.IO) {
        val file = File(attachment.path)
        if (!file.exists() || !file.canRead()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "File is not downloaded yet", Toast.LENGTH_SHORT).show()
            }
            return@withContext false
        }

        val name = attachment.transferName?.takeIf { it.isNotBlank() } ?: file.name
        val mime = attachment.mimeType ?: "application/octet-stream"
        val savedUri = saveFile(context, file, name, mime)

        withContext(Dispatchers.Main) {
            if (savedUri != null) {
                val folder = when {
                    mime.startsWith("image/") -> "Pictures"
                    mime.startsWith("video/") -> "Movies"
                    mime.startsWith("audio/") -> "Music"
                    else -> "Downloads"
                }
                Toast.makeText(context, "Saved to $folder: $name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save $name", Toast.LENGTH_SHORT).show()
            }
        }
        savedUri != null
    }

    /**
     * Saves a list of [attachments] to the public device media/downloads folder.
     */
    suspend fun saveAllAttachments(context: Context, attachments: List<Attachment>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (att in attachments) {
            val file = File(att.path)
            if (file.exists() && file.canRead()) {
                val name = att.transferName?.takeIf { it.isNotBlank() } ?: file.name
                val mime = att.mimeType ?: "application/octet-stream"
                if (saveFile(context, file, name, mime) != null) {
                    count++
                }
            }
        }
        withContext(Dispatchers.Main) {
            if (count > 0) {
                Toast.makeText(context, "Saved $count item(s) to device", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No items were saved", Toast.LENGTH_SHORT).show()
            }
        }
        count
    }

    private fun saveFile(context: Context, source: File, displayName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        return try {
            val collection: Uri
            val relativeDir: String

            when {
                mimeType.startsWith("image/") -> {
                    collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    relativeDir = Environment.DIRECTORY_PICTURES + "/OpenBubbles"
                }
                mimeType.startsWith("video/") -> {
                    collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    relativeDir = Environment.DIRECTORY_MOVIES + "/OpenBubbles"
                }
                mimeType.startsWith("audio/") -> {
                    collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    relativeDir = Environment.DIRECTORY_MUSIC + "/OpenBubbles"
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        relativeDir = Environment.DIRECTORY_DOWNLOADS + "/OpenBubbles"
                    } else {
                        // Legacy Android 9 (API 28) file saving for downloads
                        val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenBubbles").apply { mkdirs() }
                        val targetFile = File(targetDir, displayName)
                        FileInputStream(source).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        return Uri.fromFile(targetFile)
                    }
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(source).use { inp ->
                    inp.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }

            itemUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file: $displayName", e)
            null
        }
    }
}
