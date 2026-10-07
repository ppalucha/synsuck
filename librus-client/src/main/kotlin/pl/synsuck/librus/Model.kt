package pl.synsuck.librus

import java.time.Instant

public enum class Folder { INBOX, ARCHIVE }

public data class MessageSummary(
    val id: String,
    val folder: Folder,
    val senderName: String,
    val topic: String,
    val preview: String,
    val sentAt: Instant,
    val readAt: Instant?,
    val hasAttachments: Boolean,
) {
    val isUnread: Boolean get() = readAt == null
}

public data class MessagePage(
    val items: List<MessageSummary>,
    val total: Int,
)

public data class Attachment(
    val id: String,
    val filename: String,
)

public data class MessageDetail(
    val id: String,
    val folder: Folder,
    val senderName: String,
    val senderGroup: String?,
    val topic: String,
    val sentAt: Instant,
    val readAt: Instant?,
    val body: String,
    val attachmentInfo: String?,
    val attachments: List<Attachment>,
    val noReply: Boolean,
)

public data class Me(
    val firstName: String,
    val lastName: String,
    val accountId: Long,
)

public data class UnreadCounts(
    val inbox: Int,
    val archiveInbox: Int,
)

public data class DownloadResult(
    val contentType: String?,
    val contentDisposition: String?,
    /** Sanitized bare file name from Content-Disposition (no path components), if present. */
    val fileName: String?,
    val bytesWritten: Long,
)

public sealed class LibrusException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Login rejected — wrong login or password. [serverMessage] is Librus' own text, if any. */
    public class AuthFailed(public val serverMessage: String?) : LibrusException("Login rejected" + (serverMessage?.let { ": $it" } ?: ""))

    /** Librus requires a reCAPTCHA; cannot log in headlessly until the user logs in via a browser. */
    public class CaptchaRequired : LibrusException("Captcha required")

    /** Session cookies are no longer valid; log in again. */
    public class SessionExpired(detail: String? = null) : LibrusException("Session expired" + (detail?.let { ": $it" } ?: ""))

    /** Librus responded with something this client does not understand (API changed?). */
    public class UnexpectedResponse(message: String, cause: Throwable? = null) : LibrusException(message, cause)
}
