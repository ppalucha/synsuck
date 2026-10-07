package pl.synsuck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import pl.synsuck.librus.Folder
import java.time.Instant

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE login = :login)")
    suspend fun loginExists(login: String): Boolean

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE accounts SET status = :status, lastError = :lastError WHERE id = :id")
    suspend fun setStatus(id: Long, status: AccountStatus, lastError: String?)

    @Query("UPDATE accounts SET status = 'OK', lastError = NULL, lastSyncAt = :at, baselineDone = 1 WHERE id = :id")
    suspend fun markSynced(id: Long, at: Instant)

    @Query("UPDATE accounts SET passwordCipher = :cipher, passwordIv = :iv, status = 'OK', lastError = NULL WHERE id = :id")
    suspend fun updatePassword(id: Long, cipher: ByteArray, iv: ByteArray)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Query(
        """
        SELECT m.accountId, m.messageId, m.folder, m.senderName, m.topic, m.preview,
               m.sentAt, m.readAt, m.hasAttachments,
               a.label AS accountLabel, a.color AS accountColor
        FROM messages m JOIN accounts a ON a.id = m.accountId
        ORDER BY m.sentAt DESC
        """,
    )
    fun observeRows(): Flow<List<MessageRow>>

    @Query("SELECT accountId, COUNT(*) AS unread FROM messages WHERE readAt IS NULL GROUP BY accountId")
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND messageId = :messageId")
    fun observe(accountId: Long, messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND messageId = :messageId")
    suspend fun get(accountId: Long, messageId: String): MessageEntity?

    @Query("SELECT * FROM attachments WHERE accountId = :accountId AND messageId = :messageId ORDER BY filename")
    fun observeAttachments(accountId: Long, messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE accountId = :accountId AND messageId = :messageId ORDER BY filename")
    suspend fun getAttachments(accountId: Long, messageId: String): List<AttachmentEntity>

    @Query(
        """
        UPDATE messages SET body = :body, attachmentInfo = :attachmentInfo, senderGroup = :senderGroup,
               readAt = COALESCE(readAt, :readAt)
        WHERE accountId = :accountId AND messageId = :messageId
        """,
    )
    suspend fun updateDetail(
        accountId: Long,
        messageId: String,
        body: String,
        attachmentInfo: String?,
        senderGroup: String?,
        readAt: Instant,
    )

    @Query("DELETE FROM attachments WHERE accountId = :accountId AND messageId = :messageId")
    suspend fun deleteAttachments(accountId: Long, messageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    /** Stores an opened message's body and attachment list. */
    @Transaction
    suspend fun saveDetail(
        accountId: Long,
        messageId: String,
        body: String,
        attachmentInfo: String?,
        senderGroup: String?,
        readAt: Instant,
        attachments: List<AttachmentEntity>,
    ) {
        updateDetail(accountId, messageId, body, attachmentInfo, senderGroup, readAt)
        deleteAttachments(accountId, messageId)
        insertAttachments(attachments)
    }

    @Query("UPDATE messages SET notified = 1 WHERE accountId = :accountId AND messageId IN (:messageIds)")
    suspend fun markNotified(accountId: Long, messageIds: List<String>)

    @Query("SELECT accountId, messageId, preview FROM messages")
    suspend fun allPreviews(): List<PreviewRow>

    @Query("UPDATE messages SET preview = :preview WHERE accountId = :accountId AND messageId = :messageId")
    suspend fun updatePreview(accountId: Long, messageId: String, preview: String)

    /** Rewrites stored previews with [transform]; used for one-off data fixes. */
    @Transaction
    suspend fun rewritePreviews(transform: (String) -> String) {
        for (row in allPreviews()) {
            val fixed = transform(row.preview)
            if (fixed != row.preview) updatePreview(row.accountId, row.messageId, fixed)
        }
    }

    @Query("SELECT messageId FROM messages WHERE accountId = :accountId AND folder = :folder")
    suspend fun knownIds(accountId: Long, folder: Folder): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(messages: List<MessageEntity>): List<Long>

    /** Refreshes list fields only; never touches the cached body. */
    @Query(
        """
        UPDATE messages SET senderName = :senderName, topic = :topic, preview = :preview,
               readAt = :readAt, hasAttachments = :hasAttachments, folder = :folder
        WHERE accountId = :accountId AND messageId = :messageId
        """,
    )
    suspend fun updateListFields(
        accountId: Long,
        messageId: String,
        folder: Folder,
        senderName: String,
        topic: String,
        preview: String,
        readAt: Instant?,
        hasAttachments: Boolean,
    )

    /** Inserts new messages, refreshes existing ones. Returns the messages that were new. */
    @Transaction
    suspend fun upsertSummaries(messages: List<MessageEntity>): List<MessageEntity> {
        val rowIds = insertIgnore(messages)
        val inserted = mutableListOf<MessageEntity>()
        messages.forEachIndexed { i, m ->
            if (rowIds[i] != -1L) {
                inserted += m
            } else {
                updateListFields(
                    m.accountId, m.messageId, m.folder, m.senderName, m.topic, m.preview,
                    m.readAt, m.hasAttachments,
                )
            }
        }
        return inserted
    }
}
