package haruka.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import eu.kanade.presentation.components.AppStateBanners
import haruka.progress.BackgroundTaskProgress.Kind
import haruka.progress.BackgroundTaskProgress.Task
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val TaskProgressBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.primaryContainer

/**
 * Mihon's [AppStateBanners] followed by background-task progress banners.
 *
 * Whichever banner ends up at the top draws behind the status bar, matching upstream's stacking.
 */
@Composable
fun HarukaAppStateBanners(
    downloadedOnlyMode: Boolean,
    incognitoMode: Boolean,
    indexing: Boolean,
    tasks: List<Task>,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    // Keep the last tasks on screen while the banner animates out.
    var shownTasks by remember { mutableStateOf(tasks) }
    if (tasks.isNotEmpty()) shownTasks = tasks

    SubcomposeLayout(modifier = modifier) { constraints ->
        val upstream = subcompose(0) {
            AppStateBanners(
                downloadedOnlyMode = downloadedOnlyMode,
                incognitoMode = incognitoMode,
                indexing = indexing,
            )
        }.fastMap { it.measure(constraints) }
        val upstreamHeight = upstream.fastMaxBy { it.height }?.height ?: 0

        val progress = subcompose(1) {
            AnimatedVisibility(
                visible = tasks.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (statusBarTop - upstreamHeight).coerceAtLeast(0)
                TaskProgressBanner(
                    tasks = shownTasks,
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val progressHeight = progress.fastMaxBy { it.height }?.height ?: 0

        layout(constraints.maxWidth, upstreamHeight + progressHeight) {
            upstream.fastForEach { it.place(0, 0) }
            progress.fastForEach { it.place(0, upstreamHeight) }
        }
    }
}

@Composable
private fun TaskProgressBanner(
    tasks: List<Task>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .background(TaskProgressBannerBackgroundColor)
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.fastForEach { TaskProgressRow(it) }
    }
}

@Composable
private fun TaskProgressRow(task: Task) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(task.kind.label),
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
            )
            // The restore worker starts with a placeholder 0/100 before it knows the real count.
            if (task.current > 0) {
                Text(
                    text = "${task.current}/${task.total}",
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (task.current > 0 && task.total > 0) {
            LinearProgressIndicator(
                progress = { task.current.toFloat() / task.total },
                modifier = Modifier.fillMaxWidth(),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.24f),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.24f),
            )
        }
    }
}

private val Kind.label
    get() = when (this) {
        Kind.LIBRARY_UPDATE -> MR.strings.updating_library
        Kind.RESTORE -> MR.strings.restoring_backup
        Kind.SYNC -> MR.strings.syncing_library
    }
