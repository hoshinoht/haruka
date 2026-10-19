package haruka.cover

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import coil3.compose.AsyncImage
import haruka.di.harukaGraph
import tachiyomi.presentation.core.util.collectAsState

/** Covers with height / width at or below this are shown in full (same threshold as Komikku). */
internal const val WIDE_COVER_RATIO = 0.75f

// Height / width per cover model, so a wide cover is laid out correctly on recomposition and scroll-back
// without waiting for the image to load again.
private val coverRatios = LruCache<Any, Float>(4096)

internal fun isWideCover(heightOverWidth: Float): Boolean = heightOverWidth <= WIDE_COVER_RATIO

@Composable
fun rememberPanoramaCoversEnabled(): Boolean {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.panoramaCovers }
    val enabled by preference.collectAsState()
    return enabled
}

/**
 * A cover that is cropped like upstream's when it is portrait, and fitted on a neutral backdrop when it is
 * wide, so landscape covers are shown in full without changing the frame's size.
 */
@Composable
fun PanoramaAwareCover(
    data: Any?,
    placeholder: Painter,
    error: Painter,
    contentDescription: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var ratio by remember(data) { mutableStateOf(data?.let(coverRatios::get)) }
    val wide = ratio?.let(::isWideCover) == true

    // Coil keeps the scale of the request it started with, so a cover that turns out to be wide needs a new
    // request rather than just a new contentScale.
    key(wide) {
        AsyncImage(
            model = data,
            placeholder = placeholder,
            error = error,
            contentDescription = contentDescription,
            contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { state ->
                val image = state.result.image
                if (image.width > 0) {
                    val loaded = image.height.toFloat() / image.width
                    data?.let { coverRatios.put(it, loaded) }
                    ratio = loaded
                }
            },
            modifier = modifier
                .then(if (wide) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        )
    }
}
