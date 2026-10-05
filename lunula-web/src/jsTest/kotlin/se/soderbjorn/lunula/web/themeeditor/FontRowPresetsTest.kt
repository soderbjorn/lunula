/*
 * FontRowPresetsTest.kt (jsTest)
 *
 * Which fonts each Settings sidebar font row offers ([fontRowChoices]): one
 * alphabetical list of available presets and installed families, whatever
 * their source. The Monospaced row lists monospaced faces only; every other
 * row lists every font (LNA-15). The legacy "System Default" / "System Mono"
 * presets are no longer offered but still resolve, so a stored pick of them
 * keeps working.
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
        val row = fontRowChoices(FontKind.Mono, fontPresets, everyPreset, locals)
        val monoKeys = fontPresets.filter { it.kind == FontKind.Mono && it.key != "system" }.map { it.key }
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
    fun the_system_presets_are_not_offered_but_still_resolve() {
        val row = fontRowChoices(FontKind.Proportional, fontPresets, everyPreset, emptyList()).map { it.key }
        assertFalse("system" in row)
        assertFalse("systemProp" in row)
        assertTrue(resolveProportionalFontFamilyCss("systemProp").startsWith("system-ui"))
        assertTrue(resolveFontFamilyCss("system").startsWith("ui-monospace"))
        assertEquals("System Default", fontLabelFor("systemProp"))
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
