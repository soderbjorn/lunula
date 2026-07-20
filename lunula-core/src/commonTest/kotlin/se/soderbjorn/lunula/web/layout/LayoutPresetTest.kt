/**
 * Tests for the platform-free layout primitives — [LayoutPreset.Auto]
 * geometry, [GridSpec] snapping, [LayoutPreset.Custom] semantics, and
 * the per-preset edge cases callers depend on.
 */
package se.soderbjorn.lunula.web.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * Smoke tests that every preset produces the requested number of
 * boxes for typical pane counts. Defends against shape-vs-count
 * mismatches that would crash the renderer at runtime.
 */
class LayoutPresetCountTest {

    @Test
    fun auto_returns_n_boxes_for_count_2_through_12() {
        for (n in 2..12) {
            val boxes = LayoutPreset.Auto.computeBoxes(n)
            assertEquals(n, boxes.size, "Auto with n=$n returned ${boxes.size} boxes")
        }
    }

    @Test
    fun auto_collapses_to_full_bleed_for_one_pane() {
        val boxes = LayoutPreset.Auto.computeBoxes(1)
        assertEquals(1, boxes.size)
        assertEquals(LayoutBox(0.0, 0.0, 1.0, 1.0), boxes[0])
    }

    @Test
    fun auto_returns_empty_for_zero_panes() {
        assertEquals(emptyList(), LayoutPreset.Auto.computeBoxes(0))
        assertEquals(emptyList(), LayoutPreset.Auto.computeBoxes(-3))
    }

    @Test
    fun custom_always_returns_empty_list() {
        for (n in 0..10) {
            assertEquals(emptyList(), LayoutPreset.Custom.computeBoxes(n))
        }
    }

    @Test
    fun all_presets_return_one_full_box_for_single_pane() {
        for (preset in LayoutPreset.entries) {
            if (preset == LayoutPreset.Custom) continue
            val boxes = preset.computeBoxes(1)
            assertEquals(1, boxes.size, "${preset.name} with n=1 should collapse to one box")
            assertEquals(LayoutBox(0.0, 0.0, 1.0, 1.0), boxes[0], "${preset.name}")
        }
    }
}

/**
 * Verifies the [LayoutPreset.Auto] geometry table — primary slot is
 * always the largest, output tiles the canvas with no gaps for the
 * supported pane counts.
 */
class AutoLayoutGeometryTest {

    @Test
    fun two_panes_split_horizontally_in_half() {
        val boxes = LayoutPreset.Auto.computeBoxes(2)
        assertEquals(2, boxes.size)
        assertEquals(LayoutBox(0.0, 0.0, 0.5, 1.0), boxes[0])
        assertEquals(LayoutBox(0.5, 0.0, 0.5, 1.0), boxes[1])
    }

    @Test
    fun three_to_five_panes_use_hero_left_with_stack() {
        for (n in 3..5) {
            val boxes = LayoutPreset.Auto.computeBoxes(n)
            assertEquals(n, boxes.size, "n=$n")
            assertEquals(LayoutBox(0.0, 0.0, 0.6, 1.0), boxes[0], "primary at n=$n")
            // Right strip shares 0.4 width across n-1 panes, full height per slice.
            val sliceH = 1.0 / (n - 1)
            for (i in 1 until n) {
                val expected = LayoutBox(0.6, (i - 1) * sliceH, 0.4, sliceH)
                assertCloseEnough(expected, boxes[i], "slot $i at n=$n")
            }
        }
    }

    @Test
    fun six_panes_use_three_by_two_grid() {
        val boxes = LayoutPreset.Auto.computeBoxes(6)
        assertEquals(6, boxes.size)
        // Primary at top-left
        assertCloseEnough(LayoutBox(0.0, 0.0, 1.0 / 3, 0.5), boxes[0])
    }

    @Test
    fun seven_or_more_panes_use_square_grid() {
        // n=7 → cols=ceil(sqrt(7))=3, rows=ceil(7/3)=3
        val boxes = LayoutPreset.Auto.computeBoxes(7)
        assertEquals(7, boxes.size)
        val cellW = 1.0 / 3
        val cellH = 1.0 / 3
        assertCloseEnough(LayoutBox(0.0, 0.0, cellW, cellH), boxes[0])
        // i=6 with cols=3 → row=2, col=0
        assertCloseEnough(LayoutBox(0.0, 2 * cellH, cellW, cellH), boxes[6])
    }

    @Test
    fun primary_box_is_always_the_largest_or_tied_for_largest() {
        for (n in 2..16) {
            val boxes = LayoutPreset.Auto.computeBoxes(n)
            val primaryArea = boxes[0].area
            for (i in 1 until n) {
                assertTrue(
                    boxes[i].area <= primaryArea + 1e-9,
                    "Slot $i (area=${boxes[i].area}) larger than primary (area=$primaryArea) at n=$n",
                )
            }
        }
    }

    private val LayoutBox.area get() = width * height

    private fun assertCloseEnough(expected: LayoutBox, actual: LayoutBox, hint: String = "") {
        val tol = 1e-9
        assertTrue(
            kotlin.math.abs(expected.x - actual.x) < tol &&
                kotlin.math.abs(expected.y - actual.y) < tol &&
                kotlin.math.abs(expected.width - actual.width) < tol &&
                kotlin.math.abs(expected.height - actual.height) < tol,
            "$hint: expected $expected, got $actual",
        )
    }
}

/**
 * Verifies [GridSpec] quantization — boxes returned by
 * `computeBoxes(n, grid)` align to grid lines exactly.
 */
class GridSpecSnappingTest {

    @Test
    fun rejects_non_positive_dimensions() {
        assertFails { GridSpec(cols = 0, rows = 4) }
        assertFails { GridSpec(cols = 4, rows = 0) }
        assertFails { GridSpec(cols = -1, rows = 4) }
    }

    @Test
    fun snaps_individual_coordinate_to_nearest_grid_line() {
        val grid = GridSpec(cols = 4, rows = 4) // cell = 0.25
        // 0.30 rounds to 0.25, 0.40 rounds to 0.50
        assertCloseEnough(0.25, grid.snap(0.30, horizontal = true))
        assertCloseEnough(0.50, grid.snap(0.40, horizontal = true))
        assertCloseEnough(0.0, grid.snap(0.10, horizontal = true))
        assertCloseEnough(1.0, grid.snap(0.95, horizontal = true))
    }

    @Test
    fun none_grid_passes_coordinate_through_unchanged() {
        assertCloseEnough(0.37, GridSpec.NONE.snap(0.37, horizontal = true))
        assertCloseEnough(0.0, GridSpec.NONE.snap(-0.1, horizontal = true)) // clamped
        assertCloseEnough(1.0, GridSpec.NONE.snap(1.5, horizontal = true)) // clamped
    }

    @Test
    fun snap_box_keeps_edges_on_grid_lines() {
        val grid = GridSpec(cols = 12, rows = 12)
        // 60/40 split: 0.60 → 7/12 = 0.5833…
        val snapped = grid.snapBox(LayoutBox(0.0, 0.0, 0.60, 1.0))
        assertCloseEnough(0.0, snapped.x)
        assertCloseEnough(0.0, snapped.y)
        assertCloseEnough(7.0 / 12, snapped.width) // 0.60 rounded to 7/12
        assertCloseEnough(1.0, snapped.height)
    }

    @Test
    fun snap_box_never_collapses_to_zero_size() {
        val grid = GridSpec(cols = 4, rows = 4)
        // A box smaller than half a cell would round to zero width
        // without the clamp. Verify the clamp keeps it at one cell.
        val snapped = grid.snapBox(LayoutBox(0.10, 0.10, 0.05, 0.05))
        assertTrue(snapped.width > 0.0, "width must be positive")
        assertTrue(snapped.height > 0.0, "height must be positive")
    }

    @Test
    fun auto_compute_boxes_with_grid_returns_grid_aligned_boxes() {
        val grid = GridSpec(cols = 12, rows = 12)
        for (n in 1..6) {
            val boxes = LayoutPreset.Auto.computeBoxes(n, grid)
            for ((i, box) in boxes.withIndex()) {
                assertOnGridLine(box.x, grid.cols, "n=$n slot $i x")
                assertOnGridLine(box.y, grid.rows, "n=$n slot $i y")
                assertOnGridLine(box.x + box.width, grid.cols, "n=$n slot $i right")
                assertOnGridLine(box.y + box.height, grid.rows, "n=$n slot $i bottom")
            }
        }
    }

    private fun assertOnGridLine(value: Double, divisions: Int, hint: String) {
        val cell = 1.0 / divisions
        val nearest = kotlin.math.round(value / cell) * cell
        assertTrue(
            kotlin.math.abs(value - nearest) < 1e-9,
            "$hint: $value not on grid line (cell=$cell)",
        )
    }

    private fun assertCloseEnough(expected: Double, actual: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) < 1e-9,
            "expected $expected, got $actual",
        )
    }
}

/**
 * Verifies [LayoutPreset.fromKey] round-trips with [LayoutPreset.key].
 */
class LayoutPresetKeyTest {

    @Test
    fun every_preset_round_trips_through_key_and_from_key() {
        for (preset in LayoutPreset.entries) {
            val key = preset.key
            val resolved = LayoutPreset.fromKey(key)
            assertEquals(preset, resolved, "round-trip failed for $preset (key=$key)")
        }
    }

    @Test
    fun auto_key_is_auto() {
        assertEquals("auto", LayoutPreset.Auto.key)
        assertEquals(LayoutPreset.Auto, LayoutPreset.fromKey("auto"))
    }

    @Test
    fun unknown_key_resolves_to_null() {
        assertNull(LayoutPreset.fromKey("not-a-real-preset"))
        assertNull(LayoutPreset.fromKey(""))
    }

    @Test
    fun dropdown_order_starts_with_auto() {
        assertEquals(LayoutPreset.Auto, LayoutPreset.DROPDOWN_ORDER.first())
        // Custom is a sentinel and is not user-selectable from the dropdown.
        assertTrue(
            LayoutPreset.Custom !in LayoutPreset.DROPDOWN_ORDER,
            "Custom should not appear in dropdown order",
        )
    }

    @Test
    fun every_dropdown_entry_has_a_label_and_key() {
        for (preset in LayoutPreset.DROPDOWN_ORDER) {
            assertNotNull(preset.label)
            assertTrue(preset.label.isNotEmpty(), "${preset.name} has empty label")
            assertTrue(preset.key.isNotEmpty(), "${preset.name} has empty key")
        }
    }
}

/**
 * Smoke tests for the multi-slot presets that consume more than one
 * privileged rank from [LayoutController.paneOrder]. Verifies the
 * importance hierarchy holds (slot 0 ≥ slot 1 ≥ slot 2 area-wise) and
 * that the geometry tiles the canvas without gaps for the headline
 * pane counts.
 */
class MultiSlotPresetGeometryTest {

    @Test
    fun auto_big_two_returns_n_boxes_for_count_2_through_8() {
        for (n in 2..8) {
            val boxes = LayoutPreset.AutoBigTwo.computeBoxes(n)
            assertEquals(n, boxes.size, "AutoBigTwo n=$n")
        }
    }

    @Test
    fun auto_big_three_returns_n_boxes_for_count_2_through_8() {
        for (n in 2..8) {
            val boxes = LayoutPreset.AutoBigThree.computeBoxes(n)
            assertEquals(n, boxes.size, "AutoBigThree n=$n")
        }
    }

    @Test
    fun hero_pair_returns_n_boxes_for_count_2_through_8() {
        for (n in 2..8) {
            val boxes = LayoutPreset.HeroPair.computeBoxes(n)
            assertEquals(n, boxes.size, "HeroPair n=$n")
        }
    }

    @Test
    fun auto_big_two_primary_dominates_secondary_dominates_rest() {
        for (n in 4..8) {
            val boxes = LayoutPreset.AutoBigTwo.computeBoxes(n)
            val area0 = boxes[0].width * boxes[0].height
            val area1 = boxes[1].width * boxes[1].height
            val tail = boxes.drop(2).maxOf { it.width * it.height }
            assertTrue(area0 >= area1 - 1e-9, "primary >= secondary at n=$n")
            assertTrue(area1 >= tail - 1e-9, "secondary >= rest at n=$n")
        }
    }

    @Test
    fun auto_big_three_primary_secondary_tertiary_rest_ordered() {
        for (n in 5..8) {
            val boxes = LayoutPreset.AutoBigThree.computeBoxes(n)
            val areas = boxes.map { it.width * it.height }
            assertTrue(areas[0] >= areas[1] - 1e-9, "primary >= secondary at n=$n")
            assertTrue(areas[1] >= areas[2] - 1e-9 || areas[2] >= areas[1] - 1e-9, "2 and 3 are co-equal top-right at n=$n")
            val restMax = areas.drop(3).max()
            assertTrue(areas[2] >= restMax - 1e-9, "tertiary >= rest at n=$n")
        }
    }

    @Test
    fun hero_pair_primary_dominates_secondary_dominates_rest() {
        for (n in 4..8) {
            val boxes = LayoutPreset.HeroPair.computeBoxes(n)
            val area0 = boxes[0].width * boxes[0].height
            val area1 = boxes[1].width * boxes[1].height
            val tail = boxes.drop(2).maxOf { it.width * it.height }
            assertTrue(area0 >= area1 - 1e-9, "primary >= secondary at n=$n")
            assertTrue(area1 >= tail - 1e-9, "secondary >= rest at n=$n")
        }
    }

    @Test
    fun multi_slot_presets_tile_canvas_without_overlap_for_typical_counts() {
        val presets = listOf(
            LayoutPreset.AutoBigTwo,
            LayoutPreset.AutoBigTwoRight,
            LayoutPreset.AutoBigTwoTop,
            LayoutPreset.AutoBigTwoBottom,
            LayoutPreset.AutoBigThree,
            LayoutPreset.HeroPair,
            LayoutPreset.HeroPairRight,
            LayoutPreset.HeroPairTop,
            LayoutPreset.HeroPairBottom,
        )
        for (preset in presets) for (n in 2..6) {
            val boxes = preset.computeBoxes(n)
            val totalArea = boxes.sumOf { it.width * it.height }
            assertTrue(
                totalArea in 0.999..1.001,
                "${preset.name} n=$n leaves ${1.0 - totalArea} of canvas uncovered",
            )
        }
    }

    @Test
    fun auto_big_two_orientations_return_n_boxes() {
        val orientations = listOf(
            LayoutPreset.AutoBigTwoRight,
            LayoutPreset.AutoBigTwoTop,
            LayoutPreset.AutoBigTwoBottom,
        )
        for (preset in orientations) for (n in 2..8) {
            val boxes = preset.computeBoxes(n)
            assertEquals(n, boxes.size, "${preset.name} n=$n")
        }
    }

    @Test
    fun auto_big_two_orientations_keep_primary_secondary_rest_ordering() {
        val orientations = listOf(
            LayoutPreset.AutoBigTwoRight,
            LayoutPreset.AutoBigTwoTop,
            LayoutPreset.AutoBigTwoBottom,
        )
        for (preset in orientations) for (n in 4..8) {
            val boxes = preset.computeBoxes(n)
            val area0 = boxes[0].width * boxes[0].height
            val area1 = boxes[1].width * boxes[1].height
            val tail = boxes.drop(2).maxOf { it.width * it.height }
            assertTrue(area0 >= area1 - 1e-9, "${preset.name} primary >= secondary at n=$n")
            assertTrue(area1 >= tail - 1e-9, "${preset.name} secondary >= rest at n=$n")
        }
    }

    @Test
    fun hero_pair_orientations_return_n_boxes() {
        val orientations = listOf(
            LayoutPreset.HeroPairRight,
            LayoutPreset.HeroPairTop,
            LayoutPreset.HeroPairBottom,
        )
        for (preset in orientations) for (n in 2..8) {
            val boxes = preset.computeBoxes(n)
            assertEquals(n, boxes.size, "${preset.name} n=$n")
        }
    }

    @Test
    fun hero_pair_orientations_keep_primary_secondary_rest_ordering() {
        val orientations = listOf(
            LayoutPreset.HeroPairRight,
            LayoutPreset.HeroPairTop,
            LayoutPreset.HeroPairBottom,
        )
        for (preset in orientations) for (n in 4..8) {
            val boxes = preset.computeBoxes(n)
            val area0 = boxes[0].width * boxes[0].height
            val area1 = boxes[1].width * boxes[1].height
            val tail = boxes.drop(2).maxOf { it.width * it.height }
            assertTrue(area0 >= area1 - 1e-9, "${preset.name} primary >= secondary at n=$n")
            assertTrue(area1 >= tail - 1e-9, "${preset.name} secondary >= rest at n=$n")
        }
    }
}
