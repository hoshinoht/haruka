package haruka.chapter.dedup

import tachiyomi.domain.chapter.model.Chapter

fun Chapter.toDedupChapter(): DedupChapter = DedupChapter(
    id = id,
    name = name,
    chapterNumber = chapterNumber.withoutFloatNoise(),
    scanlator = scanlator?.ifBlank { null },
    dateUpload = dateUpload?.toEpochMilliseconds() ?: 0L,
    sourceOrder = sourceOrder,
)
