/**
 * Hover tooltip for sidebar rows whose label has been clipped.
 *
 * Sidebar rows squeeze their label into whatever width the (user-resizable)
 * sidebar currently has, clipping the overflow — from the tail via
 * `text-overflow: ellipsis`, or from the head in the `.dt-sidebar-row-label-rtl`
 * start-clip mode. Either way the user loses text, and the only way to read it
 * is to widen the sidebar or open the pane.
 *
 * This file wires the missing affordance: hovering a row whose label does not
 * fit reveals the full label in a tooltip. Rows that fit get no tooltip at all,
 * so hovering the sidebar to click a pane does not pop a chip that only repeats
 * text already on screen.
 *
 * Used by [buildPaneRow] (the app-shell pane list) and [SidebarRow] (the public
 * row primitive) so both surfaces behave identically.
 *
 * @see wireSidebarRowClipTooltip
 * @see SidebarRow
 */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Slack allowed when comparing a label's laid-out width against its visible
 * width. Fractional font metrics routinely leave `scrollWidth` a hair above
 * `clientWidth` on labels that are not actually clipped (both are rounded to
 * whole pixels), so an exact `>` comparison would tooltip short labels. One
 * pixel is below the threshold where any glyph is actually hidden.
 */
private const val CLIP_TOLERANCE_PX = 1

/**
 * Gives [row] a native `title` tooltip carrying [label]'s full text, but only
 * while that text is actually clipped.
 *
 * The check runs on every `mouseenter` rather than once at build time because
 * both inputs change after the row is built: the user resizes the sidebar, and
 * the label itself is rewritten in place on rename (see
 * `AppShellMount.refreshPaneLabelsInPlace`). Measuring at hover time keeps the
 * tooltip correct through both without either code path having to know about
 * it. The current text is read back from the DOM for the same reason — it is
 * the one source that is always up to date.
 *
 * Setting `title` during `mouseenter` still surfaces the tooltip for that same
 * hover: the browser resolves the attribute when its hover-delay timer fires,
 * which is after this listener has run.
 *
 * Callers: [buildPaneRow] for app-shell pane rows, [SidebarRow] for host-built
 * rows. Safe to call on any row/label pair; the listener holds no state and is
 * collected with the elements.
 *
 * @param row   element the tooltip is attached to — the whole row, so the
 *   tooltip appears wherever the pointer rests on it, not just over the text.
 * @param label the clipping element, i.e. the one carrying
 *   `.dt-sidebar-row-label`. Both the measurement and the tooltip text come
 *   from this element.
 * @see CLIP_TOLERANCE_PX
 */
internal fun wireSidebarRowClipTooltip(row: HTMLElement, label: HTMLElement) {
    row.addEventListener("mouseenter", { _: Event ->
        val text = label.textContent?.trim().orEmpty()
        val isClipped = label.scrollWidth - label.clientWidth > CLIP_TOLERANCE_PX
        if (text.isNotEmpty() && isClipped) {
            if (row.getAttribute("title") != text) row.setAttribute("title", text)
        } else if (row.hasAttribute("title")) {
            row.removeAttribute("title")
        }
    })
}
