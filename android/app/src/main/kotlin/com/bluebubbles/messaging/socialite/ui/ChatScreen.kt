package com.bluebubbles.messaging.socialite.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.Message
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: Chat,
    onBack: () -> Unit,
    /** Content shared into the app (share sheet), prefilled into the input bar. */
    initialText: String? = null,
    initialMedia: List<Uri> = emptyList(),
) {
    val context = LocalContext.current

    var text by remember { mutableStateOf(initialText ?: "") }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    var selectedMediaUris by remember { mutableStateOf(initialMedia) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var pendingSendScroll by remember { mutableStateOf(false) }
    var replyTo by remember(chat.id) { mutableStateOf<Message?>(null) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var editing by remember(chat.id) { mutableStateOf<Message?>(null) }
    // Non-null = selection mode (guids of the selected messages)
    var selected by remember(chat.id) { mutableStateOf<Set<String>?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }

    androidx.activity.compose.BackHandler(enabled = selected != null || editing != null) {
        if (selected != null) selected = null else { editing = null; text = "" }
    }
    val recorder = remember { com.bluebubbles.messaging.services.messaging.VoiceRecorder(context.applicationContext) }
    var isRecording by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(chat.id) {
        onDispose {
            recorder.cancel()
            com.bluebubbles.messaging.objectbox.ChatRepository.cleanupIfEmpty(chat.id)
        }
    }

    fun scrollToBottomOnSend() {
        if (listState.firstVisibleItemIndex <= 1) {
            pendingSendScroll = true
            coroutineScope.launch { listState.animateScrollToItem(0) }
        }
    }

    fun replyTarget(): com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.ReplyTarget? =
        replyTo?.let { com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.ReplyTarget(it.guid, 0, it.text.length) }

    fun toggleRecording() {
        if (isRecording) {
            isRecording = false
            val file = recorder.stop() ?: return
            scrollToBottomOnSend()
            com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.send(
                context, chat.id, "", listOf(file.absolutePath), replyTarget(), voice = true
            )
            replyTo = null
        } else {
            isRecording = recorder.start()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleRecording()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) selectedMediaUris = selectedMediaUris + uris }
    )

    // Media Pickers
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris -> if (uris.isNotEmpty()) selectedMediaUris = selectedMediaUris + uris }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) tempCameraUri?.let { selectedMediaUris = selectedMediaUris + it }
        }
    )

    val allMediaAttachments = remember(messages) {
        messages.flatMap { it.attachments }.filter {
            it.mimeType?.startsWith("image/") == true || it.mimeType?.startsWith("video/") == true
        }.reversed()
    }

    // Number of newest messages kept live; grows by one page when scrolling up.
    var limit by remember(chat.id) { mutableLongStateOf(PAGE_SIZE) }

    fun loadMore() {
        if (isLoading || !hasMore) return
        isLoading = true
        limit += PAGE_SIZE
    }

    LaunchedEffect(chat.id) {
        messages = emptyList()
        hasMore = true
    }

    // Tell the native incoming pipeline which chat is visible (no notification, no unread badge).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(chat.id, lifecycleOwner) {
        val tracker = com.bluebubbles.messaging.services.messaging.ActiveChatTracker
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    tracker.setActive(chat.id)
                    com.bluebubbles.messaging.services.messaging.ReadReceipts.markChatRead(context, chat.id)
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> tracker.clear(chat.id)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            tracker.setActive(chat.id)
            com.bluebubbles.messaging.services.messaging.ReadReceipts.markChatRead(context, chat.id)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.clear(chat.id)
        }
    }

    LaunchedEffect(chat.id, limit) {
        isLoading = true
        com.bluebubbles.messaging.objectbox.ChatRepository.observeMessages(chat.id, limit)
            .catch { e ->
                android.util.Log.e("ChatScreen", "Failed to load messages", e)
                isLoading = false
            }
            .collect { loaded ->
                val previousNewestId = messages.firstOrNull()?.id
                val newNewestId = loaded.firstOrNull()?.id
                val hasNewMessage = previousNewestId != null && newNewestId != null && previousNewestId != newNewestId
                messages = loaded
                hasMore = loaded.size >= limit
                isLoading = false

                if (hasNewMessage && (pendingSendScroll || listState.firstVisibleItemIndex <= 1)) {
                    pendingSendScroll = false
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            val selection = selected
            if (selection != null) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = null }) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmDelete = selection }, enabled = selection.isNotEmpty()) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
                return@Scaffold
            }
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(
                            avatarPath = chat.avatarPath,
                            avatarBytes = chat.avatarBytes,
                            isGroup = chat.isGroup,
                            size = 40 // Slightly larger for modern feel
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(chat.name, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                text = text,
                onTextChange = { text = it },
                selectedMediaUris = selectedMediaUris,
                onRemoveMedia = { uri -> selectedMediaUris = selectedMediaUris - uri },
                onGalleryClick = {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onCameraClick = {
                    val tempFile = java.io.File.createTempFile("camera_image", ".jpg", context.cacheDir)
                    val providerStr = context.getString(context.resources.getIdentifier("file_provider", "string", context.packageName))
                    val uri = FileProvider.getUriForFile(context, providerStr, tempFile)
                    tempCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onSendClick = {
                    val target = editing
                    if (target != null) {
                        if (text.isNotBlank() && text != target.text) {
                            com.bluebubbles.messaging.services.messaging.MessageActions.edit(context, target.guid, text.trim())
                        }
                        editing = null
                        text = ""
                        return@ChatInputBar
                    }
                    scrollToBottomOnSend()
                    // All picked media + text go out as one message (a stack on iPhones).
                    val attachmentPaths = selectedMediaUris.mapNotNull { uriToFile(context, it) }
                    com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.send(context, chat.id, text, attachmentPaths, replyTarget())
                    text = ""
                    selectedMediaUris = emptyList()
                    replyTo = null
                },
                replyTo = replyTo?.let { com.bluebubbles.messaging.socialite.model.ReplyPreview(it.guid, it.text.ifEmpty { "Attachment" }, if (it.isIncoming) it.senderName ?: chat.name else "yourself") },
                onCancelReply = { replyTo = null },
                editingOriginal = editing?.text,
                onCancelEdit = { editing = null; text = "" },
                isRecording = isRecording,
                onMicClick = {
                    if (isRecording || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        toggleRecording()
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onCancelRecording = {
                    recorder.cancel()
                    isRecording = false
                },
                onFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MessageList(
                messages = messages,
                chat = chat,
                isLoading = isLoading,
                onLoadMore = { loadMore() },
                onAttachmentClick = { viewingAttachment = it },
                onLongPress = { if (!it.isGroupEvent) actionMessage = it },
                selectedGuids = selected,
                onToggleSelect = { m ->
                    val current = selected ?: emptySet()
                    selected = if (m.guid in current) current - m.guid else current + m.guid
                },
                listState = listState
            )

            actionMessage?.let { target ->
                MessageActionsDialog(
                    message = target,
                    onReact = { reaction, remove ->
                        com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.react(context, chat.id, target.guid, reaction, remove)
                    },
                    onReply = { replyTo = target },
                    onCopy = {
                        val clean = target.text.replace("\uFFFC", "").replace("\uFFFD", "").trim()
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", clean))
                    },
                    onDismiss = { actionMessage = null },
                    onEdit = {
                        replyTo = null
                        editing = target
                        text = target.text
                    },
                    onUnsend = { com.bluebubbles.messaging.services.messaging.MessageActions.unsend(context, target.guid) },
                    onSelect = { selected = setOf(target.guid) },
                    onDelete = { confirmDelete = setOf(target.guid) },
                )
            }

            confirmDelete?.let { guids ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = null },
                    title = { Text(if (guids.size == 1) "Delete message?" else "Delete ${guids.size} messages?") },
                    text = { Text("They are removed from this device and from your other Apple devices. Others in the conversation keep their copy.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.bluebubbles.messaging.services.messaging.MessageActions.delete(context, chat.id, guids.toList())
                            confirmDelete = null
                            selected = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
                    }
                )
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

/**
 * Returns a real file for a picked URI. Picker/document URIs often have no file path, so their
 * content is copied to the cache under the original display name (the name the recipient sees).
 */
private fun uriToFile(context: android.content.Context, uri: Uri): String? {
    com.bluebubbles.messaging.utils.FilesystemUtils.getAbsolutePath(context, uri)?.let { if (java.io.File(it).canRead()) return it }
    return try {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val safe = (name ?: "attachment").replace("/", "_")
        val dir = java.io.File(java.io.File(context.cacheDir, "outgoing"), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val out = java.io.File(dir, safe)
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        out.absolutePath
    } catch (e: Exception) {
        android.util.Log.e("ChatScreen", "Cannot read $uri", e)
        null
    }
}
