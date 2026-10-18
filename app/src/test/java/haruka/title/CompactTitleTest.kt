package haruka.title

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CompactTitleTest {

    @Test
    fun `manga ball title keeps only the primary name`() {
        compactTitle(
            "Isn't Being a Wicked Woman Much Better? (악녀라서 편하고 좋은데요?/ Angnyeoraseo Pyeonhago Joeundeyo?/ " +
                "The Perks of Being a Villainess/ 当恶女真好/ It's Nice Being a Villainess)",
        ) shouldBe "Isn't Being a Wicked Woman Much Better?"
    }

    @Test
    fun `primary name ending in a tilde subtitle is kept whole`() {
        compactTitle(
            "Nageki no Bourei wa Intai Shitai ~Saijaku Hunter ni Yoru Saikyou Party Ikuseijutsu~ " +
                "(嘆きの亡霊は引退したい ～最弱ハンターによる最強パーティ育成術～/ Let this Grieving Soul Retire: " +
                "Woe Is the Weakling Who Leads the Strongest Party/ 叹息的亡灵想隐退/ 嘆息的亡靈想隱退)",
        ) shouldBe "Nageki no Bourei wa Intai Shitai ~Saijaku Hunter ni Yoru Saikyou Party Ikuseijutsu~"
    }

    @Test
    fun `alt list cut off before its closing paren is dropped`() {
        compactTitle(
            "Nageki no Bourei wa Intai Shitai ~Saijaku Hunter ni Yoru Saikyou Party Ikuseijutsu~ " +
                "(嘆きの亡霊は引退したい ～最弱ハンターによる最強パーティ育成術～/ Let this Grieving Soul Retire: Woe Is th",
        ) shouldBe "Nageki no Bourei wa Intai Shitai ~Saijaku Hunter ni Yoru Saikyou Party Ikuseijutsu~"
        compactTitle("Primary (Alt One/ Alt Tw") shouldBe "Primary"
        compactTitle("Primary (Alt One/") shouldBe "Primary"
    }

    @Test
    fun `alt names with their own parentheses are dropped with the group`() {
        compactTitle("Primary (Alt One (Manga)/ Alt Two (2019)/ Alt Three)") shouldBe "Primary"
        compactTitle("Primary (Alt One (Manga)/ Alt Two (unclosed") shouldBe "Primary"
    }

    @Test
    fun `parentheses without a separator are kept`() {
        compactTitle("Re:Zero (Light Novel)") shouldBe "Re:Zero (Light Novel)"
        compactTitle("Title (2024)") shouldBe "Title (2024)"
        compactTitle("Kaguya-sama (Doujinshi)") shouldBe "Kaguya-sama (Doujinshi)"
        compactTitle("Ranma 1/2 (1/2)") shouldBe "Ranma 1/2 (1/2)"
        compactTitle("Rock (AC/DC)") shouldBe "Rock (AC/DC)"
    }

    @Test
    fun `slash only nested inside an alt name does not make a group`() {
        compactTitle("Title (Remake (A/ B))") shouldBe "Title (Remake (A/ B))"
    }

    @Test
    fun `a qualifying group after a kept one is dropped`() {
        compactTitle("Re:Zero (Light Novel) (リゼロ/ Re:Zero kara Hajimeru)") shouldBe "Re:Zero (Light Novel)"
    }

    @Test
    fun `group that does not end the title is kept`() {
        compactTitle("Title (A/ B) Season 2") shouldBe "Title (A/ B) Season 2"
    }

    @Test
    fun `trailing whitespace after the group is allowed`() {
        compactTitle("Primary (A/ B)  ") shouldBe "Primary"
    }

    @Test
    fun `fullwidth spaces count as whitespace`() {
        compactTitle("主人公　(Alt One/　Alt Two)") shouldBe "主人公"
        compactTitle("Primary (Alt One/\u3000Alt Two)") shouldBe "Primary"
    }

    @Test
    fun `leftover separator punctuation is trimmed`() {
        compactTitle("Primary - (A/ B)") shouldBe "Primary"
        compactTitle("Primary: (A/ B)") shouldBe "Primary"
        compactTitle("Primary, (A/ B)") shouldBe "Primary"
        compactTitle("Primary! (A/ B)") shouldBe "Primary!"
    }

    @Test
    fun `empty primary leaves the title unchanged`() {
        compactTitle(" (A/ B)") shouldBe " (A/ B)"
        compactTitle("- (A/ B)") shouldBe "- (A/ B)"
        compactTitle("(A/ B)") shouldBe "(A/ B)"
    }

    @Test
    fun `paren not preceded by whitespace is not a group`() {
        compactTitle("Primary(A/ B)") shouldBe "Primary(A/ B)"
    }

    @Test
    fun `plain titles are unchanged`() {
        compactTitle("One Piece") shouldBe "One Piece"
        compactTitle("") shouldBe ""
    }
}
