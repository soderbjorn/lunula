/**
 * Tests for [computeSeparators] — the pure edge-detection function that
 * derives invisible draggable separator bars from the current pane layout.
 *
 * The DOM-side wiring in [mountSeparators] / [wireSeparatorDrag] is not
 * tested here; it is exercised by manual end-to-end testing in termtastic
 * and notegrow per the rollout plan.
 */
package se.soderbjorn.lunula.web.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaneSeparatorsTest {

    private fun pane(
        id: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): FloatingPaneSpec = FloatingPaneSpec(
        id = id,
        title = id,
        xPct = x,
        yPct = y,
        widthPct = w,
        heightPct = h,
    )

    @Test
    fun single_pane_yields_no_separators() {
        val panes = listOf(pane("a", 0.0, 0.0, 1.0, 1.0))
        assertTrue(computeSeparators(panes).isEmpty())
    }

    @Test
    fun two_side_by_side_panes_yield_one_full_height_vertical_bar() {
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("right", 0.5, 0.0, 0.5, 1.0),
        )
        val seps = computeSeparators(panes)
        assertEquals(1, seps.size)
        val s = seps.single()
        assertEquals(Orientation.Vertical, s.orientation)
        assertEquals(0.5, s.positionPct)
        assertEquals(0.0, s.startPct)
        assertEquals(1.0, s.endPct)
        assertEquals(listOf("left"), s.leftPaneIds)
        assertEquals(listOf("right"), s.rightPaneIds)
    }

    @Test
    fun two_stacked_panes_yield_one_full_width_horizontal_bar() {
        val panes = listOf(
            pane("top", 0.0, 0.0, 1.0, 0.5),
            pane("bot", 0.0, 0.5, 1.0, 0.5),
        )
        val seps = computeSeparators(panes)
        assertEquals(1, seps.size)
        val s = seps.single()
        assertEquals(Orientation.Horizontal, s.orientation)
        assertEquals(0.5, s.positionPct)
        assertEquals(0.0, s.startPct)
        assertEquals(1.0, s.endPct)
        assertEquals(listOf("top"), s.leftPaneIds)
        assertEquals(listOf("bot"), s.rightPaneIds)
    }

    /**
     * The user's screenshot layout: one tall left pane, two stacked panes
     * on the right. Expected separators:
     *  - Vertical at x=0.5, y=0..1, leftPanes=[left], rightPanes=[rt, rb]
     *  - Horizontal at y=0.5, x=0.5..1, leftPanes=[rt], rightPanes=[rb]
     */
    @Test
    fun screenshot_layout_yields_one_v_and_one_h() {
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("rt", 0.5, 0.0, 0.5, 0.5),
            pane("rb", 0.5, 0.5, 0.5, 0.5),
        )
        val seps = computeSeparators(panes)
        assertEquals(2, seps.size)

        val v = seps.single { it.orientation == Orientation.Vertical }
        assertEquals(0.5, v.positionPct)
        assertEquals(0.0, v.startPct)
        assertEquals(1.0, v.endPct)
        assertEquals(listOf("left"), v.leftPaneIds)
        assertEquals(setOf("rt", "rb"), v.rightPaneIds.toSet())

        val h = seps.single { it.orientation == Orientation.Horizontal }
        assertEquals(0.5, h.positionPct)
        assertEquals(0.5, h.startPct)
        assertEquals(1.0, h.endPct)
        assertEquals(listOf("rt"), h.leftPaneIds)
        assertEquals(listOf("rb"), h.rightPaneIds)
    }

    @Test
    fun four_quadrant_grid_yields_one_vertical_one_horizontal() {
        val panes = listOf(
            pane("tl", 0.0, 0.0, 0.5, 0.5),
            pane("tr", 0.5, 0.0, 0.5, 0.5),
            pane("bl", 0.0, 0.5, 0.5, 0.5),
            pane("br", 0.5, 0.5, 0.5, 0.5),
        )
        val seps = computeSeparators(panes)
        assertEquals(2, seps.size)

        val v = seps.single { it.orientation == Orientation.Vertical }
        assertEquals(0.5, v.positionPct)
        assertEquals(0.0, v.startPct)
        assertEquals(1.0, v.endPct)
        assertEquals(setOf("tl", "bl"), v.leftPaneIds.toSet())
        assertEquals(setOf("tr", "br"), v.rightPaneIds.toSet())

        val h = seps.single { it.orientation == Orientation.Horizontal }
        assertEquals(0.5, h.positionPct)
        assertEquals(0.0, h.startPct)
        assertEquals(1.0, h.endPct)
        assertEquals(setOf("tl", "tr"), h.leftPaneIds.toSet())
        assertEquals(setOf("bl", "br"), h.rightPaneIds.toSet())
    }

    @Test
    fun edge_at_container_boundary_emits_no_separator() {
        // Pane flush against x=0 — its left edge is the container edge,
        // not an internal separator. Single pane filling everything: no
        // shared edges at all.
        val panes = listOf(pane("only", 0.0, 0.0, 1.0, 1.0))
        assertTrue(computeSeparators(panes).isEmpty())
    }

    @Test
    fun overlapping_panes_drop_segment_crossed_by_third_pane() {
        // Two side-by-side panes share x=0.5 from y=0 to y=1. A third
        // pane in the middle straddles x=0.5 (xPct=0.4, widthPct=0.2)
        // from y=0.3 to y=0.7 — its body crosses the separator's
        // candidate extent in that y-range. We should drop those parts.
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("right", 0.5, 0.0, 0.5, 1.0),
            pane("crosser", 0.4, 0.3, 0.2, 0.4),
        )
        val seps = computeSeparators(panes).filter { it.orientation == Orientation.Vertical }
        // The crosser straddles the entire candidate y-overlap (0..1)
        // because the merged left/right ranges intersect to (0..1) but
        // the crosser body crosses x=0.5 inside (0.3..0.7); the
        // detection drops the whole single-segment overlap. Either zero
        // separators emerge, or the single segment is dropped — assert
        // the segment containing y in (0.3..0.7) does not appear.
        for (s in seps) {
            // No emitted segment should overlap with crosser's perp range.
            val crosserOverlap = s.endPct > 0.3 + 1e-6 && s.startPct < 0.7 - 1e-6
            assertTrue(!crosserOverlap, "separator $s overlaps the crossing pane")
        }
    }

    @Test
    fun three_panes_split_left_with_two_right_yields_segmented_separator_at_midline() {
        // Left column: one pane.   Right column: top + bottom split at y=0.4.
        // The midline x=0.5 is shared:
        //  - leftGroup = [left]      (perp range 0..1)
        //  - rightGroup = [rt, rb]   (perp ranges 0..0.4 and 0.4..1)
        // Their unions intersect to a single (0..1) segment; one bar.
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("rt", 0.5, 0.0, 0.5, 0.4),
            pane("rb", 0.5, 0.4, 0.5, 0.6),
        )
        val v = computeSeparators(panes).single { it.orientation == Orientation.Vertical }
        assertEquals(0.5, v.positionPct)
        assertEquals(0.0, v.startPct)
        assertEquals(1.0, v.endPct)
        assertEquals(listOf("left"), v.leftPaneIds)
        assertEquals(setOf("rt", "rb"), v.rightPaneIds.toSet())
    }

    @Test
    fun horizontal_bar_only_spans_overlap_extent_not_full_width() {
        // Tall left pane covers the full height, two stacked right panes.
        // The horizontal bar between rt and rb sits at y=0.5 but only
        // exists where rt and rb meet — the right column from x=0.5 to
        // x=1.0. It must NOT extend across the left pane.
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("rt", 0.5, 0.0, 0.5, 0.5),
            pane("rb", 0.5, 0.5, 0.5, 0.5),
        )
        val h = computeSeparators(panes).single { it.orientation == Orientation.Horizontal }
        assertEquals(0.5, h.startPct)
        assertEquals(1.0, h.endPct)
    }

    /**
     * Visually-adjacent panes (gap within tolerance) should still produce
     * a separator. The bar lands at the midpoint of the gap so its 8 px
     * hit zone sits in the centre of the visible space rather than on one
     * pane's edge. Mirrors the user's screenshot layout where two panes
     * are dragged "close enough" without snapping pixel-perfectly.
     */
    @Test
    fun gap_within_tolerance_produces_separator_at_gap_midpoint() {
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("right", 0.515, 0.0, 0.485, 1.0),
        )
        val v = computeSeparators(panes).single { it.orientation == Orientation.Vertical }
        assertEquals(0.5075, v.positionPct, 1e-9)
        assertEquals(0.0, v.startPct)
        assertEquals(1.0, v.endPct)
        assertEquals(listOf("left"), v.leftPaneIds)
        assertEquals(listOf("right"), v.rightPaneIds)
    }

    /**
     * Gaps larger than the tolerance are deliberate negative space; the
     * algorithm must not silently bridge them with a separator.
     */
    @Test
    fun gap_beyond_tolerance_emits_no_separator() {
        val panes = listOf(
            pane("left", 0.0, 0.0, 0.5, 1.0),
            pane("right", 0.53, 0.0, 0.47, 1.0),
        )
        assertTrue(computeSeparators(panes).isEmpty())
    }

    /**
     * Guards against the matching tolerance growing into the snap-cell
     * width. Three vertical strips at exact 25%/50% boundaries must still
     * produce two distinct separators, never one merged at ~37.5%.
     */
    @Test
    fun adjacent_grid_cells_not_conflated() {
        val panes = listOf(
            pane("a", 0.0, 0.0, 0.25, 1.0),
            pane("b", 0.25, 0.0, 0.25, 1.0),
            pane("c", 0.5, 0.0, 0.5, 1.0),
        )
        val seps = computeSeparators(panes).filter { it.orientation == Orientation.Vertical }
        assertEquals(2, seps.size)
        val sorted = seps.sortedBy { it.positionPct }
        assertEquals(0.25, sorted[0].positionPct)
        assertEquals(0.5, sorted[1].positionPct)
        assertEquals(listOf("a"), sorted[0].leftPaneIds)
        assertEquals(listOf("b"), sorted[0].rightPaneIds)
        assertEquals(listOf("b"), sorted[1].leftPaneIds)
        assertEquals(listOf("c"), sorted[1].rightPaneIds)
    }
}
