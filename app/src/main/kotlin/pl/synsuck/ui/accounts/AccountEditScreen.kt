@file:OptIn(ExperimentalMaterial3Api::class)

package pl.synsuck.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.synsuck.R
import pl.synsuck.data.AccountRepository
import pl.synsuck.data.Syncer
import pl.synsuck.data.VerifyResult
import pl.synsuck.ui.theme.AccountColors

data class AccountEditState(
    val fixMode: Boolean,
    val label: String = "",
    val login: String = "",
    val password: String = "",
    val color: Int = 0,
    val busy: Boolean = false,
    val error: VerifyResult? = null,
    val missingFields: Boolean = false,
    val done: Boolean = false,
)

/** Add a new account, or re-enter the password of [accountId]. */
class AccountEditViewModel(
    private val repository: AccountRepository,
    private val syncer: Syncer,
    private val appScope: CoroutineScope,
    private val onAccountFixed: (Long) -> Unit,
    private val accountId: Long?,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountEditState(fixMode = accountId != null))
    val state: StateFlow<AccountEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (accountId != null) {
                repository.get(accountId)?.let { a -> _state.update { it.copy(label = a.label, login = a.login, color = a.color) } }
            } else {
                // Next unused color for convenience.
                _state.update { it.copy(color = repository.nextColor(AccountColors.palette.size)) }
            }
        }
    }

    fun onLabel(v: String) = _state.update { it.copy(label = v) }
    fun onLogin(v: String) = _state.update { it.copy(login = v.trim(), error = null, missingFields = false) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null, missingFields = false) }
    fun onColor(v: Int) = _state.update { it.copy(color = v) }

    fun save() {
        val s = _state.value
        if (s.busy) return
        if (s.login.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(missingFields = true) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val password = s.password.toCharArray() // zeroed by the repository
            val result = if (accountId == null) {
                repository.add(s.label, s.login, password, s.color)
            } else {
                repository.updatePassword(accountId, password)
            }
            if (result is VerifyResult.Ok) {
                onAccountFixed(result.accountId)
                // Outlives this screen: first sync of a new/fixed account.
                appScope.launch { repository.get(result.accountId)?.let { syncer.syncAccount(it) } }
                _state.update { it.copy(busy = false, password = "", done = true) }
            } else {
                _state.update { it.copy(busy = false, error = result) }
            }
        }
    }
}

@Composable
fun AccountEditScreen(viewModel: AccountEditViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.fixMode) R.string.fix_password_title else R.string.add_account_title))
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.fixMode) {
                OutlinedTextField(
                    value = state.label,
                    onValueChange = viewModel::onLabel,
                    label = { Text(stringResource(R.string.field_label)) },
                    supportingText = { Text(stringResource(R.string.field_label_hint)) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.login,
                onValueChange = viewModel::onLogin,
                label = { Text(stringResource(R.string.field_login)) },
                singleLine = true,
                enabled = !state.busy && !state.fixMode,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPassword,
                label = { Text(stringResource(R.string.field_password)) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (!state.fixMode) {
                Text(stringResource(R.string.field_color), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountColors.palette.forEachIndexed { index, color ->
                        val selected = index == state.color
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else color,
                                    shape = CircleShape,
                                )
                                .clickable(enabled = !state.busy) { viewModel.onColor(index) },
                        )
                    }
                }
            }

            errorText(state)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = viewModel::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.verifying))
                } else {
                    Text(stringResource(R.string.verify_and_save))
                }
            }
        }
    }
}

@Composable
private fun errorText(state: AccountEditState): String? {
    if (state.missingFields) return stringResource(R.string.error_required)
    return when (val e = state.error) {
        null, is VerifyResult.Ok -> null
        is VerifyResult.AuthFailed -> e.serverMessage?.let { stringResource(R.string.error_auth_failed_detail, it) }
            ?: stringResource(R.string.error_auth_failed)
        VerifyResult.CaptchaRequired -> stringResource(R.string.error_captcha)
        VerifyResult.Network -> stringResource(R.string.network_error)
        VerifyResult.Duplicate -> stringResource(R.string.error_duplicate)
        is VerifyResult.Unexpected -> stringResource(R.string.error_unexpected, e.detail)
    }
}
