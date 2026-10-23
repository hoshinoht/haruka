package haruka.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CustomShelvesTest {

    private val unread = CustomShelf("a1", "Unread", "unread>0")
    private val dex = CustomShelf("b2", "MangaDex", "source:mangadex -genre:romance")

    @Test
    fun `encode and decode round trip keeps order and text`() {
        CustomShelves.decode(CustomShelves.encode(listOf(dex, unread))) shouldBe listOf(dex, unread)
    }

    @Test
    fun `bad or empty text decodes to no shelves`() {
        CustomShelves.decode("").shouldBeEmpty()
        CustomShelves.decode("not json").shouldBeEmpty()
        CustomShelves.decode("{}").shouldBeEmpty()
        CustomShelves.decode("""[{"id":"a1","name":"Unread"}]""").shouldBeEmpty()
        CustomShelves.decode("""[{"id":1,"name":"Unread","query":["x"]}]""").shouldBeEmpty()
    }

    @Test
    fun `decode ignores unknown fields`() {
        CustomShelves.decode("""[{"id":"a1","name":"Unread","query":"unread>0","colour":"aqua"}]""") shouldBe
            listOf(unread)
    }

    @Test
    fun `decode drops entries without id or name and keeps the first of a repeated id`() {
        CustomShelves.decode(
            """[{"id":"","name":"X","query":"q"},{"id":"c","name":" ","query":"q"},""" +
                """{"id":"a1","name":" Unread ","query":"unread>0"},{"id":"a1","name":"Again","query":"q"}]""",
        ) shouldBe listOf(unread)
    }

    @Test
    fun `save replaces by id in place, so a rename keeps the position`() {
        CustomShelves.save(listOf(unread, dex), unread.copy(name = "To read")) shouldBe
            listOf(unread.copy(name = "To read"), dex)
    }

    @Test
    fun `save appends a new id and ignores a blank name`() {
        CustomShelves.save(listOf(unread), dex) shouldBe listOf(unread, dex)
        CustomShelves.save(listOf(unread), dex.copy(name = "  ")) shouldBe listOf(unread)
    }

    @Test
    fun `move swaps with the neighbour and stops at the ends`() {
        val third = CustomShelf("c3", "Third", "total>100")
        val shelves = listOf(unread, dex, third)
        CustomShelves.move(shelves, "c3", -1) shouldBe listOf(unread, third, dex)
        CustomShelves.move(shelves, "a1", 1) shouldBe listOf(dex, unread, third)
        CustomShelves.move(shelves, "a1", -1) shouldBe shelves
        CustomShelves.move(shelves, "c3", 1) shouldBe shelves
        CustomShelves.move(shelves, "missing", 1) shouldBe shelves
    }

    @Test
    fun `delete removes only that id`() {
        CustomShelves.delete(listOf(unread, dex), "a1") shouldBe listOf(dex)
    }

    @Test
    fun `groups run largest first, then by name, then by key`() {
        val groups = ShelfGroups()
        groups.add("2", "Beta", 1)
        groups.add("1", "alpha", 2)
        groups.add("3", "Gamma", 3)
        groups.add("3", "Gamma", 4)
        groups.add("4", "Alpha", 5)
        val built = groups.build("g:", minSize = 1, names = String.CASE_INSENSITIVE_ORDER)
        built.map { it.key } shouldContainExactly listOf("g:3", "g:1", "g:4", "g:2")
        built.first().ids shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `groups below the minimum are left out and a series counts once per group`() {
        val groups = ShelfGroups()
        groups.add("one", "One", 1)
        groups.add("one", "One", 1)
        groups.add("two", "Two", 2)
        groups.add("two", "Two", 3)
        groups.build("", minSize = 2, names = naturalOrder()).map { it.name } shouldContainExactly listOf("Two")
    }
}
