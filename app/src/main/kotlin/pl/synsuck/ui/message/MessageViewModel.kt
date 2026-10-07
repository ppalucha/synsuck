package pl.synsuck.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.synsuck.data.CachedFile
import pl.synsuck.data.MessageRepository
import pl.synsuck.data.Settings
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AttachmentEntity
import pl.synsuck.data.db.MessageEntity
import pl.synsuck.librus.LibrusException
import java.io.IOException

data class MessageState(
    val message: MessageEntity? = null,
    val account: AccountEntity? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val loadingBody: Boolean = false,
    val bodyError: MessageError? = null,
    val downloading: Set<String> = emptySet(),
    val forwarding: Boolean = false,
)

enum class MessageError { NETWORK, LIBRUS }

sealed interface MessageEvent {
    data class Open(val file: CachedFile) : MessageEvent
    data class Forward(
        val message: MessageEntity,
        val account: AccountEntity,
        val files: List<CachedFile>,
        val to: String?,
    ) : MessageEvent
    data class Failed(val error: MessageError) : MessageEvent
}

class MessageViewModel(
    private val repository: MessageRepository,
    private val settings: Settings,
    private val accountId: Long,
    private val messageId: String,
) : ViewModel() {
    private val ui = MutableStateFlow(MessageState())

    val state: StateFlow<MessageState> = combine(
        repository.observeMessage(accountId, messageId),
        repository.observeAccount(accountId),
        repository.observeAttachments(accountId, messageId),
        ui,
    ) { message, account, attachments, ui ->
        ui.copy(message = message, account = account, attachments = attachments)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageState())

    private val _events = Channel<MessageEvent>(Channel.BUFFERED)
    val events: Flow<MessageEvent> = _events.receiveAsFlow()

    init {
        loadBody()
    }

    fun loadBody() {
        if (ui.value.loadingBody) return
        ui.update { it.copy(loadingBody = true, bodyError = null) }
        viewModelScope.launch {
            val error = attempt { repository.ensureBody(accountId, messageId) }
            ui.update { it.copy(loadingBody = false, bodyError = error) }
        }
    }

    fun open(attachment: AttachmentEntity) {
        if (attachment.attachmentId in ui.value.downloading) return
        ui.update { it.copy(downloading = it.downloading + attachment.attachmentId) }
        viewModelScope.launch {
            var file: CachedFile? = null
            val error = attempt { file = repository.download(attachment) }
            ui.update { it.copy(downloading = it.downloading - attachment.attachmentId) }
            file?.let { _events.send(MessageEvent.Open(it)) }
            error?.let { _events.send(MessageEvent.Failed(it)) }
        }
    }

    fun forward() {
        val s = state.value
        val message = s.message ?: return
        val account = s.account ?: return
        if (s.forwarding || message.body == null) return
        ui.update { it.copy(forwarding = true) }
        viewModelScope.launch {
            var files: List<CachedFile> = emptyList()
            val error = attempt { files = repository.downloadAll(accountId, messageId) }
            ui.update { it.copy(forwarding = false) }
            if (error != null) {
                _events.send(MessageEvent.Failed(error))
            } else {
                _events.send(MessageEvent.Forward(message, account, files, settings.validForwardAddress()))
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): MessageError? = try {
        block()
        null
    } catch (e: IOException) {
        MessageError.NETWORK
    } catch (e: LibrusException) {
        MessageError.LIBRUS
    } catch (e: IllegalStateException) {
        MessageError.LIBRUS
    }
}
