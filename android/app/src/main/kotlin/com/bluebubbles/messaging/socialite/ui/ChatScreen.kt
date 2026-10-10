package com.bluebubbles.messaging.socialite.ui



import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import androidx.activity.compose.BackHandler

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bluebubbles.messaging.services.backend_ui_interop.MethodCallHandler
import io.flutter.plugin.common.MethodChannel
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.Message
import com.bluebubbles.messaging.socialite.model.Attachment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: Chat,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val allMediaAttachments = remember(messages) {
        messages.flatMap { it.attachments }.filter { 
            (it.mimeType?.startsWith("image/") == true) || (it.mimeType?.startsWith("video/") == true)
        }.reversed()
    }

    fun loadMessages(offset: Int = 0) {
        if (isLoading || !hasMore) return
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val parsedMessages = com.bluebubbles.messaging.objectbox.ChatRepository.getMessages(chat.id, 50L, offset.toLong())
                withContext(Dispatchers.Main) {
                    if (parsedMessages.isEmpty()) {
                        hasMore = false
                    } else {
                        if (offset == 0) {
                            messages = parsedMessages
                        } else {
                            messages = messages + parsedMessages
                        }
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isLoading = false
            }
        }
    }

    LaunchedEffect(chat.id) {
        messages = emptyList()
        hasMore = true
        loadMessages(0)
    }

    // Infinite scroll detection
    val loadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= messages.size - 5
        }
    }

    LaunchedEffect(loadMore) {
        if (loadMore) {
            loadMessages(messages.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(
                            avatarPath = chat.avatarPath,
                            avatarBytes = chat.avatarBytes,
                            isGroup = chat.isGroup,
                            size = 36
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(chat.name)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentPadding = PaddingValues(8.dp)
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    placeholder = { Text("Type a message") },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                IconButton(
                    onClick = { text = "" },
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                reverseLayout = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items = messages, key = { _, message -> message.id }) { index, message ->
                    val isLastByMe = !message.isIncoming && (index == 0 || messages.getOrNull(index - 1)?.isIncoming == true)
                    MessageRow(message = message, isGroup = chat.isGroup, isLastByMe = isLastByMe, onAttachmentClick = { viewingAttachment = it })
                }
                if (isLoading && messages.isNotEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
                        }
                    }
                }
            }
            
            if (viewingAttachment != null) {
                FullscreenAttachmentViewer(
                    initialAttachment = viewingAttachment!!,
                    allAttachments = allMediaAttachments,
                    onDismiss = { viewingAttachment = null }
                )
            }
        }
    }
}

@Composable
fun MessageRow(message: Message, isGroup: Boolean, isLastByMe: Boolean, onAttachmentClick: (Attachment) -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    val maxSwipe = with(LocalDensity.current) { -80.dp.toPx() } // -80dp to the left

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { offsetX = 0f },
                    onDragCancel = { offsetX = 0f }
                ) { change, dragAmount ->
                    offsetX = (offsetX + dragAmount).coerceIn(maxSwipe, 0f)
                }
            }
    ) {
        // Timestamp revealed on the right
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateString = if (message.date > 0) timeFormat.format(Date(message.date)) else ""
        
        Text(
            text = dateString,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .alpha(if (offsetX < 0f) 1f else 0f)
        )
        
        // The bubble itself
        Box(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (message.isIncoming) Alignment.Start else Alignment.End
            ) {
                MessageBubble(message = message, isGroup = isGroup, onAttachmentClick = onAttachmentClick)
                
                // Read / Delivered Receipts for outgoing messages
                if (!message.isIncoming && isLastByMe) {
                    val statusText = when {
                        message.isError -> "Not Delivered"
                        message.dateRead > 0 -> "Read ${timeFormat.format(Date(message.dateRead))}"
                        message.dateDelivered > 0 -> "Delivered"
                        else -> "Sent"
                    }
                    val color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(end = 12.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun getReactionEmoji(type: String): String {
    return when (type) {
        "love" -> "❤️"
        "like" -> "👍"
        "dislike" -> "👎"
        "laugh" -> "😂"
        "emphasize" -> "‼️"
        "question" -> "❓"
        else -> type
    }
}

@Composable
fun MessageBubble(message: Message, isGroup: Boolean, onAttachmentClick: (Attachment) -> Unit) {
    val isIncoming = message.isIncoming
    Column(
        horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End
    ) {
        if (isIncoming && isGroup && message.senderName != null) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
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
                            topStart = 24.dp,
                            topEnd = 24.dp,
                            bottomStart = if (isIncoming) 4.dp else 24.dp,
                            bottomEnd = if (isIncoming) 24.dp else 4.dp
                        )
                    )
                    .background(if (isIncoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
                    .padding(if (message.attachments.isNotEmpty() && message.text.isEmpty()) 0.dp else 12.dp)
                    .widthIn(max = 280.dp)
            ) {
                Column {
                    if (message.attachments.isNotEmpty()) {
                        for (attachment in message.attachments) {
                            AttachmentView(attachment = attachment, onClick = { onAttachmentClick(attachment) })
                        }
                        if (message.text.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    
                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            color = if (isIncoming) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(if (isIncoming) Alignment.TopEnd else Alignment.TopStart)
                        .offset(
                            x = if (isIncoming) 16.dp else (-16).dp, 
                            y = 0.dp
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    message.reactions.forEach { reaction ->
                        Text(text = getReactionEmoji(reaction.type), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
@Composable
fun AttachmentView(attachment: Attachment, onClick: () -> Unit) {
    val mime = attachment.mimeType ?: ""
    val file = java.io.File(attachment.path)
    android.util.Log.d("AttachmentView", "Attachment path: ${attachment.path}, exists: ${file.exists()}, canRead: ${file.canRead()}, length: ${file.length()}, mime: $mime")
    
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
                val file = java.io.File(attachment.path)
                
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
