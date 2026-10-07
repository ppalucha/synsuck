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
