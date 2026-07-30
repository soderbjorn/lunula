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
 * What both grids agree on is [LayoutPreset.Auto]: it is a mode rather
 * than a geometry, so it takes no part in the skipping in either
 * direction and paints [AUTO_TILE_GLYPH] rather than a miniature. The
 * tests below pin both halves of that — `Grid` survives from six panes up
 * despite drawing what Auto draws, and Auto survives a caller list that
 * does not lead with it.
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`).
 *
 * @see LayoutDropdown
 * @see LayoutPreset.DROPDOWN_ORDER
 * @see AUTO_TILE_GLYPH
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
     * Note what this does and does not buy. For the presets that draw a
     * miniature it is a restatement: the miniature *is* the skip key, so
     * "no two tiles share markup" holds by construction and this can only
     * fail if the key and the paint come apart. The one tile the key does
     * not cover is Auto's, which sits outside the skipping entirely — so
     * for Auto this is a real assertion, and the thing it guards is that
     * exempting Auto did not cost the user a duplicate-looking tile. A
     * green run here is still not evidence that no two tiles *look* alike:
     * markup that differs by a trailing float digit would pass. The
     * hard-coded counts are what catch that.
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
     * @param presets candidate list the dropdown offers. Defaults to the
     *   toolkit's own, which leads with Auto; pass a list that does not
     *   to prove Auto's tile is not a side effect of that ordering.
     * @return the dropdown, so a test can reopen it at another count.
     */
    private fun open(
        at: Int,
        presets: List<LayoutPreset> = LayoutPreset.DROPDOWN_ORDER,
    ): LayoutDropdown {
        panes = at
        val dropdown = LayoutDropdown(
            paneCount = { panes },
            onSelect = { picked += it },
            presets = presets,
        )
        val trigger = dropdown.triggerButton
        document.body!!.appendChild(trigger)
        mounted = trigger
        trigger.click()
        return dropdown
    }

    @Test
    fun tile_count_per_pane_count() {
        // Auto always contributes exactly one tile: it is exempt from the
        // skipping, so every count below is 1 + the number of distinct
        // pictures among the 31 geometry presets.
        //
        // 1 → 2: Auto, plus the one full-bleed box they all collapse to.
        // 2 → 25: seven geometry presets repeat a picture already drawn.
        // 3 → 32: rank keeps the near-misses apart here (AutoBigTwo draws
        //         Auto's boxes but privileges two slots, not one).
        // 4, 5 → 32: no collisions at all.
        // 6+ → 32: Grid computes what Auto computes and always will, but
        //         Grid is a *static* grid — separators kept, no re-tile on
        //         the seventh pane — so it keeps its tile.
        val expected = mapOf(1 to 2, 2 to 25, 3 to 32, 4 to 32, 5 to 32, 6 to 32, 9 to 32)
        for ((n, count) in expected) {
            open(at = n)
            assertEquals(count, tiles().size, "tiles at $n pane(s)")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun grid_survives_from_six_panes_up() {
        // The headline of the rework. Grid's boxes match Auto's from six
        // panes on, and the first cut of LNA-13 suppressed it for that —
        // making a preset that keeps its drag separators and does not
        // re-tile permanently unreachable at every count from six up.
        for (n in 6..9) {
            open(at = n)
            assertTrue("Grid" in labels(), "Grid must keep its tile at n=$n")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun auto_is_the_only_tile_that_is_not_a_miniature() {
        // Auto can sit outside the de-duplication without putting two
        // identical pictures on screen precisely because it never draws
        // one: its tile is the wand.
        open(at = 6)
        val autoTile = tiles().first { it.getAttribute("aria-label") == "Auto" }
        // Asserted through the DOM rather than by comparing innerHTML to
        // AUTO_TILE_GLYPH: the browser re-serializes SVG it parsed, so the
        // markup that comes back out is never the string that went in.
        assertNotNull(
            autoTile.querySelector(".dt-layout-preview-auto"),
            "Auto paints the wand, not a miniature",
        )
        assertTrue(
            autoTile.classList.contains("dt-layout-preset-tile-auto"),
            "…and carries the class that says so",
        )
        for (tile in tiles() - autoTile) {
            assertNull(
                tile.querySelector(".dt-layout-preview-auto"),
                "only Auto wears the wand",
            )
        }
    }

    @Test
    fun auto_survives_a_caller_list_that_does_not_lead_with_it() {
        // Auto's tile must be a property of this code, not of
        // DROPDOWN_ORDER's ordering. With Grid first at six panes, Grid
        // claims the shared picture — and Auto still gets its tile,
        // because it never competes for one.
        open(at = 6, presets = listOf(LayoutPreset.Grid, LayoutPreset.Auto))
        assertEquals(listOf("Grid", "Auto"), labels(), "both tiles, in the caller's order")
        assertEveryTileLooksDifferent("custom list, n=6")
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
    fun nothing_is_dropped_once_the_geometries_have_all_separated() {
        // From three panes up every geometry preset draws its own
        // picture, and Auto's exemption means it costs none of them a
        // tile. The whole catalogue is on offer, in DROPDOWN_ORDER.
        for (n in intArrayOf(3, 4, 5, 6, 9)) {
            open(at = n)
            assertEquals(
                LayoutPreset.DROPDOWN_ORDER.map { it.label },
                labels(),
                "the full catalogue at n=$n, in DROPDOWN_ORDER",
            )
            tearDown()
        }
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
        // Grid, Columns and SplitHorizontal are all one 50/50 split at two
        // panes — and all three used to lose to Auto, which draws no
        // picture at all. Now the first of them keeps the tile and Auto
        // costs them nothing.
        assertTrue("Grid" in labels(), "Grid leads its group in DROPDOWN_ORDER at two panes")
        assertTrue("Equal columns" !in labels(), "…so Columns is the one skipped")
        assertTrue("Split horizontal" !in labels(), "…and so is SplitHorizontal")
    }

    @Test
    fun the_list_reflows_when_a_pane_is_added() {
        // The 2 → 3 step: seven tiles arrive at once as the geometries
        // separate, which is the reflow a fix aimed only at the one-pane
        // screenshot misses. Recomputed per open, not per instance.
        val dropdown = open(at = 2)
        assertEquals(25, tiles().size)
        assertTrue("Equal columns" !in labels(), "Columns draws what Grid does at two panes")
        dropdown.close()
        panes = 3
        dropdown.openAnchoredTo(mounted!!)
        assertEquals(32, tiles().size, "the recomputation must happen per open")
        assertTrue("Equal columns" in labels(), "…and its own picture at three")
        // …and collapses again when the pane closes.
        dropdown.close()
        panes = 2
        dropdown.openAnchoredTo(mounted!!)
        assertEquals(25, tiles().size)
        assertTrue("Equal columns" !in labels())
    }

    @Test
    fun one_pane_explains_its_two_tiles() {
        open(at = 1)
        // One geometry survives — they are all the same full-bleed box, so
        // it is whichever leads DROPDOWN_ORDER — and Auto sits beside it,
        // offering the one thing that will still be doing something at the
        // second pane.
        assertEquals(
            listOf("Auto", LayoutPreset.DROPDOWN_ORDER[1].label),
            labels(),
            "one arrangement, plus the mode",
        )
        val note = grid()!!.querySelector(".dt-layout-preset-note")
        assertNotNull(note, "so short a grid must say why")
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
        // At two panes seven geometries are skipped, so the eighth tile is
        // not DROPDOWN_ORDER's eighth entry. Picking by index into the
        // unfiltered list would apply the wrong preset here.
        open(at = 2)
        val eighth = labels()[7]
        assertTrue(
            eighth != LayoutPreset.DROPDOWN_ORDER[7].label,
            "the drawn list must have diverged from the catalogue by the eighth tile",
        )
        repeat(7) { press("ArrowRight") }
        press("Enter")
        assertEquals(
            listOf(LayoutPreset.entries.first { it.label == eighth }),
            picked,
            "Enter applies the tile under the ring",
        )
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
        // Lower than the supported dropdown's at n=2 precisely because
        // this renderer draws no rank: HeroLeft and LShape are one picture
        // here. Auto contributes its one wand tile at every count, exactly
        // as in the supported dropdown — the two grids agree about Auto
        // even where they disagree about everything else.
        val expected = mapOf(1 to 2, 2 to 20, 3 to 32, 4 to 32, 5 to 32, 6 to 32, 9 to 32)
        for ((n, count) in expected) {
            open(at = n)
            assertEquals(count, tiles().size, "tiles at $n pane(s)")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun grid_survives_from_six_panes_up() {
        // Same exemption, same consequence, same reason as the supported
        // dropdown's: Auto claims no picture, so the static Grid keeps its
        // tile at the counts where their boxes coincide.
        for (n in 6..9) {
            open(at = n)
            assertTrue("Grid" in labels(), "Grid must keep its tile at n=$n")
            assertEveryTileLooksDifferent("n=$n")
            tearDown()
        }
    }

    @Test
    fun auto_wears_the_same_wand_it_wears_in_the_supported_dropdown() {
        // This grid used to draw Auto as a miniature like any other
        // preset. Exempting it from the skipping without also giving it
        // the wand would have put two identical pictures side by side
        // here, which is the complaint LNA-13 was filed over.
        open(at = 6)
        val autoTile = tiles().first { it.getAttribute("aria-label") == "Auto" }
        assertNotNull(
            autoTile.querySelector(".dt-layout-preview-auto"),
            "Auto paints the wand, not a miniature",
        )
        assertTrue(
            autoTile.classList.contains("dt-layout-preset-tile-auto"),
            "…and carries the class that says so",
        )
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
    fun one_pane_explains_its_two_tiles() {
        open(at = 1)
        assertEquals(listOf("Auto", LayoutPreset.DROPDOWN_ORDER[1].label), labels())
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
