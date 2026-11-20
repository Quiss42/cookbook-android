@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.ex_parrot.cookbook.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.Recipe
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.ui.components.ErrorView
import de.ex_parrot.cookbook.ui.components.Loadable
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.components.RecipeImage
import java.time.Duration

@Composable
fun RecipeScreen(
    session: Session,
    title: String,
    viewModel: RecipeViewModel,
    keepScreenOn: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                RecipeContent(s.value, session, viewModel)
            }
        }
    }
}

@Composable
private fun RecipeContent(recipe: Recipe, session: Session, viewModel: RecipeViewModel) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "image") {
            RecipeImage(
                url = viewModel.imageUrl("full"),
                imageLoader = session.imageLoader,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            )
        }
        item(key = "header") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(recipe.name, style = MaterialTheme.typography.headlineSmall)
                if (recipe.description.isNotBlank()) {
                    Text(recipe.description, style = MaterialTheme.typography.bodyLarge)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    recipe.recipeYield?.takeIf { it > 0 }?.let { servings ->
                        InfoChip(Icons.Outlined.People, pluralStringResource(R.plurals.servings, servings, servings))
                    }
                    recipe.prepTime?.let { InfoChip(Icons.Outlined.Schedule, stringResource(R.string.prep_time, formatDuration(it))) }
                    recipe.cookTime?.let { InfoChip(Icons.Outlined.Schedule, stringResource(R.string.cook_time, formatDuration(it))) }
                    recipe.totalTime?.let { InfoChip(Icons.Outlined.Schedule, stringResource(R.string.total_time, formatDuration(it))) }
                    if (recipe.category.isNotBlank()) InfoChip(Icons.Outlined.Category, recipe.category)
                    recipe.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        InfoChip(Icons.Outlined.Sell, it)
                    }
                }
                if (recipe.url.startsWith("http")) {
                    TextButton(onClick = { runCatching { uriHandler.openUri(recipe.url) } }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.source), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        if (recipe.tools.isNotEmpty()) {
            section("tools") { SectionTitle(stringResource(R.string.tools)) }
            itemsIndexed(recipe.tools, key = { i, _ -> "tool$i" }) { _, tool ->
                Text("•  $tool", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        if (recipe.ingredients.isNotEmpty()) {
            section("ingredients") { SectionTitle(stringResource(R.string.ingredients)) }
            itemsIndexed(recipe.ingredients, key = { i, _ -> "ing$i" }) { index, ingredient ->
                val checked = index in viewModel.checkedIngredients
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleIngredient(index) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { viewModel.toggleIngredient(index) })
                    Text(
                        ingredient,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
        }

        if (recipe.instructions.isNotEmpty()) {
            section("instructions") { SectionTitle(stringResource(R.string.instructions)) }
            itemsIndexed(recipe.instructions, key = { i, _ -> "step$i" }) { index, step ->
                val done = index in viewModel.doneSteps
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleStep(index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(
                                if (done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(
                        step,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (recipe.nutrition.isNotEmpty()) {
            section("nutrition") { SectionTitle(stringResource(R.string.nutrition)) }
            itemsIndexed(recipe.nutrition, key = { i, _ -> "nut$i" }) { _, (key, value) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(nutritionLabel(key), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value)
                }
            }
        }
    }
}

private fun LazyListScope.section(key: String, content: @Composable () -> Unit) {
    item(key = key) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
    )
}

@Composable
fun formatDuration(duration: Duration): String {
    val hours = duration.toHours().toInt()
    val minutes = (duration.toMinutes() % 60).toInt()
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        hours > 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_minutes, minutes)
    }
}

@Composable
private fun nutritionLabel(key: String): String = when (key) {
    "calories" -> stringResource(R.string.nutrition_calories)
    "carbohydrateContent" -> stringResource(R.string.nutrition_carbohydrates)
    "cholesterolContent" -> stringResource(R.string.nutrition_cholesterol)
    "fatContent" -> stringResource(R.string.nutrition_fat)
    "fiberContent" -> stringResource(R.string.nutrition_fiber)
    "proteinContent" -> stringResource(R.string.nutrition_protein)
    "saturatedFatContent" -> stringResource(R.string.nutrition_saturated_fat)
    "servingSize" -> stringResource(R.string.nutrition_serving_size)
    "sodiumContent" -> stringResource(R.string.nutrition_sodium)
    "sugarContent" -> stringResource(R.string.nutrition_sugar)
    "transFatContent" -> stringResource(R.string.nutrition_trans_fat)
    "unsaturatedFatContent" -> stringResource(R.string.nutrition_unsaturated_fat)
    else -> key
}
