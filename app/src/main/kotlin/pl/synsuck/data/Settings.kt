package pl.synsuck.data

import android.content.Context
import android.util.Patterns
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class SyncInterval(val minutes: Long?) {
    OFF(null), MIN_15(15), MIN_30(30), HOUR_1(60), HOUR_3(180);

    companion object {
        val DEFAULT = HOUR_1
        fun parse(value: String?): SyncInterval = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** Non-sensitive app preferences. (Language is stored by AppCompat itself.) */
class Settings(private val context: Context) {
    val forwardTo: Flow<String> = context.dataStore.data.map { it[FORWARD_TO].orEmpty() }

    suspend fun setForwardTo(address: String) {
        context.dataStore.edit { it[FORWARD_TO] = address.trim() }
    }

    val syncInterval: Flow<SyncInterval> = context.dataStore.data.map { SyncInterval.parse(it[SYNC_INTERVAL]) }

    suspend fun setSyncInterval(interval: SyncInterval) {
        context.dataStore.edit { it[SYNC_INTERVAL] = interval.name }
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATIONS] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS] = enabled }
    }

    /** True the first time it's called; used to ask for the notification permission only once automatically. */
    suspend fun firstNotificationPermissionPrompt(): Boolean {
        var first = false
        context.dataStore.edit {
            first = it[PERMISSION_ASKED] != true
            it[PERMISSION_ASKED] = true
        }
        return first
    }

    /** Runs [fix] once per install (tracked by [id]); for one-off data repairs. */
    suspend fun runOnce(id: String, fix: suspend () -> Unit) {
        val key = booleanPreferencesKey("done_$id")
        if (context.dataStore.data.first()[key] == true) return
        fix()
        context.dataStore.edit { it[key] = true }
    }

    /** The configured address, only if it looks like a valid email. */
    suspend fun validForwardAddress(): String? =
        forwardTo.first().takeIf { isValidEmail(it) }

    companion object {
        private val FORWARD_TO = stringPreferencesKey("forward_to")
        private val SYNC_INTERVAL = stringPreferencesKey("sync_interval")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications")
        private val PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")

        fun isValidEmail(value: String): Boolean =
            value.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(value).matches()
    }
}
