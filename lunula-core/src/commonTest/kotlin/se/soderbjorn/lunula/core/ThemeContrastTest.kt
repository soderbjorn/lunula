/* ThemeContrastTest.kt
 * The contrast lint over the role-split tokens.
 *
 * Five review rounds on the Framna theme were all instances of one bug: a
 * colour chosen as a FIELD being read as TYPE, or vice versa. The role split
 * (`…On` / `…Text` in [Theme]) removes the ambiguity; this file removes the
 * regression, by asserting the pairing holds for every built-in rather than for
 * the one theme someone happened to look at.
 *
 * The two tests are deliberately scoped differently, and the difference is not
 * an oversight:
 *
 *  - [onFillForegroundsClearAA] runs over ALL built-ins, because every theme
 *    has an effective `…On` value whether or not it declared one — the fallback
 *    picks black or white by luminance, so the assertion is meaningful
 *    everywhere and holds by construction.
 *  - [declaredTextTokensClearAAOnEverySurface] runs only over themes that
 *    DECLARE a `…Text` token. An undeclared one falls back to its fill, which
 *    is the pre-split behaviour: asserting AA there would not be a lint, it
 *    would be a demand that 73 existing themes change appearance to satisfy a
 *    rule written after them. The lint applies to what a theme claims, and a
 *    theme claims a text role by declaring it.
 *
 * @see contrastRatio
 * @see Theme.accentOn
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG 2.x AA floor for body text. */
private const val AA: Double = 4.5

/** Formats a ratio to two decimals without depending on a platform formatter. */
private fun ratio(value: Double): String {
    val hundredths = (value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

class ThemeContrastTest {

    /**
     * Every foreground declared for a solid fill clears AA against that fill.
     *
     * This is the assertion the token split makes cheap: because a fill and the
     * type that lands on it are now declared together, the check needs no
     * knowledge of which surfaces a renderer happens to combine — the pair
     * itself carries the answer.
     */
    @Test
    fun onFillForegroundsClearAA() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val pairs = listOf(
                Triple("accentOn", t.effectiveAccentOn, t.accent),
                Triple("warnOn", t.effectiveWarnOn, t.warn),
                Triple("dangerOn", t.effectiveDangerOn, t.danger),
                Triple("addOn", t.effectiveAddOn, t.add),
                Triple("chromeAccentOn", t.effectiveChromeAccentOn, t.effectiveChromeAccent),
            )
            for ((name, fg, fillHex) in pairs) {
                val r = contrastRatio(fg, hexToArgb(fillHex))
                if (r < AA) failures += "${t.name}: $name on $fillHex is ${ratio(r)}:1"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "on-fill foregrounds below ${AA}:1:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Every explicitly declared `…Text` token clears AA against each ordinary
     * surface it can land on — [Theme.bg], [Theme.surface] and
     * [Theme.surfaceAlt] for the content zone, plus the chrome background for
     * the chrome one.
     *
     * `surfaceAlt` is the surface worth naming: it is where code blocks, wells
     * and gutters sit, it is usually the lowest-contrast of the three, and it
     * is the one nobody checks by eye.
     */
    @Test
    fun declaredTextTokensClearAAOnEverySurface() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val contentSurfaces = listOf("bg" to t.bg, "surface" to t.surface, "surfaceAlt" to t.surfaceAlt)
            val declared = listOf(
                Triple("accentText", t.accentText, contentSurfaces),
                Triple("warnText", t.warnText, contentSurfaces),
                Triple("dangerText", t.dangerText, contentSurfaces),
                Triple("chromeAccentText", t.chromeAccentText,
                    listOf("chromeBg" to t.effectiveChromeBg)),
            )
            for ((name, hex, surfaces) in declared) {
                val fg = hex ?: continue
                for ((surfaceName, surfaceHex) in surfaces) {
                    val r = contrastRatio(hexToArgb(fg), hexToArgb(surfaceHex))
                    if (r < AA) {
                        failures += "${t.name}: $name ($fg) on $surfaceName ($surfaceHex) is ${ratio(r)}:1"
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "declared text-role tokens below ${AA}:1:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * A theme that paints a *saturated* chrome must not be left reading its
     * dim chrome tone off the base zone.
     *
     * [Theme.effectiveChromeTextDim] derives the value in exactly that case, so
     * this pins the property the derivation exists to guarantee rather than the
     * derivation itself: whatever a split-chrome theme's dim tone ends up
     * being, it has to be legible on the chrome it actually sits on. 3:1 is the
     * large-text/secondary floor — these are labels, counts and timestamps, not
     * body copy.
     */
    @Test
    fun chromeDimToneIsLegibleOnItsOwnChrome() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            if (t.chromeBg == null) continue
            val r = contrastRatio(hexToArgb(t.effectiveChromeTextDim), hexToArgb(t.effectiveChromeBg))
            if (r < 3.0) failures += "${t.name}: chromeTextDim is ${ratio(r)}:1 on ${t.effectiveChromeBg}"
        }
        assertTrue(
            failures.isEmpty(),
            "chrome dim tones below 3.0:1 on their own chrome:\n" + failures.joinToString("\n"),
        )
    }
}
