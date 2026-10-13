package com.bluebubbles.messaging.socialite.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bluebubbles.messaging.objectbox.ChatRepository
import com.bluebubbles.messaging.socialite.model.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactEntry(
    val contactId: Long,
    val name: String,
    val address: String,
    val typeLabel: String? = null,
    val photoUri: String? = null,
    val avatarBytes: ByteArray? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewChatScreen(
    onBack: () -> Unit,
    onChatCreated: (Chat) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var hasContactPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactPermission = granted
    }

    var allContacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var selectedRecipients by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isGroupMode by remember { mutableStateOf(false) }

    // Load contacts when permission is available
    LaunchedEffect(hasContactPermission) {
        if (hasContactPermission) {
            allContacts = withContext(Dispatchers.IO) {
                querySystemContacts(context)
            }
        }
    }

    val filteredContacts = remember(allContacts, searchQuery) {
        if (searchQuery.isBlank()) {
            allContacts
        } else {
            val q = searchQuery.trim().lowercase()
            allContacts.filter {
                it.name.lowercase().contains(q) || it.address.contains(q)
            }
        }
    }

    fun startChat(recipients: List<ContactEntry>) {
        if (recipients.isEmpty()) return
        coroutineScope.launch {
            val addresses = recipients.map { it.address }
            val chat = withContext(Dispatchers.IO) {
                ChatRepository.getOrCreateChat(context, addresses)
            }
            onChatCreated(chat)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New conversation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedRecipients.isNotEmpty()) {
                        IconButton(onClick = { startChat(selectedRecipients) }) {
                            Icon(Icons.Default.Check, contentDescription = "Start chat", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (selectedRecipients.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { startChat(selectedRecipients) },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    text = { Text(if (selectedRecipients.size > 1) "Start group chat" else "Start chat") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // "To: " chip bar and input field
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "To: ",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Chips & Input
                        FlowRow(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            selectedRecipients.forEach { recipient ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        selectedRecipients = selectedRecipients - recipient
                                    },
                                    label = {
                                        Text(
                                            text = recipient.name.ifEmpty { recipient.address },
                                            maxLines = 1
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = InputChipDefaults.inputChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                            }

                            // Text input field
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .widthIn(min = 120.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val trimmed = searchQuery.trim()
                                        if (trimmed.isNotEmpty()) {
                                            val entry = ContactEntry(
                                                contactId = 0L,
                                                name = trimmed,
                                                address = trimmed
                                            )
                                            if (isGroupMode || selectedRecipients.isNotEmpty()) {
                                                if (selectedRecipients.none { it.address == trimmed }) {
                                                    selectedRecipients = selectedRecipients + entry
                                                }
                                                searchQuery = ""
                                            } else {
                                                startChat(listOf(entry))
                                            }
                                        }
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty() && selectedRecipients.isEmpty()) {
                                        Text(
                                            "Type a name, phone number, or email",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Permission Request Banner if contacts permission is missing
            if (!hasContactPermission) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Access system contacts",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Allow OpenBubbles to search your contacts by name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                            Text("Allow")
                        }
                    }
                }
            }

            // Contact results / options list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // If typed query is not empty, offer "Send to [query]"
                val trimmedQuery = searchQuery.trim()
                if (trimmedQuery.isNotEmpty()) {
                    item {
                        ListItem(
                            headlineContent = { Text("Send to $trimmedQuery", fontWeight = FontWeight.SemiBold) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                val entry = ContactEntry(
                                    contactId = 0L,
                                    name = trimmedQuery,
                                    address = trimmedQuery
                                )
                                if (isGroupMode || selectedRecipients.isNotEmpty()) {
                                    if (selectedRecipients.none { it.address == trimmedQuery }) {
                                        selectedRecipients = selectedRecipients + entry
                                    }
                                    searchQuery = ""
                                } else {
                                    startChat(listOf(entry))
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }

                // "Create group" action if no recipients selected yet and not searching
                if (selectedRecipients.isEmpty() && searchQuery.isEmpty()) {
                    item {
                        ListItem(
                            headlineContent = { Text("Create group", fontWeight = FontWeight.Medium) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.GroupAdd,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                isGroupMode = true
                            }
                        )
                        HorizontalDivider()
                    }
                }

                // Section header
                if (filteredContacts.isNotEmpty()) {
                    item {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Search results" else "Contacts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // Contacts items
                items(filteredContacts, key = { "${it.contactId}_${it.address}" }) { contact ->
                    val isSelected = selectedRecipients.any { it.address == contact.address }

                    ListItem(
                        headlineContent = {
                            Text(contact.name, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            val subtitle = if (!contact.typeLabel.isNullOrBlank()) {
                                "${contact.address} • ${contact.typeLabel}"
                            } else {
                                contact.address
                            }
                            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            ContactAvatar(contact)
                        },
                        trailingContent = {
                            if (isGroupMode || selectedRecipients.isNotEmpty()) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedRecipients = if (checked) {
                                            selectedRecipients + contact
                                        } else {
                                            selectedRecipients.filterNot { it.address == contact.address }
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            if (isGroupMode || selectedRecipients.isNotEmpty()) {
                                selectedRecipients = if (isSelected) {
                                    selectedRecipients.filterNot { it.address == contact.address }
                                } else {
                                    selectedRecipients + contact
                                }
                            } else {
                                // 1-on-1 direct tap
                                startChat(listOf(contact))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactAvatar(contact: ContactEntry) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (contact.avatarBytes != null) {
            val bitmap = remember(contact.avatarBytes) {
                android.graphics.BitmapFactory.decodeByteArray(contact.avatarBytes, 0, contact.avatarBytes.size)
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contact.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                return
            }
        }

        val initial = contact.name.firstOrNull()?.uppercaseChar()
        if (initial != null && initial.isLetterOrDigit()) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun querySystemContacts(context: Context): List<ContactEntry> {
    val results = mutableListOf<ContactEntry>()
    val seenAddresses = mutableSetOf<String>()

    try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
            val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (cursor.moveToNext()) {
                val rawNumber = cursor.getString(numIdx) ?: continue
                val cleanAddress = com.bluebubbles.messaging.objectbox.ChatRepository.normalizeAddress(rawNumber)
                if (cleanAddress.isEmpty() || seenAddresses.contains(cleanAddress)) continue
                seenAddresses.add(cleanAddress)

                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: cleanAddress
                val type = cursor.getInt(typeIdx)
                val customLabel = cursor.getString(labelIdx)
                val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    type,
                    customLabel
                ).toString()
                val photoUri = cursor.getString(photoIdx)

                var avatarBytes: ByteArray? = null
                if (photoUri != null) {
                    try {
                        context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                            avatarBytes = it.readBytes()
                        }
                    } catch (_: Exception) {}
                }

                results.add(
                    ContactEntry(
                        contactId = id,
                        name = name,
                        address = cleanAddress,
                        typeLabel = typeLabel,
                        photoUri = photoUri,
                        avatarBytes = avatarBytes
                    )
                )
            }
        }
    } catch (e: SecurityException) {
        android.util.Log.w("NewChatScreen", "READ_CONTACTS permission denied", e)
    } catch (e: Exception) {
        android.util.Log.e("NewChatScreen", "Failed to query system contacts", e)
    }

    return results
}
