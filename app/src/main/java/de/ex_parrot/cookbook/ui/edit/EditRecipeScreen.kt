@file:OptIn(ExperimentalMaterial3Api::class)

package de.ex_parrot.cookbook.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.ui.components.ErrorDetails
import de.ex_parrot.cookbook.ui.components.ErrorView
import de.ex_parrot.cookbook.ui.components.Loadable
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.components.errorMessage

@Composable
fun EditRecipeScreen(
    imageLoader: coil3.ImageLoader,
    viewModel: EditRecipeViewModel,
    onSaved: (id: String, name: String) -> Unit,
    onClose: () -> Unit,
) {
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val tryClose = { if (viewModel.hasChanges) confirmDiscard = true else onClose() }

    BackHandler(onBack = tryClose)

    LaunchedEffect(viewModel.saveState) {
        (viewModel.saveState as? SaveState.Saved)?.let { onSaved(it.id, viewModel.name.trim()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (viewModel.isNew) R.string.edit_new_title else R.string.edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = tryClose) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                },
                actions = {
                    if (viewModel.saveState == SaveState.Saving) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                    } else {
                        TextButton(onClick = viewModel::save, enabled = viewModel.canSave && loadState is Loadable.Ready) {
                            Text(stringResource(R.string.save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = loadState) {
            Loadable.Loading -> LoadingView(Modifier.padding(padding))
            is Loadable.Failed -> ErrorView(s.error, { viewModel.load() }, Modifier.padding(padding))
            is Loadable.Ready -> EditForm(viewModel, imageLoader, Modifier.padding(padding))
        }
    }

    (viewModel.saveState as? SaveState.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.edit_save_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(errorMessage(failed.error))
                    ErrorDetails(failed.error)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.ok)) } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) }
            },
        )
    }
}

@Composable
private fun EditForm(vm: EditRecipeViewModel, imageLoader: coil3.ImageLoader, modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(stringResource(R.string.edit_section_image))
        ImageSection(vm, imageLoader)

        SectionTitle(stringResource(R.string.edit_section_general))
        OutlinedTextField(
            value = vm.name,
            onValueChange = { vm.name = it },
            label = { Text(stringResource(R.string.field_name)) },
            isError = vm.name.isBlank(),
            supportingText = if (vm.name.isBlank()) {
                { Text(stringResource(R.string.field_required)) }
            } else null,
            singleLine = true,
            keyboardOptions = sentences,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.description,
            onValueChange = { vm.description = it },
            label = { Text(stringResource(R.string.field_description)) },
            minLines = 2,
            keyboardOptions = sentences,
            modifier = Modifier.fillMaxWidth(),
        )
        CategoryField(vm)
        KeywordField(vm)
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            label = { Text(stringResource(R.string.field_source)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(R.string.edit_section_times))
        OutlinedTextField(
            value = vm.servings,
            onValueChange = { v -> vm.servings = v.filter { it.isDigit() }.take(3) },
            label = { Text(stringResource(R.string.field_servings)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        DurationRow(stringResource(R.string.field_prep_time), vm.prepTime)
        DurationRow(stringResource(R.string.field_cook_time), vm.cookTime)
        DurationRow(stringResource(R.string.field_total_time), vm.totalTime)

        SectionTitle(stringResource(R.string.tools))
        ListEditor(vm.tools, stringResource(R.string.edit_add_tool), multiLine = false)
        SectionTitle(stringResource(R.string.ingredients))
        ListEditor(vm.ingredients, stringResource(R.string.edit_add_ingredient), multiLine = false)
        SectionTitle(stringResource(R.string.instructions))
        ListEditor(vm.instructions, stringResource(R.string.edit_add_step), multiLine = true, numbered = true)
        Spacer(Modifier.size(32.dp))
    }
}

private val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CategoryField(vm: EditRecipeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val matches = vm.categorySuggestions.filter {
        it.contains(vm.category.trim(), ignoreCase = true) && !it.equals(vm.category.trim(), ignoreCase = true)
    }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = vm.category,
            onValueChange = { vm.category = it; expanded = true },
            label = { Text(stringResource(R.string.field_category)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && matches.isNotEmpty()) },
            keyboardOptions = sentences,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.take(8).forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { vm.category = name; expanded = false })
            }
        }
    }
}

@Composable
private fun DurationRow(label: String, field: DurationField) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedTextField(
            value = field.hours,
            onValueChange = { v -> field.hours = v.filter { it.isDigit() }.take(3) },
            label = { Text(stringResource(R.string.field_hours)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
        OutlinedTextField(
            value = field.minutes,
            onValueChange = { v -> field.minutes = v.filter { it.isDigit() }.take(4) },
            label = { Text(stringResource(R.string.field_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
    }
}

@Composable
private fun ListEditor(items: SnapshotStateList<String>, addLabel: String, multiLine: Boolean, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { items[index] = it },
                    label = if (numbered) {
                        { Text("${index + 1}") }
                    } else null,
                    singleLine = !multiLine,
                    minLines = if (multiLine) 2 else 1,
                    keyboardOptions = sentences,
                    modifier = Modifier.weight(1f),
                )
                Column {
                    IconButton(
                        onClick = { items.add(index - 1, items.removeAt(index)) },
                        enabled = index > 0,
                        modifier = Modifier.size(36.dp),
                    ) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up), Modifier.size(18.dp)) }
                    IconButton(
                        onClick = { items.add(index + 1, items.removeAt(index)) },
                        enabled = index < items.lastIndex,
                        modifier = Modifier.size(36.dp),
                    ) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down), Modifier.size(18.dp)) }
                }
                IconButton(onClick = { items.removeAt(index) }) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.remove))
                }
            }
        }
        TextButton(onClick = { items.add("") }) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Text(addLabel, Modifier.padding(start = 8.dp))
        }
    }
}
