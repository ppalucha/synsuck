package pl.synsuck.librus

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DecodingTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `decodes base64 message envelope with CDATA`() {
        val raw = b64("<Message><Content><![CDATA[Szanowni Państwo,\nzebranie o 18:00.]]></Content><Actions><Actions/></Actions></Message>")
        assertEquals("Szanowni Państwo,\nzebranie o 18:00.", Decoding.messageBody(raw))
    }

    @Test
    fun `decodes envelope without CDATA and unescapes entities`() {
        val raw = b64("<Message><Content>a &lt;b&gt; &amp; c</Content></Message>")
        assertEquals("a <b> & c", Decoding.messageBody(raw))
    }

    @Test
    fun `falls back to raw text when not base64`() {
        assertEquals("plain text, not encoded", Decoding.messageBody("plain text, not encoded"))
    }

    @Test
    fun `attachment info may be plain or base64`() {
        assertEquals("Wiadomość zawiera załączniki.", Decoding.maybeBase64("Wiadomość zawiera załączniki."))
        assertEquals("Wiadomość zawiera załączniki.", Decoding.maybeBase64(b64("Wiadomość zawiera załączniki.")))
        assertNull(Decoding.maybeBase64(""))
        assertNull(Decoding.maybeBase64(null))
    }

    @Test
    fun `previews are decoded leniently`() {
        assertEquals("Szanowni Państwo, w dniu 15 października", Decoding.preview(b64("Szanowni Państwo,\nw dniu 15 października")))
        // truncated envelope
        assertEquals("Dzień dobry, zebranie", Decoding.preview(b64("<Message><Content><![CDATA[Dzień dobry, zebranie")))
        // cut in the middle of a two-byte character ("ż" = C5 BC) and of a base64 quantum
        val cut = Base64.getEncoder().encodeToString("Proszę o zgodę na wyjazd ż".toByteArray().dropLast(1).toByteArray())
        assertEquals("Proszę o zgodę na wyjazd", Decoding.preview(cut.dropLast(1)))
    }

    @Test
    fun `plain previews pass through`() {
        assertEquals("Szanowni Państwo, w dniu 15", Decoding.preview("Szanowni Państwo,  w dniu 15"))
        assertEquals("Zebranie", Decoding.preview("Zebranie")) // valid base64 alphabet, but not text
        assertEquals("OK", Decoding.preview("OK"))
        assertEquals("", Decoding.preview(""))
    }

    @Test
    fun `file name from content disposition`() {
        assertEquals("zgoda.pdf", Decoding.fileNameFromDisposition("attachment; filename=\"zgoda.pdf\""))
        assertEquals("plan lekcji.pdf", Decoding.fileNameFromDisposition("attachment; filename*=UTF-8''plan%20lekcji.pdf"))
        // filename* wins over the ASCII fallback
        assertEquals("żółw.jpg", Decoding.fileNameFromDisposition("attachment; filename=\"zolw.jpg\"; filename*=UTF-8''%C5%BC%C3%B3%C5%82w.jpg"))
        // raw UTF-8 bytes seen through a Latin-1 header decoder
        val mojibake = String("żółw.jpg".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("żółw.jpg", Decoding.fileNameFromDisposition("attachment; filename=\"$mojibake\""))
        assertEquals("report.pdf", Decoding.fileNameFromDisposition("attachment; filename=report.pdf"))
        assertNull(Decoding.fileNameFromDisposition("attachment"))
        assertNull(Decoding.fileNameFromDisposition(null))
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("evil.sh", Decoding.sanitizeFileName("../../evil.sh"))
        assertEquals("evil.sh", Decoding.sanitizeFileName("..\\..\\evil.sh"))
        assertEquals("bashrc", Decoding.sanitizeFileName(".bashrc"))
        assertEquals("a_b_.txt", Decoding.sanitizeFileName("a:b?.txt"))
        assertEquals("ab.txt", Decoding.sanitizeFileName("a\u0000b\n.txt"))
        assertEquals(120, Decoding.sanitizeFileName("x".repeat(500))!!.length)
        assertNull(Decoding.sanitizeFileName("../"))
        assertNull(Decoding.sanitizeFileName(".."))
    }

    @Test
    fun `dates are Warsaw local time`() {
        // October: CEST, UTC+2
        assertEquals(Instant.parse("2026-10-06T19:49:51Z"), Decoding.parseDate("2026-10-06T21:49:51"))
        // January: CET, UTC+1; space separator tolerated
        assertEquals(Instant.parse("2026-01-10T07:00:00Z"), Decoding.parseDate("2026-01-10 08:00:00"))
        assertNull(Decoding.parseDateOrNull(null))
        assertNull(Decoding.parseDateOrNull(""))
        assertFailsWith<LibrusException.UnexpectedResponse> { Decoding.parseDate("yesterday") }
    }
}
