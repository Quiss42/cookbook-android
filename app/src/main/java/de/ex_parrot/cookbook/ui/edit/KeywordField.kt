@file:OptIn(ExperimentalLayoutApi::class)

package de.ex_parrot.cookbook.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.ex_parrot.cookbook.R

private const val COLLAPSED_SUGGESTIONS = 15

/**
 * Keywords as chips: the recipe's keywords are checked, existing keywords from
 * other recipes can be checked with a tap, and new ones typed in.
 */
@Composable
fun KeywordField(vm: EditRecipeViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val filter = input.trim()

    fun addInput() {
        vm.addKeywords(input)
        input = ""
    }

    // Checked keywords first (always visible), then the other existing ones.
    val others = vm.keywordSuggestions.filter { suggestion ->
        vm.keywords.none { it.equals(suggestion, ignoreCase = true) } &&
            (filter.isEmpty() || suggestion.contains(filter, ignoreCase = true))
    }
    val visibleOthers = if (showAll || filter.isNotEmpty()) others else others.take(COLLAPSED_SUGGESTIONS)
    val canAdd = filter.isNotEmpty() && vm.keywords.none { it.equals(filter, ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.field_keywords), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = input,
            onValueChange = { text ->
                // Typing a comma adds what was typed before it.
                if (text.endsWith(",")) {
                    vm.addKeywords(text)
                    input = ""
                } else {
                    input = text
                }
            },
            label = { Text(stringResource(R.string.keywords_search_or_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canAdd) addInput() }),
            trailingIcon = {
                IconButton(onClick = ::addInput, enabled = canAdd) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.keywords_add))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.keywords.forEach { keyword ->
                FilterChip(
                    selected = true,
                    onClick = { vm.toggleKeyword(keyword) },
                    label = { Text(keyword) },
                    leadingIcon = { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) },
                )
            }
            visibleOthers.forEach { keyword ->
                FilterChip(
                    selected = false,
                    onClick = { vm.toggleKeyword(keyword); input = "" },
                    label = { Text(keyword) },
                )
            }
        }
        if (!showAll && filter.isEmpty() && others.size > COLLAPSED_SUGGESTIONS) {
            val hidden = others.size - COLLAPSED_SUGGESTIONS
            TextButton(onClick = { showAll = true }) {
                Text(pluralStringResource(R.plurals.keywords_show_more, hidden, hidden))
            }
        }
    }
}
