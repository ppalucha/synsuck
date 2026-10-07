package pl.synsuck.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import pl.synsuck.crypto.PasswordCipher
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.librus.Folder
import pl.synsuck.librus.LibrusClient
import pl.synsuck.librus.LibrusException
import pl.synsuck.librus.MessagePage
import java.util.concurrent.ConcurrentHashMap

/**
 * One logged-in [LibrusClient] per account, kept in memory only (cookies never hit disk).
 * Logs in lazily and once more if Librus reports the session as expired.
 */
class LibrusSessions(
    private val http: OkHttpClient,
    private val cipher: PasswordCipher,
) : MessageLister {
    private class Session(val client: LibrusClient) {
        val mutex = Mutex()
        var loggedIn = false
    }

    private val sessions = ConcurrentHashMap<Long, Session>()

    fun newClient(): LibrusClient = LibrusClient(http)

    /** Takes over a client that was just used for a verification login. */
    fun adopt(accountId: Long, client: LibrusClient) {
        sessions[accountId] = Session(client).apply { loggedIn = true }
    }

    suspend fun <T> withClient(account: AccountEntity, block: suspend (LibrusClient) -> T): T {
        val session = sessions.getOrPut(account.id) { Session(newClient()) }
        return session.mutex.withLock {
            if (!session.loggedIn) login(session, account)
            try {
                block(session.client)
            } catch (e: LibrusException.SessionExpired) {
                login(session, account)
                block(session.client)
            }
        }
    }

    override suspend fun list(account: AccountEntity, folder: Folder, page: Int, limit: Int): MessagePage =
        withClient(account) { it.listMessages(folder, page, limit) }

    fun forget(accountId: Long) {
        sessions.remove(accountId)?.client?.clearSession()
    }

    private suspend fun login(session: Session, account: AccountEntity) {
        session.loggedIn = false
        val password = cipher.decrypt(account.passwordCipher, account.passwordIv)
        try {
            session.client.login(account.login, password)
        } finally {
            password.fill('\u0000')
        }
        session.loggedIn = true
    }
}
