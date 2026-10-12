package com.bluebubbles.messaging.services.messaging

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_lib_bluebubbles.NativeLinkImage
import uniffi.rust_lib_bluebubbles.NativeLinkMeta
import uniffi.rust_lib_bluebubbles.newLinkMeta
import java.net.HttpURLConnection
import java.net.URL

/**
 * Rich link previews for outgoing messages (port of the link-preview part of Dart's sendMessage):
 * fetch the page, read Open Graph / <title> metadata and the preview image + favicon, and build the
 * LinkMeta Apple clients render as a card. Any failure just sends the message without a preview.
 */
internal object LinkPreviews {
    private const val TAG = "LinkPreviews"
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    suspend fun build(text: String): NativeLinkMeta? {
        val url = URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', '!', '?') ?: return null
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.IO) {
                try {
                    fetch(url)
                } catch (e: Exception) {
                    Log.w(TAG, "No preview for $url", e)
                    null
                }
            }
        }
    }

    private fun fetch(originalUrl: String): NativeLinkMeta? {
        val conn = open(originalUrl)
        val type = conn.contentType ?: ""
        val finalUrl = conn.url.toString()
        if (!type.contains("html")) {
            conn.disconnect()
            return newLinkMeta(originalUrl, finalUrl, null, null, null, null)
        }
        val html = conn.inputStream.use { it.readNBytes(512 * 1024).toString(Charsets.UTF_8) }
        conn.disconnect()

        fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            Regex("""<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(name)}["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
                ?.let { tag -> Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) }
                ?.let { decode(it) }?.takeIf { it.isNotBlank() }
        }
        val title = meta("og:title", "twitter:title")
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.trim()?.let { decode(it) }
        val summary = meta("og:description", "twitter:description", "description")
        val imageUrl = meta("og:image", "twitter:image")?.let { resolve(finalUrl, it) }
        val iconHref = Regex("""<link[^>]+rel\s*=\s*["'][^"']*icon[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            ?.let { Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val iconUrl = resolve(finalUrl, iconHref ?: "/favicon.ico")

        return newLinkMeta(originalUrl, finalUrl, title, summary, iconUrl?.let { image(it) }, imageUrl?.let { image(it) })
    }

    private fun image(url: String): NativeLinkImage? = try {
        val conn = open(url)
        val type = conn.contentType ?: ""
        if (!type.startsWith("image/")) {
            conn.disconnect()
            null
        } else {
            val data = conn.inputStream.use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
            conn.disconnect()
            if (data.size > MAX_IMAGE_BYTES) null else NativeLinkImage(type.substringBefore(';'), data, url)
        }
    } catch (e: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
        conn.connect()
        return conn
    }

    private fun resolve(base: String, href: String): String? = runCatching { URL(URL(base), href).toString() }.getOrNull()

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
