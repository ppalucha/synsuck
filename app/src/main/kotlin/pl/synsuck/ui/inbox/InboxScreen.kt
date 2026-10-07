@file:OptIn(ExperimentalMaterial3Api::class)

package pl.synsuck.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.synsuck.R
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AccountStatus
import pl.synsuck.data.db.MessageRow
import pl.synsuck.librus.Folder
import pl.synsuck.ui.rememberMessageDateFormatter
import pl.synsuck.ui.theme.AccountColors

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onFixAccount: (Long) -> Unit,
    onOpenMessage: (accountId: Long, messageId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                InboxEvent.NetworkError -> snackbar.showSnackbar(context.getString(R.string.network_error))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Unit
                state.tabs.isEmpty() -> NoAccounts(onAddAccount)
                else -> Inbox(state, refreshing, viewModel::refresh, onFixAccount, onOpenMessage)
            }
        }
    }
}

@Composable
private fun Inbox(
    state: InboxState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onFixAccount: (Long) -> Unit,
    onOpenMessage: (Long, String) -> Unit,
) {
    // Tab 0 = All; tab i = account i-1. Hidden with a single account.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (selected > state.tabs.size) selected = 0
    val multiple = state.tabs.size > 1
    val selectedAccount: AccountEntity? = if (multiple && selected > 0) state.tabs[selected - 1].account else null

    if (multiple) {
        PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            Tab(selected = selected == 0, onClick = { selected = 0 }, text = {
                TabLabel(stringResource(R.string.tab_all), state.totalUnread)
            })
            state.tabs.forEachIndexed { i, tab ->
                Tab(selected = selected == i + 1, onClick = { selected = i + 1 }, text = {
                    TabLabel(tab.account.label, tab.unread)
                })
            }
        }
    }

    val problemAccounts = state.tabs.map { it.account }
        .filter { it.status != AccountStatus.OK && (selectedAccount == null || it.id == selectedAccount.id) }
    problemAccounts.forEach { StatusBanner(it, onClick = { onFixAccount(it.id) }) }

    val rows = if (selectedAccount == null) state.messages else state.messages.filter { it.accountId == selectedAccount.id }
    val formatDate = rememberMessageDateFormatter()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.inbox_empty),
                        Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { "${it.accountId}/${it.messageId}" }) { row ->
                MessageListItem(
                    row,
                    showAccount = multiple && selectedAccount == null,
                    formatDate = formatDate,
                    onClick = { onOpenMessage(row.accountId, row.messageId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, unread: Int) {
    Text(
        text = if (unread > 0) stringResource(R.string.tab_with_unread, label, unread) else label,
        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
    )
}

@Composable
private fun StatusBanner(account: AccountEntity, onClick: () -> Unit) {
    val text = when (account.status) {
        AccountStatus.AUTH_FAILED -> stringResource(R.string.status_auth_failed, account.label)
        AccountStatus.CAPTCHA_REQUIRED -> stringResource(R.string.status_captcha, account.label)
        AccountStatus.ERROR -> stringResource(R.string.status_error, account.label, account.lastError.orEmpty())
        AccountStatus.OK -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageListItem(
    row: MessageRow,
    showAccount: Boolean,
    formatDate: (java.time.Instant) -> String,
    onClick: () -> Unit,
) {
    val unread = row.readAt == null
    val accountColor = AccountColors.of(row.accountColor)
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accountColor))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                if (showAccount) {
                    AccountChip(row.accountLabel, accountColor)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    row.senderName,
                    Modifier.weight(1f),
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatDate(row.sentAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(row.topic, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.preview,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.folder == Folder.ARCHIVE) {
                    Text(
                        stringResource(R.string.archived),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.hasAttachments) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.has_attachments),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountChip(label: String, color: Color) {
    Text(
        label,
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun NoAccounts(onAddAccount: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_accounts), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddAccount) { Text(stringResource(R.string.add_account)) }
    }
}
