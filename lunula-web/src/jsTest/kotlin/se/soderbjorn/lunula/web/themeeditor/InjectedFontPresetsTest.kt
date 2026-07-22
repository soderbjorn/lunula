/*
 * InjectedFontPresetsTest.kt (jsTest)
 *
 * The runtime font-preset injection seam a consuming app fills to reach the
 * chrome with an arbitrary family — the toolkit half of Lunicle's LNL-110
 * "approach B". The seam is deliberately generic: the toolkit ships no company
 * family; the app registers one at runtime, and every family resolution must
 * then walk builtin ∪ injected rather than builtins alone.
 *
 * These tests pin the three properties that matter:
 *   - an injected key resolves to its stack (it would not, before);
 *   - re-registering a key replaces rather than duplicates it;
 *   - an unknown key still falls back to the system stack, unchanged.
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InjectedFontPresetsTest {

    @Test
    fun injected_preset_resolves_like_a_builtin() {
        registerFontPresets(
            listOf(
                FontPreset(
                    key = "brandTest1",
                    displayName = "Acme Sans",
                    cssStack = "'Acme Sans', system-ui, sans-serif",
                    detectFamily = null,
                    bundled = true,
                    kind = FontKind.Proportional,
                ),
            ),
        )

        assertTrue(allFontPresets().any { it.key == "brandTest1" }, "injected preset is in the merged set")
        assertEquals("'Acme Sans', system-ui, sans-serif", resolveProportionalFontFamilyCss("brandTest1"))
    }

    @Test
    fun re_registering_a_key_replaces_it() {
        registerFontPresets(
            listOf(FontPreset("brandTest2", "V1", "'V1', sans-serif", null, kind = FontKind.Proportional)),
        )
        registerFontPresets(
            listOf(FontPreset("brandTest2", "V2", "'V2', sans-serif", null, kind = FontKind.Proportional)),
        )
        assertEquals(1, allFontPresets().count { it.key == "brandTest2" }, "no duplicate")
        assertEquals("'V2', sans-serif", resolveProportionalFontFamilyCss("brandTest2"))
    }

    @Test
    fun unknown_key_still_falls_back_to_system() {
        val systemProp = resolveProportionalFontFamilyCss("systemProp")
        assertEquals(systemProp, resolveProportionalFontFamilyCss("no-such-key"))
    }
}
