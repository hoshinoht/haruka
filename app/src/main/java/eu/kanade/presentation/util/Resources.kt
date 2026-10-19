package eu.kanade.presentation.util

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Create a BitmapPainter from a drawable resource.
 * Use this only if [androidx.compose.ui.res.painterResource] doesn't work.
 *
 * @param id the resource identifier
 *
 * @return the bitmap associated with the resource
 */
@Composable
fun rememberResourceBitmapPainter(@DrawableRes id: Int): BitmapPainter {
    val context = LocalContext.current
    // FORK --> Rasterize once per resource and configuration, not once per list item: every cover in a list asked for
    // its own full-size copy of the error drawable, drawn in software on the main thread during composition.
    val configuration = context.resources.configuration
    val key = ResourceBitmapKey(id, configuration.uiMode, configuration.densityDpi)
    return remember(key) {
        val bitmap = resourceBitmaps.getOrPut(key) {
            val drawable = ContextCompat.getDrawable(context, id)
                ?: throw Resources.NotFoundException()
            drawable.toBitmap().asImageBitmap()
        }
        BitmapPainter(bitmap)
    }
    // FORK <--
}

// FORK --> Only touched from composition, which runs on the main thread.
private data class ResourceBitmapKey(val id: Int, val uiMode: Int, val densityDpi: Int)
private val resourceBitmaps = HashMap<ResourceBitmapKey, ImageBitmap>()
// FORK <--
