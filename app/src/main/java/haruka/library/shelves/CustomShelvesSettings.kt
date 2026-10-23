package haruka.library.shelves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.chapter.dedup.DedupGraph
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.library.CustomShelf
import haruka.library.CustomShelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.UUID

private sealed interface ShelfDialog {
    /** Adding a shelf ([shelf] null) or editing one. */
    data class Edit(val shelf: CustomShelf?) : ShelfDialog

    data class Delete(val shelf: CustomShelf) : ShelfDialog
}

/** "Your shelves": the custom shelves in order, each editable, movable and deletable, and a row to add one. */
@Composable
internal fun CustomShelvesSection() {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.customShelves }
    val shelves by preference.collectAsState()
    var dialog by remember { mutableStateOf<ShelfDialog?>(null) }

    Column {
        HeadingItem(stringResource(HR.strings.pref_custom_shelves))
        Text(
            text = stringResource(HR.strings.pref_custom_shelves_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
        )
        shelves.forEachIndexed { index, shelf ->
            CustomShelfRow(
                shelf = shelf,
                canMoveUp = index > 0,
                canMoveDown = index < shelves.lastIndex,
                onEdit = { dialog = ShelfDialog.Edit(shelf) },
                onMove = { by -> preference.set(CustomShelves.move(shelves, shelf.id, by)) },
                onDelete = { dialog = ShelfDialog.Delete(shelf) },
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(HR.strings.custom_shelf_add)) },
            leadingContent = { Icon(MaterialSymbols.Rounded.Add, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { dialog = ShelfDialog.Edit(null) },
        )
    }

    when (val current = dialog) {
        is ShelfDialog.Edit -> CustomShelfDialog(
            initial = current.shelf,
            onConfirm = { name, query ->
                val id = current.shelf?.id ?: UUID.randomUUID().toString()
                preference.set(CustomShelves.save(shelves, CustomShelf(id, name, query)))
                dialog = null
            },
            onDismissRequest = { dialog = null },
        )
        is ShelfDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(stringResource(HR.strings.custom_shelf_delete, current.shelf.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        preference.set(CustomShelves.delete(shelves, current.shelf.id))
                        dialog = null
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun CustomShelfRow(
    shelf: CustomShelf,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(shelf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(shelf.query, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(
                        MaterialSymbols.Rounded.ArrowUpward,
                        contentDescription = stringResource(HR.strings.custom_shelf_move_up),
                    )
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(
                        MaterialSymbols.Rounded.ArrowDownward,
                        contentDescription = stringResource(HR.strings.custom_shelf_move_down),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(MaterialSymbols.Rounded.Delete, contentDescription = stringResource(MR.strings.action_delete))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

/** Name and query of a shelf, with how many series the query matches as it is typed, or why it can't be used. */
@Composable
private fun CustomShelfDialog(
    initial: CustomShelf?,
    onConfirm: (name: String, query: String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf(initial?.query.orEmpty()) }
    val parsed = remember(query) { ShelfQueries.parse(query) }
    val library by rememberQueryLibrary()
    val count by produceState<Int?>(null, library, parsed) {
        value = library?.let { withContext(Dispatchers.Default) { countMatches(it, parsed) } }
    }
    // An empty field is not an error yet; it only keeps Save off
    val error = (parsed as? ShelfQuery.Invalid)?.error?.takeIf { query.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(if (initial == null) HR.strings.custom_shelf_new else HR.strings.custom_shelf_edit))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(HR.strings.custom_shelf_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(HR.strings.custom_shelf_query)) },
                    isError = error != null,
                    supportingText = {
                        Text(
                            when {
                                error != null -> error.message()
                                query.isBlank() -> stringResource(HR.strings.custom_shelf_query_example)
                                else -> count?.let { pluralStringResource(HR.plurals.custom_shelf_matches, it, it) }
                                    .orEmpty()
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, query) },
                enabled = name.isNotBlank() && parsed is ShelfQuery.Valid,
            ) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun QueryError.message(): String = when (this) {
    QueryError.Blank -> stringResource(HR.strings.custom_shelf_query_blank)
    QueryError.Parentheses -> stringResource(HR.strings.custom_shelf_query_parentheses)
    is QueryError.NotANumber -> stringResource(HR.strings.custom_shelf_query_not_a_number, term)
    is QueryError.NotADate -> stringResource(HR.strings.custom_shelf_query_not_a_date, term)
}

/**
 * The whole library as the query evaluator reads it, loaded once while the dialog is open. Counts go through the
 * same chapter counting as the library screen, so `unread` and `total` match what the shelf will hold.
 */
@Composable
private fun rememberQueryLibrary(): State<List<LibraryItem>?> {
    val context = LocalContext.current
    return produceState<List<LibraryItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            val graph = context.shelvesGraph
            val counts = (context.appGraph as DedupGraph).libraryChapterCounts
            val library = counts.apply(flowOf(graph.getLibraryManga.await())).first()
            val sources = HashMap<Long, Source>()
            library.map { manga ->
                val id = manga.manga.source
                manga.toQueryItem(sources.getOrPut(id) { graph.sourceManager.getOrStub(id) })
            }
        }
    }
}
