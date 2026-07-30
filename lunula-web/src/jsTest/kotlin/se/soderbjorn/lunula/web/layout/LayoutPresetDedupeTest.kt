/**
 * Browser tests for LNA-13: a layout dropdown must offer each
 * arrangement once, not once per preset that happens to produce it.
 *
 * The toolkit ships two preset grids and they are tested separately
 * here, because they legitimately disagree about what "the same tile"
 * means:
 *
 * - [LayoutDropdown] renders rank-aware miniatures — slot 0 accented,
 *   the next `emphasizedSlotCount - 1` slots translucent, the rest grey
 *   — so two presets with identical boxes but different rank counts
 *   (`HeroLeft` vs `LShape` at two panes) draw *different* pictures and
 *   both keep a tile.
 * - The older [se.soderbjorn.lunula.web.shell.buildLayoutPresetButton]
 *   grid draws every non-primary slot the same grey, so those two
 *   presets are one picture there and collapse into one tile.
 *
 * Both grids therefore get their own expected tile counts, and both are
 * asserted against the rendered DOM rather than against the geometry —
 * what the ticket complained about was seeing the same picture 32 times.
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`).
 *
 * @see LayoutDropdown
 * @see LayoutPreset.DROPDOWN_ORDER
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.web.shell.buildLayoutPresetButton
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared DOM helpers for both grids — they render into the same
 * `.dt-layout-preset-grid` popover with the same `.dt-layout-preset-tile`
 * children, so the assertions are identical even though the builders are
 * not.
 */
abstract class PresetGridTestBase {

    /** Whatever the last test mounted; torn down by [tearDown]. */
    protected var mounted: HTMLElement? = null

    /**
     * Removes the popover and the trigger, and walks the cursor away from
     * every button so the click-suppression rect the toolkit keeps at
     * module scope does not outlive this test and mute a later one's
     * hover — the same disarm `LayoutDropdownHoverTest` performs.
     */
    @AfterTest
    fun tearDown() {
        val stale = document.querySelectorAll(".dt-layout-preset-grid")
        for (i in 0 until stale.length) (stale.item(i) as HTMLElement).remove()
        mounted?.parentElement?.removeChild(mounted!!)
        mounted = null
        document.dispatchEvent(
            MouseEvent("mousemove", MouseEventInit(clientX = 9999, clientY = 9999)),
        )
    }

    /** The open popover, or `null` when none is up. */
    protected fun grid(): HTMLElement? =
        document.querySelector(".dt-layout-preset-grid") as? HTMLElement

    /** The popover's preset tiles, in DOM order. */
    protected fun tiles(): List<HTMLElement> {
        val g = grid() ?: return emptyList()
        val found = g.querySelectorAll(".dt-layout-preset-tile")
        return (0 until found.length).map { found.item(it) as HTMLElement }
    }

    /** The accessible names of [tiles], which are the preset labels. */
    protected fun labels(): List<String> =
        tiles().map { it.getAttribute("aria-label") ?: "" }

    /**
     * Asserts the open popover shows no picture twice — the literal
     * reading of the ticket.
     *
     * @param hint appended to the failure message, typically the pane count.
     */
    protected fun assertEveryTileLooksDifferent(hint: String) {
        val markup = tiles().map { it.innerHTML }
        val repeated = markup.groupBy { it }.filterValues { it.size > 1 }
        assertTrue(
            repeated.isEmpty(),
            "$hint: ${repeated.size} miniature(s) drawn more than once",
        )
    }

    /** Dispatches a synthetic [key] keydown on the document, capture-visible. */
    protected fun press(key: String) {
        document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key)))
    }
}

/**
 * The supported dropdown. Its miniatures carry rank, so its tile counts
 * are higher than the older grid's at the counts where presets differ
 * only in how many slots they privilege.
 */
class LayoutDropdownDedupeTest : PresetGridTestBase() {

    /** Pane count the dropdown under test reads; mutable so one instance
     *  can be reopened at a different count, which is how reflow is tested. */
    private var panes: Int = 1

    /** Presets picked through [open]'s dropdown, newest last. */
    private val picked = mutableListOf<LayoutPreset>()

    /**
     * Builds a dropdown reading [panes], mounts its trigger, and clicks
     * it open.
     *
     * @param at pane count to report before opening.
     * @return the dropdown, so a test can reopen it at another count.
     */
    private fun open(at: Int): LayoutDropdown {
        panes = at
        val dropdown = LayoutDropdown(
            paneCount = { panes },
            onSelect = { picked += it },
        )
        val trigger = dropdown.triggerButton
        document.body!!.appendChild(trigger)
        mounted = trigger
        trigger.click()
        return dropdown
    }

    @Test
    fun tile_count_per_pane_count() {
        // 1 → only Auto: every preset is one full-bleed box.
        // 2 → 24: eight presets repeat a picture already drawn.
        // 3 → 32: Auto and AutoBigTwo share boxes but not emphasis, so
        //          both are still worth showing here.
        // 4, 5 → 32: no collisions at all.
        // 6+ → 31: Grid computes exactly what Auto does and always will.
        val expected = mapOf(1 to 1, 2 to 24, 3 to 32, 4 to 32, 5 to 32, 6 to 31, 9 to 31)
        for ((n, count) in expected) {
            open(at = n)
            assertEquals(count, tiles().size, "tiles at $n pane(s)")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun auto_always_leads_the_grid() {
        for (n in 1..9) {
            open(at = n)
            assertEquals("Auto", labels().firstOrNull(), "Auto must lead the grid at n=$n")
            tearDown()
        }
    }

    @Test
    fun the_duplicates_dropped_are_the_expected_ones() {
        open(at = 6)
        // Grid is the one and only casualty at six panes.
        assertTrue("Grid" !in labels(), "Grid duplicates Auto from six panes up")
        assertEquals(
            LayoutPreset.DROPDOWN_ORDER.filter { it != LayoutPreset.Grid }.map { it.label },
            labels(),
            "everything else keeps its tile, in DROPDOWN_ORDER",
        )
    }

    @Test
    fun two_panes_keep_the_presets_that_differ_only_in_rank() {
        open(at = 2)
        // Identical boxes, different emphasis: HeroLeft privileges one
        // slot and LShape two, so they are two visibly different tiles.
        assertTrue("Hero left" in labels(), "HeroLeft keeps its tile")
        assertTrue("L-shape (top-left)" in labels(), "…and so does its same-boxes twin")
        // Identical boxes and identical emphasis — one tile between them.
        assertTrue("Equal rows" in labels())
        assertTrue("Split vertical" !in labels(), "SplitVertical draws exactly what Rows does")
    }

    @Test
    fun the_list_reflows_when_a_pane_is_added() {
        // The 5 → 6 step: a single tile leaves a long list, which is the
        // regression a fix aimed only at the one-pane screenshot misses.
        val dropdown = open(at = 5)
        assertEquals(32, tiles().size)
        assertTrue("Grid" in labels())
        dropdown.close()
        panes = 6
        dropdown.openAnchoredTo(mounted!!)
        assertEquals(31, tiles().size, "the recomputation must happen per open")
        assertTrue("Grid" !in labels(), "Grid is no longer telling the user anything new")
        // …and comes back when the pane closes again.
        dropdown.close()
        panes = 5
        dropdown.openAnchoredTo(mounted!!)
        assertEquals(32, tiles().size)
        assertTrue("Grid" in labels())
    }

    @Test
    fun one_pane_explains_its_single_tile() {
        open(at = 1)
        assertEquals(listOf("Auto"), labels(), "one arrangement, one tile")
        val note = grid()!!.querySelector(".dt-layout-preset-note")
        assertNotNull(note, "a lone tile must say why it is alone")
        assertTrue(note.textContent!!.isNotBlank())
    }

    @Test
    fun more_than_one_pane_shows_no_note() {
        open(at = 3)
        assertNull(
            grid()!!.querySelector(".dt-layout-preset-note"),
            "the explanation belongs to the one-pane case only",
        )
    }

    @Test
    fun no_panes_still_shows_the_empty_state() {
        open(at = 0)
        assertEquals(emptyList(), labels())
        assertNotNull(grid()!!.querySelector(".dt-layout-preset-empty"))
    }

    @Test
    fun keyboard_activation_indexes_the_tiles_that_were_drawn() {
        // At six panes Grid is skipped, so the seventh tile is Columns,
        // not the seventh entry of DROPDOWN_ORDER. Picking by index into
        // the unfiltered list would apply the wrong preset here.
        open(at = 6)
        assertEquals("Equal columns", labels()[6], "seventh tile once Grid is skipped")
        repeat(6) { press("ArrowRight") }
        press("Enter")
        assertEquals(listOf(LayoutPreset.Columns), picked, "Enter applies the tile under the ring")
    }
}

/**
 * The older topbar preset grid
 * ([se.soderbjorn.lunula.web.shell.buildLayoutPresetButton]). Its
 * miniatures ignore rank, so more presets look alike and more tiles
 * collapse than in [LayoutDropdownDedupeTest].
 */
class LegacyPresetGridDedupeTest : PresetGridTestBase() {

    /**
     * Builds the legacy layout button for [at] panes, mounts it, and
     * clicks it open.
     *
     * @param at pane count the button reports.
     */
    private fun open(at: Int) {
        val btn = buildLayoutPresetButton(paneCount = { at }, onSelect = { })
        document.body!!.appendChild(btn)
        mounted = btn
        btn.click()
    }

    @Test
    fun tile_count_per_pane_count() {
        // Lower than the supported dropdown's at n=2 and n=3 precisely
        // because this renderer draws no rank: HeroLeft/LShape and
        // Auto/AutoBigTwo are one picture each here.
        val expected = mapOf(1 to 1, 2 to 19, 3 to 31, 4 to 32, 5 to 32, 6 to 31, 9 to 31)
        for ((n, count) in expected) {
            open(at = n)
            assertEquals(count, tiles().size, "tiles at $n pane(s)")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun auto_always_leads_the_grid() {
        for (n in 1..9) {
            open(at = n)
            assertEquals("Auto", labels().firstOrNull(), "Auto must lead the grid at n=$n")
            tearDown()
        }
    }

    @Test
    fun survivors_match_the_supported_dropdown_where_both_collapse() {
        // Both grids walk DROPDOWN_ORDER now, so a shape kept as
        // AutoBigTwo in one is not kept as BigTwoStack in the other.
        open(at = 2)
        assertTrue("2 emphasized" in labels(), "AutoBigTwo leads its group in DROPDOWN_ORDER")
        assertTrue("Big left + right stack" !in labels(), "…so BigTwoStack is the one skipped")
    }

    @Test
    fun one_pane_explains_its_single_tile() {
        open(at = 1)
        assertEquals(listOf("Auto"), labels())
        assertNotNull(
            grid()!!.querySelector(".dt-layout-preset-note"),
            "the older grid says the same thing the supported one does",
        )
    }

    @Test
    fun no_panes_still_shows_the_empty_state() {
        open(at = 0)
        assertEquals(emptyList(), labels())
        assertNotNull(grid()!!.querySelector(".dt-layout-preset-empty"))
    }
}
