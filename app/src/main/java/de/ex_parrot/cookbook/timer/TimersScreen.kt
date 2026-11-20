@file:OptIn(ExperimentalMaterial3Api::class)

package de.ex_parrot.cookbook.timer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.ui.components.EmptyView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TimersScreen(onOpenRecipe: (id: String, name: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TimerStore(context.applicationContext) }
    val timers by store.timers.collectAsStateWithLifecycle(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (timers.isEmpty()) {
            EmptyView(stringResource(R.string.timers_empty), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(timers, key = { it.id }) { timer ->
                    val remaining = (timer.endsAt - now).coerceAtLeast(0)
                    val progress = if (timer.durationMillis > 0) 1f - remaining.toFloat() / timer.durationMillis else 1f
                    ListItem(
                        headlineContent = { Text(timer.title) },
                        supportingContent = {
                            Column {
                                Text(
                                    if (remaining > 0) formatRemaining(remaining) else stringResource(R.string.timer_done_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { TimerController.cancel(context.applicationContext, timer.id) } }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.timer_cancel))
                            }
                        },
                        modifier = Modifier.clickable { onOpenRecipe(timer.recipeId, timer.title) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis + 999) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
