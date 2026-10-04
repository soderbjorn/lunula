/*
 * DeclaredFontPresetsTest.kt (jsTest)
 *
 * The built-in presets for fonts only some apps ship — Instrument Sans
 * (proportional) and Unbounded (display only) — are offered exactly where
 * the page declares the family through `@font-face` (or it is installed).
 *
 * These tests pin:
 *   - both presets are built in, with the kinds the Settings sidebar rows
 *     order on (Unbounded is Display: every row but Monospaced lists it);
 *   - a family the page declares with `FontFace` makes its preset available,
 *     even before the face has loaded;
 *   - a family nobody declares (and that is not installed) stays hidden;
 *   - a preset registered after the first detection is still checked.
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlinx.browser.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclaredFontPresetsTest {

    /** Declares [family] on the page through the CSS Font Loading API, without loading it. */
    private fun declare(family: String) {
        val face = js("new FontFace(family, 'url(data:font/woff2;base64,AAAA)')")
        document.asDynamic().fonts.add(face)
    }

    @Test
    fun instrument_sans_and_unbounded_are_built_in() {
        val instrument = fontPresets.single { it.key == "instrumentSans" }
        assertEquals(FontKind.Proportional, instrument.kind)
        assertEquals("Instrument Sans", instrument.detectFamily)
        assertFalse(instrument.bundled, "offered only where the app ships the files")

        val unbounded = fontPresets.single { it.key == "unbounded" }
        assertEquals(FontKind.Display, unbounded.kind)
        assertEquals("Unbounded", unbounded.detectFamily)
        assertEquals("'Unbounded', system-ui, sans-serif", resolveProportionalFontFamilyCss("unbounded"))
    }

    @Test
    fun a_declared_family_makes_its_preset_available() {
        registerFontPresets(listOf(
            FontPreset("declaredTest", "Declared Test", "'Lunula Declared Test', sans-serif",
                "Lunula Declared Test", kind = FontKind.Display),
        ))
        assertFalse("declaredTest" in detectInstalledFonts(), "not declared, not installed")

        declare("Lunula Declared Test")
        assertTrue("declaredTest" in detectInstalledFonts(), "declared through @font-face")
    }

    @Test
    fun a_preset_registered_after_detection_is_still_checked() {
        detectInstalledFonts()
        registerFontPresets(listOf(
            FontPreset("lateTest", "Late Test", "'Lunula Late Test', sans-serif",
                "Lunula Late Test", kind = FontKind.Proportional),
            FontPreset("lateAlwaysTest", "Late Always", "'Late Always', sans-serif",
                null, kind = FontKind.Proportional),
        ))
        declare("Lunula Late Test")
        val available = detectInstalledFonts()
        assertTrue("lateTest" in available)
        assertTrue("lateAlwaysTest" in available)
    }
}
