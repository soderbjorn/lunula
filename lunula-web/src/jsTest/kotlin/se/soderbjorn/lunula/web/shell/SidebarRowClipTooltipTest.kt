/**
 * Browser tests for [wireSidebarRowClipTooltip] — the sidebar row's
 * hover-reveals-the-full-label tooltip.
 *
 * The contract has two halves, and both matter: a row too narrow for its label
 * must expose the full text via the row's `title` on hover, and a row wide
 * enough must expose no tooltip at all (otherwise every hover in the sidebar
 * pops a chip repeating text already on screen).
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`)
 * against the real DOM, which is what makes them worth writing: the helper
 * decides by measuring `scrollWidth` against `clientWidth`, so it only has
 * meaning where real layout runs. The rows here carry inline styles rather
 * than the `.dt-sidebar-row-label` class because `lunula.css` is not
 * loaded in the test page — the inline styles reproduce the clipping the real
 * stylesheet applies (`overflow: hidden; white-space: nowrap`).
 *
 * @see wireSidebarRowClipTooltip
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidebarRowClipTooltipTest {

    /**
     * Builds a wired row/label pair attached to the document, sized so the
     * label clips exactly when [widthPx] is too small for [text].
     *
     * @param text    label content.
     * @param widthPx hard width for the label box, in pixels.
     * @return the row element, with the tooltip already wired.
     */
    private fun buildRow(text: String, widthPx: Int): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        val label = document.createElement("span") as HTMLElement
        label.textContent = text
        // Mirrors `.dt-sidebar-row-label`'s clipping rules; `display: block` so
        // the width actually binds on the inline element.
        label.setAttribute(
            "style",
            "display: block; width: ${widthPx}px; overflow: hidden; " +
                "white-space: nowrap; text-overflow: ellipsis; font: 13px sans-serif;",
        )
        row.appendChild(label)
        document.body!!.appendChild(row)
        wireSidebarRowClipTooltip(row, label)
        return row
    }

    /** Dispatches a synthetic `mouseenter` on [target]. */
    private fun enter(target: HTMLElement) {
        target.dispatchEvent(Event("mouseenter"))
    }

    @Test
    fun clippedLabelGetsFullTitleOnHover() {
        val full = "a-very-long-pane-title-that-cannot-possibly-fit"
        val row = buildRow(full, widthPx = 40)
        try {
            enter(row)
            assertEquals(full, row.getAttribute("title"))
        } finally {
            row.remove()
        }
    }

    @Test
    fun labelThatFitsGetsNoTooltip() {
        val row = buildRow("zsh", widthPx = 400)
        try {
            enter(row)
            assertNull(row.getAttribute("title"))
        } finally {
            row.remove()
        }
    }

    /**
     * Widening the row between hovers must retire the tooltip. Guards the
     * measure-on-every-hover design: a build-time check would leave the stale
     * tooltip behind, since the sidebar is user-resizable.
     */
    @Test
    fun tooltipIsDroppedOnceTheLabelFits() {
        val row = buildRow("a-very-long-pane-title-that-cannot-possibly-fit", widthPx = 40)
        try {
            enter(row)
            assertEquals(
                "a-very-long-pane-title-that-cannot-possibly-fit",
                row.getAttribute("title"),
                "precondition: the narrow row should be showing a tooltip",
            )
            val label = row.firstElementChild as HTMLElement
            label.style.width = "600px"
            enter(row)
            assertNull(row.getAttribute("title"))
        } finally {
            row.remove()
        }
    }

    /**
     * Renaming a pane rewrites the label's text node in place
     * (`AppShellMount.refreshPaneLabelsInPlace`) and syncs nothing else, on the
     * grounds that this helper re-reads the DOM on each hover. That is the
     * assumption under test.
     */
    @Test
    fun tooltipFollowsAnInPlaceLabelRewrite() {
        val row = buildRow("original-long-pane-title-that-overflows", widthPx = 40)
        try {
            enter(row)
            val label = row.firstElementChild as HTMLElement
            label.textContent = "renamed-long-pane-title-that-overflows"
            enter(row)
            assertEquals("renamed-long-pane-title-that-overflows", row.getAttribute("title"))
        } finally {
            row.remove()
        }
    }
}
