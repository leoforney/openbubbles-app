package com.bluebubbles.messaging.services.messaging

import com.bluebubbles.messaging.objectbox.MessageEntity
import com.bluebubbles.messaging.objectbox.ObjectBox
import io.objectbox.kotlin.boxFor
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_lib_bluebubbles.NativeIndexedMessagePart
import java.util.Date

/**
 * Stores edits and unsends exactly like Dart's reflectMessageDyn (Message_Edit / Message_Unsend),
 * for both incoming ones and the user's own. Call inside a write transaction.
 */
internal object MessageMutations {
    private val messageBox get() = ObjectBox.store.boxFor<MessageEntity>()

    fun applyEdit(target: MessageEntity, newParts: List<NativeIndexedMessagePart>, editPart: Int, editId: String,
                  sentTimestampMs: Long, verificationFailed: Boolean) {
        target.verificationFailed = verificationFailed
        val existingBody = MessageFormats.firstBody(target.dbAttributedBody)
        val inclusive = MessageFormats.partsToBody(newParts, editId, existingBody)
        val edited = MessageFormats.partsToBody(newParts, editId, null)
        target.text = inclusive.text
        target.dateEdited = Date()

        val (list, info) = MessageFormats.summaryInfo(target.dbMessageSummaryInfo)
        val editedParts = info.getJSONArray("editedParts")
        with(MessageFormats) { if (!editedParts.containsInt(editPart)) editedParts.put(editPart) }
        val content = info.getJSONObject("editedContent")
        val key = editPart.toString()
        if (!content.has(key)) {
            val original = target.dbAttributedBody?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            content.put(key, JSONArray().put(JSONObject()
                .put("text", JSONObject().put("values", original))
                .put("date", (target.dateCreated?.time ?: 0L).toDouble())))
        }
        content.getJSONArray(key).put(JSONObject()
            .put("text", JSONObject().put("values", JSONArray().put(edited.attributedBody)))
            .put("date", sentTimestampMs.toDouble()))
        target.dbMessageSummaryInfo = list.toString()
        target.dbAttributedBody = MessageFormats.bodyList(inclusive.attributedBody)
        messageBox.put(target)
    }

    fun applyUnsend(target: MessageEntity, parts: List<Int>, verificationFailed: Boolean) {
        target.verificationFailed = verificationFailed
        target.dateEdited = Date()
        val (list, info) = MessageFormats.summaryInfo(target.dbMessageSummaryInfo)
        val retracted = info.getJSONArray("retractedParts")
        with(MessageFormats) { for (p in parts) if (!retracted.containsInt(p)) retracted.put(p) }
        target.dbMessageSummaryInfo = list.toString()
        messageBox.put(target)
    }

    /** Number of edits already made to a part (Apple allows 5). */
    fun editCount(target: MessageEntity, part: Int): Int {
        val (_, info) = MessageFormats.summaryInfo(target.dbMessageSummaryInfo)
        val history = info.optJSONObject("editedContent")?.optJSONArray(part.toString()) ?: return 0
        return (history.length() - 1).coerceAtLeast(0) // first entry is the original
    }

    /** Message parts present in a message, from its attributed body (attachments and text). */
    fun partsOf(target: MessageEntity): List<Int> {
        val body = MessageFormats.firstBody(target.dbAttributedBody) ?: return listOf(0)
        val runs = body.optJSONArray("runs") ?: return listOf(0)
        val parts = (0 until runs.length()).mapNotNull { i ->
            runs.optJSONObject(i)?.optJSONObject("attributes")?.takeIf { it.has("__kIMMessagePartAttributeName") }?.optInt("__kIMMessagePartAttributeName")
        }.distinct()
        return parts.ifEmpty { listOf(0) }
    }

    /** The part index of the message's text (first run without an attachment). */
    fun textPartOf(target: MessageEntity): Int {
        val body = MessageFormats.firstBody(target.dbAttributedBody) ?: return 0
        val runs = body.optJSONArray("runs") ?: return 0
        for (i in 0 until runs.length()) {
            val attrs = runs.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            if (!attrs.has("__kIMFileTransferGUIDAttributeName")) return attrs.optInt("__kIMMessagePartAttributeName", 0)
        }
        return 0
    }
}
