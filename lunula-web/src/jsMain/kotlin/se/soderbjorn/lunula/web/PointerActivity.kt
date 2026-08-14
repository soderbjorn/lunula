/**
 * PointerActivity.kt
 *
 * Document-level "is the mouse moving right now?" tracker, published to the
 * stylesheet as the `dt-pointer-active` class on `<body>`.
 *
 * It exists for the pane titlebar action strip. `.dt-pane-actions` rests at
 * `opacity: 0` and comes back on hover, which means the buttons only appear
 * for the one pane the cursor happens to be over, and only once it has
 * arrived. With this installed, ANY mouse movement anywhere in the window
 * reveals the strip on EVERY pane at once, and they all fade out together
 * once the pointer has been still for a while — so the buttons are already
 * there when the user starts reaching for them, and the panes go back to
 * reading as just title + content when nobody is driving.
 *
 * A second thing now rides the same signal. Under `SelectionStyle.Fill` panes
 * carry no frame at all — the canvas gutter is meant to separate them — and on
 * a dark theme whose surface sits close to the canvas it does not, leaving the
 * pane edges hard to find. While the class is present each pane also gets a
 * hairline `--t-chrome-border` outline, so the edges are there whenever the
 * user is driving and gone again when the screen settles.
 *
 * Scope is the app window, deliberately. A renderer gets no pointer events
 * outside its own viewport, so "anywhere on screen" is not reachable from
 * here at all: it would take main-process cursor polling (Electron's
 * `screen.getCursorScreenPoint`) or a native global hook, and neither is
 * worth it for chrome that the user is on their way to anyway — the cursor
 * crosses the window edge long before it reaches a pane.
 *
 * Cost is one number written per `mousemove`. The idle countdown is a single
 * ~250 ms interval that only runs while the pointer is active and is cleared
 * the moment it goes idle, rather than a `setTimeout` re-armed on every
 * event — a drag across the window would otherwise churn thousands of timers.
 *
 * @see injectLunulaStyles which installs this at boot
 * @see se.soderbjorn.lunula.web.layout.PaneHeaderClassNames.ACTIONS
 */
package se.soderbjorn.lunula.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import kotlin.js.Date

/**
 * Class the tracker keeps on `<body>` while the pointer is considered
 * active. The stylesheet reveals every pane's action strip while it is
 * present, and — under `SelectionStyle.Fill` only — paints a hairline frame
 * around every pane; see the `body.dt-pointer-active .dt-pane-actions` and
 * `[data-dt-selection="fill"] body.dt-pointer-active .dt-pane` rules in
 * `lunula.css`.
 */
const val POINTER_ACTIVE_BODY_CLASS: String = "dt-pointer-active"

/**
 * How long the pointer must hold still before the chrome fades back out.
 *
 * Long enough to survive a pause mid-reach (reading a title, deciding which
 * pane), short enough that a screen left alone settles quickly.
 */
const val DEFAULT_POINTER_IDLE_MS: Int = 1800

/**
 * How often the idle countdown is checked while the pointer is active.
 * Coarse on purpose: the fade is 300 ms, so a quarter-second of slop in
 * *when* it starts is invisible, and this ticks at 4 Hz instead of arming a
 * timer per mouse event.
 */
private const val IDLE_POLL_MS: Int = 250

/** Whether [installPointerActivityTracking] has already wired its listener. */
private var installed: Boolean = false

/** Timestamp of the most recent `mousemove`, per [Date.now]. */
private var lastMoveMs: Double = 0.0

/**
 * Handle of the live idle-poll interval, or `null` when the pointer is idle.
 * Doubles as the "am I currently active?" flag, which is why the mousemove
 * handler can be a single null-check plus an assignment in the common case.
 */
private var idlePoll: Int? = null

/** Current idle delay; see [DEFAULT_POINTER_IDLE_MS]. */
private var idleAfterMs: Int = DEFAULT_POINTER_IDLE_MS

/**
 * Class put on the ONE pane whose titlebar the pointer came to rest just
 * below, while the rest of the chrome fades out. The stylesheet reveals that
 * pane's action strip from it — see the `.dt-pane-header-near` clause beside
 * `.dt-pane-header:hover`.
 *
 * **This used to be a hover.** The band that measures the reach
 * (`.dt-pane-header-proximity`) was hit-testable and `:hover` on it drove the
 * reveal, which meant it also swallowed every press in the top 26px of every
 * pane's content: the first row of a terminal, a link at the top of a page,
 * the upper half of a field mounted right under the titlebar. Hovering and
 * hit-testing are the same thing in CSS, so the only way to stop eating the
 * press was to stop asking the pointer where it is and work it out here.
 */
const val PANE_NEAR_HEADER_CLASS: String = "dt-pane-header-near"

/** Where the pointer was last seen, in client coordinates; -1 before any move. */
private var lastX: Double = -1.0
private var lastY: Double = -1.0

/** The pane currently carrying [PANE_NEAR_HEADER_CLASS], or null. */
private var paneNearHeader: Element? = null

/** Take the mark off whatever holds it. A null check when nothing does. */
private fun clearPaneNearMark() {
    paneNearHeader?.classList?.remove(PANE_NEAR_HEADER_CLASS)
    paneNearHeader = null
}

/**
 * Mark the pane whose titlebar the pointer is resting just below, if any.
 *
 * The zone is read off `.dt-pane-header-proximity` rather than named again
 * here: that element is a zero-height flow sibling of the header, so its top
 * *is* the titlebar's bottom whatever the density tokens make of it, and its
 * `::before` height is the reach — one tunable (`--dt-pane-proximity-reach`),
 * still in the stylesheet, still the thing a host retunes.
 *
 * Costs one hit test and two style reads, once per pointer-goes-idle. The
 * hit test is what makes "which pane" honest with panes that overlap: the
 * topmost element at the cursor decides, exactly as `:hover` did.
 */
private fun markPaneNearPointer() {
    clearPaneNearMark()
    if (lastX < 0) return
    val under = document.elementFromPoint(lastX, lastY) ?: return
    val pane = under.closest(".dt-pane") ?: return
    val band = pane.querySelector(":scope > .dt-pane-header-proximity") as? HTMLElement ?: return
    val box = band.getBoundingClientRect()
    if (lastX < box.left || lastX > box.right) return
    val reach = window.getComputedStyle(band, "::before")
        .height
        .removeSuffix("px")
        .toDoubleOrNull() ?: return
    if (lastY < box.top || lastY > box.top + reach) return
    pane.classList.add(PANE_NEAR_HEADER_CLASS)
    paneNearHeader = pane
}

/**
 * Drop the active class and stop the poll. Safe to call when already idle.
 *
 * The one moment the proximity mark matters, and therefore the only moment it
 * is computed: while the pointer is moving every strip is revealed anyway, so
 * a pane resting under the cursor has nothing to say until the movement stops.
 * See [markPaneNearPointer].
 */
private fun goIdle() {
    idlePoll?.let { window.clearInterval(it) }
    idlePoll = null
    markPaneNearPointer()
    document.body?.classList?.remove(POINTER_ACTIVE_BODY_CLASS)
}

/**
 * The `mousemove` handler. Hot path — it must stay a timestamp write for
 * every event after the first of a burst.
 */
private val onPointerMove: (org.w3c.dom.events.Event) -> Unit = { event ->
    lastMoveMs = Date.now()
    (event as? MouseEvent)?.let {
        lastX = it.clientX.toDouble()
        lastY = it.clientY.toDouble()
    }
    // The mark belongs to a position the pointer has left. Dropping it here
    // costs a null check per event in the common case, and leaving it would
    // strand one pane revealed after the rest had faded.
    clearPaneNearMark()
    if (idlePoll == null) {
        document.body?.classList?.add(POINTER_ACTIVE_BODY_CLASS)
        idlePoll = window.setInterval(
            { if (Date.now() - lastMoveMs >= idleAfterMs) goIdle() },
            IDLE_POLL_MS,
        )
    }
}

/**
 * Start tracking pointer activity for this document.
 *
 * Called from [injectLunulaStyles], so a host that mounts the toolkit
 * stylesheet gets the behaviour without wiring anything. Idempotent:
 * calling it again does not add a second listener, but it does adopt the
 * [idleMs] passed — which is how a test (or a host that wants a different
 * dwell) retunes it.
 *
 * The listener is registered `capture` + `passive`: capture so a component
 * that stops propagation on its own `mousemove` (a terminal, a drag handler)
 * cannot blind the tracker, and passive because it never calls
 * `preventDefault` and the browser should not have to wait to find out.
 *
 * No `(hover: hover)` gate here. The hide/reveal rules are all inside that
 * media query in the stylesheet, so on a touch device the class is inert —
 * whereas gating the *listener* on a boot-time media query would strand a
 * hybrid device that gains a mouse later.
 *
 * @param idleMs how long the pointer must be still before the chrome hides.
 * @return an uninstaller that removes the listener, stops the poll and
 *   clears the class. Mainly for tests; hosts install once for the process.
 * @see POINTER_ACTIVE_BODY_CLASS
 */
fun installPointerActivityTracking(idleMs: Int = DEFAULT_POINTER_IDLE_MS): () -> Unit {
    idleAfterMs = idleMs
    if (!installed) {
        installed = true
        document.addEventListener(
            "mousemove",
            onPointerMove,
            js("({ passive: true, capture: true })"),
        )
    }
    return {
        if (installed) {
            installed = false
            document.removeEventListener("mousemove", onPointerMove, js("({ capture: true })"))
            idleAfterMs = DEFAULT_POINTER_IDLE_MS
            goIdle()
            // …and then take back the mark [goIdle] just placed: with the
            // listener gone nothing would ever clear it, leaving one pane
            // permanently revealed after an uninstall.
            clearPaneNearMark()
        }
    }
}
