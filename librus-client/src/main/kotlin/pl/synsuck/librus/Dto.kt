package pl.synsuck.librus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val LibrusJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // Librus mixes "0" and 0, "1028812" and 1028812 for the same fields.
    isLenient = true
    coerceInputValues = true
}

@Serializable
internal data class DataEnvelope<T>(val data: T)

@Serializable
internal data class CaptchaResponse(
    @SerialName("is_needed") val isNeeded: Boolean = false,
)

@Serializable
internal data class LoginResponse(
    val status: String? = null,
    val goTo: String? = null,
    val errors: List<LoginError> = emptyList(),
)

@Serializable
internal data class LoginError(val message: String? = null)

@Serializable
internal data class MeDto(
    val firstName: String = "",
    val lastName: String = "",
    val accountId: Long = 0,
)

@Serializable
internal data class MessageListDto(
    val data: List<MessageSummaryDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
internal data class MessageSummaryDto(
    val messageId: String,
    val senderName: String? = null,
    val senderFirstName: String? = null,
    val senderLastName: String? = null,
    val topic: String = "",
    val content: String? = null,
    val sendDate: String,
    val readDate: String? = null,
    val isAnyFileAttached: Boolean = false,
)

@Serializable
internal data class MessageDetailDto(
    val messageId: String,
    val senderName: String? = null,
    val senderFirstName: String? = null,
    val senderLastName: String? = null,
    val senderGroup: String? = null,
    val topic: String = "",
    @SerialName("Message") val message: String = "",
    val sendDate: String,
    val readDate: String? = null,
    @SerialName("attachementInfo") val attachmentInfo: String? = null,
    val attachments: List<AttachmentDto> = emptyList(),
    val noReply: Int = 0,
)

@Serializable
internal data class AttachmentDto(
    val id: String,
    val filename: String = "",
)

@Serializable
internal data class AttachmentLinkDto(
    val status: String? = null,
    val downloadLink: String? = null,
)

@Serializable
internal data class UnreadCountsDto(
    val inbox: Int = 0,
    val archiveInbox: Int = 0,
)
