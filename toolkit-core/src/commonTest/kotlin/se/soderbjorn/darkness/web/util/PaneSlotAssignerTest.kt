/**
 * Tests for [PaneSlotAssigner] (sticky 1-based slot logic) and
 * [encircledIndexGlyph] (the digit/letter mapping the toolkit renders
 * as a pane-index badge). Both are pure commonMain so the JVM test
 * task is the cheapest place to verify the contract.
 */
package se.soderbjorn.darkness.web.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncircledIndexGlyphTest {

    @Test
    fun digits_one_through_nine_use_circled_digit_glyphs() {
        assertEquals("①", encircledIndexGlyph(1))
        assertEquals("②", encircledIndexGlyph(2))
        assertEquals("⑨", encircledIndexGlyph(9))
    }

    @Test
    fun ten_through_thirty_five_use_circled_letters() {
        assertEquals("Ⓐ", encircledIndexGlyph(10))
        assertEquals("Ⓑ", encircledIndexGlyph(11))
        assertEquals("Ⓩ", encircledIndexGlyph(35))
    }

    @Test
    fun out_of_range_returns_null() {
        assertNull(encircledIndexGlyph(0))
        assertNull(encircledIndexGlyph(-1))
        assertNull(encircledIndexGlyph(36))
        assertNull(encircledIndexGlyph(100))
    }
}

class PaneSlotAssignerTest {

    @Test
    fun assign_returns_lowest_free_slot_starting_at_one() {
        val a = PaneSlotAssigner()
        assertEquals(1, a.assign("p1"))
        assertEquals(2, a.assign("p2"))
        assertEquals(3, a.assign("p3"))
    }

    @Test
    fun assign_is_idempotent_for_same_pane_id() {
        val a = PaneSlotAssigner()
        val first = a.assign("p1")
        val second = a.assign("p1")
        assertEquals(first, second)
        // Subsequent unique pane still gets the next free slot.
        assertEquals(2, a.assign("p2"))
    }

    @Test
    fun release_frees_slot_for_next_assignment() {
        val a = PaneSlotAssigner()
        a.assign("p1")  // 1
        a.assign("p2")  // 2
        a.assign("p3")  // 3
        a.release("p2")
        // The next new pane reclaims the lowest free slot — slot 2.
        assertEquals(2, a.assign("p4"))
        // The other panes keep their original slots — sticky.
        assertEquals(1, a.indexOf("p1"))
        assertEquals(3, a.indexOf("p3"))
    }

    @Test
    fun indexOf_returns_null_for_unassigned_pane() {
        val a = PaneSlotAssigner()
        assertNull(a.indexOf("never-seen"))
        a.assign("p1")
        a.release("p1")
        assertNull(a.indexOf("p1"))
    }

    @Test
    fun assign_returns_null_when_all_slots_taken() {
        val a = PaneSlotAssigner()
        repeat(35) { a.assign("p${it + 1}") }
        // 36th pane gets nothing — no badge, no duplicate.
        assertNull(a.assign("p36"))
        assertNull(a.indexOf("p36"))
        // The originals keep their slots — sticky and unique.
        assertEquals(1, a.indexOf("p1"))
        assertEquals(35, a.indexOf("p35"))
    }

    @Test
    fun unassigned_pane_picks_up_a_slot_once_one_frees() {
        val a = PaneSlotAssigner()
        repeat(35) { a.assign("p${it + 1}") }
        a.assign("waiting")  // null on first try
        assertNull(a.indexOf("waiting"))
        // A holder closes — the freed slot is now claimable by the next
        // assign() / syncTo() call. The waiting pane doesn't auto-claim.
        a.release("p17")
        assertEquals(17, a.assign("waiting"))
    }

    @Test
    fun syncTo_assigns_newcomers_in_iteration_order_on_first_call() {
        val a = PaneSlotAssigner()
        a.syncTo(listOf("a", "b", "c"))
        assertEquals(1, a.indexOf("a"))
        assertEquals(2, a.indexOf("b"))
        assertEquals(3, a.indexOf("c"))
    }

    @Test
    fun syncTo_releases_panes_no_longer_in_the_live_set() {
        val a = PaneSlotAssigner()
        a.syncTo(listOf("a", "b", "c"))
        a.syncTo(listOf("a", "c"))  // b is gone
        assertNull(a.indexOf("b"))
        assertEquals(1, a.indexOf("a"))  // sticky
        assertEquals(3, a.indexOf("c"))  // sticky
        // Newcomer reclaims the lowest free slot — b's old 2.
        a.syncTo(listOf("a", "c", "d"))
        assertEquals(2, a.indexOf("d"))
    }

    @Test
    fun syncTo_is_idempotent_for_the_same_set() {
        val a = PaneSlotAssigner()
        a.syncTo(listOf("a", "b"))
        a.syncTo(listOf("a", "b"))
        assertEquals(1, a.indexOf("a"))
        assertEquals(2, a.indexOf("b"))
        // No surprise reassignment.
        assertTrue(a.indexOf("a") == 1 && a.indexOf("b") == 2)
    }

    @Test
    fun syncTo_leaves_over_cap_panes_unassigned_and_picks_them_up_on_a_later_release() {
        val a = PaneSlotAssigner()
        val firstWave = (1..35).map { "p$it" }
        a.syncTo(firstWave)
        // Add a 36th — at capacity, no slot.
        a.syncTo(firstWave + "p36")
        assertNull(a.indexOf("p36"))
        // Drop one of the original 35; next sync should give p36 the freed slot.
        a.syncTo((firstWave - "p20") + "p36")
        assertEquals(20, a.indexOf("p36"))
    }

    @Test
    fun syncTo_releases_before_assigning_so_a_closing_pane_clears_the_way_at_capacity() {
        val a = PaneSlotAssigner()
        val full = (1..35).map { "p$it" }
        a.syncTo(full)
        // Same call: drop p10, add q1. Release runs first → slot 10 frees → q1 claims it.
        a.syncTo((full - "p10") + "q1")
        assertEquals(10, a.indexOf("q1"))
    }
}
