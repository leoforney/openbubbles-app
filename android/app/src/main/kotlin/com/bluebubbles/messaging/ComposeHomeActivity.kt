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

class ComposeHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.bluebubbles.messaging.objectbox.ObjectBox.init(this.applicationContext)
        setContent {
            SocialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "chatList") {
                        composable("chatList") {
                            ChatListScreen(onChatSelected = { chat ->
                                navController.navigate("chatScreen/${chat.id}")
                            })
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
                            
                            if (chat != null) {
                                ChatScreen(
                                    chat = chat!!,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
