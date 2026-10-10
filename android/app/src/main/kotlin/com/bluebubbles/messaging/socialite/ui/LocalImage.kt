package com.bluebubbles.messaging.socialite.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads local image/video-thumbnail files without Coil.
 *
 * Coil 2 depends on okio 3 (`okio.Path`), but app/build.gradle force-pins okio 1.17.5 for
 * Cloud Firestore, so Coil's FileFetcher crashes with NoClassDefFoundError. This loader
 * uses BitmapFactory / MediaMetadataRetriever directly, with downsampling + an LRU cache.
 */
object LocalImageLoader {
    private const val TAG = "LocalImageLoader"

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(path: String, isVideo: Boolean, maxDim: Int = 1080): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = "$path|$maxDim|$isVideo"
            cache.get(key)?.let { return@withContext it.asImageBitmap() }
            val bmp = try {
                if (isVideo) decodeVideoFrame(path) else decodeSampled(path, maxDim)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to decode $path", e)
                null
            }
            if (bmp != null) cache.put(key, bmp) else Log.w(TAG, "Decoded null for $path")
            bmp?.asImageBitmap()
        }

    private fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        
        try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated != bmp) {
                    bmp.recycle()
                    return rotated
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EXIF for $path", e)
        }
        return bmp
    }

    private fun decodeVideoFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0)
        } finally {
            retriever.release()
        }
    }
}

@Composable
fun LocalImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    isVideo: Boolean = false,
    maxDim: Int = 1080,
    placeholder: @Composable () -> Unit = {}
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = LocalImageLoader.load(path, isVideo, maxDim)
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        placeholder()
    }
}

fun fileExists(path: String?): Boolean = path != null && File(path).isFile
