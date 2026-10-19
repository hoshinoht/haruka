package haruka.chapter.dedup

import java.io.File

/** The catalog bundled by :haruka:core, read straight from its asset file (unit tests run with app/ as working dir). */
val testCatalog: OfficialCatalog by lazy {
    OfficialCatalog.parse(File("../haruka/core/src/main/assets/${OfficialCatalog.ASSET_PATH}").readText())
}
