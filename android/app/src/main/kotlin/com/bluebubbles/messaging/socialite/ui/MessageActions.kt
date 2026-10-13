package com.bluebubbles.messaging.socialite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.emoji2.emojipicker.EmojiPickerView
import com.bluebubbles.messaging.socialite.model.Message
import com.bluebubbles.messaging.utils.getReactionEmoji

private val TAPBACKS = listOf("love", "like", "dislike", "laugh", "emphasize", "question")

/**
 * Long-press menu for a message: the six tapbacks plus "+" for any emoji, Reply and Copy.
 * Choosing the reaction you already gave removes it (like iMessage).
 */
@Composable
fun MessageActionsDialog(
    message: Message,
    onReact: (reaction: String, remove: Boolean) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {},
    onUnsend: () -> Unit = {},
    onSelect: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val now = System.currentTimeMillis()
    val ownAndSent = !message.isIncoming && !message.isSending && !message.isError && !message.isUnsent
    val canEdit = ownAndSent && message.text.isNotBlank() &&
        now - message.date < com.bluebubbles.messaging.services.messaging.MessageActions.EDIT_WINDOW_MS &&
        message.editCount < com.bluebubbles.messaging.services.messaging.MessageActions.MAX_EDITS
    val canUnsend = ownAndSent && now - message.date < com.bluebubbles.messaging.services.messaging.MessageActions.UNSEND_WINDOW_MS
    var showEmojiPicker by remember { mutableStateOf(false) }
    val mine = message.reactions.firstOrNull { !it.isIncoming }
    val myReaction = mine?.let { if (it.type == "emoji") it.emoji else it.type }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            if (showEmojiPicker) {
                AndroidView(
                    factory = { ctx ->
                        EmojiPickerView(ctx).apply {
                            setOnEmojiPickedListener { item ->
                                onReact(item.emoji, item.emoji == myReaction)
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                )
                return@Surface
            }
            Column(Modifier.padding(vertical = 12.dp)) {
                // A message still sending has no final id yet, so it cannot be reacted to.
                if (!message.isSending && !message.isError) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (type in TAPBACKS) {
                        ReactionChip(getReactionEmoji(type), selected = type == myReaction) {
                            onReact(type, type == myReaction)
                            onDismiss()
                        }
                    }
                    // A custom emoji already chosen is shown selected so it can be removed quickly.
                    if (myReaction != null && myReaction !in TAPBACKS) {
                        ReactionChip(myReaction, selected = true) {
                            onReact(myReaction, true)
                            onDismiss()
                        }
                    }
                    Box(
                        Modifier.size(40.dp).clickable { showEmojiPicker = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.AddReaction, contentDescription = "More reactions") }
                }
                if (!message.isSending && !message.isError) {
                    ListItem(
                        headlineContent = { Text("Reply") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        modifier = Modifier.clickable { onReply(); onDismiss() }
                    )
                }
                if (canEdit) {
                    val left = com.bluebubbles.messaging.services.messaging.MessageActions.MAX_EDITS - message.editCount
                    ListItem(
                        headlineContent = { Text("Edit") },
                        supportingContent = { Text("$left edit${if (left == 1) "" else "s"} left") },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.clickable { onEdit(); onDismiss() }
                    )
                }
                if (canUnsend) {
                    ListItem(
                        headlineContent = { Text("Undo send") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                        modifier = Modifier.clickable { onUnsend(); onDismiss() }
                    )
                }
                val cleanText = remember(message.text) { message.text.replace("\uFFFC", "").replace("\uFFFD", "").trim() }
                if (cleanText.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("Copy text") },
                        leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        modifier = Modifier.clickable { onCopy(); onDismiss() }
                    )
                }
                if (message.attachments.isNotEmpty()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                    ListItem(
                        headlineContent = {
                            Text(if (message.attachments.size > 1) "Save all media (${message.attachments.size})" else "Save to device")
                        },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                com.bluebubbles.messaging.utils.MediaSaver.saveAllAttachments(context, message.attachments)
                            }
                            onDismiss()
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Select") },
                    leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    modifier = Modifier.clickable { onSelect(); onDismiss() }
                )
                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { onDelete(); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun ReactionChip(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = 22.sp) }
}
