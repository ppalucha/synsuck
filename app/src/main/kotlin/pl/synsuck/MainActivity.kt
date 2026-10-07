package pl.synsuck

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

// AppCompatActivity: needed for the in-app language picker (AppCompatDelegate.setApplicationLocales).
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SynsuckApp).container
        setContent {
            SynsuckTheme {
                AppNavigation(container)
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
private fun AppNavigation(container: AppContainer) {
    val nav = rememberNavController()
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
                viewModel = viewModel { SettingsViewModel(container.settings) },
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
                    AccountEditViewModel(container.accounts, container.syncer, container.appScope, accountId = null)
                },
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.FIX_PASSWORD, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            AccountEditScreen(
                viewModel = viewModel {
                    AccountEditViewModel(container.accounts, container.syncer, container.appScope, accountId = id)
                },
                onDone = { nav.popBackStack() },
            )
        }
    }
}
