package pl.synsuck

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import pl.synsuck.crypto.PasswordCipher
import pl.synsuck.data.AccountRepository
import pl.synsuck.data.LibrusSessions
import pl.synsuck.data.Syncer
import pl.synsuck.data.db.SynsuckDatabase
import java.util.concurrent.TimeUnit

/** Manual dependency wiring; small enough not to need a DI framework. */
class AppContainer(app: Application) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cipher = PasswordCipher()

    /** For work that must outlive a screen (e.g. first sync after adding an account). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = SynsuckDatabase.create(app)
    val sessions = LibrusSessions(http, cipher)
    val accounts = AccountRepository(database.accounts(), cipher, sessions)
    val syncer = Syncer(database.accounts(), database.messages(), sessions)
}

class SynsuckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
