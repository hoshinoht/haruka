package haruka.release

import android.os.Build
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import haruka.preference.HarukaPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.data.release.GitHubAsset
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

/**
 * Release-build update check for Haruka's SemVer tags. Unlike upstream's `/releases/latest`, it reads the
 * release list so GitHub prereleases can be offered on the beta channel, and it ranks tags by SemVer
 * instead of trusting GitHub's ordering. Nightly builds keep upstream's commit-count check.
 */
@Inject
class HarukaReleaseChecker(
    private val network: NetworkHelper,
    private val json: Json,
    private val preferences: HarukaPreferences,
) {

    suspend fun check(installedVersion: String, repository: String, isFoss: Boolean): GetApplicationRelease.Result {
        val installed = SemVer.parse(installedVersion) ?: return GetApplicationRelease.Result.NoNewUpdate
        val releases = with(json) {
            network.client
                .newCall(GET("https://api.github.com/repos/$repository/releases?per_page=$PAGE_SIZE"))
                .awaitSuccess()
                .parseAs<List<GithubReleaseEntry>>()
        }
        val (release, version) = selectRelease(releases, preferences.betaUpdates.get())
            ?: return GetApplicationRelease.Result.NoNewUpdate
        if (version <= installed) return GetApplicationRelease.Result.NoNewUpdate
        val downloadLink = downloadLink(release.assets, isFoss, Build.SUPPORTED_ABIS[0])
            ?: return GetApplicationRelease.Result.NoNewUpdate

        return GetApplicationRelease.Result.NewUpdate(
            Release(
                version = release.tag,
                info = release.info.orEmpty().substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) {
                    "[${it.value}](https://github.com/${it.value.substring(1)})"
                },
                releaseLink = release.releaseLink,
                downloadLink = downloadLink,
            ),
        )
    }

    private companion object {
        // Enough to reach the newest stable release past a run of betas.
        const val PAGE_SIZE = 30

        // Same mention pattern as upstream's ReleaseServiceImpl (GitHub username rules).
        val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}

@Serializable
internal data class GithubReleaseEntry(
    @SerialName("tag_name")
    val tag: String,
    // GitHub sends null for an empty description.
    @SerialName("body")
    val info: String? = null,
    @SerialName("html_url")
    val releaseLink: String = "",
    val assets: List<GitHubAsset> = emptyList(),
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * Highest-versioned published release. Without [includePrereleases], both GitHub-flagged prereleases and
 * SemVer prerelease tags are skipped, so a mis-flagged beta never reaches the stable channel.
 */
internal fun selectRelease(
    releases: List<GithubReleaseEntry>,
    includePrereleases: Boolean,
): Pair<GithubReleaseEntry, SemVer>? {
    return releases
        .asSequence()
        .filterNot { it.draft }
        .filter { includePrereleases || !it.prerelease }
        .mapNotNull { release -> SemVer.parse(release.tag)?.let { release to it } }
        .filter { (_, version) -> includePrereleases || !version.isPrerelease }
        .maxByOrNull { (_, version) -> version }
}

private const val FOSS = "foss"
private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** Same asset matching as upstream: the device ABI's split APK, else the universal one. */
internal fun downloadLink(assets: List<GitHubAsset>, isFoss: Boolean, abi: String): String? {
    val map = assets.associate { asset -> BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink }
    return if (isFoss) map[FOSS] else map[abi] ?: map[null]
}
