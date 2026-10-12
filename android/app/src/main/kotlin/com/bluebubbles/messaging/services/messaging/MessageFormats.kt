package com.bluebubbles.messaging.services.messaging

import com.bluebubbles.messaging.objectbox.AttachmentEntity
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_lib_bluebubbles.NativeAttachment
import uniffi.rust_lib_bluebubbles.NativeAttachmentType
import uniffi.rust_lib_bluebubbles.NativeIndexedMessagePart
import uniffi.rust_lib_bluebubbles.NativeLinkMeta
import uniffi.rust_lib_bluebubbles.NativeMessagePart
import uniffi.rust_lib_bluebubbles.NativeReaction
import uniffi.rust_lib_bluebubbles.NativeTextFormat
import uniffi.rust_lib_bluebubbles.attachmentToPlist

/**
 * Builds the JSON that Dart stores in Message.dbAttributedBody / dbMessageSummaryInfo /
 * dbPayloadData, so rows written natively look exactly like rows written by Dart
 * (lib/database/global/attributed_body.dart, message_summary_info.dart, payload_data.dart).
 */
internal object MessageFormats {
    private const val PART = "__kIMMessagePartAttributeName"
    private const val ATTACHMENT_GUID = "__kIMFileTransferGUIDAttributeName"
    private const val MENTION = "__kIMMentionConfirmedMention"
    private const val TEXT_EFFECT = "__kIMTextEffectAttributeName"

    const val URL_BALLOON = "com.apple.messages.URLBalloonProvider"

    class Body(val attributedBody: JSONObject, val text: String, val attachments: List<AttachmentEntity>)

    /** Port of `indexedPartsToAttributedBodyDyn` (rustpush_service.dart). */
    fun partsToBody(parts: List<NativeIndexedMessagePart>, msgId: String, existing: JSONObject?): Body {
        val body = StringBuilder()
        val runs = mutableListOf<JSONObject>()
        existing?.optJSONArray("runs")?.let { arr -> for (i in 0 until arr.length()) runs += JSONObject(arr.getJSONObject(i).toString()) }
        val attachments = mutableListOf<AttachmentEntity>()
        val added = mutableSetOf<Int>()

        parts.forEachIndexed { index, indexed ->
            val fieldIdx = indexed.idx?.toInt() ?: runs.count { it.optJSONObject("attributes")?.has(ATTACHMENT_GUID) == true }
            if (added.add(fieldIdx)) {
                runs.removeAll { r -> r.optJSONObject("attributes")?.let { it.has(PART) && it.getInt(PART) == fieldIdx } == true }
            }
            when (val part = indexed.part) {
                is NativeMessagePart.Text -> {
                    val attrs = JSONObject().put(PART, fieldIdx)
                    when (val f = part.format) {
                        is NativeTextFormat.Flags -> {
                            if (f.bold) attrs.put("__kIMTextBoldAttributeName", 1)
                            if (f.italic) attrs.put("__kIMTextItalicAttributeName", 1)
                            if (f.strikethrough) attrs.put("__kIMTextStrikethroughAttributeName", 1)
                            if (f.underline) attrs.put("__kIMTextUnderlineAttributeName", 1)
                        }
                        // Dart's Attributes.BIG/SMALL/... use the same numbers as rustpush's TextEffect
                        is NativeTextFormat.Effect -> attrs.put(TEXT_EFFECT, f.effect.toInt())
                    }
                    runs += run(body.length, part.text.length, attrs)
                    body.append(part.text)
                }
                is NativeMessagePart.Mention -> {
                    runs += run(body.length, part.text.length, JSONObject().put(PART, fieldIdx).put(MENTION, part.uri))
                    body.append(part.text)
                }
                is NativeMessagePart.Attachment -> {
                    val att = part.attachment
                    if (att.iris || att.mime == "application/smil") return@forEachIndexed
                    val nextPart = parts.getOrNull(index + 1)?.part
                    val iris = (nextPart as? NativeMessagePart.Attachment)?.attachment?.takeIf { it.iris }
                    val guid = "${msgId}_$fieldIdx"
                    attachments += AttachmentEntity(
                        guid = guid,
                        uti = att.utiType,
                        mimeType = att.mime,
                        isOutgoing = false,
                        transferName = att.name.replace("/", "_").replace("\\", "_"),
                        totalBytes = sizeOf(att),
                        hasLivePhoto = iris != null,
                        dbMetadata = JSONObject()
                            .put("rustpush", attachmentToPlist(att))
                            .put("myIris", iris?.let { attachmentToPlist(it) } ?: JSONObject.NULL)
                            .toString(),
                    )
                    runs += run(body.length, 1, JSONObject().put(ATTACHMENT_GUID, guid).put(PART, runs.size))
                    body.append(" ")
                }
                is NativeMessagePart.Object -> Unit // Dart ignores object parts here too
            }
        }
        val json = JSONObject().put("string", body.toString()).put("runs", JSONArray(runs))
        return Body(json, body.toString(), attachments)
    }

    private fun run(start: Int, length: Int, attrs: JSONObject) =
        JSONObject().put("range", JSONArray().put(start).put(length)).put("attributes", attrs)

    fun sizeOf(att: NativeAttachment): Long = when (val t = att.aType) {
        is NativeAttachmentType.Inline -> t.data.size.toLong()
        is NativeAttachmentType.Mmcs -> t.file.size.toLong()
    }

    /** Dart stores a list of AttributedBody maps. */
    fun bodyList(body: JSONObject): String = JSONArray().put(body).toString()

    fun firstBody(dbAttributedBody: String?): JSONObject? =
        dbAttributedBody?.let { runCatching { JSONArray(it).optJSONObject(0) }.getOrNull() }

    /** ReactionTypes in lib/helpers/ui/reaction_helpers.dart. Null for unsupported (sticker). */
    fun reactionType(reaction: NativeReaction): String? = when (reaction) {
        is NativeReaction.Heart -> "love"
        is NativeReaction.Like -> "like"
        is NativeReaction.Dislike -> "dislike"
        is NativeReaction.Laugh -> "laugh"
        is NativeReaction.Emphasize -> "emphasize"
        is NativeReaction.Question -> "question"
        is NativeReaction.Emoji -> "emoji"
        is NativeReaction.Sticker -> null
    }

    /** First MessageSummaryInfo of a message, creating `MessageSummaryInfo.empty()` if needed. */
    fun summaryInfo(dbMessageSummaryInfo: String?): Pair<JSONArray, JSONObject> {
        val list = dbMessageSummaryInfo?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        var info = list.optJSONObject(0)
        if (info == null) {
            info = JSONObject()
                .put("retractedParts", JSONArray())
                .put("editedContent", JSONObject())
                .put("originalTextRange", JSONObject())
                .put("editedParts", JSONArray())
            list.put(info)
        }
        for (key in listOf("retractedParts", "editedParts")) if (!info.has(key)) info.put(key, JSONArray())
        for (key in listOf("editedContent", "originalTextRange")) if (!info.has(key)) info.put(key, JSONObject())
        return list to info
    }

    fun JSONArray.containsInt(v: Int): Boolean = (0 until length()).any { optInt(it, Int.MIN_VALUE) == v }

    /** Port of `linkToData` for URL previews (shared-password invites are handled by Dart). */
    fun linkPayload(link: NativeLinkMeta): String {
        val image = link.imageUrl()?.let { url ->
            JSONObject().put("size", mediaSize(link.imageSize())).put("URL", JSONObject().put("NS.relative", url))
        }
        val icon = link.iconUrl()?.let { url ->
            JSONObject().put("size", "0,0").put("URL", JSONObject().put("NS.relative", url))
        }
        val preview = JSONObject()
            .put("imageMetadata", image ?: JSONObject.NULL)
            .put("videoMetadata", JSONObject.NULL)
            .put("iconMetadata", icon ?: JSONObject.NULL)
            .put("itemType", JSONObject.NULL)
            .put("originalURL", JSONObject().put("NS.relative", link.originalUrl() ?: JSONObject.NULL))
            .put("URL", JSONObject().put("NS.relative", link.url() ?: JSONObject.NULL))
            .put("title", link.title() ?: JSONObject.NULL)
            .put("summary", link.summary() ?: JSONObject.NULL)
            .put("siteName", link.title() ?: JSONObject.NULL)
        return JSONObject()
            .put("type", 0) // PayloadType.url
            .put("urlData", JSONArray().put(preview))
            .put("appData", JSONObject.NULL)
            .toString()
    }

    /** Dart: Size(parse(first.numericOnly()), parse(last.numericOnly())) then "${w},${h}". */
    private fun mediaSize(raw: String?): String {
        if (raw == null) return "0,0"
        val nums = raw.split(",").map { it.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0 }
        return "${nums.firstOrNull() ?: 0.0},${nums.lastOrNull() ?: 0.0}"
    }
}
