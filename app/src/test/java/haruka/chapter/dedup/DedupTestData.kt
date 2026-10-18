package haruka.chapter.dedup

import java.io.File

/** The bundled catalog, read straight from the asset file (unit tests run with the module as working dir). */
val testCatalog: OfficialCatalog by lazy {
    OfficialCatalog.parse(File("src/main/assets/${OfficialCatalogLoader.ASSET_PATH}").readText())
}

fun chapter(
    id: Long,
    number: Double,
    scanlator: String? = null,
    name: String = "Chapter ${formatNumber(number)}",
    dateUpload: Long = 0,
    sourceOrder: Long = id,
) = DedupChapter(id, name, number, scanlator, dateUpload, sourceOrder)

private fun formatNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

val ChapterCopy.ids: List<Long> get() = parts.map { it.id }
