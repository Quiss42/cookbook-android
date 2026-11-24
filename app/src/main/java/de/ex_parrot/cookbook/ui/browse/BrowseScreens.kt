@file:OptIn(ExperimentalMaterial3Api::class)

package de.ex_parrot.cookbook.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.RecipeStub
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.ui.components.EmptyView
import de.ex_parrot.cookbook.ui.components.ErrorView
import de.ex_parrot.cookbook.ui.components.Loadable
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.components.RecipeImage

@Composable
fun categoryTitle(name: String): String = when (name) {
    ALL_RECIPES -> stringResource(R.string.category_all)
    de.ex_parrot.cookbook.data.Category.UNCATEGORIZED_LIST_NAME -> stringResource(R.string.category_uncategorized)
    else -> name
}

@Composable
fun CategoriesScreen(
    session: Session,
    viewModel: CategoriesViewModel,
    onOpenCategory: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNewRecipe: () -> Unit,
    onImport: () -> Unit,
    onTimers: () -> Unit,
    columns: Int,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val covers by viewModel.coverIds.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, stringResource(R.string.search))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_title)) },
                                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                onClick = { menuOpen = false; onImport() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timers)) },
                                leadingIcon = { Icon(Icons.Outlined.Timer, null) },
                                onClick = { menuOpen = false; onTimers() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewRecipe) {
                Icon(Icons.Outlined.Add, stringResource(R.string.edit_new_title))
            }
        },
    ) { padding ->
        when (val s = state) {
            Loadable.Loading -> LoadingView(Modifier.padding(padding))
            is Loadable.Failed -> ErrorView(s.error, { viewModel.load() }, Modifier.padding(padding))
            is Loadable.Ready -> PullToRefreshBox(
                isRefreshing = s.refreshing,
                onRefresh = { viewModel.load(); session.requestSync(force = true) },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                val entries = buildList {
                    add(ALL_RECIPES to s.value.totalCount)
                    s.value.categories.forEach { add(it.name to it.recipeCount) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns.coerceIn(1, 3)),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.first }) { (name, count) ->
                        CategoryCard(
                            columns = columns,
                            title = categoryTitle(name),
                            count = count,
                            imageUrl = covers[name]?.let { session.repository.imageUrl(it.id) },
                            session = session,
                            onClick = { onOpenCategory(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(columns: Int, title: String, count: Int, imageUrl: String?, session: Session, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        RecipeImage(
            url = imageUrl,
            imageLoader = session.imageLoader,
            // Wide pictures for one column, 4:3 for two or three.
            modifier = Modifier.fillMaxWidth().aspectRatio(if (columns == 1) 16f / 9f else 4f / 3f),
        )
        Column(
            Modifier.padding(
                horizontal = if (columns == 3) 8.dp else 12.dp,
                vertical = if (columns == 1) 10.dp else 5.dp,
            ),
        ) {
            Text(
                title,
                style = if (columns == 3) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.recipe_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RecipeListScreen(
    session: Session,
    category: String,
    viewModel: RecipeListViewModel,
    onOpenRecipe: (RecipeStub) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryTitle(category), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            Loadable.Loading -> LoadingView(Modifier.padding(padding))
            is Loadable.Failed -> ErrorView(s.error, { viewModel.load() }, Modifier.padding(padding))
            is Loadable.Ready -> PullToRefreshBox(
                isRefreshing = s.refreshing,
                onRefresh = { viewModel.load() },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                if (s.value.isEmpty()) {
                    EmptyView(stringResource(R.string.no_recipes))
                } else {
                    RecipeList(s.value, session, onOpenRecipe)
                }
            }
        }
    }
}

@Composable
fun RecipeList(recipes: List<RecipeStub>, session: Session, onOpenRecipe: (RecipeStub) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(recipes, key = { it.id }) { recipe ->
            ListItem(
                headlineContent = { Text(recipe.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = recipe.keywords.takeIf { it.isNotBlank() }?.let {
                    { Text(it.replace(",", ", "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                leadingContent = {
                    RecipeImage(
                        url = session.repository.imageUrl(recipe.id),
                        imageLoader = session.imageLoader,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    )
                },
                modifier = Modifier.clickable { onOpenRecipe(recipe) },
            )
            HorizontalDivider(Modifier.padding(start = 88.dp))
        }
    }
}
