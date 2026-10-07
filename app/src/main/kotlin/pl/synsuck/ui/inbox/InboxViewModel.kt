package pl.synsuck.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.synsuck.data.AccountRepository
import pl.synsuck.data.SyncOutcome
import pl.synsuck.data.Syncer
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.MessageDao
import pl.synsuck.data.db.MessageRow

data class AccountTab(val account: AccountEntity, val unread: Int)

data class InboxState(
    val loaded: Boolean = false,
    val tabs: List<AccountTab> = emptyList(),
    val messages: List<MessageRow> = emptyList(),
) {
    val totalUnread: Int get() = tabs.sumOf { it.unread }
}

sealed interface InboxEvent {
    data object NetworkError : InboxEvent
}

class InboxViewModel(
    accounts: AccountRepository,
    messages: MessageDao,
    private val syncer: Syncer,
) : ViewModel() {
    val state: StateFlow<InboxState> = combine(
        accounts.observeAccounts(),
        messages.observeRows(),
        messages.observeUnreadCounts(),
    ) { accountList, rows, counts ->
        val unread = counts.associate { it.accountId to it.unread }
        InboxState(
            loaded = true,
            tabs = accountList.map { AccountTab(it, unread[it.id] ?: 0) },
            messages = rows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxState())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _events = Channel<InboxEvent>(Channel.BUFFERED)
    val events: Flow<InboxEvent> = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val outcomes = syncer.syncAll()
                if (outcomes.any { it is SyncOutcome.NetworkError }) _events.send(InboxEvent.NetworkError)
            } finally {
                _refreshing.value = false
            }
        }
    }
}
