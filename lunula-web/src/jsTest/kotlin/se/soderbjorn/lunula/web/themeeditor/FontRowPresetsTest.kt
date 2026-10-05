/*
 * FontRowPresetsTest.kt (jsTest)
 *
 * Which fonts each Settings sidebar font row offers ([fontRowChoices]): one
 * alphabetical list of available presets and installed families, whatever
 * their source. The Monospaced row lists monospaced faces only; every other
 * row lists every font (LNA-15). The system presets are listed under the
 * name of the font they paint (SF Pro / SF Mono on a Mac) — the only way to
 * reach the macOS system fonts, which the installed-font list hides.
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontRowPresetsTest {

    private val everyPreset = fontPresets.map { it.key }.toSet()

    @Test
    fun the_monospaced_row_offers_only_monospaced_faces() {
        assertEquals(setOf(FontKind.Mono), offeredFontKinds(FontKind.Mono))
        val locals = listOf(LocalFontFamily("Andale Mono", isMono = true), LocalFontFamily("Avenir", isMono = false))
        val row = fontRowChoices(FontKind.Mono, fontPresets, everyPreset, locals, platform = "Linux x86_64")
        val monoKeys = fontPresets.filter { it.kind == FontKind.Mono }.map { it.key }
        assertEquals((monoKeys + localFontKey("Andale Mono")).toSet(), row.map { it.key }.toSet())
    }

    @Test
    fun every_other_row_offers_every_font() {
        assertEquals(FontKind.entries.toSet(), offeredFontKinds(FontKind.Proportional))
        val locals = listOf(LocalFontFamily("Andale Mono", isMono = true), LocalFontFamily("Avenir", isMono = false))
        val row = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset, locals).map { it.key }
        assertTrue("unbounded" in row, "Display faces are offered for prose and chrome")
        assertTrue("jetbrainsMono" in row)
        assertTrue(localFontKey("Avenir") in row)
        assertTrue(localFontKey("Andale Mono") in row)
    }

    @Test
    fun the_system_presets_are_named_for_the_font_they_paint() {
        val mac = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset, emptyList(), platform = "MacIntel")
        assertEquals("SF Pro", mac.single { it.key == "systemProp" }.label)
        assertEquals("SF Mono", mac.single { it.key == "system" }.label)
        assertFalse(mac.any { it.key == "sfPro" }, "the system entry stands in for the installed-only SF Pro preset")
        assertFalse(mac.any { it.key == "sfMono" })
        val win = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset,
            listOf(LocalFontFamily("Segoe UI", isMono = false)), platform = "Win32")
        assertEquals("Segoe UI", win.single { it.key == "systemProp" }.label)
        assertFalse(win.any { it.key == localFontKey("Segoe UI") })
        assertEquals("System UI font", systemFontName("systemProp", platform = "Linux x86_64"))
        assertTrue(resolveProportionalFontFamilyCss("systemProp").startsWith("system-ui"))
        assertTrue(resolveFontFamilyCss("system").startsWith("ui-monospace"))
    }

    @Test
    fun a_row_is_one_alphabetical_list() {
        val locals = listOf(LocalFontFamily("avenir", isMono = false), LocalFontFamily("Zapfino", isMono = false))
        val labels = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset, locals).map { it.label }
        assertEquals(labels.sortedBy { it.lowercase() }, labels)
    }

    @Test
    fun a_preset_wins_over_the_same_installed_family() {
        val locals = listOf(LocalFontFamily("Menlo", isMono = true), LocalFontFamily("georgia", isMono = false))
        val row = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset, locals).map { it.key }
        assertTrue("menlo" in row)
        assertTrue("georgia" in row)
        assertFalse(localFontKey("Menlo") in row)
        assertFalse(localFontKey("georgia") in row)
    }

    @Test
    fun unavailable_presets_are_left_out() {
        val row = fontRowChoices(FontKind.Mono, fontPresets, setOf("menlo"), emptyList()).map { it.key }
        assertEquals(listOf("menlo"), row)
    }

    @Test
    fun an_installed_family_key_resolves_to_that_family() {
        val key = localFontKey("Avenir Next")
        assertEquals("Avenir Next", localFamilyOf(key))
        assertEquals("Avenir Next", fontLabelFor(key))
        assertEquals("'Avenir Next', system-ui, sans-serif", resolveProportionalFontFamilyCss(key))
        assertEquals("'Avenir Next', ui-monospace, monospace", resolveFontFamilyCss(key))
        assertEquals("'Bob\\'s Font', system-ui, sans-serif", resolveProportionalFontFamilyCss(localFontKey("Bob's Font")))
        assertEquals(null, localFamilyOf("menlo"))
        assertEquals(null, localFamilyOf("local:"))
    }
}
