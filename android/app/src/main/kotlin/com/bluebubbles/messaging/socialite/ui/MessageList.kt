package com.bluebubbles.messaging.socialite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onAttachmentClick: (Attachment) -> Unit
) {
    val listState = rememberLazyListState()

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

            val isFirstInGroup = previousMessage == null || previousMessage.isIncoming != message.isIncoming
            val isLastInGroup = nextMessage == null || nextMessage.isIncoming != message.isIncoming

            val spacing = if (isLastInGroup) 8.dp else 2.dp

            Box(modifier = Modifier.padding(bottom = spacing)) {
                MessageRow(
                    message = message,
                    isGroup = chat.isGroup,
                    isLastByMe = isLastByMe,
                    isFirstInGroup = isFirstInGroup,
                    isLastInGroup = isLastInGroup,
                    onAttachmentClick = onAttachmentClick
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