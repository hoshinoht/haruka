package haruka.migration

import haruka.migration.MatchConfidence.Level
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MatchConfidenceTest {

    private fun score(
        original: String,
        candidate: String = original,
        originalAuthor: String? = null,
        candidateAuthor: String? = null,
        read: Double? = null,
        available: Double? = null,
    ) = scoreMatch(MatchInput(original, candidate, originalAuthor, candidateAuthor, read, available))

    // Title similarity boundaries

    @Test
    fun `similarity of exactly 0_9 is high`() {
        titleSimilarity("abcdefghij", "abcdefghik") shouldBe 0.9
        score("abcdefghij", "abcdefghik").level shouldBe Level.HIGH
    }

    @Test
    fun `similarity just under 0_9 needs a check`() {
        val result = score("abcdefghi", "abcdefghk")
        result.level shouldBe Level.CHECK
        result.reasons.first() shouldBe MatchReason.TitleDiffers(8.0 / 9)
    }

    @Test
    fun `similarity of exactly 0_6 needs a check`() {
        titleSimilarity("abcdefghij", "abcdefwxyz") shouldBe 0.6
        score("abcdefghij", "abcdefwxyz").level shouldBe Level.CHECK
    }

    @Test
    fun `similarity just under 0_6 is low`() {
        val result = score("abcdefghijkl", "abcdefgvwxyz")
        result.level shouldBe Level.LOW
        result.reasons shouldBe listOf(MatchReason.TitleDiffers(7.0 / 12))
    }

    // Progress boundaries

    @Test
    fun `candidate covering the furthest read chapter is high`() {
        score("Solo Leveling", read = 120.0, available = 120.0) shouldBe MatchConfidence(
            Level.HIGH,
            listOf(MatchReason.ProgressCovered(read = 120.0, available = 120.0), MatchReason.TitleMatches),
        )
    }

    @Test
    fun `one chapter short needs a check`() {
        score("Solo Leveling", read = 120.0, available = 119.0).level shouldBe Level.CHECK
    }

    @Test
    fun `exactly 10 chapters short needs a check`() {
        score("Solo Leveling", read = 120.0, available = 110.0) shouldBe MatchConfidence(
            Level.CHECK,
            listOf(MatchReason.ProgressShort(read = 120.0, available = 110.0), MatchReason.TitleMatches),
        )
    }

    @Test
    fun `more than 10 chapters short is low`() {
        score("Solo Leveling", read = 120.0, available = 109.5).level shouldBe Level.LOW
        score("Solo Leveling", read = 120.0, available = 98.0).reasons.first() shouldBe
            MatchReason.ProgressShort(read = 120.0, available = 98.0)
    }

    @Test
    fun `a short candidate leads with progress even when the title also differs`() {
        score("abcdefghi", "abcdefghk", read = 120.0, available = 98.0).reasons shouldBe listOf(
            MatchReason.ProgressShort(read = 120.0, available = 98.0),
            MatchReason.TitleDiffers(8.0 / 9),
        )
    }

    // Unknown signals never count against a match

    @Test
    fun `nothing read is unknown progress`() {
        score("Solo Leveling", read = null, available = 5.0) shouldBe
            MatchConfidence(Level.HIGH, listOf(MatchReason.TitleMatches))
    }

    @Test
    fun `missing latest chapter is unknown progress`() {
        score("Solo Leveling", read = 120.0, available = null).level shouldBe Level.HIGH
    }

    @Test
    fun `negative chapter numbers are unknown progress`() {
        score("Solo Leveling", read = -1.0, available = 3.0).level shouldBe Level.HIGH
        score("Solo Leveling", read = 120.0, available = -1.0).level shouldBe Level.HIGH
    }

    @Test
    fun `blank author on either side is unknown`() {
        compareAuthors(null, "Oda Eiichiro") shouldBe AuthorMatch.UNKNOWN
        compareAuthors("Oda Eiichiro", "  ") shouldBe AuthorMatch.UNKNOWN
        score("One Piece", originalAuthor = "Oda Eiichiro", candidateAuthor = "").level shouldBe Level.HIGH
    }

    // Authors

    @Test
    fun `author mismatch caps an otherwise high match at check`() {
        score(
            "One Piece",
            originalAuthor = "Oda Eiichiro",
            candidateAuthor = "Kishimoto Masashi",
            read = 10.0,
            available = 20.0,
        ) shouldBe MatchConfidence(
            Level.CHECK,
            listOf(
                MatchReason.AuthorMismatch,
                MatchReason.ProgressCovered(read = 10.0, available = 20.0),
                MatchReason.TitleMatches,
            ),
        )
    }

    @Test
    fun `author mismatch does not lift a low match`() {
        score("One Piece", "Kingdom", originalAuthor = "Oda", candidateAuthor = "Hara").level shouldBe Level.LOW
    }

    @Test
    fun `authors compare as token sets`() {
        compareAuthors("Oda Eiichiro", "Eiichiro ODA") shouldBe AuthorMatch.MATCH
        compareAuthors("Kishimoto Masashi", "Ikemoto Mikio, Kishimoto Masashi") shouldBe AuthorMatch.MATCH
        compareAuthors("Kentarō Miura (Story & Art)", "MIURA Kentaro") shouldBe AuthorMatch.MATCH
        compareAuthors("尾田栄一郎", "尾田 栄一郎") shouldBe AuthorMatch.MATCH
        compareAuthors("Oda Eiichiro", "Kishimoto Masashi") shouldBe AuthorMatch.MISMATCH
    }

    @Test
    fun `author credits in different scripts are unknown`() {
        compareAuthors("尾田栄一郎", "Oda Eiichiro") shouldBe AuthorMatch.UNKNOWN
    }

    @Test
    fun `matching author is listed as agreeing`() {
        score("One Piece", originalAuthor = "Oda Eiichiro", candidateAuthor = "ODA Eiichiro").reasons shouldBe
            listOf(MatchReason.TitleMatches, MatchReason.AuthorMatches)
    }

    // Search engine single-result case

    @Test
    fun `a lone search result is still compared by title`() {
        // Smart search gives a single result for a single query similarity 1.0 without comparing; scoring must not.
        val result = score("One Piece", "Kingdom", read = 5.0, available = 700.0)
        result.level shouldBe Level.LOW
        (result.reasons.first() is MatchReason.TitleDiffers) shouldBe true
    }

    // Scripts and romanisation

    @Test
    fun `identical CJK titles are high`() {
        score("葬送のフリーレン", "葬送のフリーレン").level shouldBe Level.HIGH
    }

    @Test
    fun `CJK title with a bracketed tag is high`() {
        cleanTitle("葬送のフリーレン (公式)") shouldBe "葬送のフリーレン"
        score("葬送のフリーレン (公式)", "葬送のフリーレン").level shouldBe Level.HIGH
    }

    @Test
    fun `CJK title against its romanisation is low`() {
        score("葬送のフリーレン", "Sousou no Frieren").level shouldBe Level.LOW
    }

    @Test
    fun `romanised title against its English title is low`() {
        score("Shingeki no Kyojin", "Attack on Titan").level shouldBe Level.LOW
    }

    @Test
    fun `romanised titles differing only in punctuation are high`() {
        score(
            "Kaguya-sama wa Kokurasetai: Tensai-tachi no Renai Zunousen",
            "Kaguya-sama wa Kokurasetai ~Tensai-tachi no Renai Zunousen~",
        ).level shouldBe Level.HIGH
    }

    // Bracket and punctuation noise

    @Test
    fun `bracketed tags are ignored`() {
        cleanTitle("Solo Leveling (Official) [Webtoon]") shouldBe "solo leveling"
        score("Solo Leveling (Official)", "Solo Leveling [Webtoon]").level shouldBe Level.HIGH
    }

    @Test
    fun `a title made only of brackets keeps its text`() {
        cleanTitle("[Oshi no Ko]") shouldBe "oshi no ko"
        score("[Oshi no Ko]", "Oshi no Ko").level shouldBe Level.HIGH
    }

    @Test
    fun `punctuation and case are ignored`() {
        score("Chainsaw Man!", "CHAINSAW-MAN.").level shouldBe Level.HIGH
    }

    @Test
    fun `titles that clean to nothing do not match each other`() {
        score("!!!", "???").level shouldBe Level.LOW
    }
}
