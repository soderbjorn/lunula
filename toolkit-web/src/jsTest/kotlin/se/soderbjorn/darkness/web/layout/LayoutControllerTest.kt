/**
 * Tests for [LayoutController]: importance ordering (driven by user
 * reorder + create/remove), active-pane signal (decoupled from order),
 * preset transitions, and pure preset application.
 */
package se.soderbjorn.darkness.web.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class LayoutControllerOrderingTest {

    @Test
    fun record_create_appends_new_panes_at_tail() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", "a")
        controller.recordCreate("c", "b")
        assertEquals(listOf("a", "b", "c"), controller.paneOrder)
        assertEquals("a", controller.parentByPane["b"])
        assertEquals("b", controller.parentByPane["c"])
    }

    @Test
    fun record_create_with_null_parent_records_no_linkage() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        assertEquals(listOf("a", "b"), controller.paneOrder)
        assertTrue(controller.parentByPane.isEmpty())
    }

    @Test
    fun reorder_pane_moves_to_target_index() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        controller.recordCreate("c", null) // order: [a, b, c]

        controller.reorderPane("c", 0)
        assertEquals(listOf("c", "a", "b"), controller.paneOrder)

        controller.reorderPane("a", 2)
        assertEquals(listOf("c", "b", "a"), controller.paneOrder)

        controller.reorderPane("b", 1) // already at index 1 — no-op
        assertEquals(listOf("c", "b", "a"), controller.paneOrder)
    }

    @Test
    fun reorder_pane_clamps_target_index() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        controller.recordCreate("c", null) // [a, b, c]

        controller.reorderPane("a", 99) // clamps to last (size-1 after removal = 2)
        assertEquals(listOf("b", "c", "a"), controller.paneOrder)

        controller.reorderPane("a", -5) // clamps to 0
        assertEquals(listOf("a", "b", "c"), controller.paneOrder)
    }

    @Test
    fun set_active_does_not_change_pane_order() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        controller.recordCreate("c", null)

        controller.setActive("c")
        assertEquals("c", controller.activePaneId)
        assertEquals(listOf("a", "b", "c"), controller.paneOrder)

        controller.setActive("a")
        assertEquals("a", controller.activePaneId)
        assertEquals(listOf("a", "b", "c"), controller.paneOrder)
    }

    @Test
    fun set_active_is_idempotent_when_unchanged() {
        var changes = 0
        val controller = LayoutController(onChange = { changes++ })
        controller.setActive("a")
        val before = changes
        controller.setActive("a")
        assertEquals(before, changes, "no change when active pane unchanged")
    }

    @Test
    fun record_remove_drops_pane_and_clears_active_when_matched() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", "a")
        controller.recordCreate("c", "b")
        controller.setActive("b")

        controller.recordRemove("b")
        assertEquals(listOf("a", "c"), controller.paneOrder)
        assertNull(controller.activePaneId, "active cleared when its pane removed")
        assertEquals(null, controller.parentByPane["b"])
        // c's parent linkage is preserved (points at the now-closed b);
        // it's harmless and only used as a future tie-break hint.
        assertEquals("b", controller.parentByPane["c"])
    }

    @Test
    fun record_remove_keeps_active_when_other_pane_removed() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        controller.setActive("a")

        controller.recordRemove("b")
        assertEquals("a", controller.activePaneId)
    }

    @Test
    fun reset_replaces_state_atomically_and_clears_active() {
        val controller = LayoutController()
        controller.recordCreate("a", null)
        controller.recordCreate("b", "a")
        controller.setActive("a")
        controller.reset(listOf("x", "y", "z"))
        assertEquals(listOf("x", "y", "z"), controller.paneOrder)
        assertTrue(controller.parentByPane.isEmpty())
        assertNull(controller.activePaneId)
    }

    @Test
    fun on_change_fires_for_state_mutations() {
        var changes = 0
        val controller = LayoutController(onChange = { changes++ })
        controller.recordCreate("a", null)
        assertEquals(1, changes)
        controller.setActive("a")
        assertEquals(2, changes)
        controller.recordCreate("b", "a")
        assertEquals(3, changes)
        controller.reorderPane("b", 0)
        assertEquals(4, changes)
        controller.recordRemove("b")
        assertEquals(5, changes)
        controller.setPreset(LayoutPreset.Auto)
        assertEquals(6, changes)
        controller.markCustom()
        assertEquals(7, changes)
        controller.markCustom() // already Custom — no fire
        assertEquals(7, changes)
    }
}

class LayoutControllerPresetTest {

    @Test
    fun set_preset_transitions_active_preset_and_fires_onchange() {
        var changes = 0
        val controller = LayoutController(onChange = { changes++ })
        controller.setPreset(LayoutPreset.Auto)
        assertEquals(LayoutPreset.Auto, controller.activePreset)
        assertEquals(1, changes)
        controller.setPreset(LayoutPreset.Auto) // no-op
        assertEquals(1, changes)
    }

    @Test
    fun mark_custom_overrides_any_preset() {
        val controller = LayoutController(initialPreset = LayoutPreset.Auto)
        controller.markCustom()
        assertEquals(LayoutPreset.Custom, controller.activePreset)
    }

    @Test
    fun apply_preset_with_custom_returns_input_unchanged() {
        val controller = LayoutController(initialPreset = LayoutPreset.Custom)
        val panes = listOf(
            FloatingPaneSpec(id = "a", xPct = 0.1, yPct = 0.2, widthPct = 0.3, heightPct = 0.4),
            FloatingPaneSpec(id = "b", xPct = 0.5, yPct = 0.6, widthPct = 0.2, heightPct = 0.3),
        )
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        val result = controller.applyPresetToPanes(panes)
        assertEquals(panes, result, "Custom must not modify geometry")
    }

    @Test
    fun apply_auto_assigns_head_of_paneorder_to_primary_slot() {
        val controller = LayoutController(initialPreset = LayoutPreset.Auto)
        val panes = listOf(
            FloatingPaneSpec(id = "a", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "b", xPct = 0.0, yPct = 0.0),
        )
        controller.recordCreate("a", null)
        controller.recordCreate("b", null) // order: [a, b]
        controller.reorderPane("b", 0)     // order: [b, a]
        val result = controller.applyPresetToPanes(panes)

        val byId = result.associateBy { it.id }
        // Auto for n=2: slot 0 = (0,0,0.5,1.0), slot 1 = (0.5,0,0.5,1.0)
        assertEquals(0.0, byId["b"]!!.xPct, "head pane lands at slot 0 (left)")
        assertEquals(0.5, byId["a"]!!.xPct, "next pane lands at slot 1 (right)")
    }

    @Test
    fun set_active_does_not_affect_layout() {
        val controller = LayoutController(initialPreset = LayoutPreset.Auto)
        val panes = listOf(
            FloatingPaneSpec(id = "a", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "b", xPct = 0.0, yPct = 0.0),
        )
        controller.recordCreate("a", null)
        controller.recordCreate("b", null) // order: [a, b]
        controller.setActive("b")          // active=b, but order unchanged

        val result = controller.applyPresetToPanes(panes)
        val byId = result.associateBy { it.id }
        // 'a' remains at slot 0 because it's still head of paneOrder.
        assertEquals(0.0, byId["a"]!!.xPct, "active pane does NOT influence layout")
        assertEquals(0.5, byId["b"]!!.xPct)
    }

    @Test
    fun apply_preset_skips_minimised_panes() {
        val controller = LayoutController(initialPreset = LayoutPreset.Auto)
        val panes = listOf(
            FloatingPaneSpec(id = "a", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "b", xPct = 0.0, yPct = 0.0, isMinimized = true),
            FloatingPaneSpec(id = "c", xPct = 0.0, yPct = 0.0),
        )
        controller.recordCreate("a", null)
        controller.recordCreate("c", null) // order: [a, c]; b not tracked, minimised anyway
        controller.reorderPane("c", 0)     // order: [c, a]
        val result = controller.applyPresetToPanes(panes)

        val visible = result.filter { !it.isMinimized }
        assertEquals(2, visible.size)
        // Auto for n=2 puts the head pane at left.
        val byId = result.associateBy { it.id }
        assertEquals(0.0, byId["c"]!!.xPct)
        assertEquals(0.5, byId["a"]!!.xPct)
        // Minimised pane is preserved unchanged.
        assertTrue(byId["b"]!!.isMinimized)
    }

    @Test
    fun untracked_panes_get_appended_at_tail_in_existing_order() {
        val controller = LayoutController(initialPreset = LayoutPreset.Auto)
        val panes = listOf(
            FloatingPaneSpec(id = "untracked-1", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "tracked", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "untracked-2", xPct = 0.0, yPct = 0.0),
        )
        controller.recordCreate("tracked", null) // order: [tracked]
        val result = controller.applyPresetToPanes(panes)

        // n=3 → Auto slot 0 = primary 60% wide, slots 1+2 = right strip.
        val byId = result.associateBy { it.id }
        assertEquals(0.0, byId["tracked"]!!.xPct)
        assertEquals(0.6, byId["tracked"]!!.widthPct)
        // The untracked panes go to slots 1 and 2 in their existing order.
        assertEquals(0.6, byId["untracked-1"]!!.xPct)
        assertEquals(0.6, byId["untracked-2"]!!.xPct)
        assertNotEquals(byId["untracked-1"]!!.yPct, byId["untracked-2"]!!.yPct)
    }

    @Test
    fun apply_preset_uses_grid_when_supplied() {
        val grid = GridSpec(cols = 5, rows = 5)
        val controller = LayoutController(initialPreset = LayoutPreset.Auto, grid = grid)
        val panes = listOf(
            FloatingPaneSpec(id = "a", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "b", xPct = 0.0, yPct = 0.0),
            FloatingPaneSpec(id = "c", xPct = 0.0, yPct = 0.0),
        )
        controller.recordCreate("a", null)
        controller.recordCreate("b", null)
        controller.recordCreate("c", null) // order: [a, b, c]
        controller.reorderPane("c", 0)     // order: [c, a, b]
        controller.reorderPane("b", 1)     // order: [c, b, a]
        val result = controller.applyPresetToPanes(panes)

        // n=3 Auto: primary 60% → 3/5 cells (0.6 lands exactly on grid),
        // right strip width = 0.4 = 2/5.
        val byId = result.associateBy { it.id }
        assertEquals(0.6, byId["c"]!!.widthPct)
        assertEquals(0.6, byId["b"]!!.xPct)
        assertEquals(0.4, byId["b"]!!.widthPct)
    }
}
