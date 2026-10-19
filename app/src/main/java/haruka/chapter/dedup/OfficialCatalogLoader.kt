package haruka.chapter.dedup

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Reads the bundled catalog asset once, on first use. */
@Inject
@SingleIn(AppScope::class)
class OfficialCatalogLoader(private val context: Context) {

    val catalog: OfficialCatalog by lazy {
        context.assets.open(OfficialCatalog.ASSET_PATH).bufferedReader().use { OfficialCatalog.parse(it.readText()) }
    }
}
