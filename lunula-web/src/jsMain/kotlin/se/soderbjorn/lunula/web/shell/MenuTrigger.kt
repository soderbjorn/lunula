/*
 * MenuTrigger.kt (jsMain)
 * -----------------------
 * The closed face of a menu: a button that reads as a value with a chevron.
 *
 * Every menu in the toolkit already shares one panel geometry (see the "Menu
 * surface" block in `lunula.css`). This is the other half of that object — the
 * control the panel hangs off — and it lives here for the same reason: an app
 * that draws its own ends up with a chevron the theme cannot reach.
 *
 * That is not hypothetical. The shape this replaces was a `<button>` with the
 * chevron painted as a `background-image` data URI, which means a colour
 * literal: a background image cannot take `currentColor` and cannot be rotated,
 * so the arrow could neither follow the palette nor flip when the menu opened.
 * Lunicle shipped a bright cyan one under every theme for exactly that reason.
 * An element can do both, so the chevron is an element.
 *
 * What this does NOT own is the menu, the anchoring or the dismissal. A trigger
 * is a button and a label; which panel it opens, and where, stays with whoever
 * built the panel — see [attachHoverMenu] and the hosts' own dropdowns.
 *
 * @see buildMenuTrigger
 * @see MenuTriggerZone
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * Toolkit DOM class names used by [buildMenuTrigger]. Stable so host
 * stylesheets can hang their own surface rules off them.
 */
object MenuTriggerClassNames {
    const val TRIGGER = "dt-menu-trigger"
    const val LABEL = "dt-menu-trigger-label"
    const val UNSET = "dt-menu-trigger-unset"
    const val CHEVRON = "dt-menu-trigger-chevron"
    const val DOT = "dt-menu-trigger-dot"

    /** The form-field variant: 34px, filled, ringed rather than filled on open. */
    const val FIELD = "dt-menu-trigger-field"

    /**
     * Chrome-zone marker, worn by a trigger on the top bar and by the panel it
     * opens. Selects the `--t-chrome-*` half of the palette; see the menu
     * surface block in `lunula.css` for why a menu belongs to its zone.
     */
    const val CHROME = "dt-menu-chrome"

    /** A menu row that holds the control's current value. */
    const val SELECTED = "dt-menu-selected"

    /** A trailing hint on a menu row — a keyboard shortcut, a count. */
    const val SHORTCUT = "dt-menu-shortcut"
}

/**
 * Which half of the palette a trigger and its menu paint from.
 *
 * A menu belongs to the zone it was opened from: one raised from a pane is
 * content, one raised from the top bar is chrome. On a theme that paints the
 * two differently the distinction is the whole visual structure, and collapsing
 * it is how one app starts reading as two.
 */
enum class MenuTriggerZone {
    /** Inside a pane — `--t-accent`, `--t-accent-on`. The default. */
    Content,

    /** On the top bar — `--t-chrome-accent`, `--t-chrome-accent-on`. */
    Chrome,
    ;

    /** The class this zone puts on a trigger or a panel, or `""` for content. */
    val className: String get() = if (this == Chrome) MenuTriggerClassNames.CHROME else ""
}

/**
 * The chevron. Drawn at 11px with `currentColor`, so it inherits the trigger's
 * text colour and the stylesheet can rotate it when the menu opens.
 */
private const val CHEVRON_SVG: String =
    "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.4\" " +
        "stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M6 9l6 6 6-6\"/></svg>"

/**
 * Build a menu trigger.
 *
 * The returned button starts closed and unset; call [setMenuTriggerLabel] on
 * every render and [setMenuTriggerOpen] when the menu opens and closes. Both
 * are idempotent, so a host is free to call them from a state flow that emits
 * more often than the value changes.
 *
 * @param zone which half of the palette to paint from.
 * @param isField `true` for the form variant — 34px, full width, filled, and
 *   ringed rather than filled when open. `false` for the toolbar pill.
 * @param extraClass host classes appended to the button, for surface rules the
 *   toolkit has no opinion about.
 * @param onClick fires on every press. The host decides whether that opens or
 *   closes; this does not track the menu.
 */
fun buildMenuTrigger(
    zone: MenuTriggerZone = MenuTriggerZone.Content,
    isField: Boolean = false,
    extraClass: String = "",
    onClick: () -> Unit = {},
): HTMLButtonElement {
    val btn = document.createElement("button") as HTMLButtonElement
    btn.type = "button"
    btn.className = listOf(
        MenuTriggerClassNames.TRIGGER,
        if (isField) MenuTriggerClassNames.FIELD else "",
        zone.className,
        extraClass,
    ).filter { it.isNotEmpty() }.joinToString(" ")
    btn.setAttribute("aria-haspopup", "menu")
    btn.setAttribute("aria-expanded", "false")

    // The dot leads the label and is absent, not merely invisible, until the
    // value moves off its default — a hidden element would still claim the gap
    // beside it and leave every unchanged trigger looking indented.
    val label = document.createElement("span") as HTMLElement
    label.className = MenuTriggerClassNames.LABEL

    val chevron = document.createElement("span") as HTMLElement
    chevron.className = MenuTriggerClassNames.CHEVRON
    // Sanctioned innerHTML: the argument is this file's own constant, so there
    // is no input to escape and no caller who could supply one.
    chevron.innerHTML = CHEVRON_SVG

    btn.appendChild(label)
    btn.appendChild(chevron)
    btn.addEventListener("click", { onClick() })
    return btn
}

/**
 * Set what the trigger reads, and whether that value is one the user chose.
 *
 * @param text the current value, or the placeholder when there is none.
 * @param isUnset `true` when [text] is a placeholder rather than a value, which
 *   renders it dim. "None" and "Nobody" are values a user can pick and are not
 *   unset — see the field select's note in the design.
 * @param isChanged `true` to show the accent dot: this control has moved off
 *   its default, and what you are looking at is therefore filtered. Only the
 *   toolbar pills use it; a form field has no default to differ from.
 * @param title hover text, defaulting to [text] so a value truncated by the
 *   control's width is still readable. Pass `""` to leave it off.
 */
fun setMenuTriggerLabel(
    trigger: HTMLElement,
    text: String,
    isUnset: Boolean = false,
    isChanged: Boolean = false,
    title: String = text,
) {
    val label = trigger.querySelector(".${MenuTriggerClassNames.LABEL}") as? HTMLElement ?: return
    if (label.textContent != text) label.textContent = text
    label.classList.toggle(MenuTriggerClassNames.UNSET, isUnset)
    if (trigger.getAttribute("title") != title) trigger.setAttribute("title", title)

    val existing = trigger.querySelector(".${MenuTriggerClassNames.DOT}") as? HTMLElement
    when {
        isChanged && existing == null -> {
            val dot = document.createElement("span") as HTMLElement
            dot.className = MenuTriggerClassNames.DOT
            trigger.insertBefore(dot, label)
        }
        !isChanged && existing != null -> existing.remove()
    }
}

/**
 * Say whether the menu is up. Drives the fill (or the ring) and flips the
 * chevron; both are the stylesheet's, keyed off `aria-expanded` so the paint
 * and the accessibility tree cannot drift apart.
 */
fun setMenuTriggerOpen(trigger: HTMLElement, isOpen: Boolean) {
    trigger.setAttribute("aria-expanded", isOpen.toString())
}
