package haruka.chapter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Compact "copies + count" pill on a group's head row; tapping it shows or hides the group's other rows.
 *
 * It sits on most rows of multi-group series, so it stays low-key while collapsed (tonal, count only) and fills in
 * with a flipped chevron while expanded. The visible pill is 32dp tall to keep row height unchanged; Compose expands
 * its touch target to the 48dp minimum, as it does for the download indicator beside it.
 */
@Composable
fun ChapterGroupChip(row: ChapterGroupRow?, modifier: Modifier = Modifier) {
    if (row == null || !row.head) return
    val hasVersions = row.versions > 1
    val parts = if (row.parts > 1) pluralStringResource(HR.plurals.chapter_group_parts, row.parts, row.parts) else null
    val description = listOfNotNull(
        if (hasVersions) pluralStringResource(HR.plurals.chapter_group_versions, row.versions, row.versions) else null,
        parts,
    ).joinToString(", ")
    val toggleLabel = stringResource(
        if (row.expanded) HR.strings.chapter_group_collapse else HR.strings.chapter_group_expand,
    )
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (row.expanded) colors.secondaryContainer else colors.surfaceContainerHighest,
        label = "chapterGroupChipContainer",
    )
    val content by animateColorAsState(
        if (row.expanded) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "chapterGroupChipContent",
    )
    val chevronRotation by animateFloatAsState(if (row.expanded) 180f else 0f, label = "chapterGroupChipChevron")

    Row(
        modifier = modifier
            .padding(start = 4.dp)
            .height(32.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClickLabel = toggleLabel, role = Role.Button) { row.expansion.toggle(row.key) }
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasVersions) {
            Icon(
                imageVector = MaterialSymbols.Rounded.ContentCopy,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = listOfNotNull(if (hasVersions) row.versions.toString() else null, parts).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Icon(
            imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = content,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .rotate(chevronRotation),
        )
    }
}

/**
 * Marks an expanded group as one region: its head and revealed rows share a tinted background, and the revealed
 * rows are indented behind a primary-coloured rail so they read as copies of the head, not separate chapters.
 */
@Composable
fun chapterGroupIndent(row: ChapterGroupRow?): Modifier {
    if (row == null || (row.head && !row.expanded)) return Modifier
    val region = MaterialTheme.colorScheme.surfaceContainerHigh
    if (row.head) return Modifier.background(region)
    val rail = MaterialTheme.colorScheme.primary
    return Modifier
        .background(region)
        .drawBehind {
            val x = RailInset.toPx()
            drawLine(
                color = rail,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = RailWidth.toPx(),
                cap = StrokeCap.Round,
            )
        }
        .padding(start = ChildIndent)
}

private val ChildIndent = 24.dp
private val RailInset = 20.dp
private val RailWidth = 3.dp
