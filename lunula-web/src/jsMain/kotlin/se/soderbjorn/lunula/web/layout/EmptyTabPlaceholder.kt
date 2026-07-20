/*
 * EmptyTabPlaceholder.kt (jsMain)
 * -------------------------------
 * Tab-level empty-state element shown when an active tab contains no panes
 * — e.g. immediately after the user closes the last pane in a tab. Mirrors
 * termtastic's `buildEmptyTabPlaceholder` so the family converges on one
 * "this tab has no panes" affordance with a primary call-to-action.
 *
 * Hosts mount the result in place of [LayoutRenderer]'s container when
 * `PaneLayout.floatingPanes` is empty. The button click re-enters the
 * host's "spawn pane" flow.
 *
 * Visual styles ride on the `.dt-empty-tab*` classes shipped in
 * `lunula.css`; consumers must call
 * [se.soderbjorn.lunula.web.injectLunulaStyles] once at boot.
 *
 * @see renderEmptyPanePlaceholder for the in-pane "blank slot" variant.
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * Build a styled empty-tab placeholder with a primary action button.
 *
 * Used by hosts whose tab can be in a "no panes" state: render this in
 * place of the [LayoutRenderer] container so the user gets a clear
 * call-to-action instead of a blank canvas.
 *
 * @param message    headline text shown above the button. Defaults to
 *   termtastic's wording.
 * @param buttonText label of the primary action button. Defaults to
 *   "New pane".
 * @param onAdd      invoked when the user clicks the button. Hosts wire
 *   this to whatever spawns a fresh pane in the active tab.
 * @return a fresh `<div class="dt-empty-tab">` element ready to be
 *   attached to the active tab's pane host.
 */
fun renderEmptyTabPlaceholder(
    message: String = "This tab has no panes.",
    buttonText: String = "New pane",
    onAdd: () -> Unit,
): HTMLElement {
    val wrap = document.createElement("div") as HTMLElement
    wrap.className = "dt-empty-tab"
    val msg = document.createElement("div") as HTMLElement
    msg.className = "dt-empty-tab-message"
    msg.textContent = message
    val btn = document.createElement("button") as HTMLButtonElement
    btn.type = "button"
    btn.className = "dt-empty-tab-button"
    btn.textContent = buttonText
    btn.addEventListener("click", { ev ->
        ev.stopPropagation()
        onAdd()
    })
    wrap.appendChild(msg)
    wrap.appendChild(btn)
    return wrap
}
