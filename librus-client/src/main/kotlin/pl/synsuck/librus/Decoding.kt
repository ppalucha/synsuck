package pl.synsuck.librus

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64

internal object Decoding {
    private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
    private val BASE64 = Regex("^[A-Za-z0-9+/\\r\\n]+={0,2}$")
    private val CDATA_CONTENT = Regex("<Content>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</Content>", RegexOption.DOT_MATCHES_ALL)
    private val PLAIN_CONTENT = Regex("<Content>(.*?)</Content>", RegexOption.DOT_MATCHES_ALL)

    /** Librus dates are Warsaw local time without an offset, e.g. `2026-10-06T21:49:51`. */
    fun parseDate(value: String): Instant = try {
        LocalDateTime.parse(value.trim().replace(' ', 'T')).atZone(WARSAW).toInstant()
    } catch (e: DateTimeParseException) {
        throw LibrusException.UnexpectedResponse("Unparseable date", e)
    }

    fun parseDateOrNull(value: String?): Instant? =
        value?.takeIf { it.isNotBlank() }?.let(::parseDate)

    /**
     * The `Message` field is base64 of `<Message><Content><![CDATA[text]]></Content>…</Message>`.
     * Extracted with regexes on purpose — no XML parser, no entity expansion.
     * Falls back to the raw value if the format ever changes.
     */
    fun messageBody(raw: String): String {
        val xml = decodeBase64OrNull(raw) ?: return raw
        CDATA_CONTENT.find(xml)?.let { return it.groupValues[1] }
        PLAIN_CONTENT.find(xml)?.let { return unescapeXml(it.groupValues[1]) }
        return xml
    }

    private val PREVIEW_PREFIX = Regex("^\\s*(<Message>)?\\s*(<Content>)?\\s*(<!\\[CDATA\\[)?")
    private val PREVIEW_SUFFIX = Regex("(]]>)?\\s*</Content>.*$", RegexOption.DOT_MATCHES_ALL)

    /**
     * The list's `content` preview is base64 of a truncated text (sometimes of the XML envelope),
     * so the last character may be cut mid-way. Decodes leniently; anything that doesn't decode
     * to plausible text is returned unchanged, so plain-text previews pass through.
     */
    fun preview(raw: String): String {
        val text = lenientBase64(raw.trim())
            ?.replace(PREVIEW_PREFIX, "")
            ?.replace(PREVIEW_SUFFIX, "")
            ?: raw
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun lenientBase64(value: String): String? {
        if (value.length < 8 || !BASE64.matches(value)) return null
        // Complete base64 decodes as-is; a truncated one is cut back to whole 4-char groups.
        val compact = value.filterNot { it == '\r' || it == '\n' }
        val usable = compact.substring(0, compact.length - compact.length % 4)
        val bytes = try {
            Base64.getDecoder().decode(usable)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // Truncation can only break the last character (trimmed); anything invalid elsewhere means
        // this wasn't base64 text after all.
        val text = String(bytes, Charsets.UTF_8).trimEnd('\uFFFD')
        if (text.isEmpty() || '\uFFFD' in text) return null
        val printable = text.count { !it.isISOControl() || it.isWhitespace() }
        return text.takeIf { printable >= text.length * 0.95 }
    }

    /** `attachementInfo` is sometimes base64, sometimes plain text. */
    fun maybeBase64(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return decodeBase64OrNull(raw) ?: raw
    }

    private val FILENAME_EXT = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
    private val FILENAME_QUOTED = Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val FILENAME_TOKEN = Regex("filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)

    /** File name from a Content-Disposition header (RFC 6266 `filename*` preferred), sanitized. */
    fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val raw = FILENAME_EXT.find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            try {
                java.net.URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset)
            } catch (e: Exception) {
                null
            }
        } ?: FILENAME_QUOTED.find(header)?.groupValues?.get(1)
            ?: FILENAME_TOKEN.find(header)?.groupValues?.get(1)
            ?: return null
        return sanitizeFileName(fixMojibake(raw))
    }

    /** Bare, filesystem-safe name: no directories, no control or reserved characters, no leading dots. */
    fun sanitizeFileName(name: String): String? = name
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace(Regex("\\p{Cntrl}"), "")
        .replace(Regex("[<>:\"|?*]"), "_")
        .trim()
        .trimStart('.')
        .take(120)
        .ifBlank { null }

    /** Raw UTF-8 header bytes arrive as Latin-1 chars; re-decode when that yields valid UTF-8. */
    private fun fixMojibake(s: String): String {
        if (s.any { it.code > 0xFF } || s.all { it.code < 0x80 }) return s
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            s
        }
    }

    private fun decodeBase64OrNull(value: String): String? {
        if (!BASE64.matches(value)) return null
        val bytes = try {
            Base64.getMimeDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
