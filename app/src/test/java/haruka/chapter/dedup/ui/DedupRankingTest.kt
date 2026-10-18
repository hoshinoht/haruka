package haruka.chapter.dedup.ui

import haruka.chapter.dedup.DedupRule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DedupRankingTest {

    private val abc = listOf("a", "b", "c")

    @Test
    fun `move up swaps with the previous item`() {
        DedupRanking.moveUp(abc, 2) shouldBe listOf("a", "c", "b")
    }

    @Test
    fun `move down swaps with the next item`() {
        DedupRanking.moveDown(abc, 0) shouldBe listOf("b", "a", "c")
    }

    @Test
    fun `moves past either end or out of range leave the list unchanged`() {
        DedupRanking.moveUp(abc, 0) shouldBe abc
        DedupRanking.moveDown(abc, 2) shouldBe abc
        DedupRanking.moveUp(abc, 5) shouldBe abc
        DedupRanking.moveDown(abc, -1) shouldBe abc
    }

    @Test
    fun `rules count as default, enabling reset, only in the default order`() {
        DedupRanking.isDefault(DedupRule.DEFAULT) shouldBe true

        val userRank = DedupRule.DEFAULT.indexOf(DedupRule.USER_RANK)
        val moved = DedupRanking.moveUp(DedupRule.DEFAULT, userRank)
        DedupRanking.isDefault(moved) shouldBe false
        moved[userRank - 1] shouldBe DedupRule.USER_RANK

        DedupRanking.isDefault(DedupRanking.moveDown(moved, userRank - 1)) shouldBe true
    }

    @Test
    fun `normalize keeps the first spelling of each group, trimmed`() {
        DedupRanking.normalize(listOf(" Lua Comic ", "Lua_Comics", "Asura", "ASURA (comick)")) shouldBe
            listOf("Lua Comic", "Asura")
    }

    @Test
    fun `normalize drops names that can never match`() {
        DedupRanking.normalize(listOf("", "   ", "!!", "Flame")) shouldBe listOf("Flame")
    }

    @Test
    fun `add appends at the bottom`() {
        DedupRanking.add(listOf("Asura"), "  Flame Scans ") shouldBe listOf("Asura", "Flame Scans")
    }

    @Test
    fun `add ignores another spelling of a ranked group and blank names`() {
        val ranking = listOf("Lua Comic", "Asura")

        DedupRanking.add(ranking, "lua_comics") shouldBe ranking
        DedupRanking.add(ranking, " ") shouldBe ranking
    }

    @Test
    fun `unranked filters by normalized name and keeps order`() {
        DedupRanking.unranked(listOf("Asura", "Flame", "Lua Comics"), listOf("ASURA (comick)", "LUA COMIC")) shouldBe
            listOf("Flame")
    }

    @Test
    fun `groups splits joint credits and dedupes spellings, sorted case-insensitively`() {
        DedupRanking.groups(listOf("Flame & asura", "Asura", "", "beta, Lua Comic", "Lua_Comics")) shouldBe
            listOf("asura", "beta", "Flame", "Lua Comic")
    }

    @Test
    fun `ordered puts preferred groups first in preferred order and keeps the rest in place`() {
        val names = listOf("Alpha", "Beta", "Gamma", "Delta")

        DedupRanking.ordered(listOf("gamma", "Missing", "alpha"), names) shouldBe
            listOf("Gamma", "Alpha", "Beta", "Delta")
    }

    @Test
    fun `ordered without preferences keeps the given order`() {
        val names = listOf("Beta", "Alpha")

        DedupRanking.ordered(emptyList(), names) shouldBe names
    }
}
