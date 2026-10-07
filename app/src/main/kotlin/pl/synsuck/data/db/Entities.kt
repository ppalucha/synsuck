package pl.synsuck.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import pl.synsuck.librus.Folder
import java.time.Instant

enum class AccountStatus { OK, AUTH_FAILED, CAPTCHA_REQUIRED, ERROR }

@Entity(tableName = "accounts", indices = [Index(value = ["login"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val login: String,
    val passwordCipher: ByteArray,
    val passwordIv: ByteArray,
    val color: Int,
    val sortOrder: Int,
    val status: AccountStatus = AccountStatus.OK,
    val lastError: String? = null,
    val lastSyncAt: Instant? = null,
    /** False until the first successful sync; no notifications for mail that existed before. */
    val baselineDone: Boolean = false,
) {
    /** Background sync must not retry these — repeated bad logins could lock the Librus account. */
    val needsUserAction: Boolean
        get() = status == AccountStatus.AUTH_FAILED || status == AccountStatus.CAPTCHA_REQUIRED

    // Keep credentials material out of logs and crash reports.
    override fun toString(): String = "AccountEntity(id=$id, label=$label, status=$status)"
}

@Entity(
    tableName = "messages",
    primaryKeys = ["accountId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["accountId", "sentAt"]), Index(value = ["readAt"])],
)
data class MessageEntity(
    val accountId: Long,
    val messageId: String,
    val folder: Folder,
    val senderName: String,
    val topic: String,
    val preview: String,
    val sentAt: Instant,
    val readAt: Instant?,
    val hasAttachments: Boolean,
    /** Decoded message text; null until the user opens the message (opening marks it read). */
    val body: String? = null,
    val attachmentInfo: String? = null,
    val firstSeenAt: Instant,
    val notified: Boolean = false,
)

@Entity(
    tableName = "attachments",
    primaryKeys = ["accountId", "messageId", "attachmentId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "messageId"],
            childColumns = ["accountId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttachmentEntity(
    val accountId: Long,
    val messageId: String,
    val attachmentId: String,
    val filename: String,
)

/** Inbox list row: message list fields plus the owning account's label and color. */
data class MessageRow(
    val accountId: Long,
    val messageId: String,
    val folder: Folder,
    val senderName: String,
    val topic: String,
    val preview: String,
    val sentAt: Instant,
    val readAt: Instant?,
    val hasAttachments: Boolean,
    @ColumnInfo(name = "accountLabel") val accountLabel: String,
    @ColumnInfo(name = "accountColor") val accountColor: Int,
)

data class UnreadCount(val accountId: Long, val unread: Int)
