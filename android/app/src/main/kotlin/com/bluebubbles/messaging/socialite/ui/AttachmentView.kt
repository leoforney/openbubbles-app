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

@Composable
fun AttachmentView(attachment: Attachment, onClick: () -> Unit) {
    val mime = attachment.mimeType ?: ""
    val file = File(attachment.path)
    Log.d("AttachmentView", "Attachment path: ${attachment.path}, exists: ${file.exists()}, canRead: ${file.canRead()}, length: ${file.length()}, mime: $mime")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            mime.startsWith("image/") -> {
                LocalImage(
                    path = file.absolutePath,
                    contentDescription = "Image attachment",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp),
                    contentScale = ContentScale.Crop,
                    placeholder = { Box(Modifier.fillMaxWidth().height(150.dp)) }
                )
            }
            mime.startsWith("video/") -> {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
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
            mime.startsWith("audio/") -> {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play Audio")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Audio Message")
                }
            }
            else -> {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "File")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("File Attachment")
                }
            }
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