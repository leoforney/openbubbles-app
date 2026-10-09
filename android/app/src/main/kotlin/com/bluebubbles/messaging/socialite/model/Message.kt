package com.bluebubbles.messaging.socialite.model

data class Attachment(
    val guid: String,
    val mimeType: String?,
    val path: String
)

data class Message(
    val id: Long,
    val text: String,
    val isIncoming: Boolean,
    val date: Long,
    val dateDelivered: Long,
    val dateRead: Long,
    val isError: Boolean,
    val senderName: String?,
    val attachments: List<Attachment>
)
