/**
 * Collapsible section primitive for use inside [SidebarSpec.content].
 *
 * A section is a chevron-prefixed header with a vertically stacking child
 * list. The host owns the open/closed state — toolkit just paints what it
 * is told and emits a single `onToggle` event when the header is clicked.
 *
 * Built so multiple sections can stack inside a single sidebar (note
 * outline + bookmarks + history, etc.) and each tracks its own open state.
 *
 * Visual styles ride on `.dt-sidebar-section*` classes shipped in
 * `darkness-toolkit.css`.
 *
 * @see SidebarSectionSpec
 * @see renderSidebarSection
 * @see SidebarRow
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.darkness.web.util.encircledIndexGlyph

/**
 * Configuration for a single collapsible sidebar section.
 *
 * @property title       text shown next to the chevron in the section header.
 * @property isOpen      whether the section is currently expanded. The toolkit
 *   never mutates this — it is read-only state owned by the host. Flip the
 *   value and re-call [renderSidebarSection] (or rebuild the sidebar) to
 *   collapse/expand.
 * @property items       row elements rendered inside the section body when
 *   open. Build these freely — the toolkit ships [SidebarRow] for the common
 *   icon-plus-label case but does not require it.
 * @property onToggle    fired when the user clicks the section header. The
 *   host should flip its open state and re-render. May be null for static
 *   always-open sections (the chevron then renders as a non-interactive cue).
 * @property trailingHeader optional element placed flush-right inside the
 *   header (action button, count badge, etc.). Click events on this element
 *   should call `stopPropagation` if they should not also toggle the section.
 */
data class SidebarSectionSpec(
    val title: String,
    val isOpen: Boolean,
    val items: List<HTMLElement>,
    val onToggle: (() -> Unit)? = null,
    val trailingHeader: HTMLElement? = null,
)

/**
 * Builds a fresh collapsible-section element from [spec].
 *
 * The returned element has `.dt-sidebar-section` plus `.dt-open` /
 * `.dt-closed` depending on [SidebarSectionSpec.isOpen]; rows are rendered
 * inside `.dt-sidebar-section-children` which is `display: none` when
 * closed.
 *
 * @param spec section configuration
 * @return a fresh section [HTMLElement] suitable for appending to
 *   `SidebarSpec.content`
 * @see SidebarSectionSpec
 */
fun renderSidebarSection(spec: SidebarSectionSpec): HTMLElement {
    val section = document.createElement("div") as HTMLElement
    section.className = "dt-sidebar-section ${if (spec.isOpen) "dt-open" else "dt-closed"}"

    val header = document.createElement("button") as HTMLElement
    header.className = "dt-sidebar-section-header"
    header.setAttribute("type", "button")
    header.setAttribute("aria-expanded", spec.isOpen.toString())

    val chevron = document.createElement("span") as HTMLElement
    chevron.className = "dt-sidebar-section-chevron"
    // Single rotating glyph; CSS rotates 90° when the section is closed so
    // the whole section reads at-a-glance even when scanned peripherally.
    chevron.innerHTML =
        """<svg viewBox="0 0 16 16" width="10" height="10" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><polyline points="4,6 8,10 12,6"/></svg>"""
    header.appendChild(chevron)

    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "dt-sidebar-section-title"
    titleEl.textContent = spec.title
    header.appendChild(titleEl)

    spec.trailingHeader?.let {
        val slot = document.createElement("span") as HTMLElement
        slot.className = "dt-sidebar-section-trailing"
        slot.appendChild(it)
        header.appendChild(slot)
    }

    if (spec.onToggle != null) {
        header.addEventListener("click", { _: Event -> spec.onToggle.invoke() })
    } else {
        header.setAttribute("disabled", "")
    }
    section.appendChild(header)

    val children = document.createElement("div") as HTMLElement
    children.className = "dt-sidebar-section-children"
    for (item in spec.items) children.appendChild(item)
    section.appendChild(children)

    return section
}

/**
 * Convenience builder for the common "icon + label, click to do something"
 * sidebar row. Hosts that need bespoke row layout (badges, multi-line,
 * inline rename, etc.) should construct the element directly.
 *
 * The returned element has `.dt-sidebar-row` plus `.dt-active` if [isActive].
 *
 * Whenever the row is too narrow for [label], hovering it reveals the full text
 * in a tooltip; rows wide enough to show their label get no tooltip. See
 * [wireSidebarRowClipTooltip].
 *
 * @param label       row text — overflow tail-truncates with ellipsis.
 * @param iconHtml    optional inline SVG / icon HTML rendered before the
 *   label. Keep glyphs ≤ 14×14 to align with the toolkit's other icon slots.
 * @param leadingBadge optional element placed between the icon and the
 *   label (status spinner, unread dot, etc.).
 * @param isActive    paints the row with the toolkit's active-row treatment
 *   (used to highlight the current note, focused pane, etc.).
 * @param handler     fired when the row is clicked. May be null for
 *   informational rows.
 * @param labelRtl    when `true`, the label overflows from the LEFT instead
 *   of the right (CSS `direction: rtl; text-align: left`) so long
 *   path-style labels (notegrow's zoom path, file-tree paths) keep the
 *   rightmost segment visible. Mirrors `PaneHeaderSpec.titleAlignRight`.
 * @param index       optional 1-based pane slot. When non-null and within
 *   the renderable range (`1..35`), the toolkit appends an encircled glyph
 *   (`①..⑨`, `Ⓐ..Ⓩ`) at the trailing edge of the row and forces the label
 *   to start-clip so both the badge and the label's informative tail
 *   remain visible. Apps maintain the index via
 *   [se.soderbjorn.darkness.web.util.PaneSlotAssigner]; out-of-range or
 *   null values render the row as before.
 * @return a fresh row [HTMLElement]
 */
fun SidebarRow(
    label: String,
    iconHtml: String? = null,
    leadingBadge: HTMLElement? = null,
    isActive: Boolean = false,
    handler: (() -> Unit)? = null,
    labelRtl: Boolean = false,
    index: Int? = null,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-sidebar-row" + if (isActive) " dt-active" else ""
    if (handler != null) row.setAttribute("role", "button")

    iconHtml?.let {
        val icon = document.createElement("span") as HTMLElement
        icon.className = "dt-sidebar-row-icon"
        icon.innerHTML = it
        row.appendChild(icon)
    }
    leadingBadge?.let {
        val badge = document.createElement("span") as HTMLElement
        badge.className = "dt-sidebar-row-badge"
        badge.appendChild(it)
        row.appendChild(badge)
    }
    val indexGlyph = index?.let { encircledIndexGlyph(it) }
    // Same start-clip rationale as PaneHeader: the badge sits at the row's
    // trailing edge with `flex: 0 0 auto`, and the label's informative tail
    // (current dir, note name) lives at the right end too — start-clip
    // keeps both visible when space is tight.
    val forceStartClip = indexGlyph != null
    val labelEl = document.createElement("span") as HTMLElement
    labelEl.className = "dt-sidebar-row-label" +
        if (labelRtl || forceStartClip) " dt-sidebar-row-label-rtl" else ""
    labelEl.textContent = label
    row.appendChild(labelEl)
    // Tooltip carries the full label whenever the row is too narrow to show
    // it — whichever end it clips from.
    wireSidebarRowClipTooltip(row, labelEl)

    if (indexGlyph != null) {
        val badge = document.createElement("span") as HTMLElement
        badge.className = "dt-sidebar-row-index"
        badge.textContent = indexGlyph
        badge.setAttribute("aria-label", "Pane $index")
        row.appendChild(badge)
    }

    if (handler != null) {
        row.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            handler.invoke()
        })
    }
    return row
}
