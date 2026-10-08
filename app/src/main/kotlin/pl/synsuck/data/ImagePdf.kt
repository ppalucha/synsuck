package pl.synsuck.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** Attachment types that can go into a combined PDF; judged by name since the type is known only after download. */
fun isImageFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** Where an image goes on its page, in PDF points (1/72 in). */
data class PageLayout(
    val pageWidth: Int,
    val pageHeight: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private const val A4_SHORT = 595
private const val A4_LONG = 842
private const val MARGIN = 24f

/** A4 page (landscape for wide images) with the image scaled to fit inside the margins, centered. */
fun fitToPage(imageWidth: Int, imageHeight: Int): PageLayout {
    val landscape = imageWidth > imageHeight
    val pageWidth = if (landscape) A4_LONG else A4_SHORT
    val pageHeight = if (landscape) A4_SHORT else A4_LONG
    val scale = minOf((pageWidth - 2 * MARGIN) / imageWidth, (pageHeight - 2 * MARGIN) / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PageLayout(pageWidth, pageHeight, (pageWidth - width) / 2, (pageHeight - height) / 2, width, height)
}

/** Largest power-of-two subsampling that keeps the long side at or above [maxSide]. */
fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** ~190 dpi on A4: sharp enough to print, small enough to keep memory and file size down. */
private const val MAX_SIDE = 1600

/**
 * Writes [images] into [out], one per page. Files that don't decode are skipped.
 * Returns the number of pages written.
 */
fun writeImagesPdf(images: List<File>, out: File): Int {
    val document = PdfDocument()
    try {
        var pages = 0
        for (file in images) {
            val bitmap = decodeForPrint(file) ?: continue
            try {
                val layout = fitToPage(bitmap.width, bitmap.height)
                val page = document.startPage(PdfDocument.PageInfo.Builder(layout.pageWidth, layout.pageHeight, pages + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(layout.left, layout.top, layout.left + layout.width, layout.top + layout.height),
                    null,
                )
                document.finishPage(page)
                pages++
            } finally {
                bitmap.recycle()
            }
        }
        if (pages > 0) out.outputStream().use { document.writeTo(it) }
        return pages
    } finally {
        document.close()
    }
}

/** Decodes [file] downsampled to about [MAX_SIDE] and upright per its EXIF orientation. */
private fun decodeForPrint(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
    var bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null

    val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
    val matrix = orientation(file)
    if (scale < 1f) matrix.postScale(scale, scale)
    if (!matrix.isIdentity) {
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        bitmap = transformed
    }
    return bitmap
}

private fun orientation(file: File): Matrix {
    val matrix = Matrix()
    val tag = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    when (tag) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
    }
    return matrix
}
