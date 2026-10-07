package pl.synsuck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import pl.synsuck.ui.theme.SynsuckTheme

class MainActivity : ComponentActivity() {
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
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) },
                onFixAccount = { nav.navigate(Routes.fixPassword(it)) },
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
