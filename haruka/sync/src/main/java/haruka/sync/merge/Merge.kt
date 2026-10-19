package haruka.sync.merge

/*
 * Shared pieces of the three-way merge. Every merge takes (base, local, remote); "local" is the syncing device,
 * the one running the merge, and wins conflicting scalar settings. A null base means union mode (first sync,
 * remote epoch change, or a Mihon restore since the last sync): every field is then merged against its default,
 * which makes booleans OR, counters max and sets union, and lets a non-default scalar beat a default one.
 */

/** Removed-entry and deleted-category tombstones are pruned this long after their removal. */
const val TOMBSTONE_RETENTION_MILLIS: Long = 180L * 24 * 60 * 60 * 1000

/** A scalar that both sides changed to different values; the syncing device's value was kept. */
data class MergeConflict(
    /** What the field belongs to, e.g. `entry 1 /manga/x`, `category <uuid>` or `pref <key>`. */
    val subject: String,
    val field: String,
)

/**
 * Three-way pick: a value changed on one side only takes that side, one changed to the same value on both sides
 * takes it, and [onConflict] decides when both sides changed it to different values.
 */
internal inline fun <T> merge3(base: T, local: T, remote: T, onConflict: () -> T): T = when {
    local == remote -> local
    local == base -> remote
    remote == base -> local
    else -> onConflict()
}

/** [merge3] for a setting where the syncing device wins a conflict; the conflict is recorded in this list. */
internal fun <T> MutableList<MergeConflict>.pickSetting(
    subject: String,
    field: String,
    base: T,
    local: T,
    remote: T,
): T = merge3(base, local, remote) {
    add(MergeConflict(subject, field))
    local
}

/**
 * Per-element three-way merge of sorted, unique string sets: an add on either side wins over the other side not
 * changing that element, and a remove wins unless the other side added it back. Returns a sorted, unique list.
 */
internal fun mergeSet(base: List<String>, local: List<String>, remote: List<String>): List<String> {
    if (local == remote || remote == base) return local
    if (local == base) return remote
    val b = base.toHashSet()
    val l = local.toHashSet()
    val r = remote.toHashSet()
    val all = sortedSetOf<String>().apply {
        addAll(base)
        addAll(local)
        addAll(remote)
    }
    // Booleans changed on both sides are equal, so the conflict branch is unreachable.
    return all.filter { merge3(it in b, it in l, it in r) { it in l } }
}

internal fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

internal fun isExpired(removedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis - removedAtMillis > TOMBSTONE_RETENTION_MILLIS
