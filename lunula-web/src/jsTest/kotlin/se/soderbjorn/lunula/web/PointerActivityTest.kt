/*
 * PointerActivityTest.kt (jsTest)
 *
 * Browser tests for the window-wide reveal of the pane titlebar action
 * strips: any mouse movement anywhere in the window brings EVERY pane's
 * buttons back, and they all fade out together once the pointer has been
 * still for a while.
 *
 * Half the behaviour is Kotlin ([installPointerActivityTracking] toggling
 * `dt-pointer-active` on the body) and half is CSS (the rule that reads the
 * class). Both halves are asserted here, because either one alone is silent:
 * a dropped CSS rule leaves the class flipping correctly over permanently
 * invisible buttons, and a dropped listener leaves a rule nothing ever
 * matches.
 *
 * The same class also lends filled panes a temporary frame, so the CSS half is
 * asserted twice — once per consumer.
 *
 * Timing is real — the idle countdown is a live interval — so these run at a
 * deliberately short idle delay and return Promises.
 *
 * @see PointerActivity.kt
 * @see se.soderbjorn.lunula.web.layout.PaneActionsHoverRevealTest for the
 *   hover half of the same strip
 */
package se.soderbjorn.lunula.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.web.layout.FloatingPaneSpec
import se.soderbjorn.lunula.web.layout.LayoutRenderer
import se.soderbjorn.lunula.web.layout.PaneActions
import se.soderbjorn.lunula.web.layout.PaneCallbacks
import se.soderbjorn.lunula.web.layout.PaneHeaderClassNames
import se.soderbjorn.lunula.web.layout.PaneHeaderSpec
import se.soderbjorn.lunula.web.layout.PaneLayout
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Idle delay used throughout. Short enough to keep the suite quick, but kept
 * well above the tracker's fixed 250 ms poll interval: at a delay near or
 * below the poll, "has it gone idle yet?" turns into a race with the tick
 * boundary and the timing tests below would flake either way.
 */
private const val TEST_IDLE_MS = 400

/**
 * Comfortably past [TEST_IDLE_MS] plus one poll tick, so a test that waits
 * this long has genuinely seen the countdown fire rather than raced it.
 */
private const val PAST_IDLE_MS = 800

/**
 * A pause shorter than [TEST_IDLE_MS] — a mouse still on its way — but long
 * enough that at least one poll tick lands inside it.
 */
private const val MID_MOVE_PAUSE_MS = 300

/**
 * Whether this browser reports a hovering pointer. The hide is scoped to
 * `@media (hover: hover) and (pointer: fine)`, so opacity assertions only
 * mean anything where the test browser matches; the body-class assertions
 * hold everywhere.
 */
private fun hasHoverPointer(): Boolean =
    window.matchMedia("(hover: hover) and (pointer: fine)").matches

class PointerActivityTest {

    private lateinit var container: HTMLElement
    private lateinit var uninstall: () -> Unit

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        // Re-arm at the short delay. The call above already installed the
        // tracker at its default 1.8s; installing again adopts the new delay
        // without stacking a second listener.
        uninstall = installPointerActivityTracking(idleMs = TEST_IDLE_MS)
        container = (document.createElement("div") as HTMLElement).also {
            it.style.width = "800px"
            it.style.height = "600px"
            it.style.position = "relative"
            document.body!!.appendChild(it)
        }
    }

    @AfterTest
    fun unmount() {
        uninstall()
        container.parentElement?.removeChild(container)
    }

    /** Renders two floating panes, each with one action, and returns their strips. */
    private fun renderTwoPanes(): List<HTMLElement> {
        val renderer = LayoutRenderer(
            container = container,
            callbacks = PaneCallbacks(
                contentRenderer = { _, slot -> slot.textContent = "content" },
                paneHeader = { id, title ->
                    PaneHeaderSpec(title = title ?: id, actions = listOf(PaneActions.close { }))
                },
                onFloatingResized = { _, _, _ -> },
            ),
        )
        renderer.render(
            layout = PaneLayout(
                floatingPanes = listOf(
                    FloatingPaneSpec(id = "p1", title = "Pane 1", xPct = 0.05, yPct = 0.05),
                    FloatingPaneSpec(id = "p2", title = "Pane 2", xPct = 0.55, yPct = 0.55),
                ),
            ),
            suppressSeparators = true,
        )
        val strips = container.querySelectorAll(".${PaneHeaderClassNames.ACTIONS}")
        assertEquals(2, strips.length, "expected one action strip per pane")
        return (0 until strips.length).map { strips.item(it) as HTMLElement }
    }

    /**
     * Dispatches a `mousemove` on the document, as far from any pane as the
     * fixture allows — the whole claim is that the movement does NOT have to
     * be over a pane.
     */
    private fun moveMouse() {
        document.dispatchEvent(
            MouseEvent(
                "mousemove",
                MouseEventInit(clientX = 1, clientY = 1, bubbles = true, cancelable = false),
            ),
        )
    }

    private fun <T> after(ms: Int, block: () -> T): Promise<T> =
        Promise { resolve, _ -> window.setTimeout({ resolve(block()) }, ms) }

    private fun isActive(): Boolean =
        document.body!!.classList.contains(POINTER_ACTIVE_BODY_CLASS)

    /**
     * The headline behaviour: one movement, nowhere near a pane, and every
     * pane's buttons are visible and aimable.
     */
    @Test
    fun anyMovementRevealsEveryPanesStrip() {
        val strips = renderTwoPanes()
        // Opacity is transitioned and `getComputedStyle` reports the value
        // mid-flight, so read settled states with the transition taken out.
        strips.forEach { it.style.transition = "none" }
        assertFalse(isActive(), "fixture should start idle")
        moveMouse()
        assertTrue(isActive(), "a mousemove did not mark the body active")
        if (!hasHoverPointer()) return
        strips.forEachIndexed { i, strip ->
            assertEquals(
                "1",
                window.getComputedStyle(strip).opacity,
                "pane $i's action strip stayed hidden after pointer movement",
            )
            assertEquals(
                "auto",
                window.getComputedStyle(strip).getPropertyValue("pointer-events").trim(),
                "pane $i's revealed strip is not clickable",
            )
        }
    }

    /**
     * And the other half: stop moving and the chrome goes away again on its
     * own, everywhere at once. A tracker that only ever added the class would
     * pass the test above and quietly pin the buttons on forever.
     */
    @Test
    fun theStripsHideAgainOnceThePointerHoldsStill(): Promise<Unit> {
        val strips = renderTwoPanes()
        strips.forEach { it.style.transition = "none" }
        moveMouse()
        assertTrue(isActive())
        return after(PAST_IDLE_MS) {
            assertFalse(isActive(), "the body stayed active after the idle delay elapsed")
            if (hasHoverPointer()) {
                strips.forEachIndexed { i, strip ->
                    assertEquals(
                        "0",
                        window.getComputedStyle(strip).opacity,
                        "pane $i's strip did not fade back out when the pointer went idle",
                    )
                }
            }
        }
    }

    /**
     * Continued movement must keep pushing the deadline out. If the countdown
     * ran from the FIRST event of a burst instead of the latest, the buttons
     * would blink out mid-drag while the user was still moving the mouse.
     */
    @Test
    fun continuedMovementKeepsPushingTheIdleDeadlineOut(): Promise<Unit> {
        renderTwoPanes()
        moveMouse()
        return after(MID_MOVE_PAUSE_MS) { moveMouse() }
            .then {
                after(MID_MOVE_PAUSE_MS) {
                    // 600 ms since the first movement (past its deadline of
                    // 400) but only 300 since the second (inside it), with a
                    // poll tick having landed in between to act on the
                    // difference.
                    assertTrue(
                        isActive(),
                        "the idle countdown ran from the first movement, not the latest",
                    )
                }
            }
            .then { }
    }

    /**
     * The tracker is installed by [injectLunulaStyles] itself, so a host that
     * mounts the stylesheet gets the behaviour without wiring anything. This
     * is the wiring that is easiest to lose in a refactor and hardest to
     * notice: everything else here would still pass with the call gone,
     * because the fixture installs the tracker by hand.
     */
    @Test
    fun injectingTheStylesheetInstallsTheTracker(): Promise<Unit> {
        // Take the fixture's tracker out, then re-inject from scratch.
        uninstall()
        assertFalse(isActive())
        moveMouse()
        assertFalse(isActive(), "uninstall left the listener attached")
        // `injectLunulaStyles` short-circuits when its <style> is already
        // mounted, which it is by now — drop it so this exercises the real
        // first-boot path a host takes.
        document.head!!.querySelector("style[data-lunula]")?.let { it.parentElement?.removeChild(it) }
        injectLunulaStyles()
        uninstall = installPointerActivityTracking(idleMs = TEST_IDLE_MS)
        moveMouse()
        assertTrue(isActive(), "injectLunulaStyles did not install pointer tracking")
        return after(PAST_IDLE_MS) { assertFalse(isActive()) }
    }

    /**
     * The CSS half. `dt-pointer-active` is a contract between Kotlin and the
     * stylesheet, and the failure mode of breaking it is silent — so assert
     * the rule survived parsing, mirroring
     * `PaneActionsHoverRevealTest.everyRevealSelectorSurvivesParsing`.
     */
    @Test
    fun theRevealRuleSurvivesParsing() {
        injectLunulaStyles()
        val selectors = parsedSelectors()
        assertNotNull(
            selectors.firstOrNull {
                it.contains("body.$POINTER_ACTIVE_BODY_CLASS") && it.contains("dt-pane-actions")
            },
            "no rule reveals `.dt-pane-actions` from `body.$POINTER_ACTIVE_BODY_CLASS` — " +
                "pointer movement now toggles a class nothing reads. Survivors " +
                "mentioning dt-pane-actions: ${selectors.filter { it.contains("dt-pane-actions") }}",
        )
    }

    /**
     * The other consumer of the class: under `SelectionStyle.Fill` the panes
     * have no frame of their own, and this rule lends them one for as long as
     * the pointer is moving. Same silent failure mode as the strip — the class
     * would keep flipping over panes that never gain an edge — and the same
     * shape of assertion.
     *
     * The rule must stay keyed on Fill. Under Tint the pane already carries a
     * permanent 2px ring, and a hairline appearing inside it on every mouse
     * move would be a second frame, not a rescue.
     */
    @Test
    fun theTransientPaneFrameRuleSurvivesParsing() {
        injectLunulaStyles()
        val selectors = parsedSelectors()
        assertNotNull(
            selectors.firstOrNull {
                it.contains("body.$POINTER_ACTIVE_BODY_CLASS") &&
                    it.contains("data-dt-selection=\"fill\"") &&
                    it.contains(".dt-pane")
            },
            "no rule frames `.dt-pane` from `body.$POINTER_ACTIVE_BODY_CLASS` under " +
                "Fill — filled panes on a dark theme are back to having no visible " +
                "edge. Survivors mentioning the class: " +
                "${selectors.filter { it.contains(POINTER_ACTIVE_BODY_CLASS) }}",
        )
    }
}

/**
 * Every selector the browser actually parsed out of the injected stylesheet,
 * media queries included (hence the recursion into nested rule lists — the
 * reveal rules all live inside `@media (hover: hover)`).
 *
 * Shared by the two CSS-contract tests above. Asserting on parsed selectors
 * rather than on the source text is the point: a stray comment terminator
 * upstream silently drops the rules that follow it, and a text search would
 * still find them.
 *
 * @return the selector text of every rule in every reachable stylesheet.
 */
private fun parsedSelectors(): List<String> {
    val selectors = mutableListOf<String>()
    fun collect(rules: dynamic) {
        val len = (rules.length as? Number)?.toInt() ?: return
        for (i in 0 until len) {
            val rule = rules.item(i) ?: continue
            val selector = rule.selectorText
            if (selector != null && selector != undefined) selectors += selector as String
            val nested = rule.cssRules
            if (nested != null && nested != undefined) collect(nested)
        }
    }
    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i).asDynamic()
        collect(runCatching { sheet.cssRules }.getOrNull() ?: continue)
    }
    return selectors
}
