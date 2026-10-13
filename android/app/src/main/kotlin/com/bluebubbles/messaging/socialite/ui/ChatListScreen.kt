package com.bluebubbles.messaging.socialite.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bluebubbles.messaging.socialite.model.Chat
import kotlinx.coroutines.flow.catch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatSelected: (Chat) -> Unit,
    onNewChat: () -> Unit,
    onOpenLegacy: () -> Unit = {},
    /** Something was shared into the app: pick the conversation to send it to. */
    shareMode: Boolean = false,
    onCancelShare: () -> Unit = {}
) {
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<Chat?>(null) }

    LaunchedEffect(Unit) {
        com.bluebubbles.messaging.objectbox.ChatRepository.observeChats()
            .catch { e -> android.util.Log.e("ChatListScreen", "Failed to load chats", e) }
            .collect { chats = it }
    }

    Scaffold(
        topBar = {
            var menuOpen by remember { mutableStateOf(false) }
            if (shareMode) {
                TopAppBar(
                    title = { Text("Share to…") },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onCancelShare) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Cancel sharing")
                        }
                    }
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                            Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Legacy") },
                                onClick = {
                                    menuOpen = false
                                    onOpenLegacy()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onNewChat,
                icon = {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = com.bluebubbles.messaging.R.drawable.ic_start_chat),
                        contentDescription = "Start chat",
                        modifier = Modifier.size(24.dp)
                    )
                },
                text = { Text("Start chat") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
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
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))
                        context.startActivity(intent)
                    }) {
                        Text("Call")
                    }
                    OutlinedButton(onClick = { 
                        val address = chat.participantInfo.firstOrNull()?.address ?: ""
                        val intent = Intent(android.provider.ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.parse("tel:$address"))
                        intent.putExtra(android.provider.ContactsContract.Intents.EXTRA_FORCE_CREATE, false)
                        context.startActivity(intent)
                    }) {
                        Text("Info")
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
    } else if (avatarPath != null && fileExists(avatarPath)) {
        LocalImage(
            path = avatarPath,
            contentDescription = "Avatar",
            modifier = modifier,
            contentScale = ContentScale.Crop,
            maxDim = 256,
            placeholder = { DefaultAvatar(isGroup, modifier) }
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

    val unread = chat.unreadCount > 0
    val emphasis = if (unread) FontWeight.Bold else FontWeight.Normal
    val preview = chat.lastMessage

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
                    fontWeight = emphasis,
                    color = if (unread) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (chat.date > 0) {
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = emphasis,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = emphasis,
                        color = if (unread) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (unread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            } else if (unread) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

