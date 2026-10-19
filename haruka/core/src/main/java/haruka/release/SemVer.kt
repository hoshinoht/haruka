package haruka.release

/**
 * A SemVer 2.0 version as used by Haruka release tags (`v0.1.0-beta.1`, `v0.1.0`).
 * Build metadata is accepted but ignored, as the spec requires for precedence.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A version without prerelease identifiers ranks above any of its prereleases.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return other.prerelease.size.compareTo(prerelease.size)
        }
        for (i in 0..<minOf(prerelease.size, other.prerelease.size)) {
            compareIdentifiers(prerelease[i], other.prerelease[i]).let { if (it != 0) return it }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        // semver.org's reference pattern, with an optional `v` prefix and core numbers capped to fit an Int.
        private val pattern = Regex(
            """^v?(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""" +
                """(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?""" +
                """(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?$""",
        )

        fun parse(text: String): SemVer? {
            val groups = pattern.matchEntire(text.trim())?.groupValues ?: return null
            return SemVer(
                major = groups[1].toInt(),
                minor = groups[2].toInt(),
                patch = groups[3].toInt(),
                prerelease = groups[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty(),
            )
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            return when {
                // Numeric identifiers have no leading zeros, so a longer one is larger; no overflow possible.
                aNumeric && bNumeric -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
