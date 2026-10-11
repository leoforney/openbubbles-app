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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bluebubbles.messaging.socialite.model.Attachment
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: Chat,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    var selectedMediaUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

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

    fun loadMessages(offset: Int = 0) {
        if (isLoading || !hasMore) return
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Keep your existing backend logic
                val parsedMessages = com.bluebubbles.messaging.objectbox.ChatRepository.getMessages(chat.id, 50L, offset.toLong())
                withContext(Dispatchers.Main) {
                    if (parsedMessages.isEmpty()) hasMore = false
                    else messages = if (offset == 0) parsedMessages else messages + parsedMessages
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

    Scaffold(
        topBar = {
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
                    val attachmentPaths = selectedMediaUris.mapNotNull { com.bluebubbles.messaging.utils.FilesystemUtils.getAbsolutePath(context, it) }
                    com.bluebubbles.messaging.services.backend_ui_interop.MessageSender.sendMessage(context, chat.id, text, attachmentPaths)
                    text = ""
                    selectedMediaUris = emptyList()
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MessageList(
                messages = messages,
                chat = chat,
                isLoading = isLoading,
                onLoadMore = { loadMessages(messages.size) },
                onAttachmentClick = { viewingAttachment = it }
            )

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