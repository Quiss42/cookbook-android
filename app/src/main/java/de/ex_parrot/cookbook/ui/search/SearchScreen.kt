@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)

package de.ex_parrot.cookbook.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.CookbookRepository
import de.ex_parrot.cookbook.data.RecipeStub
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.ui.browse.RecipeList
import de.ex_parrot.cookbook.ui.components.EmptyView
import de.ex_parrot.cookbook.ui.components.ErrorView
import de.ex_parrot.cookbook.ui.components.Loadable
import de.ex_parrot.cookbook.ui.components.LoadingView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SearchViewModel(private val repository: CookbookRepository) : ViewModel() {
    val query = MutableStateFlow("")
    private val _results = MutableStateFlow<Loadable<List<RecipeStub>>?>(null)
    val results: StateFlow<Loadable<List<RecipeStub>>?> = _results

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collectLatest { search(it) }
        }
    }

    fun retry() {
        viewModelScope.launch { search(query.value) }
    }

    private suspend fun search(text: String) {
        val q = text.trim()
        if (q.length < 2) {
            _results.value = null
            return
        }
        _results.value = Loadable.Loading
        _results.value = try {
            // Server search covers names, keywords and categories; fall back to
            // matching names locally if the server search fails.
            Loadable.Ready(runCatching { repository.search(q) }.getOrElse {
                repository.allRecipes().filter { it.name.contains(q, ignoreCase = true) }
            })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Loadable.Failed(e)
        }
    }
}

@Composable
fun SearchScreen(
    session: Session,
    viewModel: SearchViewModel,
    onOpenRecipe: (RecipeStub) -> Unit,
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.query.value = "" }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.clear))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (val r = results) {
            null -> EmptyView(stringResource(R.string.search_prompt), modifier)
            Loadable.Loading -> LoadingView(modifier)
            is Loadable.Failed -> ErrorView(r.error, viewModel::retry, modifier)
            is Loadable.Ready -> if (r.value.isEmpty()) {
                EmptyView(stringResource(R.string.search_no_results), modifier)
            } else {
                androidx.compose.foundation.layout.Box(modifier) { RecipeList(r.value, session, onOpenRecipe) }
            }
        }
    }
}
