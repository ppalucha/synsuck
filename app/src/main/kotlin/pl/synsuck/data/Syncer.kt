package pl.synsuck.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import pl.synsuck.data.db.AccountDao
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AccountStatus
import pl.synsuck.data.db.MessageDao
import pl.synsuck.data.db.MessageEntity
import pl.synsuck.librus.Folder
import pl.synsuck.librus.LibrusException
import pl.synsuck.librus.MessagePage
import pl.synsuck.librus.MessageSummary
import java.io.IOException
import java.time.Clock

sealed interface SyncOutcome {
    val accountId: Long

    /** [newUnread] is empty on the first (baseline) sync of an account. */
    data class Synced(override val accountId: Long, val newUnread: List<MessageEntity>) : SyncOutcome
    data class Skipped(override val accountId: Long) : SyncOutcome
    data class NetworkError(override val accountId: Long) : SyncOutcome
    data class Failed(override val accountId: Long, val status: AccountStatus) : SyncOutcome
}

/** Fetches one page of an account's message list (implemented by [LibrusSessions]). */
fun interface MessageLister {
    suspend fun list(account: AccountEntity, folder: Folder, page: Int, limit: Int): MessagePage
}

/**
 * Fetches message lists (never bodies — opening a message marks it read in Librus)
 * and merges them into the local database.
 */
class Syncer(
    private val accounts: AccountDao,
    private val messages: MessageDao,
    private val lister: MessageLister,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun syncAll(): List<SyncOutcome> = coroutineScope {
        accounts.getAll().map { async { syncAccount(it) } }.awaitAll()
    }

    suspend fun syncAccount(account: AccountEntity): SyncOutcome {
        if (account.needsUserAction) return SyncOutcome.Skipped(account.id)
        return try {
            val inserted = Folder.entries.flatMap { folder -> syncFolder(account, folder) }
            accounts.markSynced(account.id, clock.instant())
            val newUnread = if (account.baselineDone) inserted.filter { it.readAt == null } else emptyList()
            SyncOutcome.Synced(account.id, newUnread)
        } catch (e: LibrusException.AuthFailed) {
            fail(account, AccountStatus.AUTH_FAILED, e.serverMessage)
        } catch (e: LibrusException.CaptchaRequired) {
            fail(account, AccountStatus.CAPTCHA_REQUIRED, null)
        } catch (e: LibrusException) {
            fail(account, AccountStatus.ERROR, e.message)
        } catch (e: IOException) {
            SyncOutcome.NetworkError(account.id)
        }
    }

    /** Pages newest-first until a page contains only known messages (or the cap is reached). */
    private suspend fun syncFolder(account: AccountEntity, folder: Folder): List<MessageEntity> {
        val known = messages.knownIds(account.id, folder).toHashSet()
        val inserted = mutableListOf<MessageEntity>()
        var page = 1
        while (true) {
            val result = lister.list(account, folder, page, PAGE_SIZE)
            if (result.items.isEmpty()) break
            inserted += messages.upsertSummaries(result.items.map { it.toEntity(account.id) })

            val fetched = page * PAGE_SIZE
            val reachedKnown = known.isNotEmpty() && result.items.all { it.id in known }
            if (reachedKnown || fetched >= result.total || fetched >= MAX_MESSAGES_PER_FOLDER) break
            page++
        }
        return inserted
    }

    private suspend fun fail(account: AccountEntity, status: AccountStatus, detail: String?): SyncOutcome {
        accounts.setStatus(account.id, status, detail)
        return SyncOutcome.Failed(account.id, status)
    }

    private fun MessageSummary.toEntity(accountId: Long) = MessageEntity(
        accountId = accountId,
        messageId = id,
        folder = folder,
        senderName = senderName,
        topic = topic,
        preview = preview,
        sentAt = sentAt,
        readAt = readAt,
        hasAttachments = hasAttachments,
        firstSeenAt = clock.instant(),
    )

    internal companion object {
        const val PAGE_SIZE = 50
        const val MAX_MESSAGES_PER_FOLDER = 500
    }
}
