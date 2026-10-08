package pl.synsuck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImagePdfTest {
    @Test
    fun `recognizes image attachments by extension`() {
        assertTrue(isImageFileName("zdjecie.JPG"))
        assertTrue(isImageFileName("scan.page1.png"))
        assertFalse(isImageFileName("plan.pdf"))
        assertFalse(isImageFileName("jpg"))
        assertFalse(isImageFileName("notes.docx"))
    }

    @Test
    fun `tall image goes on a portrait page, scaled to the height`() {
        val l = fitToPage(1000, 2000)
        assertEquals(595 to 842, l.pageWidth to l.pageHeight)
        assertEquals(842f - 48, l.height, 0.01f)
        assertEquals(l.height / 2, l.width, 0.01f)
        assertEquals((595 - l.width) / 2, l.left, 0.01f)
        assertEquals(24f, l.top, 0.01f)
    }

    @Test
    fun `wide image goes on a landscape page`() {
        val l = fitToPage(4000, 3000)
        assertEquals(842 to 595, l.pageWidth to l.pageHeight)
        assertEquals(595f - 48, l.height, 0.01f)
        assertEquals(l.height * 4 / 3, l.width, 0.01f)
    }

    @Test
    fun `small image is scaled up to fill the page`() {
        val l = fitToPage(100, 100)
        assertEquals(595f - 48, l.width, 0.01f)
    }

    @Test
    fun `sample size keeps the long side at least the target`() {
        assertEquals(1, sampleSize(1600, 1200, 1600))
        assertEquals(1, sampleSize(3199, 2000, 1600))
        assertEquals(2, sampleSize(3200, 2400, 1600))
        assertEquals(2, sampleSize(4032, 3024, 1600))
        assertEquals(4, sampleSize(8000, 6000, 1600))
    }
}
