package com.bluebubbles.messaging.socialite.model

data class Attachment(
    val guid: String,
    val mimeType: String?,
    val path: String,
    val id: Long = 0,
    val transferName: String? = null,
    val isDownloaded: Boolean = true,
    val totalBytes: Long = 0,
)

data class Reaction(
    /** ReactionTypes value ("love", "like", ...) or "emoji". */
    val type: String,
    val isIncoming: Boolean,
    val senderName: String?,
    val emoji: String? = null,
)

data class ReplyPreview(
    val guid: String,
    val text: String,
    val senderName: String?,
)

data class LinkPreview(
    val url: String,
    val title: String?,
    val summary: String?,
    val imageUrl: String?,
)

data class Message(
    val id: Long,
    val guid: String,
    val text: String,
    val isIncoming: Boolean,
    val date: Long,
    val dateDelivered: Long,
    val dateRead: Long,
    val isError: Boolean,
    /** Queued or in flight (Dart convention: guid starts with "temp"). */
    val isSending: Boolean = false,
    val senderName: String?,
    val attachments: List<Attachment>,
    val reactions: List<Reaction>,
    val replyTo: ReplyPreview? = null,
    val linkPreview: LinkPreview? = null,
    val isEdited: Boolean = false,
    /** Renames, participant changes etc.; rendered as a centered system line. */
    val isGroupEvent: Boolean = false,
)
