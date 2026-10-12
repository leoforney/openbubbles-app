package com.bluebubbles.messaging.socialite.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bluebubbles.messaging.socialite.model.Attachment
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File

/** Opens a file (video, document) in the system app for its type. */
fun openExternally(context: android.content.Context, attachment: Attachment) {
    try {
        val file = File(attachment.path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.getString(com.bluebubbles.messaging.R.string.file_provider), file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.mimeType ?: "*/*")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("AttachmentView", "No app can open ${attachment.mimeType}", e)
    }
}

/**
 * All attachments of one message, rendered like iMessage: 1 = full width, 2 = side by side,
 * 3+ = 2-column grid with a "+N" tile after 4. Non-visual attachments (audio, files) are listed below.
 */
@Composable
fun AttachmentGroup(attachments: List<Attachment>, isIncoming: Boolean, onClick: (Attachment) -> Unit) {
    val visual = attachments.filter { val m = it.mimeType ?: ""; m.startsWith("image/") || m.startsWith("video/") }
    val other = attachments - visual.toSet()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        when {
            visual.size == 1 -> AttachmentView(visual[0], onClick = { onClick(visual[0]) })
            visual.size > 1 -> {
                val shown = visual.take(4)
                shown.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { i, att ->
                            val index = rowIndex * 2 + i
                            Box(Modifier.weight(1f).height(if (visual.size == 2) 180.dp else 130.dp)) {
                                AttachmentView(att, onClick = { onClick(att) }, tile = true)
                                if (index == 3 && visual.size > 4) {
                                    Box(
                                        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                                            .background(Color.Black.copy(alpha = 0.5f)).clickable { onClick(att) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+${visual.size - 4}", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                                    }
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        for (att in other) AttachmentView(att, onClick = { onClick(att) }, isIncoming = isIncoming)
    }
}

@Composable
fun AttachmentView(attachment: Attachment, onClick: () -> Unit, tile: Boolean = false, isIncoming: Boolean = true) {
    val mime = attachment.mimeType ?: ""
    val file = File(attachment.path)
    val context = androidx.compose.ui.platform.LocalContext.current

    if (!attachment.isDownloaded) {
        // Not downloaded yet (auto-download off or still in progress): tap to fetch it.
        var requested by remember(attachment.id) { androidx.compose.runtime.mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tile) Modifier.fillMaxSize() else Modifier.height(120.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.15f))
                .clickable(enabled = !requested && attachment.id != 0L) {
                    requested = true
                    com.bluebubbles.messaging.services.messaging.AttachmentDownloader.downloadOnDemand(context, attachment.id)
                },
            contentAlignment = Alignment.Center
        ) {
            if (requested) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, contentDescription = "Download")
                    Spacer(Modifier.width(6.dp))
                    Text(attachment.transferName ?: "Attachment", maxLines = 1)
                }
            }
        }
        return
    }

    when {
        mime.startsWith("audio/") -> {
            AudioPlayer(attachment, isIncoming)
            return
        }
        !mime.startsWith("image/") && !mime.startsWith("video/") -> {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.1f))
                    .clickable { openExternally(context, attachment) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = "File")
                Spacer(modifier = Modifier.width(8.dp))
                Text(attachment.transferName ?: "File", maxLines = 1)
            }
            return
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tile) Modifier.fillMaxSize() else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable { if (mime.startsWith("video/")) openExternally(context, attachment) else onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (mime.startsWith("image/")) {
            LocalImage(
                path = file.absolutePath,
                contentDescription = "Image attachment",
                modifier = if (tile) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp),
                contentScale = ContentScale.Crop,
                placeholder = { Box(Modifier.fillMaxWidth().height(150.dp)) }
            )
        } else {
            Box(modifier = (if (tile) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(200.dp)).background(Color.Black)) {
                LocalImage(
                    path = file.absolutePath,
                    contentDescription = "Video thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    isVideo = true
                )
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Video",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp).align(Alignment.Center)
                )
            }
        }
    }
}

/** Inline voice note / audio player: play/pause, draggable scrubber, elapsed / total time. */
@Composable
fun AudioPlayer(attachment: Attachment, isIncoming: Boolean) {
    var playing by remember(attachment.path) { androidx.compose.runtime.mutableStateOf(false) }
    var positionMs by remember(attachment.path) { androidx.compose.runtime.mutableIntStateOf(0) }
    var durationMs by remember(attachment.path) { androidx.compose.runtime.mutableIntStateOf(0) }
    var scrubbing by remember(attachment.path) { androidx.compose.runtime.mutableStateOf(false) }
    var scrubValue by remember(attachment.path) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val player = remember(attachment.path) { androidx.compose.runtime.mutableStateOf<android.media.MediaPlayer?>(null) }

    // Show the length before playing.
    androidx.compose.runtime.LaunchedEffect(attachment.path) {
        durationMs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Not `use {}`: MediaMetadataRetriever is only AutoCloseable on API 29+, minSdk is 28.
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(attachment.path)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
            } catch (e: Exception) {
                0
            } finally {
                r.release()
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(attachment.path) {
        onDispose { player.value?.release(); player.value = null }
    }
    androidx.compose.runtime.LaunchedEffect(playing) {
        while (playing) {
            val p = player.value ?: break
            if (!scrubbing) positionMs = p.currentPosition
            kotlinx.coroutines.delay(50)
        }
    }

    fun ensurePlayer(): android.media.MediaPlayer? = player.value ?: try {
        android.media.MediaPlayer().apply {
            setDataSource(attachment.path)
            prepare()
            if (duration > 0) durationMs = duration
            setOnCompletionListener { playing = false; positionMs = 0; seekTo(0) }
        }.also { player.value = it }
    } catch (e: Exception) {
        Log.w("AudioPlayer", "Cannot play ${attachment.path}", e)
        null
    }

    Row(
        modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = 220.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = {
            val p = ensurePlayer() ?: return@IconButton
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
        }) {
            Icon(
                if (playing) androidx.compose.material.icons.Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play"
            )
        }
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        androidx.compose.material3.Slider(
            value = if (scrubbing) scrubValue else fraction.coerceIn(0f, 1f),
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                val target = (scrubValue * durationMs).toInt()
                ensurePlayer()?.seekTo(target)
                positionMs = target
                scrubbing = false
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        val shown = if (scrubbing) (scrubValue * durationMs).toInt() else if (playing || positionMs > 0) positionMs else durationMs
        val secs = shown / 1000
        Text(
            if (durationMs > 0) "%d:%02d".format(secs / 60, secs % 60) else "Audio",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun FullscreenAttachmentViewer(
    initialAttachment: Attachment,
    allAttachments: List<Attachment>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val initialPage = remember { allAttachments.indexOfFirst { it.guid == initialAttachment.guid }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { allAttachments.size })

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val attachment = allAttachments[page]
                val mime = attachment.mimeType ?: ""
                val file = File(attachment.path)

                if (mime.startsWith("image/")) {
                    val zoomState = rememberZoomState()
                    LocalImage(
                        path = file.absolutePath,
                        contentDescription = "Image attachment fullscreen",
                        modifier = Modifier.fillMaxSize().zoomable(
                            zoomState = zoomState,
                            onTap = { onDismiss() }
                        ),
                        contentScale = ContentScale.Fit
                    )
                } else if (mime.startsWith("video/")) {
                    Box(modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }) {
                        LocalImage(
                            path = file.absolutePath,
                            contentDescription = "Video attachment fullscreen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            isVideo = true
                        )
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play Video",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp).align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}