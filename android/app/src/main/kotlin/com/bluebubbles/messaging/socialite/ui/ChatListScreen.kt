package com.bluebubbles.messaging.socialite.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bluebubbles.messaging.services.backend_ui_interop.MethodCallHandler
import io.flutter.plugin.common.MethodChannel
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.model.ParticipantInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(onChatSelected: (Chat) -> Unit) {
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<Chat?>(null) }

    LaunchedEffect(Unit) {
        MethodCallHandler.invokeMethodCb("getChats", mapOf(), object : MethodChannel.Result {
            override fun success(result: Any?) {
                val list = result as? List<Map<String, Any>> ?: emptyList()
                val parsedChats = list.map {
                    val participantInfoList = (it["participantInfo"] as? List<Map<String, Any>>)?.map { p ->
                        ParticipantInfo(
                            address = p["address"] as? String ?: "",
                            name = p["name"] as? String ?: "",
                            avatarBytes = p["avatarBytes"] as? ByteArray
                        )
                    } ?: emptyList()

                    Chat(
                        id = (it["id"] as? Number)?.toLong() ?: 0L,
                        name = (it["name"] as? String) ?: "Unknown",
                        avatarPath = it["avatarPath"] as? String,
                        avatarBytes = it["avatarBytes"] as? ByteArray,
                        date = (it["date"] as? Number)?.toLong() ?: 0L,
                        lastMessage = (it["lastMessage"] as? String) ?: "",
                        isGroup = (it["isGroup"] as? Boolean) ?: false,
                        participantInfo = participantInfoList
                    )
                }
                chats = parsedChats
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {}
            override fun notImplemented() {}
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(chats) { chat ->
                ChatListItem(
                    chat = chat,
                    onClick = { onChatSelected(chat) },
                    onProfileClick = { selectedProfile = chat }
                )
            }
        }
    }

    selectedProfile?.let { chat ->
        ProfileDialog(chat = chat, onDismiss = { selectedProfile = null })
    }
}

@Composable
fun ProfileDialog(chat: Chat, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AvatarView(
                    avatarPath = chat.avatarPath,
                    avatarBytes = chat.avatarBytes,
                    isGroup = chat.isGroup,
                    size = 100
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.headlineSmall
                )
                if (!chat.isGroup && chat.participantInfo.isNotEmpty()) {
                    Text(
                        text = chat.participantInfo.first().address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { 
                        val address = chat.participantInfo.firstOrNull()?.address ?: ""
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:\$address"))
                        context.startActivity(intent)
                    }) {
                        Text("Call")
                    }
                    OutlinedButton(onClick = { 
                        val address = chat.participantInfo.firstOrNull()?.address ?: ""
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:\$address"))
                        context.startActivity(intent)
                    }) {
                        Text("Message")
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarView(avatarPath: String?, avatarBytes: ByteArray?, isGroup: Boolean, size: Int = 48) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)

    if (avatarBytes != null && avatarBytes.isNotEmpty()) {
        val bitmap = remember(avatarBytes) {
            BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size)?.asImageBitmap()
        }
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = "Avatar",
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        } else {
            DefaultAvatar(isGroup, modifier)
        }
    } else if (avatarPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(File(avatarPath))
                .crossfade(true)
                .build(),
            contentDescription = "Avatar",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        DefaultAvatar(isGroup, modifier)
    }
}

@Composable
fun DefaultAvatar(isGroup: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ChatListItem(chat: Chat, onClick: () -> Unit, onProfileClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateObj = Date(chat.date)
    val now = Date()
    val dateString = if (now.time - dateObj.time < 24 * 60 * 60 * 1000) {
        timeFormat.format(dateObj)
    } else {
        dateFormat.format(dateObj)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.clickable { onProfileClick() }) {
            AvatarView(
                avatarPath = chat.avatarPath,
                avatarBytes = chat.avatarBytes,
                isGroup = chat.isGroup
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (chat.date > 0) {
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (chat.lastMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
