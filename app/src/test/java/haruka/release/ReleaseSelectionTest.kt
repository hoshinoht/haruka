package haruka.release

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.release.GitHubAsset

class ReleaseSelectionTest {

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = GithubReleaseEntry(
        tag = tag,
        info = "notes",
        releaseLink = "https://github.com/hoshinoht/haruka/releases/tag/$tag",
        assets = emptyList(),
        draft = draft,
        prerelease = prerelease,
    )

    // Deliberately not in version order: selection must not trust GitHub's list order.
    private val releases = listOf(
        release("v0.1.0-beta.2", prerelease = true),
        release("v0.2.0-beta.1", prerelease = true, draft = true),
        release("v0.1.0-beta.10", prerelease = true),
        release("v0.1.0"),
        release("v0.1.1-beta.1", prerelease = true),
        release("v0.0.9"),
        release("nightly-r1234"),
    )

    private fun selectedTag(releases: List<GithubReleaseEntry>, includePrereleases: Boolean) =
        selectRelease(releases, includePrereleases)?.first?.tag

    @Test
    fun `beta channel picks the highest version including prereleases`() {
        selectedTag(releases, includePrereleases = true) shouldBe "v0.1.1-beta.1"
    }

    @Test
    fun `stable channel skips prereleases`() {
        selectedTag(releases, includePrereleases = false) shouldBe "v0.1.0"
    }

    @Test
    fun `stable channel skips a prerelease tag GitHub did not flag`() {
        selectedTag(listOf(release("v0.1.0"), release("v0.2.0-beta.1")), includePrereleases = false) shouldBe "v0.1.0"
    }

    @Test
    fun `drafts are ignored on both channels`() {
        val withDraft = listOf(release("v0.1.0-beta.1", prerelease = true), release("v0.3.0", draft = true))

        selectedTag(withDraft, includePrereleases = true) shouldBe "v0.1.0-beta.1"
        selectedTag(withDraft, includePrereleases = false) shouldBe null
    }

    @Test
    fun `prerelease numbers compare numerically`() {
        val betas = listOf(release("v0.1.0-beta.10", prerelease = true), release("v0.1.0-beta.9", prerelease = true))

        selectedTag(betas, includePrereleases = true) shouldBe "v0.1.0-beta.10"
    }

    @Test
    fun `GitHub release list parses with unknown fields and a null body`() {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val body = """
            [{"id":1,"tag_name":"v0.1.0-beta.1","body":null,"html_url":"https://example.com","draft":false,
              "prerelease":true,"assets":[{"name":"haruka-v0.1.0-beta.1.apk","browser_download_url":"https://example.com/a.apk"}]}]
        """.trimIndent()

        val parsed = json.decodeFromString<List<GithubReleaseEntry>>(body).single()

        parsed.tag shouldBe "v0.1.0-beta.1"
        parsed.info shouldBe null
        parsed.prerelease shouldBe true
    }

    @Test
    fun `download link prefers the device ABI and falls back to the universal APK`() {
        val assets = listOf(
            GitHubAsset("haruka-v0.1.0-beta.1.apk", "universal"),
            GitHubAsset("haruka-arm64-v8a-v0.1.0-beta.1.apk", "arm64"),
        )

        downloadLink(assets, isFoss = false, abi = "arm64-v8a") shouldBe "arm64"
        downloadLink(assets, isFoss = false, abi = "x86_64") shouldBe "universal"
        downloadLink(assets, isFoss = true, abi = "arm64-v8a") shouldBe null
    }
}
