package pl.synsuck.data

import kotlinx.coroutines.flow.Flow
import pl.synsuck.crypto.PasswordCipher
import pl.synsuck.data.db.AccountDao
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.librus.LibrusClient
import pl.synsuck.librus.LibrusException
import pl.synsuck.librus.Me
import java.io.IOException

sealed interface VerifyResult {
    data class Ok(val accountId: Long) : VerifyResult
    data class AuthFailed(val serverMessage: String?) : VerifyResult
    data object CaptchaRequired : VerifyResult
    data object Network : VerifyResult
    data object Duplicate : VerifyResult
    data class Unexpected(val detail: String) : VerifyResult
}

class AccountRepository(
    private val accounts: AccountDao,
    private val cipher: PasswordCipher,
    private val sessions: LibrusSessions,
) {
    fun observeAccounts(): Flow<List<AccountEntity>> = accounts.observeAll()

    suspend fun get(id: Long): AccountEntity? = accounts.get(id)

    /** First palette index not used by any account yet. */
    suspend fun nextColor(paletteSize: Int): Int {
        val used = accounts.getAll().map { it.color }.toSet()
        return (0 until paletteSize).firstOrNull { it !in used } ?: (used.size % paletteSize)
    }

    /**
     * Logs in to Librus first; only stores the account (with the password encrypted)
     * if Librus accepts the credentials. [password] is zeroed before returning.
     */
    suspend fun add(label: String, login: String, password: CharArray, color: Int): VerifyResult {
        try {
            val trimmedLogin = login.trim()
            if (accounts.loginExists(trimmedLogin)) return VerifyResult.Duplicate

            val client = sessions.newClient()
            val me = when (val r = tryLogin(client, trimmedLogin, password)) {
                is Login.Failure -> return r.result
                is Login.Success -> r.me
            }
            val encrypted = cipher.encrypt(password)
            val id = accounts.insert(
                AccountEntity(
                    label = label.trim().ifEmpty { me.firstName.ifBlank { trimmedLogin } },
                    login = trimmedLogin,
                    passwordCipher = encrypted.ciphertext,
                    passwordIv = encrypted.iv,
                    color = color,
                    sortOrder = accounts.nextSortOrder(),
                ),
            )
            sessions.adopt(id, client)
            return VerifyResult.Ok(id)
        } finally {
            password.fill('\u0000')
        }
    }

    /** Re-enter password for an existing account (e.g. after AUTH_FAILED). */
    suspend fun updatePassword(accountId: Long, password: CharArray): VerifyResult {
        try {
            val account = accounts.get(accountId) ?: return VerifyResult.Unexpected("account removed")
            val client = sessions.newClient()
            val r = tryLogin(client, account.login, password)
            if (r is Login.Failure) return r.result
            val encrypted = cipher.encrypt(password)
            accounts.updatePassword(accountId, encrypted.ciphertext, encrypted.iv)
            sessions.adopt(accountId, client)
            return VerifyResult.Ok(accountId)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun remove(accountId: Long) {
        sessions.forget(accountId)
        accounts.delete(accountId) // messages and attachments cascade
    }

    private sealed interface Login {
        data class Success(val me: Me) : Login
        data class Failure(val result: VerifyResult) : Login
    }

    private suspend fun tryLogin(client: LibrusClient, login: String, password: CharArray): Login = try {
        Login.Success(client.login(login, password))
    } catch (e: LibrusException.AuthFailed) {
        Login.Failure(VerifyResult.AuthFailed(e.serverMessage))
    } catch (e: LibrusException.CaptchaRequired) {
        Login.Failure(VerifyResult.CaptchaRequired)
    } catch (e: LibrusException) {
        Login.Failure(VerifyResult.Unexpected(e.message ?: e::class.simpleName.orEmpty()))
    } catch (e: IOException) {
        Login.Failure(VerifyResult.Network)
    }
}
