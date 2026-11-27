@file:OptIn(ExperimentalMaterial3Api::class)

package de.ex_parrot.cookbook.ui.settings

import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalResources
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ex_parrot.cookbook.BuildConfig
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.AppSettings
import de.ex_parrot.cookbook.data.PendingChange
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.data.SettingsStore
import de.ex_parrot.cookbook.data.ThemeMode
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import de.ex_parrot.cookbook.data.UpdateChecker
import de.ex_parrot.cookbook.data.UpdateInfo
import de.ex_parrot.cookbook.ui.components.formatDateTime
import kotlinx.coroutines.CancellationException

@Composable
fun SettingsScreen(
    session: Session,
    settings: AppSettings,
    settingsStore: SettingsStore,
    onUpdateFound: (UpdateInfo) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeMode.entries.forEach { mode ->
                ListItem(
                    leadingContent = { RadioButton(selected = settings.themeMode == mode, onClick = null) },
                    modifier = Modifier.selectable(selected = settings.themeMode == mode) {
                        scope.launch { settingsStore.setThemeMode(mode) }
                    },
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            },
                        ),
                    )
                }
            }
            ListItem(
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                        (1..3).forEach { count ->
                            SegmentedButton(
                                selected = settings.categoryColumns == count,
                                onClick = { scope.launch { settingsStore.setCategoryColumns(count) } },
                                shape = SegmentedButtonDefaults.itemShape(index = count - 1, count = 3),
                            ) { Text("$count") }
                        }
                    }
                },
            ) { Text(stringResource(R.string.settings_category_columns)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchItem(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_hint),
                    checked = settings.dynamicColor,
                ) { scope.launch { settingsStore.setDynamicColor(it) } }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ListItem(
                    supportingContent = { Text(stringResource(R.string.settings_language_hint)) },
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()),
                        )
                    },
                ) { Text(stringResource(R.string.settings_language)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_cooking))
            SwitchItem(
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_hint),
                checked = settings.keepScreenOn,
            ) { scope.launch { settingsStore.setKeepScreenOn(it) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_account))
            ListItem(
                supportingContent = { Text(session.account.server) },
            ) { Text(session.account.loginName) }
            ListItem(
                modifier = Modifier.clickable { confirmLogout = true },
            ) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_offline))
            val offlineStatus by session.offlineStore.status.collectAsStateWithLifecycle()
            ListItem(
                supportingContent = {
                    Text(
                        when {
                            offlineStatus.syncing -> stringResource(R.string.offline_syncing)
                            offlineStatus.lastSync != null ->
                                stringResource(R.string.offline_last_sync, formatDateTime(offlineStatus.lastSync!!))
                            else -> stringResource(R.string.offline_never)
                        },
                    )
                },
                trailingContent = {
                    if (offlineStatus.syncing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { session.requestSync(force = true) }) {
                            Text(stringResource(R.string.offline_sync_now))
                        }
                    }
                },
            ) {
                Text(pluralStringResource(R.plurals.offline_recipe_count, offlineStatus.recipeCount, offlineStatus.recipeCount))
            }

            val pendingChanges by session.offlineStore.pending.changes.collectAsStateWithLifecycle()
            if (pendingChanges.isNotEmpty()) {
                Text(
                    stringResource(R.string.pending_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                pendingChanges.forEach { change ->
                    ListItem(
                        supportingContent = {
                            val kind = stringResource(
                                when (change.type) {
                                    PendingChange.Type.CREATE -> R.string.pending_create
                                    PendingChange.Type.UPDATE -> R.string.pending_update
                                    PendingChange.Type.DELETE -> R.string.pending_delete
                                },
                            )
                            val state = when {
                                change.conflict -> stringResource(R.string.pending_conflict)
                                change.error != null -> stringResource(R.string.pending_error, change.error)
                                else -> null
                            }
                            Text(listOfNotNull(kind, state).joinToString(" · "))
                        },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { session.repository.discardPending(change) } }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.pending_discard))
                            }
                        },
                    ) { Text(change.name.ifBlank { "–" }) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_updates))
            ListItem(
                trailingContent = { Checkbox(checked = settings.checkUpdatesOnStart, onCheckedChange = null) },
                modifier = Modifier.clickable {
                    scope.launch { settingsStore.setCheckUpdatesOnStart(!settings.checkUpdatesOnStart) }
                },
            ) { Text(stringResource(R.string.settings_check_updates_on_start)) }
            OutlinedButton(
                onClick = {
                    checkingUpdates = true
                    scope.launch {
                        try {
                            val update = UpdateChecker.check(BuildConfig.VERSION_NAME)
                            if (update != null) {
                                onUpdateFound(update)
                            } else {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.update_none, BuildConfig.VERSION_NAME),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.update_check_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            checkingUpdates = false
                        }
                    }
                },
                enabled = !checkingUpdates,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                if (checkingUpdates) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_check_updates_now))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            ) { Text(stringResource(R.string.app_name)) }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = {
                val pendingNow by session.offlineStore.pending.changes.collectAsStateWithLifecycle()
                val waiting = pendingNow.size
                Text(
                    stringResource(R.string.logout_confirm) +
                        if (waiting > 0) "\n\n" + pluralStringResource(R.plurals.logout_pending_lost, waiting, waiting) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) },
    ) { Text(title) }
}
