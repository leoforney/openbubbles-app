package com.bluebubbles.messaging.socialite.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.bluebubbles.messaging.R
import com.bluebubbles.messaging.services.messaging.AttachmentDownloader
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.utils.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File
import kotlin.math.abs

/** Opens a file (video, document) in the system app for its type. */
fun openExternally(context: Context, attachment: Attachment) {
    try {
        val file = File(attachment.path)
        val uri = FileProvider.getUriForFile(
            context, context.getString(R.string.file_provider), file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("AttachmentView", "No app can open ${attachment.mimeType}", e)
    }
}

/**
 * All attachments of one message. Visual attachments (images/videos) are rendered as an iMessage-style
 * deck-of-cards photo stack (or single photo), while non-visual attachments are rendered below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentGroup(
    attachments: List<Attachment>,
    isIncoming: Boolean,
    onClick: (Attachment) -> Unit,
    onLongClick: () -> Unit = {}
) {
    val visual = attachments.filter { val m = it.mimeType ?: ""; m.startsWith("image/") || m.startsWith("video/") }
    val other = attachments - visual.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (visual.isNotEmpty()) {
            PhotoCardStack(attachments = visual, isIncoming = isIncoming, onClick = onClick, onLongClick = onLongClick)
        }
        for (att in other) {
            AttachmentView(att, onClick = { onClick(att) }, onLongClick = onLongClick, isIncoming = isIncoming)
        }
    }
}

/**
 * iMessage-style photo stack. When 1 photo is present, shows a clean borderless card.
 * When 2 or more photos are present, renders an interactive deck of cards with physics-like
 * horizontal swipe transitions, slight card rotation/offsets, a counter pill, and a save button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoCardStack(
    attachments: List<Attachment>,
    isIncoming: Boolean,
    onClick: (Attachment) -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    if (attachments.size == 1) {
        SingleVisualAttachment(
            attachment = attachments[0],
            isIncoming = isIncoming,
            onClick = { onClick(attachments[0]) },
            onLongClick = onLongClick,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentIndex by remember { mutableIntStateOf(0) }
    val dragOffsetX = remember { Animatable(0f) }
    val cardCount = attachments.size

    val density = LocalDensity.current
    val thresholdPx = with(density) { 70.dp.toPx() }

    Box(
        modifier = modifier
            .width(260.dp)
            .height(310.dp)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Render up to 3 cards from back to front
        val maxVisibleCards = minOf(3, cardCount)
        val indices = (maxVisibleCards - 1 downTo 0).map { offset ->
            (currentIndex + offset) % cardCount
        }

        indices.forEach { idx ->
            val positionFromFront = (idx - currentIndex + cardCount) % cardCount
            val attachment = attachments[idx]
            val isTopCard = positionFromFront == 0

            val dragProgress = (abs(dragOffsetX.value) / with(density) { 260.dp.toPx() }).coerceIn(0f, 1f)

            val baseRotation = when (positionFromFront) {
                0 -> (dragOffsetX.value / with(density) { 260.dp.toPx() }) * 14f
                1 -> if (isIncoming) -4.5f * (1f - dragProgress) else 4.5f * (1f - dragProgress)
                2 -> if (isIncoming) 3.5f * (1f - dragProgress) else -3.5f * (1f - dragProgress)
                else -> 0f
            }

            val baseScale = when (positionFromFront) {
                0 -> 1f
                1 -> 0.94f + (0.06f * dragProgress)
                2 -> 0.88f + (0.06f * dragProgress)
                else -> 0.85f
            }

            val baseOffsetY = when (positionFromFront) {
                0 -> 0.dp
                1 -> (8.dp * (1f - dragProgress))
                2 -> (16.dp * (1f - dragProgress))
                else -> 20.dp
            }

            val baseOffsetX = when (positionFromFront) {
                0 -> with(density) { dragOffsetX.value.toDp() }
                1 -> if (isIncoming) (5.dp * (1f - dragProgress)) else (-5.dp * (1f - dragProgress))
                2 -> if (isIncoming) (-4.dp * (1f - dragProgress)) else (4.dp * (1f - dragProgress))
                else -> 0.dp
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = baseOffsetX, y = baseOffsetY)
                    .graphicsLayer {
                        rotationZ = baseRotation
                        scaleX = baseScale
                        scaleY = baseScale
                        shadowElevation = if (isTopCard) 8.dp.toPx() else 4.dp.toPx()
                        shape = RoundedCornerShape(18.dp)
                        clip = true
                    }
                    .background(Color(0xFF1E1E1E))
                    .then(
                        if (isTopCard) {
                            Modifier
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (abs(dragOffsetX.value) < 10f) {
                                            onClick(attachment)
                                        }
                                    },
                                    onLongClick = onLongClick
                                )
                                .draggable(
                                    state = rememberDraggableState { delta ->
                                        coroutineScope.launch {
                                            dragOffsetX.snapTo(dragOffsetX.value + delta)
                                        }
                                    },
                                    orientation = Orientation.Horizontal,
                                    onDragStopped = { velocity ->
                                        coroutineScope.launch {
                                            if (dragOffsetX.value < -thresholdPx || velocity < -1000f) {
                                                // Swipe Left -> advance to next card
                                                dragOffsetX.animateTo(-700f, tween(160))
                                                currentIndex = (currentIndex + 1) % cardCount
                                                dragOffsetX.snapTo(0f)
                                            } else if (dragOffsetX.value > thresholdPx || velocity > 1000f) {
                                                // Swipe Right -> previous card
                                                dragOffsetX.animateTo(700f, tween(160))
                                                currentIndex = (currentIndex - 1 + cardCount) % cardCount
                                                dragOffsetX.snapTo(0f)
                                            } else {
                                                dragOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                            }
                                        }
                                    }
                                )
                        } else {
                            Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = onLongClick
                            )
                        }
                    )
            ) {
                VisualAttachmentContent(
                    attachment = attachment
                )

                if (isTopCard) {
                    // Counter pill at bottom-right ("1 of 4")
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${currentIndex + 1} of $cardCount",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    // Save / download button at top-right
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .clickable {
                                coroutineScope.launch {
                                    MediaSaver.saveAttachment(context, attachment)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save media",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Clean borderless single photo / video card without bubble wrapper. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SingleVisualAttachment(
    attachment: Attachment,
    isIncoming: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .widthIn(max = 260.dp)
            .heightIn(min = 160.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E1E1E))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        VisualAttachmentContent(attachment = attachment)

        // Save button overlay
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable {
                    coroutineScope.launch {
                        MediaSaver.saveAttachment(context, attachment)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Save media",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun VisualAttachmentContent(attachment: Attachment) {
    val mime = attachment.mimeType ?: ""
    val file = File(attachment.path)
    val context = LocalContext.current

    if (!attachment.isDownloaded) {
        var requested by remember(attachment.id) { mutableStateOf(false) }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (requested) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
            } else {
                Row(
                    modifier = Modifier.clickable(enabled = attachment.id != 0L) {
                        requested = true
                        AttachmentDownloader.downloadOnDemand(context, attachment.id)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download", tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(attachment.transferName ?: "Download", color = Color.White, maxLines = 1)
                }
            }
        }
        return
    }

    if (mime.startsWith("image/")) {
        LocalImage(
            path = file.absolutePath,
            contentDescription = "Image attachment",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = { Box(Modifier.fillMaxSize().background(Color.Gray)) }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentView(
    attachment: Attachment,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    tile: Boolean = false,
    isIncoming: Boolean = true
) {
    val mime = attachment.mimeType ?: ""
    val file = File(attachment.path)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (!attachment.isDownloaded) {
        var requested by remember(attachment.id) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tile) Modifier.fillMaxSize() else Modifier.height(120.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.15f))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !requested && attachment.id != 0L,
                    onClick = {
                        requested = true
                        AttachmentDownloader.downloadOnDemand(context, attachment.id)
                    },
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (requested) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download")
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
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.1f))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { openExternally(context, attachment) },
                        onLongClick = onLongClick
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = "File")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = attachment.transferName ?: "File",
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            MediaSaver.saveAttachment(context, attachment)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save file",
                        modifier = Modifier.size(18.dp)
                    )
                }
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
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (mime.startsWith("video/")) openExternally(context, attachment) else onClick() },
                onLongClick = onLongClick
            ),
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

/** Inline voice note / audio player: play/pause, draggable scrubber, elapsed / total time, and save. */
@Composable
fun AudioPlayer(attachment: Attachment, isIncoming: Boolean) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var playing by remember(attachment.path) { mutableStateOf(false) }
    var positionMs by remember(attachment.path) { mutableIntStateOf(0) }
    var durationMs by remember(attachment.path) { mutableIntStateOf(0) }
    var scrubbing by remember(attachment.path) { mutableStateOf(false) }
    var scrubValue by remember(attachment.path) { mutableFloatStateOf(0f) }
    val player = remember(attachment.path) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(attachment.path) {
        durationMs = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(attachment.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
            } catch (e: Exception) {
                0
            } finally {
                r.release()
            }
        }
    }
    DisposableEffect(attachment.path) {
        onDispose { player.value?.release(); player.value = null }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player.value ?: break
            if (!scrubbing) positionMs = p.currentPosition
            delay(50)
        }
    }

    fun ensurePlayer(): MediaPlayer? = player.value ?: try {
        MediaPlayer().apply {
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
        modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = 240.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val p = ensurePlayer() ?: return@IconButton
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
        }) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play"
            )
        }
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Slider(
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
        Spacer(Modifier.width(6.dp))
        val shown = if (scrubbing) (scrubValue * durationMs).toInt() else if (playing || positionMs > 0) positionMs else durationMs
        val secs = shown / 1000
        Text(
            if (durationMs > 0) "%d:%02d".format(secs / 60, secs % 60) else "Audio",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.width(2.dp))
        IconButton(
            onClick = {
                coroutineScope.launch {
                    MediaSaver.saveAttachment(context, attachment)
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Save audio",
                modifier = Modifier.size(18.dp)
            )
        }
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
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

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

            // Top action bar overlay with Close, Counter, and Download/Save button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                if (allAttachments.size > 1) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} of ${allAttachments.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val current = allAttachments.getOrNull(pagerState.currentPage)
                        if (current != null) {
                            coroutineScope.launch {
                                MediaSaver.saveAttachment(context, current)
                            }
                        }
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Save to device", tint = Color.White)
                }
            }
        }
    }
}