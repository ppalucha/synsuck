package pl.synsuck.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import pl.synsuck.data.db.AccountDao
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AccountStatus
import pl.synsuck.data.db.AttachmentEntity
import pl.synsuck.data.db.MessageDao
import pl.synsuck.data.db.MessageEntity
import pl.synsuck.data.db.MessageRow
import pl.synsuck.data.db.PreviewRow
import pl.synsuck.data.db.UnreadCount
import pl.synsuck.librus.Folder
import pl.synsuck.librus.LibrusException
import pl.synsuck.librus.MessagePage
import pl.synsuck.librus.MessageSummary
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncerTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private val accounts = FakeAccountDao()
    private val messages = FakeMessageDao()
    private val server = FakeLibrus()
    private val syncer = Syncer(accounts, messages, server, Clock.fixed(now, ZoneOffset.UTC))

    private fun account(baselineDone: Boolean = false, status: AccountStatus = AccountStatus.OK) =
        AccountEntity(
            id = 1, label = "Ania", login = "1u", passwordCipher = ByteArray(0), passwordIv = ByteArray(0),
            color = 0, sortOrder = 0, status = status, baselineDone = baselineDone,
        ).also { accounts.rows[it.id] = it }

    @Test
    fun `first sync fetches everything but reports nothing new`() = runTest {
        server.inbox = (1..120).map { msg(it, unread = it > 117) }
        server.archive = (1001..1010).map { msg(it, folder = Folder.ARCHIVE) }

        val outcome = syncer.syncAccount(account(baselineDone = false))

        assertEquals(SyncOutcome.Synced(1, emptyList()), outcome)
        assertEquals(130, messages.rows.size)
        assertEquals(listOf(1, 2, 3), server.requestedPages(Folder.INBOX))
        assertTrue(accounts.rows.getValue(1).baselineDone)
        assertEquals(now, accounts.rows.getValue(1).lastSyncAt)
    }

    @Test
    fun `later sync reports only new unread messages and stops at known page`() = runTest {
        server.inbox = (1..120).map { msg(it) }
        syncer.syncAccount(account())
        server.requests.clear()

        // Two new messages on top: one unread, one already read on the web.
        server.inbox = listOf(msg(122, unread = true), msg(121, unread = false)) + server.inbox
        val outcome = syncer.syncAccount(accounts.rows.getValue(1))

        assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(listOf("122"), outcome.newUnread.map { it.messageId })
        // Page 1: 2 new + 48 known → continue; page 2: all known → stop.
        assertEquals(listOf(1, 2), server.requestedPages(Folder.INBOX))
    }

    @Test
    fun `refresh updates read state without touching cached body`() = runTest {
        server.inbox = listOf(msg(1, unread = true))
        syncer.syncAccount(account())
        messages.rows[1L to "1"] = messages.rows.getValue(1L to "1").copy(body = "cached text")

        server.inbox = listOf(msg(1, unread = false))
        syncer.syncAccount(accounts.rows.getValue(1))

        val row = messages.rows.getValue(1L to "1")
        assertEquals("cached text", row.body)
        assertTrue(row.readAt != null)
    }

    @Test
    fun `first sync is capped per folder`() = runTest {
        server.inbox = (1..800).map { msg(it) }
        syncer.syncAccount(account())
        assertEquals(Syncer.MAX_MESSAGES_PER_FOLDER, messages.rows.size)
    }

    @Test
    fun `auth failure marks account and later syncs skip it`() = runTest {
        server.failure = LibrusException.AuthFailed("Nieprawidłowe hasło")

        val first = syncer.syncAccount(account(baselineDone = true))
        assertEquals(SyncOutcome.Failed(1, AccountStatus.AUTH_FAILED), first)
        assertEquals(AccountStatus.AUTH_FAILED, accounts.rows.getValue(1).status)

        server.requests.clear()
        val second = syncer.syncAccount(accounts.rows.getValue(1))
        assertIs<SyncOutcome.Skipped>(second)
        assertTrue(server.requests.isEmpty(), "must not retry a rejected password")
    }

    @Test
    fun `network errors leave account status alone`() = runTest {
        server.failure = IOException("offline")
        val outcome = syncer.syncAccount(account(baselineDone = true))
        assertIs<SyncOutcome.NetworkError>(outcome)
        assertEquals(AccountStatus.OK, accounts.rows.getValue(1).status)
    }

    // --- fakes ---

    private fun msg(n: Int, unread: Boolean = false, folder: Folder = Folder.INBOX) = MessageSummary(
        id = n.toString(),
        folder = folder,
        senderName = "Sender $n",
        topic = "Topic $n",
        preview = "Preview $n",
        sentAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n * 60L),
        readAt = if (unread) null else Instant.parse("2026-01-02T00:00:00Z"),
        hasAttachments = false,
    )

    private class FakeLibrus : MessageLister {
        var inbox: List<MessageSummary> = emptyList()
        var archive: List<MessageSummary> = emptyList()
        var failure: Exception? = null
        val requests = mutableListOf<Pair<Folder, Int>>()

        fun requestedPages(folder: Folder) = requests.filter { it.first == folder }.map { it.second }

        override suspend fun list(account: AccountEntity, folder: Folder, page: Int, limit: Int): MessagePage {
            failure?.let { throw it }
            requests += folder to page
            // Newest first, like Librus.
            val all = (if (folder == Folder.INBOX) inbox else archive).sortedByDescending { it.sentAt }
            return MessagePage(all.drop((page - 1) * limit).take(limit), all.size)
        }
    }

    private class FakeAccountDao : AccountDao {
        val rows = linkedMapOf<Long, AccountEntity>()
        override fun observeAll(): Flow<List<AccountEntity>> = emptyFlow()
        override suspend fun getAll() = rows.values.toList()
        override suspend fun get(id: Long) = rows[id]
        override fun observe(id: Long): Flow<AccountEntity?> = emptyFlow()
        override suspend fun loginExists(login: String) = rows.values.any { it.login == login }
        override suspend fun nextSortOrder() = rows.size
        override suspend fun insert(account: AccountEntity): Long = account.id.also { rows[it] = account }
        override suspend fun update(account: AccountEntity) { rows[account.id] = account }
        override suspend fun setStatus(id: Long, status: AccountStatus, lastError: String?) {
            rows[id] = rows.getValue(id).copy(status = status, lastError = lastError)
        }
        override suspend fun markSynced(id: Long, at: Instant) {
            rows[id] = rows.getValue(id).copy(status = AccountStatus.OK, lastError = null, lastSyncAt = at, baselineDone = true)
        }
        override suspend fun updatePassword(id: Long, cipher: ByteArray, iv: ByteArray) = Unit
        override suspend fun delete(id: Long) { rows.remove(id) }
    }

    private class FakeMessageDao : MessageDao {
        val rows = linkedMapOf<Pair<Long, String>, MessageEntity>()
        override fun observeRows(): Flow<List<MessageRow>> = emptyFlow()
        override fun observeUnreadCounts(): Flow<List<UnreadCount>> = emptyFlow()
        override fun observe(accountId: Long, messageId: String): Flow<MessageEntity?> = emptyFlow()
        override suspend fun get(accountId: Long, messageId: String) = rows[accountId to messageId]
        override fun observeAttachments(accountId: Long, messageId: String): Flow<List<AttachmentEntity>> = emptyFlow()
        override suspend fun getAttachments(accountId: Long, messageId: String) = emptyList<AttachmentEntity>()
        override suspend fun updateDetail(
            accountId: Long, messageId: String, body: String, attachmentInfo: String?, senderGroup: String?, readAt: Instant,
        ) = Unit
        override suspend fun deleteAttachments(accountId: Long, messageId: String) = Unit
        override suspend fun allPreviews() = rows.values.map { PreviewRow(it.accountId, it.messageId, it.preview) }
        override suspend fun updatePreview(accountId: Long, messageId: String, preview: String) {
            rows[accountId to messageId] = rows.getValue(accountId to messageId).copy(preview = preview)
        }
        override suspend fun insertAttachments(attachments: List<AttachmentEntity>) = Unit
        override suspend fun knownIds(accountId: Long, folder: Folder) =
            rows.values.filter { it.accountId == accountId && it.folder == folder }.map { it.messageId }
        override suspend fun insertIgnore(messages: List<MessageEntity>): List<Long> = messages.map { m ->
            val key = m.accountId to m.messageId
            if (key in rows) -1L else { rows[key] = m; rows.size.toLong() }
        }
        override suspend fun updateListFields(
            accountId: Long, messageId: String, folder: Folder, senderName: String, topic: String,
            preview: String, readAt: Instant?, hasAttachments: Boolean,
        ) {
            val key = accountId to messageId
            rows[key] = rows.getValue(key).copy(
                folder = folder, senderName = senderName, topic = topic, preview = preview,
                readAt = readAt, hasAttachments = hasAttachments,
            )
        }
    }
}
