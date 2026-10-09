package com.bluebubbles.messaging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bluebubbles.messaging.socialite.ui.SocialTheme
import com.bluebubbles.messaging.socialite.ui.ChatScreen
import com.bluebubbles.messaging.socialite.ui.ChatListScreen
import com.bluebubbles.messaging.socialite.model.Chat

class ComposeHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SocialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var selectedChat by remember { mutableStateOf<Chat?>(null) }

                    if (selectedChat == null) {
                        ChatListScreen(onChatSelected = { selectedChat = it })
                    } else {
                        BackHandler { selectedChat = null }
                        ChatScreen(
                            chat = selectedChat!!,
                            onBack = { selectedChat = null }
                        )
                    }
                }
            }
        }
    }
}
