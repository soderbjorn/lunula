/*
 * CalmCssVarsTest.kt (jsTest)
 *
 * Pins the derived CSS custom properties [toCssVarMap] emits for the style
 * settings (LNA-19) by their exact names, because the per-setting stylesheet
 * blocks (Sheet, Blended, Tone, Soft) read them by name:
 * `--t-accent-soft-solid`, `--t-text-dim-legible`, `--t-accent-soft-text`,
 * `--t-window-ring` and `--t-window-active`. Also checks each carries the
 * opaque resolved value, that the old `--t-clean-*` names are gone, and that
 * the soft lift (`--dt-soft-lift`) is tuned per light/dark side by
 * [softLiftTuningVars].
 */
package se.soderbjorn.lunula.web

import se.soderbjorn.lunula.core.argbToHex
import se.soderbjorn.lunula.core.builtinTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalmCssVarsTest {

    @Test
    fun theCalmTokensAreEmittedUnderTheirContractNames() {
        val resolved = builtinTheme("Lunamux Light")!!.resolve()
        val vars = resolved.toCssVarMap()
        assertEquals(argbToHex(resolved.accentSoftSolid), vars["--t-accent-soft-solid"])
        assertEquals(argbToHex(resolved.legibleTextDim), vars["--t-text-dim-legible"])
        assertEquals(argbToHex(resolved.accentSoftText), vars["--t-accent-soft-text"])
        assertEquals(argbToHex(resolved.windowRing), vars["--t-window-ring"])
        assertEquals(argbToHex(resolved.windowActive), vars["--t-window-active"])
    }

    @Test
    fun theOldCleanNamesAreNoLongerEmitted() {
        val vars = builtinTheme("Lunamux Dark")!!.resolve().toCssVarMap()
        assertTrue(vars.keys.none { it.startsWith("--t-clean-") }, vars.keys.filter { "clean" in it }.toString())
    }

    @Test
    fun theActiveWindowFillIsLiftedForAnUndeclaredDarkTheme() {
        val theme = builtinTheme("Lunamux Classic Dark")!!
        val vars = theme.resolve().toCssVarMap()
        assertTrue(vars["--t-window-active"] != theme.surface, "the active fill must be lifted off surface")
        assertEquals(theme.effectiveWindowActive, vars["--t-window-active"])
        // A theme that declares its idle fill (the Calm ones) keeps surface.
        val calm = se.soderbjorn.lunula.core.builtinThemes.first { it.windowIdle != null && it.isDarkToned }
        assertEquals(calm.surface, calm.resolve().toCssVarMap()["--t-window-active"])
    }

    @Test
    fun theFocusedWindowRingIsEmittedForACalmTheme() {
        assertEquals("#d5d5d1", builtinTheme("Lunicle Calm Light")!!.resolve().toCssVarMap()["--t-window-ring"])
    }

    @Test
    fun theSoftLiftIsStrongerOnDark() {
        val light = softLiftTuningVars(isDark = false)["--dt-soft-lift"]!!
        val dark = softLiftTuningVars(isDark = true)["--dt-soft-lift"]!!
        assertTrue("0.1)" in light && "0.05)" in light, light)
        assertTrue("0.35)" in dark, dark)
        val el = kotlinx.browser.document.createElement("div") as org.w3c.dom.HTMLElement
        applyColorScheme(el, isDark = true)
        assertEquals(dark, el.style.getPropertyValue("--dt-soft-lift").trim())
        assertEquals("", el.style.getPropertyValue("--dt-clean-lift"), "the old name must not be written")
        applyColorScheme(el, isDark = false)
        assertEquals(light, el.style.getPropertyValue("--dt-soft-lift").trim())
    }

    @Test
    fun aCalmThemesDeclaredWashIsEmittedVerbatim() {
        val vars = builtinTheme("Moss Calm Light")!!.resolve().toCssVarMap()
        assertEquals("#e4ece6", vars["--t-accent-soft-solid"])
        assertEquals("#e4ece6", vars["--t-accent-soft"], "an opaque override replaces the translucent wash")
        assertEquals("#62686d", vars["--t-text-dim-legible"], "a passing textDim is emitted unchanged")
        assertEquals("#1f4d38", vars["--t-accent-soft-text"], "a passing accentText is emitted unchanged")
    }
}
