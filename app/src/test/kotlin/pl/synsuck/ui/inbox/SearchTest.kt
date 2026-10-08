package pl.synsuck.ui.inbox

import pl.synsuck.data.db.MessageRow
import pl.synsuck.librus.Folder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTest {
    private fun row(body: String? = null) = MessageRow(
        accountId = 1, messageId = "1", folder = Folder.INBOX,
        senderName = "Łukasz Nowak", topic = "Wycieczka do Krakowa", preview = "Proszę o zgodę rodziców",
        sentAt = Instant.EPOCH, readAt = null, hasAttachments = false,
        accountLabel = "Ania", accountColor = 0, body = body,
    )

    @Test
    fun `blank query matches everything`() {
        assertTrue(row().matches(""))
        assertTrue(row().matches("   "))
    }

    @Test
    fun `matches sender, topic and preview ignoring case including Polish letters`() {
        assertTrue(row().matches("łukasz"))
        assertTrue(row().matches("KRAKOWA"))
        assertTrue(row().matches("zgodę"))
        assertTrue(row().matches("  nowak "))
    }

    @Test
    fun `body is searched only when present`() {
        assertFalse(row().matches("autokar"))
        assertTrue(row(body = "Wyjazd autokarem o 8:00").matches("autokar"))
    }
}
