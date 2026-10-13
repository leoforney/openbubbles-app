package com.bluebubbles.messaging.objectbox

import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.equal
import io.objectbox.kotlin.query
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-line summary of a message, used for the chat list preview and notifications.
 * Port of `MessageHelper.getNotificationText` (lib/helpers/types/helpers/message_helper.dart),
 * including reactions ("Grace liked “See you at 8”"), attachments ("📷 Image"), group events,
 * Invisible Ink, unsent messages, links and app messages.
 */
object MessagePreview {
    private const val URL_BALLOON = "com.apple.messages.URLBalloonProvider"
    private val URL_REGEX = Regex("""^\s*(https?://|www\.)\S+\s*$""", RegexOption.IGNORE_CASE)

    // ReactionTypes.reactionToVerb (lib/helpers/ui/reaction_helpers.dart)
    private val REACTION_VERBS = mapOf(
        "love" to "loved", "like" to "liked", "dislike" to "disliked", "laugh" to "laughed at",
        "emphasize" to "emphasized", "question" to "questioned",
        "emoji" to "reacted emoji_placeholder to", "stickerback" to "reacted with a sticker to",
        "-love" to "removed a heart from", "-like" to "removed a like from", "-dislike" to "removed a dislike from",
        "-laugh" to "removed a laugh from", "-emphasize" to "removed an exclamation from",
        "-question" to "removed a question mark from", "-emoji" to "removed emoji_placeholder from",
        "-stickerback" to "removed a sticker from",
    )

    /**
     * @param nameOf display name for a handle's originalROWID (contact name or address), null if unknown.
     */
    fun text(msg: MessageEntity, nameOf: (Long?) -> String?): String {
        if (isGroupEvent(msg)) return groupEventText(msg, nameOf)
        if (msg.expressiveSendStyleId == "com.apple.MobileSMS.expressivesend.invisibleink") return "Message sent with Invisible Ink"
        if (isFullyUnsent(msg)) return "Unsent message"
        if (isInteractive(msg)) return interactiveText(msg)

        val fullText = fullText(msg)
        if (fullText.isEmpty() && msg.hasAttachments != true && msg.associatedMessageGuid.isNullOrEmpty()) {
            return if (msg.dateEdited != null) "Unsent message" else "Empty message"
        }

        val attachments = attachmentsOf(msg.id).filter { it.mimeType != null }
        if (attachments.isNotEmpty()) {
            val count = attachments.size
            val description = attachmentText(attachments)
            val emoji = when {
                description.contains("image") -> "📷"
                description.contains("movie") -> "🎥"
                description.contains("GIF") -> "🖼️"
                description.contains("audio") -> "🎵"
                else -> null
            }
            if (emoji != null) {
                if (count == 1) when (description) {
                    "1 image" -> return "📷 Image"
                    "1 movie" -> return "🎥 Video"
                    "1 GIF" -> return "🖼️ GIF"
                    "1 audio" -> return "🎵 Audio"
                }
                return "$emoji $description"
            }
            return "Attachment${if (count > 1) "s" else ""}: $description"
        }

        val associatedGuid = msg.associatedMessageGuid
        if (!associatedGuid.isNullOrEmpty()) {
            val sender = if (msg.isFromMe == true) "You" else nameOf(msg.handleId) ?: "Someone"
            val target = ObjectBox.store.boxFor<MessageEntity>().query { apply(MessageEntity_.guid.equal(associatedGuid)) }.findFirst()
                ?: return "$sender ${msg.text ?: ""}".trim()
            var verb = REACTION_VERBS[msg.associatedMessageType]
            if (verb != null && msg.associatedMessageEmoji != null) verb = verb.replaceFirst("emoji_placeholder", msg.associatedMessageEmoji!!)
            if (isInteractive(target)) return "$sender $verb ${interactiveText(msg)}"
            if (target.expressiveSendStyleId == "com.apple.MobileSMS.expressivesend.invisibleink") return "$sender $verb a message with Invisible Ink"

            // The reacted-to part: an attachment of the target, or its text runs for that part.
            var messageText: String? = null
            var isAttachment = false
            val part = msg.associatedMessagePart
            val body = target.dbAttributedBody?.let { runCatching { JSONArray(it).optJSONObject(0) }.getOrNull() }
            if (part != null && body != null) {
                val runs = body.optJSONArray("runs") ?: JSONArray()
                val string = body.optString("string")
                val partRuns = (0 until runs.length()).map { runs.getJSONObject(it) }
                    .filter { it.optJSONObject("attributes")?.optInt("__kIMMessagePartAttributeName", Int.MIN_VALUE)?.toLong() == part }
                val guids = partRuns.mapNotNull { it.optJSONObject("attributes")?.optString("__kIMFileTransferGUIDAttributeName")?.takeIf { g -> g.isNotEmpty() } }.toSet()
                if (guids.isNotEmpty()) {
                    isAttachment = true
                    messageText = attachmentText(attachmentsOf(target.id).filter { it.guid in guids })
                } else if (partRuns.isNotEmpty()) {
                    messageText = partRuns.mapNotNull { r ->
                        val range = r.optJSONArray("range") ?: return@mapNotNull null
                        val start = range.optInt(0); val len = range.optInt(1)
                        if (start in 0..string.length && start + len <= string.length) start to string.substring(start, start + len) else null
                    }.sortedBy { it.first }.joinToString("") { it.second }
                }
            }
            if (messageText == null) {
                if (target.hasAttachments == true) {
                    isAttachment = true
                    messageText = attachmentText(attachmentsOf(target.id))
                } else {
                    val subject = target.subject ?: ""
                    messageText = subject + (if (subject.isNotBlank()) "\n" else "") + (target.text ?: "")
                }
            }
            return if (isAttachment) "$sender $verb $messageText" else "$sender $verb “$messageText”"
        }
        return fullText
    }

    /** Every part of the message is in messageSummaryInfo.retractedParts. */
    private fun isFullyUnsent(msg: MessageEntity): Boolean {
        val summary = msg.dbMessageSummaryInfo?.let { runCatching { JSONArray(it).optJSONObject(0) }.getOrNull() } ?: return false
        val retracted = summary.optJSONArray("retractedParts")?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toSet() } ?: return false
        if (retracted.isEmpty()) return false
        return com.bluebubbles.messaging.services.messaging.MessageMutations.partsOf(msg).all { it in retracted }
    }

    private fun fullText(msg: MessageEntity) =
        listOfNotNull(msg.subject, msg.text).filter { it.isNotEmpty() }.joinToString("\n").trim()

    private fun isGroupEvent(msg: MessageEntity) =
        msg.groupTitle != null || (msg.itemType ?: 0) > 0 || (msg.groupActionType ?: 0) > 0

    // Message.isLegacyUrlPreview / isInteractive
    private fun isLegacyUrlPreview(msg: MessageEntity): Boolean {
        val dd = msg.hasDdResults == true
        return (msg.balloonBundleId == URL_BALLOON && dd) ||
            ((dd || msg.isFromMe != false) && URL_REGEX.matches(msg.text ?: ""))
    }

    private fun isInteractive(msg: MessageEntity) = msg.balloonBundleId != null && !isLegacyUrlPreview(msg)

    // Message.interactiveText: the link URL for previews, otherwise the app's name.
    private fun interactiveText(msg: MessageEntity): String {
        val payload = msg.dbPayloadData?.let { runCatching { JSONObject(it) }.getOrNull() }
        payload?.optJSONArray("urlData")?.optJSONObject(0)?.optJSONObject("URL")?.optString("NS.relative")
            ?.takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        payload?.optJSONArray("appData")?.optJSONObject(0)?.optString("an")?.takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        val bundle = msg.balloonBundleId ?: return "Unknown"
        return when {
            bundle.contains("com.apple.Handwriting") -> "Handwritten Message"
            bundle.contains("com.apple.DigitalTouch") -> "Digital Touch Message"
            bundle.contains("com.apple.PassbookUIService") || bundle.contains("PeerPayment") -> "Apple Pay"
            bundle.contains("gamepigeon", ignoreCase = true) -> "GamePigeon"
            else -> bundle.substringAfterLast(":").substringAfterLast(".")
        }
    }

    private fun groupEventText(msg: MessageEntity, nameOf: (Long?) -> String?): String {
        val name = if (msg.isFromMe == true) "You" else nameOf(msg.handleId) ?: "You"
        val other = msg.otherHandle?.let { nameOf(it) } ?: "someone"
        return when (msg.itemType) {
            1L -> when (msg.groupActionType) {
                0L -> "$name added $other to the conversation"
                1L -> "$name removed $other from the conversation"
                else -> "Unknown group event"
            }
            2L -> if (msg.groupTitle != null) "$name named the conversation \"${msg.groupTitle}\"" else "$name removed the name from the conversation"
            3L -> when (msg.groupActionType ?: 0L) {
                0L -> "$name left the conversation"
                1L -> "$name changed the group photo"
                2L -> "$name removed the group photo"
                else -> "Unknown group event"
            }
            4L -> "$name shared ${if (name == "You") "your" else "their"} location"
            5L -> "$name kept an audio message"
            6L -> "$name started a FaceTime call"
            7L -> if (msg.groupActionType == 1L) "$name changed the background" else "$name removed the background"
            else -> "Unknown group event"
        }
    }

    private fun attachmentsOf(messageId: Long): List<AttachmentEntity> =
        ObjectBox.store.boxFor<AttachmentEntity>().query { apply(AttachmentEntity_.messageIdId.equal(messageId)) }.find()

    // MessageHelper._getAttachmentText: "2 images & 1 movie"
    private fun attachmentText(attachments: List<AttachmentEntity>): String {
        val counts = LinkedHashMap<String, Int>()
        for (a in attachments) {
            val mime = a.mimeType
            val key = when {
                mime == null -> "link"
                mime.contains("vcard") -> "contact card"
                mime.contains("location") -> "location"
                mime.contains("contact") -> "contact"
                mime.contains("video") -> "movie"
                mime.contains("image/gif") -> "GIF"
                mime.contains("application/pdf") -> "PDF"
                else -> mime.substringBefore("/")
            }
            counts[key] = (counts[key] ?: 0) + 1
            if (key == "link") break
        }
        val parts = counts.map { (k, v) -> "$v $k${if (v > 1) "s" else ""}" }
        return parts.joinToString(if (parts.size == 2) " & " else ", ")
    }
}
