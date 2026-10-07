package pl.synsuck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.synsuck.sync.NotificationTarget
import pl.synsuck.ui.accounts.AccountEditScreen
import pl.synsuck.ui.accounts.AccountEditViewModel
import pl.synsuck.ui.accounts.AccountsScreen
import pl.synsuck.ui.accounts.AccountsViewModel
import pl.synsuck.ui.inbox.InboxScreen
import pl.synsuck.ui.inbox.InboxViewModel
import pl.synsuck.ui.message.MessageScreen
import pl.synsuck.ui.message.MessageViewModel
import pl.synsuck.ui.settings.SettingsScreen
import pl.synsuck.ui.settings.SettingsViewModel
import pl.synsuck.ui.theme.SynsuckTheme

/** Navigation requested from outside the UI (a tapped notification). */
sealed interface NavRequest {
    data class Message(val accountId: Long, val messageId: String) : NavRequest
    data class Account(val accountId: Long) : NavRequest
    data class FixPassword(val accountId: Long) : NavRequest
}

// AppCompatActivity: needed for the in-app language picker (AppCompatDelegate.setApplicationLocales).
class MainActivity : AppCompatActivity() {
    private val container get() = (application as SynsuckApp).container
    private val navRequests = MutableStateFlow<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleNotificationTap(intent)
        setContent {
            SynsuckTheme {
                AppNavigation(container, navRequests, onConsumed = { navRequests.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    /** Extras are only trusted after the account/message is found in the local database. */
    private fun handleNotificationTap(intent: Intent) {
        val accountId = intent.getLongExtra(NotificationTarget.EXTRA_ACCOUNT_ID, -1L).takeIf { it > 0 } ?: return
        val messageId = intent.getStringExtra(NotificationTarget.EXTRA_MESSAGE_ID)
        val fixPassword = intent.getBooleanExtra(NotificationTarget.EXTRA_FIX_PASSWORD, false)
        lifecycleScope.launch {
            container.database.accounts().get(accountId) ?: return@launch
            navRequests.value = when {
                fixPassword -> NavRequest.FixPassword(accountId)
                messageId != null && container.database.messages().get(accountId, messageId) != null ->
                    NavRequest.Message(accountId, messageId)
                else -> NavRequest.Account(accountId)
            }
        }
    }
}

private object Routes {
    const val INBOX = "inbox"
    const val ACCOUNTS = "accounts"
    const val SETTINGS = "settings"
    const val MESSAGE = "message/{accountId}/{messageId}"
    fun message(accountId: Long, messageId: String) = "message/$accountId/${Uri.encode(messageId)}"
    const val ADD_ACCOUNT = "account/new"
    const val FIX_PASSWORD = "account/{id}/password"
    fun fixPassword(id: Long) = "account/$id/password"
}

@Composable
private fun AppNavigation(container: AppContainer, navRequests: StateFlow<NavRequest?>, onConsumed: () -> Unit) {
    val nav = rememberNavController()
    val request by navRequests.collectAsStateWithLifecycle()
    var selectAccount by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(request) {
        when (val r = request) {
            null -> return@LaunchedEffect
            is NavRequest.Message -> {
                nav.popBackStack(Routes.INBOX, inclusive = false)
                nav.navigate(Routes.message(r.accountId, r.messageId))
            }
            is NavRequest.Account -> {
                nav.popBackStack(Routes.INBOX, inclusive = false)
                selectAccount = r.accountId
            }
            is NavRequest.FixPassword -> nav.navigate(Routes.fixPassword(r.accountId))
        }
        onConsumed()
    }

    NotificationPermissionPrompt(container)

    NavHost(nav, startDestination = Routes.INBOX) {
        composable(Routes.INBOX) {
            InboxScreen(
                viewModel = viewModel {
                    InboxViewModel(container.accounts, container.database.messages(), container.syncer)
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) },
                onFixAccount = { nav.navigate(Routes.fixPassword(it)) },
                onOpenMessage = { accountId, messageId -> nav.navigate(Routes.message(accountId, messageId)) },
                selectAccountId = selectAccount,
                onAccountSelected = { selectAccount = null },
            )
        }
        composable(
            Routes.MESSAGE,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("messageId") { type = NavType.StringType },
            ),
        ) { entry ->
            val accountId = entry.arguments!!.getLong("accountId")
            val messageId = entry.arguments!!.getString("messageId")!!
            MessageScreen(
                viewModel = viewModel {
                    MessageViewModel(container.messages, container.settings, accountId, messageId)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings, container.scheduler) },
                onBack = { nav.popBackStack() },
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
            )
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                viewModel = viewModel { AccountsViewModel(container.accounts) },
                onBack = { nav.popBackStack() },
                onAdd = { nav.navigate(Routes.ADD_ACCOUNT) },
                onFixPassword = { nav.navigate(Routes.fixPassword(it)) },
            )
        }
        composable(Routes.ADD_ACCOUNT) {
            AccountEditScreen(
                viewModel = viewModel {
                    AccountEditViewModel(container.accounts, container.syncer, container.appScope, container.notifier::cancelProblem, accountId = null)
                },
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.FIX_PASSWORD, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            AccountEditScreen(
                viewModel = viewModel {
                    AccountEditViewModel(container.accounts, container.syncer, container.appScope, container.notifier::cancelProblem, accountId = id)
                },
                onDone = { nav.popBackStack() },
            )
        }
    }
}

/** Android 13+: ask once, automatically, after the first account exists (if notifications are on). */
@Composable
private fun NotificationPermissionPrompt(container: AppContainer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        container.accounts.observeAccounts().first { it.isNotEmpty() }
        if (!container.settings.notificationsEnabled.first()) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && container.settings.firstNotificationPermissionPrompt()) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
