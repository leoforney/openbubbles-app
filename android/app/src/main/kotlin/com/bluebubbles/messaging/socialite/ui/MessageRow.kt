package com.bluebubbles.messaging.socialite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MessageRow(
    message: Message,
    isGroup: Boolean,
    isLastByMe: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onAttachmentClick: (Attachment) -> Unit
) {
    // Use Animatable for physics-based spring return instead of a raw float
    val offsetX = remember { Animatable(0f) }
    val maxSwipe = with(LocalDensity.current) { -80.dp.toPx() } // -80dp to the left
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Spring back to 0 smoothly when the user lets go
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch { offsetX.animateTo(0f) }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    coroutineScope.launch {
                        offsetX.snapTo((offsetX.value + dragAmount).coerceIn(maxSwipe, 0f))
                    }
                }
            }
    ) {
        // Timestamp revealed on the right
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateString = if (message.date > 0) timeFormat.format(Date(message.date)) else ""

        // Calculate smooth alpha based on how far the user has swiped
        val revealProgress = (offsetX.value / maxSwipe).coerceIn(0f, 1f)

        Text(
            text = dateString,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .alpha(revealProgress)
        )

        // The bubble and receipts
        Box(modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (message.isIncoming) Alignment.Start else Alignment.End
            ) {
                MessageBubble(
                    message = message,
                    isGroup = isGroup,
                    isFirstInGroup = isFirstInGroup,
                    isLastInGroup = isLastInGroup,
                    onAttachmentClick = onAttachmentClick
                )

                // Read / Delivered Receipts for outgoing messages
                if (!message.isIncoming && isLastByMe) {
                    val statusText = when {
                        message.isError -> "Not Delivered"
                        message.isSending -> "Sending…"
                        message.dateRead > 0 -> "Read ${timeFormat.format(Date(message.dateRead))}"
                        message.dateDelivered > 0 -> "Delivered"
                        else -> "Sent"
                    }
                    val color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(end = 4.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}