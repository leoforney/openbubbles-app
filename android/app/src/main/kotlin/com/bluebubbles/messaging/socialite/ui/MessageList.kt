package com.bluebubbles.messaging.socialite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.Message

@Composable
fun MessageList(
    messages: List<Message>,
    chat: Chat,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onAttachmentClick: (Attachment) -> Unit,
    onLongPress: (Message) -> Unit = {},
    selectedGuids: Set<String>? = null,
    onToggleSelect: (Message) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    // Infinite scroll detection
    val loadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= messages.size - 5
        }
    }

    LaunchedEffect(loadMore) {
        if (loadMore) onLoadMore()
    }

    // Auto-scroll to bottom when new messages arrive if user is looking at the most recent message
    val newestMessageId = messages.firstOrNull()?.id
    var previousNewestMessageId by remember(chat.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null) {
            if (previousNewestMessageId != null && newestMessageId != previousNewestMessageId) {
                // If user was looking at the bottom of the page (most recent messages),
                // smoothly scroll to the bottom to reveal the new message.
                val isAtBottom = listState.firstVisibleItemIndex <= 1
                if (isAtBottom) {
                    listState.animateScrollToItem(0)
                }
            }
            previousNewestMessageId = newestMessageId
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(items = messages, key = { _, msg -> msg.id }) { index, message ->
            val isLastByMe = !message.isIncoming && (index == 0 || messages.getOrNull(index - 1)?.isIncoming == true)

            // Logic to check if consecutive messages are from the same person for tighter spacing
            val previousMessage = messages.getOrNull(index + 1)
            val nextMessage = messages.getOrNull(index - 1)

            // A run breaks on side change, sender change (group chats) and system lines.
            fun sameRun(a: Message?, b: Message) = a != null && !a.isGroupEvent && !b.isGroupEvent &&
                a.isIncoming == b.isIncoming && (!b.isIncoming || a.senderName == b.senderName)
            val isFirstInGroup = !sameRun(previousMessage, message)
            val isLastInGroup = !sameRun(nextMessage, message)

            val spacing = if (isLastInGroup) 8.dp else 2.dp

            Box(modifier = Modifier.padding(bottom = spacing)) {
                MessageRow(
                    message = message,
                    isGroup = chat.isGroup,
                    isLastByMe = isLastByMe,
                    isFirstInGroup = isFirstInGroup,
                    isLastInGroup = isLastInGroup,
                    onAttachmentClick = onAttachmentClick,
                    onLongPress = onLongPress,
                    selectionMode = selectedGuids != null,
                    selected = selectedGuids?.contains(message.guid) == true,
                    onToggleSelect = onToggleSelect
                )
            }
        }

        if (isLoading && messages.isNotEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}