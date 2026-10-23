package com.mattdixon.jobjar.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.ChecklistItem
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors

/**
 * A job's checklist on its page. With no items it's just a small "Add checklist" link; once
 * there are items it's a card that starts collapsed to its header ("Checklist · 2 of 5") and
 * opens to the full list of tick boxes when tapped. Every change goes to [onChange] as an edit to apply to the saved
 * list, so quick changes in a row can't overwrite each other. [onDelete] removes the whole list
 * (the caller offers Undo).
 */
@Composable
fun ChecklistSection(
    items: List<ChecklistItem>,
    onChange: ((List<ChecklistItem>) -> List<ChecklistItem>) -> Unit,
    onDelete: () -> Unit
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    if (items.isEmpty() && !adding) {
        TextButton(onClick = { adding = true; expanded = true }, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.checklist_add))
        }
        return
    }

    Card(colors = appCardColors(), border = appCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.sm)) {
            // The whole header opens and closes the list.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    expanded = !expanded
                    if (!expanded) {
                        adding = false
                        editingIndex = null
                    }
                }
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.cd_collapse_checklist else R.string.cd_expand_checklist),
                    modifier = Modifier.padding(start = Spacing.sm)
                )
                Text(
                    if (items.isEmpty()) {
                        stringResource(R.string.checklist_title)
                    } else {
                        stringResource(R.string.checklist_progress, items.count { it.checked }, items.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.sm)
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_checklist_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (items.any { it.checked }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.checklist_untick_all)) },
                                onClick = {
                                    menuOpen = false
                                    onChange { list -> list.map { it.copy(checked = false) } }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checklist_delete)) },
                            onClick = {
                                menuOpen = false
                                adding = false
                                editingIndex = null
                                if (items.isNotEmpty()) onDelete()
                            }
                        )
                    }
                }
            }

            if (expanded) {
                items.forEachIndexed { index, item ->
                    if (editingIndex == index) {
                        ItemEditor(
                            initialText = item.text,
                            onCommit = { text ->
                                editingIndex = null
                                val trimmed = text.trim()
                                onChange { list ->
                                    if (trimmed.isEmpty()) list.filterIndexed { i, _ -> i != index }
                                    else list.mapIndexed { i, it -> if (i == index) it.copy(text = trimmed) else it }
                                }
                            },
                            onRemove = {
                                editingIndex = null
                                onChange { list -> list.filterIndexed { i, _ -> i != index } }
                            }
                        )
                    } else {
                        ItemRow(
                            item = item,
                            onCheckedChange = { checked ->
                                onChange { list -> list.mapIndexed { i, it -> if (i == index) it.copy(checked = checked) else it } }
                            },
                            onEdit = {
                                adding = false
                                editingIndex = index
                            }
                        )
                    }
                }

                if (adding) {
                    NewItemField(
                        onAdd = { text -> onChange { list -> list + ChecklistItem(text) } },
                        onFinished = { adding = false }
                    )
                } else {
                    TextButton(
                        onClick = {
                            editingIndex = null
                            adding = true
                        },
                        contentPadding = PaddingValues(horizontal = Spacing.md)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.checklist_add_item))
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ChecklistItem, onCheckedChange: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.checked, onCheckedChange = onCheckedChange)
        Text(
            item.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = Spacing.sm, horizontal = Spacing.xs)
        )
    }
}

/**
 * Editing an existing line. Done or tapping away saves it; clearing it removes it. Only the first
 * of those counts: the editor also loses focus as it closes, and that mustn't save again.
 */
@Composable
private fun ItemEditor(initialText: String, onCommit: (String) -> Unit, onRemove: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun commit() {
        if (!finished) {
            finished = true
            onCommit(text)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        trailingIcon = {
            IconButton(onClick = {
                if (!finished) {
                    finished = true
                    onRemove()
                }
            }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_item))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) commit()
            }
    )
}

/**
 * The "add an item" line. Done adds the item and stays open for the next one; Done on an empty
 * line, or tapping away, closes it.
 */
@Composable
private fun NewItemField(onAdd: (String) -> Unit, onFinished: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.checklist_add_item)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (text.isBlank()) {
                finished = true
                onFinished()
            } else {
                onAdd(text.trim())
                text = ""
            }
        }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                } else if (hadFocus && !finished) {
                    finished = true
                    if (text.isNotBlank()) onAdd(text.trim())
                    text = ""
                    onFinished()
                }
            }
    )
}
