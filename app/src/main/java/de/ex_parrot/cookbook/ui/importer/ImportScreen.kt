@file:OptIn(ExperimentalMaterial3Api::class)

package de.ex_parrot.cookbook.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.HttpException
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.ui.components.ErrorDetails
import de.ex_parrot.cookbook.ui.components.errorMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Finds the first http(s) link in shared text such as "Great recipe! https://…". */
fun extractUrl(text: String): String? =
    Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

/** The link in [text], or [text] itself with "https://" added if it has no scheme. */
fun normalizeWebUrl(text: String): String {
    extractUrl(text)?.let { return it }
    val trimmed = text.trim()
    return if (trimmed.isEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Done(val recipe: Recipe) : ImportState
    data class Failed(val error: Throwable) : ImportState
}

class ImportViewModel(private val repository: CookbookRepository, initialUrl: String) : ViewModel() {
    var url by mutableStateOf(initialUrl)
    var state by mutableStateOf<ImportState>(ImportState.Idle)
        private set

    init {
        if (initialUrl.isNotBlank()) import()
    }

    fun import() {
        val target = normalizeWebUrl(url)
        if (target.isBlank()) return
        viewModelScope.launch {
            state = ImportState.Importing
            state = try {
                ImportState.Done(repository.importRecipe(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ImportState.Failed(e)
            }
        }
    }
}

@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onImported: (id: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel.state) {
        (viewModel.state as? ImportState.Done)?.let { onImported(it.recipe.id, it.recipe.name) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.import_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val busy = viewModel.state == ImportState.Importing
            OutlinedTextField(
                value = viewModel.url,
                onValueChange = { viewModel.url = it },
                label = { Text(stringResource(R.string.import_url)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.import() }),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { viewModel.url = extractUrl(it) ?: it } }) {
                        Icon(Icons.Outlined.ContentPaste, stringResource(R.string.paste))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_running), textAlign = TextAlign.Center)
            } else {
                Button(onClick = viewModel::import, enabled = viewModel.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_button))
                }
            }
            (viewModel.state as? ImportState.Failed)?.let { failed ->
                val error = failed.error
                Text(
                    if (error is HttpException && error.code == 409) {
                        stringResource(R.string.import_failed_exists)
                    } else if (error is HttpException && error.code in 400..499 && error.code != 401) {
                        stringResource(R.string.import_failed_page)
                    } else {
                        errorMessage(error)
                    },
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                ErrorDetails(error)
            }
        }
    }
}
