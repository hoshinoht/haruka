package haruka.release

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SemVerTest {

    private fun v(text: String) = SemVer.parse(text)!!

    @Test
    fun `versions sort by SemVer precedence`() {
        val ordered = listOf(
            "0.1.0-beta.1",
            "0.1.0-beta.2",
            "0.1.0-beta.10",
            "0.1.0",
            "0.1.1-beta.1",
            "0.2.0",
            "0.10.0",
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-rc.1",
            "1.0.0",
        )

        ordered.shuffled().map(::v).sorted() shouldBe ordered.map(::v)
        ordered.zipWithNext().forEach { (lower, higher) -> v(higher) shouldBeGreaterThan v(lower) }
    }

    @Test
    fun `numeric components compare as numbers, not per digit`() {
        v("0.21.2") shouldBeGreaterThan v("0.20.5")
        v("1.0.0") shouldBeGreaterThan v("0.99.99")
    }

    @Test
    fun `equal versions are not newer`() {
        v("0.1.0-beta.1").compareTo(v("0.1.0-beta.1")) shouldBe 0
        v("0.1.0").compareTo(v("v0.1.0")) shouldBe 0
        (v("0.1.0") > v("0.1.0")) shouldBe false
    }

    @Test
    fun `leading v and build metadata are accepted`() {
        SemVer.parse("v0.1.0-beta.1") shouldBe SemVer(0, 1, 0, listOf("beta", "1"))
        SemVer.parse("0.1.0") shouldBe SemVer(0, 1, 0)
        SemVer.parse("v1.2.3+build.5")!!.compareTo(v("1.2.3")) shouldBe 0
    }

    @Test
    fun `prerelease flag follows the suffix`() {
        v("0.1.0-beta.1").isPrerelease shouldBe true
        v("v0.1.0").isPrerelease shouldBe false
    }

    @Test
    fun `non SemVer tags are rejected`() {
        listOf("r1234", "0.1", "0.1.0.1", "v01.0.0", "0.1.0-", "0.1.0-beta..1", "0.1.0-01", "").forEach {
            SemVer.parse(it) shouldBe null
        }
    }
}
