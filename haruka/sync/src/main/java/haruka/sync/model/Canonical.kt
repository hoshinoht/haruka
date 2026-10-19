package haruka.sync.model

/*
 * Canonical forms: what the codec encodes, so equal content always yields equal bytes regardless of the order the
 * local database or a merge produced. Sets are sorted and deduplicated; keyed collections are sorted by key and
 * must not repeat a key (the database guarantees uniqueness, so a repeat is a bug upstream of the codec).
 * Already-canonical values are returned as-is without copying.
 */

fun Bucket.canonical(): Bucket {
    val sorted = entries.mapCanonical { it.canonical() }.sortedUniqueBy("entry key") { it.key }
    return if (sorted === entries) this else copy(entries = sorted)
}

fun EntryRecord.canonical(): EntryRecord {
    val categories = categoryUuids.canonicalSet()
    val excluded = excludedScanlators.canonicalSet()
    // An override with every field null means "follow the global settings", the same as no override.
    val override = dedupOverride?.takeUnless { it == DedupOverrideRecord() }
    val sortedChapters = chapters.mapCanonical { it.canonical() }.sortedUniqueBy("chapter url") { it.url }
    val sortedTracks = tracks.sortedUniqueBy("tracker id") { it.trackerId }
    if (
        categories === categoryUuids &&
        excluded === excludedScanlators &&
        override === dedupOverride &&
        sortedChapters === chapters &&
        sortedTracks === tracks
    ) {
        return this
    }
    return copy(
        categoryUuids = categories,
        excludedScanlators = excluded,
        dedupOverride = override,
        chapters = sortedChapters,
        tracks = sortedTracks,
    )
}

fun ChapterRecord.canonical(): ChapterRecord =
    if (historyReadAtMillis == null && historyDurationMs != 0L) copy(historyDurationMs = 0) else this

fun CategoriesPayload.canonical(): CategoriesPayload {
    val sorted = categories.sortedUniqueBy("category uuid") { it.uuid }
    return if (sorted === categories) this else copy(categories = sorted)
}

fun PrefsPayload.canonical(): PrefsPayload {
    val sorted = prefs.sortedUniqueBy("pref key") { it.key }
    return if (sorted === prefs) this else copy(prefs = sorted)
}

fun ManifestBody.canonical(): ManifestBody {
    val sortedBuckets = buckets.sortedUniqueBy("bucket index") { it.index }
    val sortedDevices = devices.sortedUniqueBy("device id") { it.id }
    if (sortedBuckets === buckets && sortedDevices === devices) return this
    return copy(buckets = sortedBuckets, devices = sortedDevices)
}

private fun List<String>.canonicalSet(): List<String> =
    if (isStrictlyAscending { it }) this else toSortedSet().toList()

private inline fun <T, K : Comparable<K>> List<T>.sortedUniqueBy(what: String, crossinline key: (T) -> K): List<T> {
    if (isStrictlyAscending(key)) return this
    val sorted = sortedBy(key)
    for (i in 1..<sorted.size) {
        require(key(sorted[i - 1]) != key(sorted[i])) { "Duplicate $what: ${key(sorted[i])}" }
    }
    return sorted
}

private inline fun <T, K : Comparable<K>> List<T>.isStrictlyAscending(key: (T) -> K): Boolean {
    for (i in 1..<size) {
        if (key(this[i - 1]) >= key(this[i])) return false
    }
    return true
}

/** Like [map], but returns this list itself when [transform] returns every element unchanged. */
private inline fun <T> List<T>.mapCanonical(transform: (T) -> T): List<T> {
    var out: ArrayList<T>? = null
    for (i in indices) {
        val element = this[i]
        val mapped = transform(element)
        if (out == null && mapped !== element) {
            out = ArrayList(size)
            for (j in 0..<i) out.add(this[j])
        }
        out?.add(mapped)
    }
    return out ?: this
}
