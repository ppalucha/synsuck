package pl.synsuck.data

import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import pl.synsuck.data.db.AccountDao
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AttachmentEntity
import pl.synsuck.data.db.MessageDao
import pl.synsuck.data.db.MessageEntity
import pl.synsuck.librus.decodePreview
import pl.synsuck.librus.sanitizeFileName
import java.io.File
import java.time.Clock
import java.time.Duration

/** A downloaded attachment in the app cache, ready to be shared through the FileProvider. */
data class CachedFile(val file: File, val mimeType: String)

class MessageRepository(
    private val accounts: AccountDao,
    private val messages: MessageDao,
    private val sessions: LibrusSessions,
    cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val attachmentsRoot = File(cacheDir, ATTACHMENTS_DIR)

    fun observeMessage(accountId: Long, messageId: String): Flow<MessageEntity?> = messages.observe(accountId, messageId)
    fun observeAttachments(accountId: Long, messageId: String): Flow<List<AttachmentEntity>> =
        messages.observeAttachments(accountId, messageId)
    fun observeAccount(accountId: Long): Flow<AccountEntity?> = accounts.observe(accountId)

    /**
     * Fetches the full message unless it's cached. Librus marks it read when it's fetched,
     * so the local copy is marked read too.
     */
    suspend fun ensureBody(accountId: Long, messageId: String) {
        val message = messages.get(accountId, messageId) ?: return
        if (message.body != null) return
        val account = accounts.get(accountId) ?: return
        val detail = sessions.withClient(account) { it.getMessage(message.folder, messageId) }
        messages.saveDetail(
            accountId = accountId,
            messageId = messageId,
            body = detail.body,
            attachmentInfo = detail.attachmentInfo,
            senderGroup = detail.senderGroup,
            readAt = detail.readAt ?: clock.instant(),
            attachments = detail.attachments.map { AttachmentEntity(accountId, messageId, it.id, it.filename) },
        )
    }

    /** Downloads [attachment] into the cache (or reuses an earlier download). */
    suspend fun download(attachment: AttachmentEntity): CachedFile = withContext(Dispatchers.IO) {
        val dir = File(attachmentsRoot, "${attachment.accountId}/${safeSegment(attachment.messageId)}/${safeSegment(attachment.attachmentId)}")
        dir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 && !it.name.endsWith(PART_SUFFIX) }
            ?.let { return@withContext cached(it) }

        val message = messages.get(attachment.accountId, attachment.messageId)
            ?: error("Message not found")
        val account = accounts.get(attachment.accountId) ?: error("Account not found")

        dir.mkdirs()
        val temp = File.createTempFile("download", PART_SUFFIX, dir)
        try {
            val result = sessions.withClient(account) {
                it.downloadAttachment(message.folder, attachment.attachmentId, attachment.messageId, temp)
            }
            val name = result.fileName
                ?: sanitizeFileName(attachment.filename)
                ?: "attachment-${safeSegment(attachment.attachmentId)}"
            val target = File(dir, name)
            check(isInside(target, attachmentsRoot)) { "Refusing to write outside the attachments cache" }
            target.delete()
            if (!temp.renameTo(target)) error("Could not store attachment")
            CachedFile(target, mimeType(result.contentType, target.name))
        } finally {
            temp.delete()
        }
    }

    suspend fun downloadAll(accountId: Long, messageId: String): List<CachedFile> =
        messages.getAttachments(accountId, messageId).map { download(it) }

    /** Downloads the message's image attachments and combines them into one PDF, one image per page. */
    suspend fun imagesPdf(accountId: Long, messageId: String): CachedFile {
        val message = messages.get(accountId, messageId) ?: error("Message not found")
        val images = messages.getAttachments(accountId, messageId)
            .filter { isImageFileName(it.filename) }
            .map { download(it) }
        return withContext(Dispatchers.IO) {
            val dir = File(attachmentsRoot, "$accountId/${safeSegment(messageId)}/$PDF_DIR")
            val target = File(dir, sanitizeFileName("${message.topic}.pdf") ?: "images.pdf")
            check(isInside(target, attachmentsRoot)) { "Refusing to write outside the attachments cache" }
            dir.mkdirs()
            val temp = File.createTempFile("pdf", PART_SUFFIX, dir)
            try {
                check(writeImagesPdf(images.map { it.file }, temp) > 0) { "No images could be read" }
                target.delete()
                if (!temp.renameTo(target)) error("Could not store PDF")
                CachedFile(target, "application/pdf")
            } finally {
                temp.delete()
            }
        }
    }

    /** Previews stored before they were decoded (v0.1) were raw base64. */
    suspend fun decodeStoredPreviews() = messages.rewritePreviews(::decodePreview)

    /** Removes downloads older than [maxAge]; called at app start. */
    suspend fun cleanupCache(maxAge: Duration = Duration.ofHours(24)) = withContext(Dispatchers.IO) {
        val cutoff = clock.millis() - maxAge.toMillis()
        attachmentsRoot.walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
            else if (f.isDirectory && f != attachmentsRoot && f.list().isNullOrEmpty()) f.delete()
        }
    }

    private fun cached(file: File) = CachedFile(file, mimeType(null, file.name))

    private fun mimeType(contentType: String?, fileName: String): String {
        val fromServer = contentType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" && !it.startsWith("text/html") }
        return fromServer
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }

    private companion object {
        const val ATTACHMENTS_DIR = "attachments" // must match res/xml/file_paths.xml
        const val PART_SUFFIX = ".part"
        const val PDF_DIR = "combined.pdf" // safeSegment never yields a ".", so no attachment dir can clash

        /** Ids come from Librus; keep only characters that are safe as a single path segment. */
        fun safeSegment(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "_" }

        fun isInside(file: File, root: File): Boolean =
            file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }
}
