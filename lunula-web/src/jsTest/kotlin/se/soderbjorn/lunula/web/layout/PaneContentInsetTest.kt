/*
 * PaneContentInsetTest.kt (jsTest)
 * --------------------------------
 * Geometry regression for the frame `.dt-pane-content` leaves around itself,
 * measured in real pixels through the browser's own layout after
 * `injectLunulaStyles()`.
 *
 * By default the pane body sits `--dt-pane-content-inset` (4px) in from the
 * sides and the bottom of its pane, so the pane's `--t-surface` shows as a
 * band around it — a window edge, which is what a pane whose body is a
 * terminal or an editor cell wants, and which termtastic/Lunamux builds its
 * own matching inset on top of.
 *
 * A host whose pane body is one continuous document surface wants no such
 * band: there the strip is a third tone with no other job on the window,
 * drawn on three sides only because the header covers the fourth, and it
 * reads as the pane's background leaking out from behind the body rather
 * than as a frame. That is what LunaPin reported as LPN-64 — "wrong colour
 * seeping through on window sides and bottom" — and the fix is the host
 * setting the token to 0.
 *
 * Both halves are asserted because both are easy to break and neither shows
 * up in any other test:
 *
 *  - the DEFAULT must stay exactly 4px, or every consumer that never heard
 *    of this token silently moves;
 *  - at 0 the body must reach the pane's edge on all three sides, or the
 *    bug this token exists to fix comes back with a knob that appears to
 *    work.
 *
 * The rule is pure CSS, so the fixture is built by hand from the class names
 * rather than driven through the renderer: what is under test is the
 * stylesheet, not who mounts it.
 *
 * @see se.soderbjorn.lunula.web.injectLunulaStyles
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.Test
import kotlin.test.assertEquals

/** The inset the toolkit applies when a host says nothing. */
private const val DEFAULT_INSET_PX: Double = 4.0

/**
 * Mounts `.dt-app-frame > .dt-pane > .dt-pane-content` at a fixed size and
 * returns the body element, with [inset] written onto the frame as
 * `--dt-pane-content-inset` when it is non-null (null = a host that never
 * sets the token, which is the case every existing consumer is in).
 *
 * The pane is given an explicit size because `.dt-pane` is `flex: 1 1 auto`
 * with `min-height: 0`: dropped into a bare `<body>` with no sized ancestor
 * it lays out at zero height, and every gap below would measure 0 and pass
 * for the wrong reason.
 */
private fun mountPaneBody(inset: String?): HTMLElement {
    val frame = document.createElement("div") as HTMLElement
    frame.className = "dt-app-frame"
    if (inset != null) frame.style.setProperty("--dt-pane-content-inset", inset)

    val pane = document.createElement("div") as HTMLElement
    pane.className = "dt-pane"
    pane.style.width = "400px"
    pane.style.height = "300px"

    val body = document.createElement("div") as HTMLElement
    body.className = "dt-pane-content"

    pane.appendChild(body)
    frame.appendChild(pane)
    document.body?.appendChild(frame)
    return body
}

/** How far [this] element's painted box sits inside [outer], per side. */
private fun HTMLElement.gapsInside(outer: HTMLElement): Gaps {
    val inner = getBoundingClientRect()
    val box = outer.getBoundingClientRect()
    return Gaps(
        top = inner.top - box.top,
        left = inner.left - box.left,
        right = box.right - inner.right,
        bottom = box.bottom - inner.bottom,
    )
}

private data class Gaps(
    val top: Double,
    val left: Double,
    val right: Double,
    val bottom: Double,
)

class PaneContentInsetTest {

    @Test
    fun aHostThatSaysNothingKeepsTheFourPixelFrame() {
        injectLunulaStyles()
        val body = mountPaneBody(inset = null)
        val pane = body.parentElement as HTMLElement
        val gaps = body.gapsInside(pane)

        // The header sits flush at the top of the pane, so the body does too:
        // the frame is a three-sided band, never four.
        assertEquals(0.0, gaps.top, "the pane body must stay flush with the top of its pane")
        assertEquals(DEFAULT_INSET_PX, gaps.left, "the default left frame moved")
        assertEquals(DEFAULT_INSET_PX, gaps.right, "the default right frame moved")
        assertEquals(DEFAULT_INSET_PX, gaps.bottom, "the default bottom frame moved")
    }

    @Test
    fun aHostCanTurnTheFrameOffAndTheBodyReachesEveryEdge() {
        injectLunulaStyles()
        val body = mountPaneBody(inset = "0")
        val pane = body.parentElement as HTMLElement
        val gaps = body.gapsInside(pane)

        // LPN-64: any non-zero gap here is a strip of the pane's own
        // `--t-surface` showing through beside a body painted `--t-bg`.
        assertEquals(0.0, gaps.left, "pane surface still shows down the left of the body")
        assertEquals(0.0, gaps.right, "pane surface still shows down the right of the body")
        assertEquals(0.0, gaps.bottom, "pane surface still shows along the bottom of the body")
    }

    @Test
    fun theBodyIsNeverRounderThanTheFrameIsThick() {
        injectLunulaStyles()
        // A radius wider than the inset carves a notch at each corner with
        // nothing behind it but the pane's surface — the very artifact the
        // inset is meant to be, one corner at a time. So the radius tracks
        // the inset down rather than staying at its flat 4px.
        val squared = mountPaneBody(inset = "0")
        assertEquals(
            "0px",
            kotlinx.browser.window.getComputedStyle(squared).borderRadius,
            "a frameless pane body must square off with the pane's own clip",
        )

        val thin = mountPaneBody(inset = "2px")
        assertEquals(
            "2px",
            kotlinx.browser.window.getComputedStyle(thin).borderRadius,
            "the body's radius must not exceed the frame it is nested in",
        )
    }
}
