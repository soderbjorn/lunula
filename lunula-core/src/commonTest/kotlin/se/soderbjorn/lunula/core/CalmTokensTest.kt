/* CalmTokensTest.kt
 * Covers the three legible derived tokens the soft / toned style settings read
 * and the defaults ignore (LNA-19): [ResolvedTheme.accentSoftSolid],
 * [ResolvedTheme.legibleTextDim] and [ResolvedTheme.accentSoftText]. The dim
 * tone must clear AA on every backdrop it is painted on: the idle and active
 * window fills ([ResolvedTheme.windowIdle], [ResolvedTheme.windowActive]), the
 * ground and the surface.
 *
 * LNA-18 found 69 built-ins whose textDim falls short of AA on windowIdle and
 * 51 whose accentText falls short on the accent wash. Those failures are kept,
 * allowlisted, in [ThemeContrastTest] — they describe the Classic palettes as
 * drawn. This file asserts the opposite for the Calm tokens: across EVERY
 * built-in, with no allowlist, the derived value clears AA — and for a theme
 * that already passed, it is the original colour exactly.
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG AA for body text. */
private const val AA: Double = 4.5

/** Formats a ratio to two decimals without depending on a platform formatter. */
private fun ratio(value: Double): String {
    val hundredths = (value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

class CalmTokensTest {

    @Test
    fun legibleTextDimClearsAAOnWindowIdleWindowActiveBgAndSurfaceForEveryBuiltin() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val r = t.resolve()
            val backdrops = listOf(
                "windowIdle" to r.windowIdle, "windowActive" to r.windowActive, "bg" to r.bg, "surface" to r.surface,
            )
            for ((name, backdrop) in backdrops) {
                val c = contrastRatio(r.legibleTextDim, backdrop)
                if (c < AA) failures += "${t.name}: legibleTextDim (${argbToHex(r.legibleTextDim)}) on $name is ${ratio(c)}:1"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun accentSoftTextClearsAAOnAccentSoftSolidForEveryBuiltin() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val r = t.resolve()
            val c = contrastRatio(r.accentSoftText, r.accentSoftSolid)
            if (c < AA) {
                failures += "${t.name}: accentSoftText (${argbToHex(r.accentSoftText)}) on " +
                    "accentSoftSolid (${argbToHex(r.accentSoftSolid)}) is ${ratio(c)}:1"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun legibleTextDimIsTextDimExactlyWhereTextDimAlreadyPasses() {
        var unchanged = 0
        for (t in builtinThemes) {
            val r = t.resolve()
            val passes = listOf(r.windowIdle, r.windowActive, r.bg, r.surface).all { contrastRatio(r.textDim, it) >= AA }
            if (passes) {
                assertEquals(r.textDim, r.legibleTextDim, "${t.name}: a passing textDim must not move")
                unchanged++
            } else {
                assertTrue(r.legibleTextDim != r.textDim, "${t.name}: a failing textDim must move")
            }
        }
        assertTrue(unchanged > 0, "fixture: some built-ins must already pass")
    }

    @Test
    fun accentSoftTextIsAccentTextExactlyWhereAccentTextAlreadyPasses() {
        var unchanged = 0
        for (t in builtinThemes) {
            val r = t.resolve()
            if (contrastRatio(r.accentText, r.accentSoftSolid) >= AA) {
                assertEquals(r.accentText, r.accentSoftText, "${t.name}: a passing accentText must not move")
                unchanged++
            } else {
                assertTrue(r.accentSoftText != r.accentText, "${t.name}: a failing accentText must move")
            }
        }
        assertTrue(unchanged > 0, "fixture: some built-ins must already pass")
    }

    @Test
    fun accentSoftSolidIsTheWashCompositedOverSurfaceUnlessDeclared() {
        for (t in builtinThemes) {
            val r = t.resolve()
            val expected = t.accentSoft?.let(::hexToArgb) ?: compositeOver(r.accentSoft, r.surface)
            assertEquals(expected, r.accentSoftSolid, t.name)
            assertEquals(0xFFL, (r.accentSoftSolid ushr 24) and 0xFF, "${t.name}: accentSoftSolid must be opaque")
        }
    }

    @Test
    fun legibleTokensAreOpaque() {
        for (t in builtinThemes) {
            val r = t.resolve()
            assertEquals(0xFFL, (r.legibleTextDim ushr 24) and 0xFF, t.name)
            assertEquals(0xFFL, (r.accentSoftText ushr 24) and 0xFF, t.name)
        }
    }

    @Test
    fun aFailingDimToneMovesTowardTextAndNoFurtherThanNeeded() {
        // A custom theme with a dim tone that is far too faint: the result is
        // on the textDim → text line, passes, and the previous step would not.
        val t = builtinTheme("Lunamux Light")!!.copy(
            name = "Faint", bg = "#ffffff", surface = "#ffffff", windowIdle = "#f2f2f2",
            text = "#111111", textDim = "#cccccc",
        )
        val r = t.resolve()
        val backdrops = listOf(r.windowIdle, r.windowActive, r.bg, r.surface)
        assertTrue(backdrops.all { contrastRatio(r.legibleTextDim, it) >= AA })
        val step = Theme.LEGIBLE_NUDGE_STEP
        val k = (1..kotlin.math.round(1 / step).toInt()).first { mix(r.text, r.textDim, it * step) == r.legibleTextDim }
        val previous = mix(r.text, r.textDim, (k - 1) * step)
        assertTrue(backdrops.any { contrastRatio(previous, it) < AA }, "the step before must still fail")
    }

    @Test
    fun nudgeForContrastReturnsTheColourItselfWhenItAlreadyPasses() {
        val black = 0xFF000000L
        val white = 0xFFFFFFFFL
        assertEquals(black, nudgeForContrast(black, 0xFF777777L, listOf(white), AA, 0.02))
        // And falls through to the target when nothing on the way passes.
        assertEquals(black, nudgeForContrast(white, black, listOf(white), 21.0, 0.25))
    }

    @Test
    fun aLiftedActiveWindowIsABackdropOfTheDimTone() {
        // A dark theme whose surface sits on its ground, so the active fill is
        // lifted toward text: the dim tone must still clear AA there too.
        val t = builtinTheme("Lunamux Classic Dark")!!.copy(name = "Lifted", textDim = "#5a6a60")
        assertTrue(t.windowIdle == null)
        val r = t.resolve()
        assertTrue(r.windowActive != hexToArgb(t.surface), "fixture: the active fill must be lifted")
        assertTrue(contrastRatio(r.legibleTextDim, r.windowActive) >= AA)
    }
}
