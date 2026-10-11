package com.bluebubbles.messaging.socialite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Message
import com.bluebubbles.messaging.utils.getReactionEmoji

@Composable
fun MessageBubble(
    message: Message,
    isGroup: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onAttachmentClick: (Attachment) -> Unit
) {
    val isIncoming = message.isIncoming

    // Google Messages Corner Radius Logic
    // If it's the middle of a group of messages, the corners facing the edge are flat (4.dp)
    // If it's the top or bottom of a group, the outer edge gets rounded (24.dp)
    val topStartRadius = if (isIncoming && !isFirstInGroup) 4.dp else 24.dp
    val bottomStartRadius = if (isIncoming && !isLastInGroup) 4.dp else 24.dp
    val topEndRadius = if (!isIncoming && !isFirstInGroup) 4.dp else 24.dp
    val bottomEndRadius = if (!isIncoming && !isLastInGroup) 4.dp else 24.dp

    Column(
        horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End
    ) {
        // Show sender name only on the first message of an incoming group
        if (isIncoming && isGroup && isFirstInGroup && message.senderName != null) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }

        Box {
            Box(
                modifier = Modifier
                    .padding(top = if (message.reactions.isNotEmpty()) 16.dp else 0.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = topStartRadius,
                            topEnd = topEndRadius,
                            bottomStart = bottomStartRadius,
                            bottomEnd = bottomEndRadius
                        )
                    )
                    .background(
                        if (isIncoming) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .padding(if (message.attachments.isNotEmpty() && message.text.isEmpty()) 0.dp else 12.dp)
                    .widthIn(max = 280.dp) // Keeps bubbles from stretching across the entire screen
            ) {
                Column {
                    if (message.attachments.isNotEmpty()) {
                        for (attachment in message.attachments) {
                            AttachmentView(attachment = attachment, onClick = { onAttachmentClick(attachment) })
                        }
                        if (message.text.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isIncoming) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Floating Reactions
            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(if (isIncoming) Alignment.TopEnd else Alignment.TopStart)
                        .offset(
                            x = if (isIncoming) 16.dp else (-16).dp,
                            y = 0.dp
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    message.reactions.forEach { reaction ->
                        Text(text = getReactionEmoji(reaction.type), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}