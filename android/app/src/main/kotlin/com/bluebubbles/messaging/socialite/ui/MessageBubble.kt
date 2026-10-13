package com.bluebubbles.messaging.socialite.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.LinkPreview
import com.bluebubbles.messaging.socialite.model.Message
import com.bluebubbles.messaging.socialite.model.ReplyPreview
import com.bluebubbles.messaging.utils.getReactionEmoji

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isGroup: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onAttachmentClick: (Attachment) -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isIncoming = message.isIncoming

    // Google Messages corner radius logic: corners facing the edge are flat inside a group.
    val topStartRadius = if (isIncoming && !isFirstInGroup) 4.dp else 24.dp
    val bottomStartRadius = if (isIncoming && !isLastInGroup) 4.dp else 24.dp
    val topEndRadius = if (!isIncoming && !isFirstInGroup) 4.dp else 24.dp
    val bottomEndRadius = if (!isIncoming && !isLastInGroup) 4.dp else 24.dp
    val onBubble = if (isIncoming) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    val visualAttachments = remember(message.attachments) {
        message.attachments.filter {
            val m = it.mimeType ?: ""
            m.startsWith("image/") || m.startsWith("video/")
        }
    }
    val otherAttachments = remember(message.attachments, visualAttachments) {
        message.attachments.filter { it !in visualAttachments }
    }

    // Strip object replacement characters and whitespace so image/video messages don't render empty bubbles
    val cleanText = remember(message.text) {
        message.text
            .replace("\uFFFC", "")
            .replace("\uFFFD", "")
            .trim()
    }
    val linkOnly = message.linkPreview != null && message.attachments.isEmpty() && isOnlyUrl(cleanText)
    val bodyText = if (linkOnly) "" else cleanText

    Column(horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End) {
        // Sender name on the first message of an incoming run in group chats
        if (isIncoming && isGroup && isFirstInGroup && message.senderName != null) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }

        Box {
            Column(
                modifier = Modifier.padding(top = if (message.reactions.isNotEmpty()) 16.dp else 0.dp),
                horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End
            ) {
                message.replyTo?.let { ReplyQuote(it, onBubble) }

                // Visual attachments (photo stack or single photo) without bubble wrapper
                if (visualAttachments.isNotEmpty()) {
                    PhotoCardStack(
                        attachments = visualAttachments,
                        isIncoming = isIncoming,
                        onClick = onAttachmentClick,
                        onLongClick = onLongClick
                    )
                }

                // Text bubble and other attachments
                if (bodyText.isNotEmpty() || otherAttachments.isNotEmpty() || message.linkPreview != null) {
                    if (visualAttachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = topStartRadius, topEnd = topEndRadius, bottomStart = bottomStartRadius, bottomEnd = bottomEndRadius))
                            .background(if (isIncoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = onLongClick
                            )
                            .padding(if (linkOnly) 0.dp else 12.dp)
                            .widthIn(max = 280.dp)
                    ) {
                        Column {
                            for (att in otherAttachments) {
                                AttachmentView(
                                    att,
                                    onClick = { onAttachmentClick(att) },
                                    onLongClick = onLongClick,
                                    isIncoming = isIncoming
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            if (bodyText.isNotEmpty()) {
                                Text(text = bodyText, style = MaterialTheme.typography.bodyLarge, color = onBubble)
                            }
                            message.linkPreview?.let {
                                if (bodyText.isNotEmpty()) Spacer(Modifier.height(8.dp))
                                LinkPreviewCard(it, onBubble, asBubble = linkOnly, onLongClick = onLongClick)
                            }
                        }
                    }
                }
            }

            // Floating reactions (one per sender)
            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(if (isIncoming) Alignment.TopEnd else Alignment.TopStart)
                        .offset(x = if (isIncoming) 16.dp else (-16).dp, y = 0.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    message.reactions.forEach { reaction ->
                        Text(text = reaction.emoji ?: getReactionEmoji(reaction.type), fontSize = 14.sp)
                    }
                }
            }
        }
        if (message.isEdited) {
            Text(
                "Edited",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ReplyQuote(reply: ReplyPreview, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f))) {
        Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(color.copy(alpha = 0.5f)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            reply.senderName?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color) }
            Text(reply.text, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val URL_ONLY = Regex("""^\s*https?://\S+\s*$""", RegexOption.IGNORE_CASE)

private fun isOnlyUrl(text: String) = text.isEmpty() || URL_ONLY.matches(text)

/**
 * Rich link card. With [asBubble] it *is* the bubble (the parent clips it to the bubble shape and
 * adds no padding); otherwise it sits inside a text bubble below the text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkPreviewCard(
    link: LinkPreview,
    color: androidx.compose.ui.graphics.Color,
    asBubble: Boolean,
    onLongClick: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        Modifier
            .widthIn(min = 240.dp)
            .fillMaxWidth()
            .then(if (asBubble) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.08f)))
            .combinedClickable(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                onLongClick = onLongClick
            )
    ) {
        link.imageUrl?.let {
            // Fixed height: AsyncImage measures 0 tall before the image loads otherwise.
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(if (asBubble) 200.dp else 150.dp)
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            link.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            link.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(Uri.parse(link.url).host?.removePrefix("www.") ?: link.url, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}
