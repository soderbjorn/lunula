/*
 * FontRowPresetsTest.kt (jsTest)
 *
 * Which presets each Settings sidebar font row offers (LNA-15): the
 * Monospaced row lists monospaced faces only; every other row — Proportional,
 * Sidebar, Tab bar, Window title and Display, all built with
 * `FontKind.Proportional` — lists every preset, Display faces such as
 * Unbounded included.
 *
 * These tests pin:
 *   - the kinds each row offers ([offeredFontKinds]);
 *   - the order within a row: system default, own kind, Display, the rest.
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontRowPresetsTest {

    @Test
    fun the_monospaced_row_offers_only_monospaced_faces() {
        assertEquals(setOf(FontKind.Mono), offeredFontKinds(FontKind.Mono))
        val row = fontRowPresets(FontKind.Mono, fontPresets)
        assertTrue(row.all { it.kind == FontKind.Mono })
        assertEquals(fontPresets.count { it.kind == FontKind.Mono }, row.size)
        assertEquals("system", row.first().key)
    }

    @Test
    fun every_other_row_offers_every_preset() {
        assertEquals(FontKind.entries.toSet(), offeredFontKinds(FontKind.Proportional))
        assertEquals(FontKind.entries.toSet(), offeredFontKinds(FontKind.Display))
        val row = fontRowPresets(FontKind.Proportional, fontPresets)
        assertEquals(fontPresets.map { it.key }.toSet(), row.map { it.key }.toSet())
        assertTrue(row.any { it.key == "unbounded" }, "Display faces are offered for prose and chrome")
    }

    @Test
    fun a_row_lists_system_default_then_own_kind_then_display_then_the_rest() {
        val row = fontRowPresets(FontKind.Proportional, fontPresets)
        assertEquals("systemProp", row.first().key)
        val kinds = row.drop(1).map { it.kind }
        val expected = kinds.sortedBy {
            when (it) { FontKind.Proportional -> 0; FontKind.Display -> 1; FontKind.Mono -> 2 }
        }
        assertEquals(expected, kinds)
        val proportional = fontPresets.filter { it.kind == FontKind.Proportional && it.key != "systemProp" }
        assertEquals(proportional.map { it.key }, row.drop(1).take(proportional.size).map { it.key },
            "declared order kept within a kind")
    }
}
