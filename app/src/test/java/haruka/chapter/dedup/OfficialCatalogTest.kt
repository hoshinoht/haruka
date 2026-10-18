package haruka.chapter.dedup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class OfficialCatalogTest {

    private val normalize = ScanlatorNames::normalize

    @Test
    fun `spelling variants of one group normalize alike`() {
        normalize("Lua Comic") shouldBe "luacomic"
        normalize("Lua_Comic") shouldBe "luacomic"
        normalize("Lua Comics") shouldBe "luacomic"
        normalize("Magus Manga") shouldBe normalize("magusmanga")
        normalize("MagusManga") shouldBe normalize("magusmanga")
        normalize("Reaper_Scans") shouldBe normalize("Reaper Scans")
    }

    @Test
    fun `a trailing parenthesised site suffix is dropped unless nothing would remain`() {
        normalize("Articuno (comick)") shouldBe normalize("Articuno")
        normalize("Entei (harimanga)") shouldBe "entei"
        normalize("(low quality)") shouldBe "lowquality"
    }

    @Test
    fun `a by suffix is dropped`() {
        normalize("MANGA Plus by SHUEISHA") shouldBe normalize("MANGA Plus")
        normalize("MANGA Plus (by SHUEISHA)") shouldBe normalize("MANGA Plus")
        normalize("Ruby Scans") shouldBe "rubyscan"
    }

    @Test
    fun `LuaScans may stay distinct from Lua Comic`() {
        normalize("LuaScans") shouldNotBe normalize("Lua Comic")
    }

    @Test
    fun `multi-group strings split on every separator outside parentheses`() {
        ScanlatorNames.split("A & B") shouldBe listOf("A", "B")
        ScanlatorNames.split("A, B + C | D and E") shouldBe listOf("A", "B", "C", "D", "E")
        ScanlatorNames.split("A AND B") shouldBe listOf("A", "B")
        ScanlatorNames.split("Entei (harimanga, comick)") shouldBe listOf("Entei (harimanga, comick)")
        ScanlatorNames.split("Brandon Scans") shouldBe listOf("Brandon Scans")
        ScanlatorNames.split(" & A &") shouldBe listOf("A")
    }

    @Test
    fun `census official strings get their exact tier`() {
        testCatalog.statusOf("Official") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("official") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Official (low quality)") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Official?") shouldBe OfficialStatus.UNCERTAIN
        testCatalog.statusOf("unofficial") shouldBe OfficialStatus.NONE
    }

    @Test
    fun `placeholders and missing scanlators are not official`() {
        listOf(null, "", "   ", "<empty>", "Unknown", "www.mangabats.com", "WeebCentral", "(low quality)").forEach {
            testCatalog.statusOf(it) shouldBe OfficialStatus.NONE
        }
    }

    @Test
    fun `aliases resolve to their canonical publisher`() {
        mapOf(
            "MANGA Plus by SHUEISHA" to "MANGA Plus",
            "MangaPlus" to "MANGA Plus",
            "VIZ Media" to "VIZ",
            "K MANGA" to "Kodansha",
            "Kodansha US" to "Kodansha",
            "Yen Press" to "Yen Press",
            "Seven Seas Entertainment" to "Seven Seas",
            "Manga UP!" to "Square Enix",
            "LINE Webtoon" to "Webtoon",
            "Webtoons" to "Webtoon",
            "Tapas" to "Tapas",
            "Comikey" to "Comikey",
            "Azuki" to "Azuki",
            "INKR" to "INKR",
            "Tappytoon" to "Tappytoon",
            "Lezhin Comics" to "Lezhin",
            "Manta" to "Manta",
            "J-Novel Club" to "J-Novel Club",
            "Pocket Comics" to "Pocket Comics",
            "Bilibili Comics" to "Bilibili Comics",
        ).forEach { (alias, publisher) -> testCatalog.publisherOf(alias) shouldBe publisher }
    }

    @Test
    fun `matching is exact per group, never substring`() {
        testCatalog.publisherOf("Webtoon Scans") shouldBe null
        testCatalog.publisherOf("VIZ Fan Edits") shouldBe null
        testCatalog.publisherOf("Mantaray") shouldBe null
    }

    @Test
    fun `any official group in a multi-group string counts`() {
        testCatalog.statusOf("Group A & MANGA Plus by SHUEISHA") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A, VIZ") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A + Yen Press") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A | Tapas") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A and Comikey") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A & Official?") shouldBe OfficialStatus.UNCERTAIN
        testCatalog.statusOf("Official? & VIZ") shouldBe OfficialStatus.OFFICIAL
        testCatalog.statusOf("Group A & Group B") shouldBe OfficialStatus.NONE
        testCatalog.publisherOf("Group A & K MANGA") shouldBe "Kodansha"
    }

    @Test
    fun `publisher sources are recognized by name`() {
        testCatalog.isOfficialSource("MANGA Plus by SHUEISHA") shouldBe true
        testCatalog.isOfficialSource("Webtoons.com") shouldBe true
        testCatalog.isOfficialSource("Comikey") shouldBe true
        testCatalog.isOfficialSource("MangaDex") shouldBe false
        testCatalog.isOfficialSource("WeebCentral") shouldBe false
    }

    @Test
    fun `an alias claimed by two publishers is rejected`() {
        val json = """
            {"version": 1, "sources": [], "publishers": [
              {"name": "A", "aliases": ["Same"]},
              {"name": "B", "aliases": ["same!"]}
            ]}
        """.trimIndent()
        shouldThrow<IllegalArgumentException> { OfficialCatalog.parse(json) }
    }
}
