package haruka.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import haruka.i18n.HR
import haruka.motion.FilterChip
import mihon.feature.migration.list.MigrationListItemResult
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** A bulk-migration row's target column with the match confidence under it once it is scored. */
@Composable
fun MigrationResultWithConfidence(
    modifier: Modifier,
    mangaId: Long,
    result: SearchResult,
    onItemClick: (Manga) -> Unit,
) {
    Column(modifier) {
        MigrationListItemResult(modifier = Modifier.fillMaxWidth(), result = result, onItemClick = onItemClick)
        if (result is SearchResult.Success) {
            val viewModel = metroViewModel<MigrationReviewViewModel>()
            val confidence by remember(viewModel, mangaId) { viewModel.confidenceOf(mangaId) }
                .collectAsStateWithLifecycle(null)
            confidence?.let {
                MatchConfidenceLabel(
                    confidence = it,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Coloured High / Check / Low chip with the leading reason on one line under it. */
@Composable
fun MatchConfidenceLabel(confidence: MatchConfidence, modifier: Modifier = Modifier) {
    val label = levelLabel(confidence.level)
    val reason = confidence.reasons.firstOrNull()?.let { reasonText(it) }
    val description = stringResource(HR.strings.migration_confidence_description, label, reason.orEmpty())
    val color = when (confidence.level) {
        MatchConfidence.Level.HIGH -> MaterialTheme.colorScheme.tertiary
        MatchConfidence.Level.CHECK -> MaterialTheme.colorScheme.secondary
        MatchConfidence.Level.LOW -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .background(color.copy(alpha = 0.16f), MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
    }
}

@Composable
private fun levelLabel(level: MatchConfidence.Level) = stringResource(
    when (level) {
        MatchConfidence.Level.HIGH -> HR.strings.migration_confidence_high
        MatchConfidence.Level.CHECK -> HR.strings.migration_confidence_check
        MatchConfidence.Level.LOW -> HR.strings.migration_confidence_low
    },
)

@Composable
private fun reasonText(reason: MatchReason): String = when (reason) {
    is MatchReason.ProgressShort -> stringResource(
        HR.strings.migration_reason_progress,
        formatChapterNumber(reason.read),
        formatChapterNumber(reason.available),
    )
    is MatchReason.ProgressCovered -> stringResource(
        HR.strings.migration_reason_progress,
        formatChapterNumber(reason.read),
        formatChapterNumber(reason.available),
    )
    is MatchReason.TitleDiffers -> stringResource(HR.strings.migration_reason_title_differs)
    MatchReason.TitleMatches -> stringResource(HR.strings.migration_reason_title_matches)
    MatchReason.AuthorMismatch -> stringResource(HR.strings.migration_reason_author_differs)
    MatchReason.AuthorMatches -> stringResource(HR.strings.migration_reason_author_matches)
}

/**
 * Stands between Migrate all / Copy all and the existing confirmation dialog. Returns the handler for the bulk
 * actions; when an entry is flagged Check or Low and not accepted, it shows the review sheet first, otherwise it
 * calls [openMigrationDialog] straight away.
 */
@Composable
fun migrationReviewGate(
    items: List<MigratingManga>,
    onSearchManually: (MigratingManga) -> Unit,
    onSkip: (Long) -> Unit,
    openMigrationDialog: (Boolean) -> Unit,
): (Boolean) -> Unit {
    val viewModel = metroViewModel<MigrationReviewViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openDialog by rememberUpdatedState(openMigrationDialog)

    LaunchedEffect(viewModel, items) { viewModel.track(items) }
    LaunchedEffect(viewModel, state) { viewModel.resolve()?.let(openDialog) }

    if (state.review?.stage == MigrationReviewViewModel.Stage.Reviewing) {
        MigrationReviewSheet(
            state = state,
            onToggleAccepted = viewModel::toggleAccepted,
            onSearchManually = onSearchManually,
            onSkip = onSkip,
            onAcceptAll = { viewModel.acceptAll()?.let(openDialog) },
            onContinueWithAccepted = { viewModel.continueWithAccepted().forEach(onSkip) },
            onDismissRequest = viewModel::dismissReview,
        )
    }

    return remember(viewModel) {
        { copy -> if (viewModel.requestBulkAction(copy)) openDialog(copy) }
    }
}

@Composable
private fun MigrationReviewSheet(
    state: MigrationReviewViewModel.State,
    onToggleAccepted: (Long) -> Unit,
    onSearchManually: (MigratingManga) -> Unit,
    onSkip: (Long) -> Unit,
    onAcceptAll: () -> Unit,
    onContinueWithAccepted: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = stringResource(HR.strings.migration_review_title),
                    style = MaterialTheme.typography.header,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )
                Text(
                    text = stringResource(HR.strings.migration_review_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
                        .secondaryItemAlpha(),
                )
                state.flagged.forEachIndexed { index, entry ->
                    if (index >
                        0
                    ) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
                    }
                    ReviewEntry(
                        entry = entry,
                        accepted = entry.mangaId in state.accepted,
                        onToggleAccepted = { onToggleAccepted(entry.mangaId) },
                        onSearchManually = { onSearchManually(entry.item) },
                        onSkip = { onSkip(entry.mangaId) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onContinueWithAccepted,
                    enabled = !state.busy && state.acceptedOrUnflagged > 0,
                ) {
                    Text(text = stringResource(HR.strings.migration_review_continue))
                }
                Button(onClick = onAcceptAll, enabled = !state.busy) {
                    Text(text = stringResource(HR.strings.migration_review_accept_all))
                }
            }
        }
    }
}

@Composable
private fun ReviewEntry(
    entry: MigrationReviewViewModel.Entry,
    accepted: Boolean,
    onToggleAccepted: () -> Unit,
    onSearchManually: () -> Unit,
    onSkip: () -> Unit,
) {
    val result = entry.result as? SearchResult.Success ?: return
    val confidence = entry.confidence ?: return
    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReviewManga(entry.item.manga, entry.item.source, Modifier.weight(1f))
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.extraSmall)
                    .size(20.dp),
            )
            ReviewManga(result.manga, result.source, Modifier.weight(1f))
        }
        MatchConfidenceLabel(confidence = confidence)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSearchManually) {
                Text(text = stringResource(MR.strings.migrationListScreen_searchManuallyActionLabel))
            }
            TextButton(onClick = onSkip) {
                Text(text = stringResource(MR.strings.migrationListScreen_skipActionLabel))
            }
            FilterChip(
                selected = accepted,
                onClick = onToggleAccepted,
                label = {
                    Text(
                        text = stringResource(
                            if (accepted) HR.strings.migration_review_accepted else HR.strings.migration_review_accept,
                        ),
                    )
                },
                leadingIcon = if (accepted) {
                    { Icon(MaterialSymbols.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ReviewManga(manga: Manga, source: String, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(data = manga, modifier = Modifier.width(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
    }
}
