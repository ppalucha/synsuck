package pl.synsuck.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import pl.synsuck.R
import pl.synsuck.data.CachedFile
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.MessageEntity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun Context.contentUri(file: CachedFile): Uri =
    FileProvider.getUriForFile(this, "$packageName.files", file.file)

/** Opens a downloaded attachment in a viewer app. Returns false if no app can handle it. */
fun Context.openAttachment(file: CachedFile): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(file), file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(Intent.createChooser(intent, null))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Opens the user's mail app with the message prefilled: recipient, "Fwd:" subject,
 * a header (from / date / subject / account), the text, and the attachments.
 * Returns false if there's no mail app.
 */
fun Context.forwardAsEmail(
    message: MessageEntity,
    account: AccountEntity,
    files: List<CachedFile>,
    to: String?,
): Boolean {
    val locale = resources.configuration.locales[0]
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(message.sentAt.atZone(ZoneId.systemDefault()))
    val from = message.senderGroup?.let { "${message.senderName} ($it)" } ?: message.senderName
    val text = buildString {
        appendLine(getString(R.string.forward_header_title))
        appendLine(getString(R.string.forward_header_from, from))
        appendLine(getString(R.string.forward_header_date, date))
        appendLine(getString(R.string.forward_header_subject, message.topic))
        appendLine(getString(R.string.forward_header_account, account.label))
        appendLine()
        append(message.body.orEmpty())
    }
    val uris = files.map { contentUri(it) }

    val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = if (uris.isEmpty()) "text/plain" else "*/*"
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.forward_subject, message.topic))
        putExtra(Intent.EXTRA_TEXT, text)
        to?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        when (uris.size) {
            0 -> Unit
            1 -> putExtra(Intent.EXTRA_STREAM, uris.single())
            else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        if (uris.isNotEmpty()) {
            clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    // Preferably only email apps: a mailto: selector keeps the attachments (SENDTO alone can't
    // carry them). Not every phone/mail app resolves that combination — then use the share sheet.
    val emailOnly = Intent(send).apply { selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()) }
    return try {
        startActivity(emailOnly)
        true
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent.createChooser(send, getString(R.string.forward_as_email)))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
