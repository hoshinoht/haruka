package haruka.chapter.dedup.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import haruka.chapter.dedup.DedupRule
import haruka.chapter.dedup.OfficialStatus
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One entry of an ordered list, with up/down buttons and an optional remove button. [startInset] aligns the text. */
@Composable
internal fun ReorderRow(
    title: String,
    supporting: String?,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    startInset: Dp,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = index > 0) {
            Icon(
                imageVector = MaterialSymbols.Rounded.ArrowUpward,
                contentDescription = stringResource(HR.strings.action_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = index < count - 1) {
            Icon(
                imageVector = MaterialSymbols.Rounded.ArrowDownward,
                contentDescription = stringResource(HR.strings.action_move_down),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Close,
                    contentDescription = stringResource(MR.strings.action_remove),
                )
            }
        }
    }
}

/** How the official catalog classifies [status], or null for an unofficial group. */
@Composable
internal fun officialLabel(status: OfficialStatus): String? = when (status) {
    OfficialStatus.OFFICIAL -> stringResource(HR.strings.dedup_official)
    OfficialStatus.UNCERTAIN -> stringResource(HR.strings.dedup_official_uncertain)
    OfficialStatus.NONE -> null
}

@Composable
internal fun ruleLabel(rule: DedupRule): String = stringResource(
    when (rule) {
        DedupRule.DOWNLOADED -> MR.strings.label_downloaded
        DedupRule.OFFICIAL -> HR.strings.dedup_rule_official
        DedupRule.OFFICIAL_UNCERTAIN -> HR.strings.dedup_rule_official_uncertain
        DedupRule.USER_RANK -> HR.strings.dedup_rule_user_rank
        DedupRule.NEWEST_UPLOAD -> HR.strings.dedup_rule_newest_upload
        DedupRule.SOURCE_ORDER -> HR.strings.dedup_rule_source_order
    },
)
