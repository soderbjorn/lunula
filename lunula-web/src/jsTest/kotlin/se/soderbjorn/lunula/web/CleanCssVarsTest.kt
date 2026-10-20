/*
 * CleanCssVarsTest.kt (jsTest)
 *
 * Pins the three Clean-only CSS custom properties [toCssVarMap] emits (LNA-19)
 * by their exact names, because the Clean stylesheet block (LNA-20) reads
 * them by name: `--t-accent-soft-solid`, `--t-clean-text-dim` and
 * `--t-clean-accent-text`. Also checks each carries the opaque resolved value.
 */
package se.soderbjorn.lunula.web

import se.soderbjorn.lunula.core.argbToHex
import se.soderbjorn.lunula.core.builtinTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class CleanCssVarsTest {

    @Test
    fun theCleanTokensAreEmittedUnderTheirContractNames() {
        val resolved = builtinTheme("Lunamux Light")!!.resolve()
        val vars = resolved.toCssVarMap()
        assertEquals(argbToHex(resolved.accentSoftSolid), vars["--t-accent-soft-solid"])
        assertEquals(argbToHex(resolved.cleanTextDim), vars["--t-clean-text-dim"])
        assertEquals(argbToHex(resolved.cleanAccentText), vars["--t-clean-accent-text"])
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
