@file:OptIn(ExperimentalMaterial3Api::class)

package pl.synsuck.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.synsuck.R
import pl.synsuck.data.Settings
import pl.synsuck.data.SyncInterval
import pl.synsuck.sync.SyncScheduler

class SettingsViewModel(private val settings: Settings, private val scheduler: SyncScheduler) : ViewModel() {
    val forwardTo: StateFlow<String?> = settings.forwardTo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncInterval: StateFlow<SyncInterval?> = settings.syncInterval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val notifications: StateFlow<Boolean?> = settings.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setForwardTo(value: String) {
        viewModelScope.launch { settings.setForwardTo(value) }
    }

    fun setSyncInterval(interval: SyncInterval) {
        viewModelScope.launch {
            settings.setSyncInterval(interval)
            scheduler.apply(interval)
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { settings.setNotificationsEnabled(enabled) }
    }
}

private val SyncInterval.label: Int
    get() = when (this) {
        SyncInterval.OFF -> R.string.interval_off
        SyncInterval.MIN_15 -> R.string.interval_15m
        SyncInterval.MIN_30 -> R.string.interval_30m
        SyncInterval.HOUR_1 -> R.string.interval_1h
        SyncInterval.HOUR_3 -> R.string.interval_3h
    }

private enum class Language(val tag: String?, val label: Int) {
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_english),
    POLISH("pl", R.string.language_polish),
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenAccounts: () -> Unit) {
    val stored by viewModel.forwardTo.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenAccounts).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ManageAccounts, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.accounts_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            HorizontalDivider()

            SectionTitle(R.string.background_title)
            val interval by viewModel.syncInterval.collectAsStateWithLifecycle()
            val notifications by viewModel.notifications.collectAsStateWithLifecycle()
            interval?.let { current ->
                SyncInterval.entries.forEach { option ->
                    RadioRow(selected = option == current, label = option.label) { viewModel.setSyncInterval(option) }
                }
            }
            notifications?.let { enabled ->
                NotificationsSwitch(enabled, enabledInterval = interval != SyncInterval.OFF, onChange = viewModel::setNotifications)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(R.string.forward_to_title)
            // Wait for the stored value before showing the field, so it isn't overwritten with "".
            stored?.let { initial -> ForwardAddressField(initial, viewModel::setForwardTo) }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(R.string.language_title)
            LanguagePicker()
        }
    }
}

@Composable
private fun ForwardAddressField(initial: String, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val invalid = value.isNotBlank() && !Settings.isValidEmail(value.trim())
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onChange(it) },
        label = { Text(stringResource(R.string.forward_to_label)) },
        supportingText = {
            Text(stringResource(if (invalid) R.string.forward_to_invalid else R.string.forward_to_hint))
        },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun LanguagePicker() {
    // AppCompat persists the choice and applies it on every start (also below Android 13).
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifEmpty { null }
    Language.entries.forEach { language ->
        RadioRow(selected = language.tag == current, label = language.label) {
            val locales = language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: Int, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Turning notifications on asks for the Android 13+ permission. If notifications stay blocked,
 * the hint says so and tapping it opens the app's system notification settings.
 */
@Composable
private fun NotificationsSwitch(enabled: Boolean, enabledInterval: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var systemAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemAllowed = granted
    }
    val openSystemSettings = {
        context.startActivity(Intent(ACTION_APP_NOTIFICATION_SETTINGS).putExtra(EXTRA_APP_PACKAGE, context.packageName))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { toggle(!enabled, systemAllowed, onChange) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notifications_label), style = MaterialTheme.typography.bodyLarge)
            val hint = when {
                enabled && !systemAllowed -> R.string.notifications_blocked
                enabled && !enabledInterval -> R.string.notifications_need_interval
                else -> R.string.notifications_hint
            }
            val blocked = hint == R.string.notifications_blocked
            Text(
                stringResource(hint),
                Modifier.clickable(enabled = blocked) { openSystemSettings() },
                style = MaterialTheme.typography.bodySmall,
                color = if (hint == R.string.notifications_hint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { on -> toggle(on, systemAllowed, onChange) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } },
        )
    }
}

private fun toggle(on: Boolean, systemAllowed: Boolean, onChange: (Boolean) -> Unit, requestPermission: () -> Unit) {
    onChange(on)
    if (on && !systemAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermission()
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
