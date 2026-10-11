package com.bluebubbles.messaging.utils

import androidx.compose.runtime.Composable

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