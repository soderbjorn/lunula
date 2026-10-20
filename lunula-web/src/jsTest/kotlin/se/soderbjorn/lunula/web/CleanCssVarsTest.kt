/*
 * CleanCssVarsTest.kt (jsTest)
 *
 * Pins the three Clean-only CSS custom properties [toCssVarMap] emits (LNA-19)
 * by their exact names, because the Clean stylesheet block (LNA-20) reads
 * them by name: `--t-accent-soft-solid`, `--t-clean-text-dim`,
 * `--t-clean-accent-text` and `--t-window-ring`. Also checks each carries the
 * opaque resolved value, and that the Clean lift (`--dt-clean-lift`) is tuned
 * per light/dark side by [cleanTuningVars].
 */
package se.soderbjorn.lunula.web

import se.soderbjorn.lunula.core.argbToHex
import se.soderbjorn.lunula.core.builtinTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanCssVarsTest {

    @Test
    fun theCleanTokensAreEmittedUnderTheirContractNames() {
        val resolved = builtinTheme("Lunamux Light")!!.resolve()
        val vars = resolved.toCssVarMap()
        assertEquals(argbToHex(resolved.accentSoftSolid), vars["--t-accent-soft-solid"])
        assertEquals(argbToHex(resolved.cleanTextDim), vars["--t-clean-text-dim"])
        assertEquals(argbToHex(resolved.cleanAccentText), vars["--t-clean-accent-text"])
        assertEquals(argbToHex(resolved.windowRing), vars["--t-window-ring"])
    }

    @Test
    fun theFocusedWindowRingIsEmittedForACalmTheme() {
        assertEquals("#d5d5d1", builtinTheme("Lunicle Calm Light")!!.resolve().toCssVarMap()["--t-window-ring"])
    }

    @Test
    fun theCleanLiftIsStrongerOnDark() {
        val light = cleanTuningVars(isDark = false)["--dt-clean-lift"]!!
        val dark = cleanTuningVars(isDark = true)["--dt-clean-lift"]!!
        assertTrue("0.1)" in light && "0.05)" in light, light)
        assertTrue("0.35)" in dark, dark)
        val el = kotlinx.browser.document.createElement("div") as org.w3c.dom.HTMLElement
        applyColorScheme(el, isDark = true)
        assertEquals(dark, el.style.getPropertyValue("--dt-clean-lift").trim())
    }

    @Test
    fun aCalmThemesDeclaredWashIsEmittedVerbatim() {
        val vars = builtinTheme("Moss Calm Light")!!.resolve().toCssVarMap()
        assertEquals("#e4ece6", vars["--t-accent-soft-solid"])
        assertEquals("#e4ece6", vars["--t-accent-soft"], "an opaque override replaces the translucent wash")
        assertEquals("#62686d", vars["--t-clean-text-dim"], "a passing textDim is emitted unchanged")
        assertEquals("#1f4d38", vars["--t-clean-accent-text"], "a passing accentText is emitted unchanged")
    }
}
