package haruka.updateerrors

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.BaseMangaListItem
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import haruka.i18n.HR
import haruka.selection.SelectionListActions
import haruka.title.rememberDisplayTitle
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Error
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.shouldExpandFAB

/** Entries that failed in the last library update, grouped by error, with bulk migration. */
class UpdateErrorsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<UpdateErrorsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lazyListState = rememberLazyListState()

        BackHandler(enabled = state.selectionMode) {
            viewModel.clearSelection()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val actions: @Composable () -> Unit = {
                    SelectionListActions(
                        listState = lazyListState,
                        itemCount = state.groups.size + state.ordered.size,
                        hasSelection = state.selectionMode,
                        onSelectAll = viewModel::selectAll,
                        onSelectNone = viewModel::clearSelection,
                    )
                }
                AppBar(
                    title = stringResource(HR.strings.label_update_errors),
                    navigateUp = { navigator.pop() },
                    actions = { actions() },
                    actionModeCounter = state.selection.size,
                    onCancelActionMode = viewModel::clearSelection,
                    actionModeActions = { actions() },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_migrate)) },
                    icon = {
                        Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null)
                    },
                    onClick = {
                        val selection = state.selection
                        viewModel.clearSelection()
                        navigator.push(MigrationConfigScreen(selection))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.selectionMode,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.groups.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> FastScrollLazyColumn(
                    state = lazyListState,
                    contentPadding = contentPadding,
                ) {
                    state.groups.forEach { group ->
                        item(key = "header-${group.message}", contentType = "header") {
                            ListGroupHeader(
                                text = stringResource(
                                    HR.strings.update_errors_group_header,
                                    group.message ?: stringResource(MR.strings.unknown_error),
                                    group.entries.size,
                                ),
                                modifier = Modifier.animateItem(),
                            )
                        }
                        items(group.entries, key = { it.manga.id }, contentType = { "entry" }) { entry ->
                            UpdateErrorItem(
                                entry = entry,
                                isSelected = entry.manga.id in state.selection,
                                // Like the library: tap opens until a long-press starts selecting.
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelection(entry.manga)
                                    } else {
                                        navigator.push(MangaScreen(entry.manga.id))
                                    }
                                },
                                onLongClick = { viewModel.selectRange(entry.manga) },
                                onClickCover = { navigator.push(MangaScreen(entry.manga.id)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "haruka.updateerrors.SHOW"

        /** Opens this screen from the library update error notification and dismisses that notification. */
        fun pendingActivity(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("notificationId", Notifications.ID_LIBRARY_ERROR)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

@Composable
private fun UpdateErrorItem(
    entry: UpdateErrorsViewModel.Entry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseMangaListItem(
        manga = entry.manga,
        modifier = modifier.selectedBackground(isSelected),
        onClickItem = onClick,
        onClickCover = onClickCover,
        onLongClickItem = onLongClick,
        content = {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = rememberDisplayTitle(entry.manga.title),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = entry.sourceName,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** More tab row, shown only while the last library update left failed entries. */
@Composable
fun UpdateErrorsMoreItem() {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<UpdateErrorsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val count = state.ordered.size
    if (count == 0) return
    TextPreferenceWidget(
        title = stringResource(HR.strings.label_update_errors),
        subtitle = pluralStringResource(HR.plurals.update_errors_entries, count, count),
        icon = MaterialSymbols.Rounded.Error,
        onPreferenceClick = { navigator.push(UpdateErrorsScreen()) },
    )
}
