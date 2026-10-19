package haruka.migration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourcePresetsTest {

    private val main = SourcePreset("Main", listOf(1L, 2L, 3L))
    private val backup = SourcePreset("Backup", listOf(3L, 1L))

    @Test
    fun `encode and decode round trip keeps order`() {
        val text = SourcePresets.encode(listOf(main, backup))
        text shouldBe """[{"name":"Main","sourceIds":[1,2,3]},{"name":"Backup","sourceIds":[3,1]}]"""
        SourcePresets.decode(text) shouldBe listOf(main, backup)
    }

    @Test
    fun `bad or empty text decodes to no presets`() {
        SourcePresets.decode("") shouldBe emptyList()
        SourcePresets.decode("not json") shouldBe emptyList()
        SourcePresets.decode("{}") shouldBe emptyList()
        SourcePresets.decode("""[{"name":"Main"}]""") shouldBe emptyList()
        SourcePresets.decode("""[{"name":"Main","sourceIds":["x"]}]""") shouldBe emptyList()
    }

    @Test
    fun `decode ignores unknown fields`() {
        SourcePresets.decode("""[{"name":"Main","sourceIds":[1],"colour":"aqua"}]""") shouldBe
            listOf(SourcePreset("Main", listOf(1L)))
    }

    @Test
    fun `decode drops blank names, repeated names and repeated ids`() {
        SourcePresets.decode(
            """[{"name":" ","sourceIds":[1]},{"name":" Main ","sourceIds":[2,2,1]},{"name":"main","sourceIds":[9]}]""",
        ) shouldBe listOf(SourcePreset("Main", listOf(2L, 1L)))
    }

    @Test
    fun `save appends a new name at the end`() {
        SourcePresets.save(listOf(main), "Backup", listOf(3L, 1L)) shouldBe listOf(main, backup)
    }

    @Test
    fun `save replaces a preset with the same name in place`() {
        SourcePresets.save(listOf(main, backup), " main ", listOf(9L, 9L, 8L)) shouldBe
            listOf(SourcePreset("main", listOf(9L, 8L)), backup)
    }

    @Test
    fun `save ignores a blank name`() {
        SourcePresets.save(listOf(main), "  ", listOf(1L)) shouldBe listOf(main)
    }

    @Test
    fun `rename keeps position and sources`() {
        SourcePresets.rename(listOf(main, backup), "main", "Everyday") shouldBe
            listOf(SourcePreset("Everyday", main.sourceIds), backup)
    }

    @Test
    fun `rename to the same name with other casing is allowed`() {
        SourcePresets.rename(listOf(main), "Main", "MAIN") shouldBe listOf(SourcePreset("MAIN", main.sourceIds))
    }

    @Test
    fun `rename never collides, blanks or invents`() {
        val presets = listOf(main, backup)
        SourcePresets.rename(presets, "Main", "backup") shouldBe presets
        SourcePresets.rename(presets, "Main", " ") shouldBe presets
        SourcePresets.rename(presets, "Missing", "New") shouldBe presets
    }

    @Test
    fun `delete removes by name`() {
        SourcePresets.delete(listOf(main, backup), "MAIN") shouldBe listOf(backup)
    }

    @Test
    fun `find matches names loosely`() {
        SourcePresets.find(listOf(main, backup), " backup") shouldBe backup
        SourcePresets.find(listOf(main), "Other") shouldBe null
    }

    @Test
    fun `apply keeps preset order and reports missing sources`() {
        SourcePresets.apply(SourcePreset("Mixed", listOf(5L, 99L, 1L, 42L)), available = setOf(1L, 5L, 7L)) shouldBe
            PresetApplication(selected = listOf(5L, 1L), missing = listOf(99L, 42L))
    }

    @Test
    fun `apply with nothing available selects nothing`() {
        SourcePresets.apply(main, available = emptyList()) shouldBe
            PresetApplication(selected = emptyList(), missing = listOf(1L, 2L, 3L))
    }
}
