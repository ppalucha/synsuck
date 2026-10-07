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

/** Non-sensitive app preferences. (Language is stored by AppCompat itself.) */
class Settings(private val context: Context) {
    val forwardTo: Flow<String> = context.dataStore.data.map { it[FORWARD_TO].orEmpty() }

    suspend fun setForwardTo(address: String) {
        context.dataStore.edit { it[FORWARD_TO] = address.trim() }
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

        fun isValidEmail(value: String): Boolean =
            value.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(value).matches()
    }
}
