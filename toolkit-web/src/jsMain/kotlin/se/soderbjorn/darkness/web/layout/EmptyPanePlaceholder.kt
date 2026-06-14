/*
 * EmptyPanePlaceholder.kt (jsMain)
 * --------------------------------
 * Default empty-state element for a pane whose content is intentionally
 * blank — e.g. a freshly split pane the host has no content model for yet.
 * Replaces the ad-hoc `[ empty pane: id ]` debug strings consumers were
 * previously rolling on their own.
 *
 * Visual treatment is centered, dim, with a single short caption — the
 * "nothing to do here" affordance. Apps that want a richer empty state
 * (call-to-action, inline new-tab button, etc.) should build their own
 * element instead.
 *
 * @see renderEmptyPanePlaceholder
 */
package se.soderbjorn.darkness.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Build a styled "empty pane" placeholder element. Apps mount this from
 * their [PaneCallbacks.contentRenderer] when a pane id has no content to
 * render.
 *
 * @param caption the visible message (defaults to a generic, polite
 *   "Empty pane"). Apps can pass an app-specific call-to-action, e.g.
 *   "Drop a note here" or "Pick a file to view".
 * @return a fresh `<div>` carrying the `.dt-pane-empty` class for CSS
 *   theming.
 */
fun renderEmptyPanePlaceholder(caption: String = "Empty pane"): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "dt-pane-empty"
    val textEl = document.createElement("span") as HTMLElement
    textEl.className = "dt-pane-empty-caption"
    textEl.textContent = caption
    el.appendChild(textEl)
    return el
}
