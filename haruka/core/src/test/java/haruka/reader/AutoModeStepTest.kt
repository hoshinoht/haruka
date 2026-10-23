package haruka.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutoModeStepTest {

    private val longStripTags = listOf("Long Strip")
    private val pagedTags = listOf("Action")

    @Test
    fun `a mode the user set is never touched, whatever the tags or setting`() {
        for (enabled in listOf(true, false)) {
            for (decided in listOf(true, false)) {
                for (genres in listOf(longStripTags, pagedTags, null)) {
                    autoModeStep(enabled, storedModeIsDefault = false, decided, genres, "Webtoons") shouldBe
                        AutoModeStep.Keep
                }
            }
        }
    }

    @Test
    fun `a decided series is not detected again, even back on Default`() {
        autoModeStep(enabled = true, storedModeIsDefault = true, decided = true, longStripTags, null) shouldBe
            AutoModeStep.Keep
        autoModeStep(enabled = true, storedModeIsDefault = true, decided = true, pagedTags, null) shouldBe
            AutoModeStep.Keep
    }

    @Test
    fun `nothing happens with the setting off`() {
        autoModeStep(enabled = false, storedModeIsDefault = true, decided = false, longStripTags, null) shouldBe
            AutoModeStep.Keep
    }

    @Test
    fun `tags decide before any page is measured`() {
        autoModeStep(enabled = true, storedModeIsDefault = true, decided = false, longStripTags, null) shouldBe
            AutoModeStep.LongStripFromTags
        autoModeStep(enabled = true, storedModeIsDefault = true, decided = false, pagedTags, null) shouldBe
            AutoModeStep.MeasurePages
        autoModeStep(enabled = true, storedModeIsDefault = true, decided = false, null, null) shouldBe
            AutoModeStep.MeasurePages
    }
}
