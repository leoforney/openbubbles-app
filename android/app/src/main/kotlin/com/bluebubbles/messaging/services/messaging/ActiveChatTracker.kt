package com.bluebubbles.messaging.services.messaging

/** The chat currently visible in the Compose UI, if any (set by ChatScreen while resumed). */
object ActiveChatTracker {
    @Volatile
    private var activeChatId: Long? = null

    fun setActive(chatId: Long?) {
        activeChatId = chatId
    }

    fun clear(chatId: Long) {
        if (activeChatId == chatId) activeChatId = null
    }

    fun isActive(chatId: Long): Boolean = activeChatId == chatId
}
