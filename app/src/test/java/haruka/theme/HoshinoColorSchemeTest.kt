package haruka.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.pow

class HoshinoColorSchemeTest {

    @Test
    fun `light scheme text pairs meet WCAG AA`() = assertAa(HoshinoColorScheme.lightScheme)

    @Test
    fun `dark scheme text pairs meet WCAG AA`() = assertAa(HoshinoColorScheme.darkScheme)

    @Test
    fun `AMOLED scheme text pairs meet WCAG AA`() = assertAa(
        HoshinoColorScheme.getColorScheme(isDark = true, isAmoled = true, overrideDarkSurfaceContainers = true),
    )

    @Test
    fun `contrast matches WCAG reference values`() {
        contrast(Color.Black, Color.White) shouldBe (21.0 plusOrMinus 0.01)
        contrast(Color(0xFF777777), Color.White) shouldBe (4.48 plusOrMinus 0.01)
    }

    private fun assertAa(scheme: ColorScheme) = assertSoftly {
        listOf(
            Triple("primary", scheme.primary, scheme.onPrimary),
            Triple("secondary", scheme.secondary, scheme.onSecondary),
            Triple("tertiary", scheme.tertiary, scheme.onTertiary),
            Triple("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer),
            Triple("surface", scheme.surface, scheme.onSurface),
            Triple("surfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant),
            Triple("error", scheme.error, scheme.onError),
        ).forEach { (name, background, foreground) ->
            withClue(name) { contrast(background, foreground) shouldBeGreaterThanOrEqual 4.5 }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // WCAG 2.x relative luminance of an sRGB colour.
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
