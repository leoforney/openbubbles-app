package com.bluebubbles.messaging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bluebubbles.messaging.objectbox.ChatRepository
import com.bluebubbles.messaging.socialite.model.Chat
import com.bluebubbles.messaging.socialite.ui.ChatListScreen
import com.bluebubbles.messaging.socialite.ui.ChatScreen
import com.bluebubbles.messaging.socialite.ui.SocialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main entry point (launcher) and target of message notifications. Pass [EXTRA_CHAT_GUID] to open a
 * chat directly. Setup/onboarding still lives in the Flutter app ([MainActivity]), reachable via
 * the "Legacy" menu entry.
 */
class ComposeHomeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CHAT_GUID = "chatGuid"

        fun openChatIntent(context: android.content.Context, chatGuid: String) =
            android.content.Intent(context, ComposeHomeActivity::class.java)
                .putExtra(EXTRA_CHAT_GUID, chatGuid)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** Chat guid requested by the launching intent; consumed by the NavHost. */
    private val pendingChatGuid = mutableStateOf<String?>(null)

    /** Content shared into the app, waiting for a chat (direct share target or picked from the list). */
    class SharePayload(val text: String?, val files: List<java.io.File>)
    private val pendingShare = mutableStateOf<SharePayload?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CHAT_GUID)?.takeIf { it != "-1" }?.let { pendingChatGuid.value = it }
        handleShare(intent)
    }

    /**
     * ACTION_SEND / SEND_MULTIPLE from the share sheet. Files are copied right away: the read grant for
     * shared URIs only lasts while we handle the intent. A direct-share target carries the chat guid as
     * its shortcut id.
     */
    private fun handleShare(intent: android.content.Intent?) {
        val action = intent?.action ?: return
        if (action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uris: List<android.net.Uri> = if (action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: emptyList()
        } else {
            listOfNotNull(intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM))
        }
        val shortcutId = intent.getStringExtra(androidx.core.content.pm.ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        val files = uris.mapNotNull { copySharedUri(it) }
        if (text.isNullOrBlank() && files.isEmpty()) return
        pendingShare.value = SharePayload(text, files)
        if (shortcutId != null) pendingChatGuid.value = shortcutId
    }

    private fun copySharedUri(uri: android.net.Uri): java.io.File? = try {
        var name: String? = null
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val dir = java.io.File(java.io.File(cacheDir, "shared"), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val out = java.io.File(dir, (name ?: uri.lastPathSegment ?: "shared").replace("/", "_"))
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.takeIf { it.isFile && it.length() > 0 }
    } catch (e: Exception) {
        android.util.Log.e("ComposeHomeActivity", "Cannot read shared $uri", e)
        null
    }

    private fun openLegacy() {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Onboarding (Apple ID, registration) is only in Flutter: until it is finished, go there.
        if (!com.bluebubbles.messaging.services.messaging.FlutterPrefs.bool(this, "finishedSetup", false)) {
            openLegacy()
            finish()
            return
        }
        com.bluebubbles.messaging.objectbox.ObjectBox.init(this.applicationContext)
        // Flutter used to start the messaging service; make sure it runs when only Compose is open.
        try {
            val client = com.bluebubbles.messaging.services.rustpush.APNClient(applicationContext)
            client.bind { client.destroy() }
        } catch (e: Exception) {
            android.util.Log.e("ComposeHomeActivity", "Could not start the messaging service", e)
        }
        com.bluebubbles.messaging.services.messaging.ShareTargets.refresh(this)
        if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_CHAT_GUID)?.takeIf { it != "-1" }?.let { pendingChatGuid.value = it }
            handleShare(intent)
        }
        setContent {
            SocialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // Open the chat a notification (or other intent) asked for.
                    val requestedGuid by pendingChatGuid
                    LaunchedEffect(requestedGuid) {
                        val guid = requestedGuid ?: return@LaunchedEffect
                        pendingChatGuid.value = null
                        val chatId = withContext(Dispatchers.IO) {
                            com.bluebubbles.messaging.services.messaging.OutgoingMessageQueue.findChatIdByGuid(guid)
                        } ?: return@LaunchedEffect
                        navController.navigate("chatScreen/$chatId") {
                            popUpTo("chatList")
                            launchSingleTop = true
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "chatList",
                        enterTransition = { androidx.compose.animation.EnterTransition.None },
                        exitTransition = { androidx.compose.animation.ExitTransition.None },
                        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                        popExitTransition = { androidx.compose.animation.ExitTransition.None }
                    ) {
                        composable("chatList") {
                            ChatListScreen(
                                onChatSelected = { chat ->
                                    navController.navigate("chatScreen/${chat.id}")
                                },
                                onNewChat = {
                                    navController.navigate("newChat")
                                },
                                onOpenLegacy = { openLegacy() },
                                shareMode = pendingShare.value != null,
                                onCancelShare = { pendingShare.value = null }
                            )
                        }
                        composable("newChat") {
                            com.bluebubbles.messaging.socialite.ui.NewChatScreen(
                                onBack = { navController.popBackStack() },
                                onChatCreated = { chat ->
                                    navController.navigate("chatScreen/${chat.id}") {
                                        popUpTo("chatList")
                                    }
                                }
                            )
                        }
                        composable("chatScreen/{chatId}") { backStackEntry ->
                            val chatId = backStackEntry.arguments?.getString("chatId")?.toLongOrNull()
                            var chat by remember { mutableStateOf<Chat?>(null) }
                            
                            LaunchedEffect(chatId) {
                                if (chatId != null) {
                                    withContext(Dispatchers.IO) {
                                        val c = ChatRepository.getChatById(chatId)
                                        withContext(Dispatchers.Main) {
                                            chat = c
                                        }
                                    }
                                }
                            }
                            
                            // Shared content (if any) goes to the first chat opened after sharing.
                            val share = remember { pendingShare.value.also { pendingShare.value = null } }
                            if (chat != null) {
                                ChatScreen(
                                    chat = chat!!,
                                    onBack = { navController.popBackStack() },
                                    initialText = share?.text,
                                    initialMedia = share?.files?.map { android.net.Uri.fromFile(it) } ?: emptyList(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
