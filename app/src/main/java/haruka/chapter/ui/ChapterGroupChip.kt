package haruka.chapter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** "N versions · N parts" on a group's head row; tapping it shows or hides the group's other rows. */
@Composable
fun ChapterGroupChip(row: ChapterGroupRow?, modifier: Modifier = Modifier) {
    if (row == null || !row.head) return
    val versions = if (row.versions > 1) {
        pluralStringResource(HR.plurals.chapter_group_versions, row.versions, row.versions)
    } else {
        null
    }
    val parts = if (row.parts > 1) pluralStringResource(HR.plurals.chapter_group_parts, row.parts, row.parts) else null
    val toggleLabel = stringResource(
        if (row.expanded) HR.strings.chapter_group_collapse else HR.strings.chapter_group_expand,
    )
    Row(
        modifier = modifier
            .padding(start = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClickLabel = toggleLabel) { row.expansion.toggle(row.key) }
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(versions, parts).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = if (row.expanded) {
                MaterialSymbols.Rounded.KeyboardArrowUp
            } else {
                MaterialSymbols.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Indents the rows an expanded group reveals under its head. */
fun chapterGroupIndent(row: ChapterGroupRow?): Modifier =
    if (row != null && !row.head) Modifier.padding(start = ChildIndent) else Modifier

private val ChildIndent = 24.dp
