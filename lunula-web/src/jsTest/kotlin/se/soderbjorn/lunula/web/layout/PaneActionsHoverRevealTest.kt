/*
 * PaneActionsHoverRevealTest.kt (jsTest)
 *
 * Browser tests for the pane titlebar's hidden-until-hover action strip.
 *
 * The behaviour is expressed in CSS — `.dt-pane-actions` sits at `opacity: 0`
 * until something hovers or focuses the titlebar — which is exactly why it
 * needs a test here: nothing in the Kotlin build reads `lunula.css`, so a rule
 * lost to a stray edit would leave the buttons permanently invisible (or
 * permanently visible) with every other test still green.
 *
 * `:hover` cannot be synthesised, so these assert the things that can be
 * checked without a real pointer: the resting state is genuinely hidden AND
 * inert, every reveal selector survived parsing, the two reveal paths that are
 * driven from Kotlin rather than from the pointer — an open pane menu, and the
 * proximity element the renderer mounts — work end to end, and the element that
 * wins the hit-test over the hidden Close button is one the stylesheet reveals
 * from. That last one is the closest reachable proxy for "hovering Close brings
 * the buttons back", and it is where the corner-grip dead zone showed up.
 *
 * @see PaneHeaderClassNames.ACTIONS
 * @see LayoutClassNames.PANE_HEADER_PROXIMITY
 * @see LayoutClassNames.CORNER_RESIZE
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.css.CSSStyleSheet
import se.soderbjorn.lunula.web.POINTER_ACTIVE_BODY_CLASS
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether this browser reports a hovering pointer. The hide is deliberately
 * scoped to `@media (hover: hover) and (pointer: fine)` — a touch device that
 * cannot hover keeps the buttons visible — so the resting-state assertions
 * only mean anything when the test browser matches.
 */
private fun hasHoverPointer(): Boolean =
    window.matchMedia("(hover: hover) and (pointer: fine)").matches

/**
 * Every selector the parser accepted, `@media`-nested ones included — the
 * hide/reveal rules live inside `@media (hover: hover)`, so a flat walk of the
 * sheet's top level would report every one of them as missing.
 *
 * Reads `selectorText` rather than slicing `cssText`: in a browser with CSS
 * nesting, every style rule also carries a (usually empty) `cssRules`, so
 * "has children" is no longer a way to tell a group rule from a style rule.
 */
private fun parsedSelectors(): List<String> {
    injectLunulaStyles()
    val out = mutableListOf<String>()
    fun collect(rules: dynamic) {
        val len = (rules.length as? Number)?.toInt() ?: return
        for (i in 0 until len) {
            val rule = rules.item(i) ?: continue
            val selector = rule.selectorText
            if (selector != null && selector != undefined) out += selector as String
            val nested = rule.cssRules
            if (nested != null && nested != undefined) collect(nested)
        }
    }
    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        collect(runCatching { sheet.cssRules }.getOrNull() ?: continue)
    }
    return out
}

class PaneActionsHoverRevealTest {

    private lateinit var container: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        // The "at rest" this file asserts is *no pointer activity* as well as
        // no hover: `body.dt-pointer-active` (PointerActivity.kt) reveals
        // every strip in the document, and it is a shared body class that a
        // stray movement — a sibling test's synthetic event, or a real cursor
        // over a headed karma browser — can leave behind.
        document.body!!.classList.remove(POINTER_ACTIVE_BODY_CLASS)
        container = (document.createElement("div") as HTMLElement).also {
            // Real size: the renderer reads the container's box when placing
            // floating panes, and a zero-sized host would collapse the pane.
            it.style.width = "800px"
            it.style.height = "600px"
            it.style.position = "relative"
            document.body!!.appendChild(it)
        }
    }

    @AfterTest
    fun unmount() {
        container.parentElement?.removeChild(container)
    }

    /**
     * Renders a single floating pane carrying one action, and returns its
     * `.dt-pane` element.
     */
    private fun renderOnePane(): HTMLElement {
        val renderer = LayoutRenderer(
            container = container,
            callbacks = PaneCallbacks(
                contentRenderer = { _, slot -> slot.textContent = "content" },
                paneHeader = { id, title ->
                    PaneHeaderSpec(
                        title = title ?: id,
                        actions = listOf(PaneActions.close { }),
                    )
                },
                // Present so the renderer mounts the corner resize grips: they
                // overlap the titlebar's ends and are part of what the reveal
                // has to cope with, so a fixture without them would hide the
                // very hit-testing this file is about.
                onFloatingResized = { _, _, _ -> },
            ),
        )
        renderer.render(
            layout = PaneLayout(
                floatingPanes = listOf(
                    FloatingPaneSpec(id = "p1", title = "Pane 1", xPct = 0.1, yPct = 0.1),
                ),
            ),
            suppressSeparators = true,
        )
        return assertNotNull(
            container.querySelector(".${LayoutClassNames.PANE}") as? HTMLElement,
            "renderer produced no .dt-pane",
        )
    }

    /**
     * The whole point: with nothing hovered and nothing focused, the strip is
     * invisible *and* takes no clicks.
     *
     * The second half is the one worth guarding. An `opacity: 0` button still
     * hit-tests by default, so dropping `pointer-events: none` would leave an
     * invisible Close button sitting in the titlebar — and a user who clicks
     * the titlebar to focus a pane would sometimes close it instead.
     */
    @Test
    fun theActionStripIsHiddenAndInertAtRest() {
        if (!hasHoverPointer()) return
        val pane = renderOnePane()
        val actions = assertNotNull(
            pane.querySelector(".${PaneHeaderClassNames.ACTIONS}") as? HTMLElement,
            "header rendered no actions strip",
        )
        val style = window.getComputedStyle(actions)
        assertEquals("0", style.opacity, "action strip should rest at opacity 0")
        assertEquals("none", style.getPropertyValue("pointer-events").trim())
    }

    /**
     * Hiding must be paint-only: the strip keeps its box so revealing it never
     * reflows the title beside it. A `display: none` regression would show up
     * here as a zero-width strip.
     */
    @Test
    fun theHiddenStripStillOccupiesItsBox() {
        val pane = renderOnePane()
        val actions = assertNotNull(
            pane.querySelector(".${PaneHeaderClassNames.ACTIONS}") as? HTMLElement,
        )
        assertTrue(
            actions.getBoundingClientRect().width > 0,
            "hidden strip collapsed its box — revealing it would shift the title",
        )
    }

    /**
     * The reported bug: aiming straight at the Close button did not bring the
     * buttons back. The strip takes no clicks while hidden, so the topmost
     * element at the Close button's own centre is whatever else covers those
     * pixels — in practice `.dt-pane-corner-resize-tr`, a child of `.dt-pane`
     * rather than of the header, which is why `.dt-pane-header:hover` never
     * matched there.
     *
     * `:hover` can't be synthesised, but the thing that decides which element
     * hovers can be read directly: assert that whatever wins the hit-test over
     * the hidden Close button is an element the stylesheet does reveal from.
     * This stays honest if the overlap later moves or disappears — it asserts
     * the property (that point is live), not today's stacking accident.
     */
    @Test
    fun theHitTargetOverTheHiddenCloseButtonIsSomethingThatReveals() {
        if (!hasHoverPointer()) return
        val pane = renderOnePane()
        val button = assertNotNull(
            pane.querySelector(".${PaneHeaderClassNames.ACTION}") as? HTMLElement,
            "header rendered no action button",
        )
        val box = button.getBoundingClientRect()
        val hit = assertNotNull(
            document.elementFromPoint(
                (box.left + box.right) / 2,
                (box.top + box.bottom) / 2,
            ) as? HTMLElement,
            "nothing hit-tested over the action button",
        )
        // The reveal paths, in the same order as the stylesheet: inside the
        // titlebar (covers the strip itself once it is live), or one of the two
        // top corner grips.
        val revealsTheStrip = hit.closest(".${LayoutClassNames.PANE_HEADER}") != null ||
            hit.closest(".${LayoutClassNames.CORNER_RESIZE_TL}") != null ||
            hit.closest(".${LayoutClassNames.CORNER_RESIZE_TR}") != null
        assertTrue(
            revealsTheStrip,
            "the point where Close paints hit-tests to `${hit.className}`, which no " +
                "reveal selector covers — hovering the Close button leaves the " +
                "action strip invisible",
        )
    }

    /**
     * The renderer must mount the proximity hover target as a SIBLING of the
     * header, not a child of it: inside the header it would inherit the
     * drag-to-move gesture and the HTML5 pane-drag, so a press-and-drag on the
     * pane's first content row would start moving the pane.
     */
    @Test
    fun theProximityBandIsMountedAfterTheHeaderNotInsideIt() {
        val pane = renderOnePane()
        val header = assertNotNull(
            pane.querySelector(".${LayoutClassNames.PANE_HEADER}") as? HTMLElement,
        )
        val proximity = assertNotNull(
            pane.querySelector(".${LayoutClassNames.PANE_HEADER_PROXIMITY}") as? HTMLElement,
            "no proximity band mounted — the titlebar's hover reach is titlebar-only",
        )
        assertTrue(
            header.nextElementSibling === proximity,
            "proximity band must directly follow the header",
        )
        assertFalse(
            header.contains(proximity),
            "proximity band must not live inside the header (it would inherit its drags)",
        )
        // Zero-height in flow, with the hover area supplied by ::before, so it
        // costs the pane no vertical space whatever the density tokens say.
        assertEquals(0.0, proximity.getBoundingClientRect().height)
        // Asserted as a floor rather than an exact value: the reach is a tuning
        // knob (`--dt-pane-proximity-reach`) and hosts may retune it. What must
        // not regress is the band existing at all with a usable depth — a
        // dropped rule reports as `auto`/`0px` here.
        val reach = window.getComputedStyle(proximity, "::before").height
        val reachPx = reach.removeSuffix("px").toDoubleOrNull() ?: 0.0
        assertTrue(
            reachPx >= 18.0,
            "the ::before hover band did not survive parsing (height was `$reach`)",
        )
    }

    /**
     * The band declares the zone; it must not take the presses inside it.
     *
     * It used to, because the reveal was a `:hover` on the band and hovering
     * is hit-testing: every press in the ~26px below a titlebar went to an
     * invisible element instead of the pane's own content — the first row of a
     * terminal, a link at the top of a page, the upper half of a field mounted
     * right under the header. The zone is now measured off this element by
     * `PointerActivity.kt`, so the pixels belong to the content again.
     *
     * Asserted at a point just inside the band's own reach, which is exactly
     * where the old behaviour differed: anywhere below it always worked.
     */
    @Test
    fun theProximityBandTakesNoPressesInTheContentBelowIt() {
        val pane = renderOnePane()
        val proximity = assertNotNull(
            pane.querySelector(".${LayoutClassNames.PANE_HEADER_PROXIMITY}") as? HTMLElement,
        )
        assertEquals(
            "none",
            window.getComputedStyle(proximity).getPropertyValue("pointer-events").trim(),
            "the proximity band hit-tests again — it is swallowing presses meant " +
                "for the top of the pane's content",
        )
        val box = proximity.getBoundingClientRect()
        val hit = assertNotNull(
            document.elementFromPoint(box.left + box.width / 2, box.top + 4) as? HTMLElement,
            "nothing hit-tested just below the titlebar",
        )
        assertTrue(
            hit.closest(".${LayoutClassNames.PANE_CONTENT}") != null,
            "a press just below the titlebar landed on `${hit.className}` rather " +
                "than the pane's content",
        )
    }

    /**
     * A menu opened from an action button mounts on `<body>`, so the header
     * stops being hovered the moment the cursor travels into it. [openPaneMenu]
     * marks the anchor to hold the strip open; without that the buttons fade
     * out from under a menu the user is still using.
     */
    @Test
    fun anOpenMenuHoldsTheStripRevealed() {
        val pane = renderOnePane()
        val actions = assertNotNull(
            pane.querySelector(".${PaneHeaderClassNames.ACTIONS}") as? HTMLElement,
        )
        val button = assertNotNull(
            actions.querySelector(".${PaneHeaderClassNames.ACTION}") as? HTMLElement,
        )
        // Opacity is transitioned, and `getComputedStyle` reports the value
        // mid-flight — so read the settled state by taking the transition out.
        actions.style.transition = "none"
        val close = openPaneMenu(button, PaneMenuSpec(items = listOf(PaneMenuItem(label = "Item"))))
        try {
            assertTrue(
                button.classList.contains(PaneHeaderClassNames.ACTION_MENU_OPEN),
                "openPaneMenu did not mark its anchor",
            )
            if (hasHoverPointer()) {
                assertEquals("1", window.getComputedStyle(actions).opacity)
            }
        } finally {
            close()
        }
        assertFalse(
            button.classList.contains(PaneHeaderClassNames.ACTION_MENU_OPEN),
            "closing the menu left the anchor marked, pinning the strip visible",
        )
        if (hasHoverPointer()) {
            assertEquals("0", window.getComputedStyle(actions).opacity)
        }
    }

    /**
     * The reveal selectors are the half of the behaviour no computed style can
     * reach (they all need a pointer). Assert instead that each one survived
     * parsing — the failure this guards against is a rule silently dropped by
     * an edit above it, which would strand the buttons invisible forever.
     */
    @Test
    fun everyRevealSelectorSurvivesParsing() {
        val selectors = parsedSelectors()
        val required = listOf(
            // hover on the titlebar itself
            ".dt-pane-header:hover .dt-pane-actions",
            // keyboard users, who never hover at all
            ".dt-pane-header:focus-within .dt-pane-actions",
            // the pointer resting below the titlebar, published on the pane
            // by PointerActivity.kt rather than hovered (see
            // PANE_NEAR_HEADER_CLASS)
            ".dt-pane.dt-pane-header-near",
            // the top corner resize grips, which overlay the ends of the
            // titlebar — the trailing one sits on the Close button, so
            // without these two clauses aiming at Close reveals nothing
            ".dt-pane-corner-resize-tl:hover",
            ".dt-pane-corner-resize-tr:hover",
            // a menu still open over the pane
            ".dt-pane-action.dt-open",
        )
        for (needle in required) {
            assertTrue(
                selectors.any { it.contains(needle) },
                "no rule containing `$needle` survived parsing — the action " +
                    "strip has a reveal path it can no longer take. Survivors " +
                    "mentioning dt-pane-actions: " +
                    selectors.filter { it.contains("dt-pane-actions") },
            )
        }
    }
}
