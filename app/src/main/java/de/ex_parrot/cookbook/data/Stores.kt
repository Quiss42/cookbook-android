package de.ex_parrot.cookbook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.accountDataStore by preferencesDataStore(name = "account")
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Stores the account encrypted with a Keystore key. */
class AccountStore(private val context: Context) {
    private val accountKey = stringPreferencesKey("account")

    val account: Flow<Account?> = context.accountDataStore.data.map { prefs ->
        prefs[accountKey]?.let { encrypted ->
            runCatching { Json.decodeFromString<Account>(SecureStore.decrypt(encrypted)) }.getOrNull()
        }
    }

    suspend fun save(account: Account) {
        val encrypted = SecureStore.encrypt(Json.encodeToString(account))
        context.accountDataStore.edit { it[accountKey] = encrypted }
    }

    suspend fun clear() {
        context.accountDataStore.edit { it.remove(accountKey) }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val keepScreenOn: Boolean = true,
)

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[themeKey]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[dynamicColorKey] ?: true,
            keepScreenOn = prefs[keepScreenOnKey] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = enabled }
    }
}
