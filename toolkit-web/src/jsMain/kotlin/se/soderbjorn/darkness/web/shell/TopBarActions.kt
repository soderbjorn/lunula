/*
 * TopBarActions.kt (jsMain)
 * --------------------------
 * Reusable button factories for the trailing slot of [renderTopBar].
 * Apps compose these into their `TopBarSpec.trailingContent` so they
 * don't reinvent button chrome, icons, or dropdown wiring per project.
 *
 * The pieces here are deliberately presentational: each factory takes
 * a callback the app fires on click. The toolkit owns icon SVGs,
 * styling (`.dt-topbar-icon-button`), and dropdown anchoring; the app
 * owns the actual mutation to its pane tree / tab state.
 *
 * @see buildTopbarIconButton
 * @see buildSplitPaneButton
 * @see buildLayoutPresetButton
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.darkness.web.layout.LayoutBox
import se.soderbjorn.darkness.web.layout.LayoutPreset
import se.soderbjorn.darkness.web.layout.PaneMenuItem
import se.soderbjorn.darkness.web.layout.PaneMenuSpec
import se.soderbjorn.darkness.web.layout.openPaneMenu

/** Inline SVG: branching diagonal lines suggesting a new pane / split. */
private const val ICON_SPLIT_PANE: String =
    "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<rect x=\"2\" y=\"2.5\" width=\"12\" height=\"11\" rx=\"1.5\"/>" +
        "<line x1=\"8\" y1=\"2.5\" x2=\"8\" y2=\"13.5\"/></svg>"

/**
 * Inline SVG: a clean "+" glyph with rounded caps — the universal "new"/"add"
 * icon. Drawn from scratch but inspired by the iOS SF Symbol `plus`; shares the
 * same 24-unit geometry as the Android/iOS `PlusIcon` so every client matches.
 */
/**
 * Clean `+` plus-sign glyph (24-unit viewBox, 2px round-cap stroke). Matches
 * termtastic's Android/iOS `PlusIcon` so the "New" affordance reads the same
 * across the family. Exposed `internal` so [AppShellMount] can paint the
 * topbar "New" split-button with it (issue #65 swapped the old window-with-`+`
 * mark for this plain plus).
 */
internal const val ICON_NEW_TAB: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">" +
        "<line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/>" +
        "<line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>"

/** Inline SVG: a sidebar panel with the left column highlighted. */
private const val ICON_LEFT_SIDEBAR: String =
    "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linejoin=\"round\">" +
        "<rect x=\"2\" y=\"2.5\" width=\"12\" height=\"11\" rx=\"1.5\"/>" +
        "<line x1=\"6\" y1=\"2.5\" x2=\"6\" y2=\"13.5\"/></svg>"

/** Inline SVG: a sidebar panel with the right column highlighted. */
private const val ICON_RIGHT_SIDEBAR: String =
    "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linejoin=\"round\">" +
        "<rect x=\"2\" y=\"2.5\" width=\"12\" height=\"11\" rx=\"1.5\"/>" +
        "<line x1=\"10\" y1=\"2.5\" x2=\"10\" y2=\"13.5\"/></svg>"

/**
 * Inline SVG: a window with a `+` glyph in its right half. Termtastic's
 * `#new-window-button` shape — reads as "open a new pane in this tab".
 * The 24-unit viewbox + 2px stroke is the termtastic spec verbatim.
 */
private const val ICON_NEW_WINDOW: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" " +
        "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" " +
        "aria-hidden=\"true\">" +
        "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"1.5\"/>" +
        "<line x1=\"12\" y1=\"3\" x2=\"12\" y2=\"21\"/>" +
        "<line x1=\"15\" y1=\"12\" x2=\"21\" y2=\"12\"/>" +
        "<line x1=\"18\" y1=\"9\" x2=\"18\" y2=\"15\"/></svg>"

/**
 * Inline SVG: a "color/palette" glyph (theme manager). Termtastic's
 * `#theme-button` SVG verbatim — a curved palette outline with four
 * colour-dot punctures inside. The 24-unit viewbox + 2px stroke matches.
 */
private const val ICON_THEME_MANAGER: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" " +
        "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" " +
        "aria-hidden=\"true\">" +
        "<path d=\"M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.93 0 1.65-.75 " +
        "1.65-1.69 0-.44-.18-.83-.44-1.12-.29-.29-.44-.65-.44-1.13a1.64 " +
        "1.64 0 0 1 1.67-1.67h2c3.05 0 5.55-2.5 5.55-5.55C21.97 6.01 " +
        "17.46 2 12 2z\"/>" +
        "<circle cx=\"6.5\" cy=\"12.5\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"8.5\" cy=\"7.5\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"13.5\" cy=\"6.5\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"17.5\" cy=\"10.5\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/>" +
        "</svg>"

/**
 * Inline SVG: four unequal panes in a 2×2 "Mondrian" tiling with roomy 3-unit
 * gaps — a "layout presets" glyph drawn from scratch. Shares the same 24-unit
 * geometry as the Android/iOS `LayoutGridIcon` so every client shows an
 * identical mark.
 *
 * `internal` so the layout-package trigger button ([se.soderbjorn.darkness.web
 * .layout.LayoutDropdown]) renders the exact same glyph — this is the single
 * source of truth for the mark; do not duplicate it.
 */
internal const val ICON_LAYOUT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linejoin=\"round\">" +
        "<rect x=\"3\" y=\"3\" width=\"9.5\" height=\"8\" rx=\"1.6\"/>" +
        "<rect x=\"15.5\" y=\"3\" width=\"5.5\" height=\"8\" rx=\"1.6\"/>" +
        "<rect x=\"3\" y=\"14\" width=\"5\" height=\"7\" rx=\"1.6\"/>" +
        "<rect x=\"11\" y=\"14\" width=\"10\" height=\"7\" rx=\"1.6\"/></svg>"

/**
 * Build a generic icon-button suitable for the topbar trailing area.
 * Carries the `.dt-topbar-icon-button` class so consumers get consistent
 * sizing, hover, and theming via the bundled stylesheet.
 *
 * @param iconHtml inline SVG (or any HTML) to render inside the button
 * @param tooltip  hover tooltip / accessible name
 * @param onClick  callback invoked on button click
 * @return a fresh `<button>` element ready to be appended to the topbar.
 */
fun buildTopbarIconButton(
    iconHtml: String,
    tooltip: String,
    onClick: () -> Unit,
): HTMLElement {
    val btn = document.createElement("button") as HTMLElement
    btn.setAttribute("type", "button")
    btn.title = tooltip
    btn.className = "dt-topbar-icon-button"
    btn.innerHTML = iconHtml
    btn.addEventListener("click", { _: Event -> onClick() })
    return btn
}

/**
 * Topbar `+` button. Apps wire [onClick] to whatever "create" action
 * makes sense in their model — termtastic uses it to add a new pane,
 * other apps may use it to add a tab. Tooltip defaults to "New" so it
 * reads sensibly in either context; pass [tooltip] to be specific.
 *
 * @param onClick invoked when the button is clicked
 * @param tooltip hover label / accessible name (defaults to "New")
 */
fun buildNewTabButton(
    onClick: () -> Unit,
    tooltip: String = "New",
): HTMLElement = buildTopbarIconButton(ICON_NEW_TAB, tooltip, onClick)

/**
 * "New window" button. Spawns a new pane in the active tab — termtastic
 * uses it to open the pane-type modal; notegrow uses it to add a new
 * floating overlay pane. The icon is termtastic's `#new-window-button`
 * SVG (a window with `+` in the right half) so apps in the family read
 * the same.
 *
 * @param tooltip text used for both `title` and `aria-label`.
 * @param onClick invoked when the button is clicked.
 * @return a freshly-built button element.
 */
fun buildNewWindowButton(
    tooltip: String = "New window",
    onClick: () -> Unit,
): HTMLElement = buildTopbarIconButton(ICON_NEW_WINDOW, tooltip, onClick)

/**
 * Split-button variant of [buildNewWindowButton]: the icon click still
 * fires [onDefaultClick] (e.g. "add a terminal"), but hovering the
 * button reveals a popover menu of secondary types provided by [items].
 *
 * Apps that want a chooser instead of opening a modal on every "new
 * pane" wire their flavours into [items] — the primary type (a terminal,
 * in termtastic's case) remains a single-click affordance while the
 * other types are one hover + click away.
 *
 * @param tooltip hover label / accessible name on the host button.
 * @param iconHtml inline SVG painted on the host button. Defaults to the
 *   window-with-`+` [buildNewWindowButton] glyph; pass [ICON_NEW_TAB] for a
 *   plain plus when the button reads as a general "New" menu (issue #65).
 * @param items lazy provider returning the menu rows. Evaluated each
 *   time the menu opens so the host can return contextual items (e.g.
 *   different actions per active tab).
 * @param onDefaultClick invoked when the user clicks the icon itself.
 *   Item clicks `stopPropagation()` so they never reach this handler.
 * @return a freshly-built button with the hover-menu attached.
 * @see attachHoverMenu
 * @see buildNewWindowButton
 */
fun buildNewWindowSplitButton(
    tooltip: String = "New pane",
    iconHtml: String = ICON_NEW_WINDOW,
    items: () -> List<HoverMenuItem>,
    onDefaultClick: () -> Unit,
): HTMLElement {
    val btn = buildTopbarIconButton(iconHtml, tooltip, onDefaultClick)
    attachHoverMenu(btn, items)
    return btn
}

/** Termtastic's `#appearance-toggle` SVGs verbatim — sun (light), moon
 *  (dark), half-filled circle (auto). 24-unit viewBox + 2px stroke so the
 *  optical sizes match the new-window / theme-manager icons. */
private const val ICON_APPEARANCE_LIGHT: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
        "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"5\"/>" +
        "<line x1=\"12\" y1=\"1\" x2=\"12\" y2=\"3\"/>" +
        "<line x1=\"12\" y1=\"21\" x2=\"12\" y2=\"23\"/>" +
        "<line x1=\"4.22\" y1=\"4.22\" x2=\"5.64\" y2=\"5.64\"/>" +
        "<line x1=\"18.36\" y1=\"18.36\" x2=\"19.78\" y2=\"19.78\"/>" +
        "<line x1=\"1\" y1=\"12\" x2=\"3\" y2=\"12\"/>" +
        "<line x1=\"21\" y1=\"12\" x2=\"23\" y2=\"12\"/>" +
        "<line x1=\"4.22\" y1=\"19.78\" x2=\"5.64\" y2=\"18.36\"/>" +
        "<line x1=\"18.36\" y1=\"5.64\" x2=\"19.78\" y2=\"4.22\"/></svg>"

private const val ICON_APPEARANCE_DARK: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
        "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<path d=\"M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z\"/></svg>"

private const val ICON_APPEARANCE_AUTO: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
        "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
        "<path d=\"M12 3a9 9 0 0 1 0 18\" fill=\"currentColor\"/></svg>"

/**
 * Three-state appearance cycle button: Auto → Dark → Light → Auto.
 * Ships termtastic's exact `#appearance-toggle` SVGs so the chrome
 * matches across the family. Hosts persist the chosen state and pass
 * the current value back as [appearance] each render so the icon
 * reflects it.
 *
 * @param appearance current appearance value driving icon + tooltip.
 * @param onCycle    invoked when the user clicks the button. The host
 *   advances the appearance state (Auto → Dark → Light → Auto), persists
 *   it, and re-renders the topbar so this factory paints the new icon.
 * @return a freshly-built button element.
 */
fun buildAppearanceCycleButton(
    appearance: se.soderbjorn.darkness.core.Appearance,
    onCycle: () -> Unit,
): HTMLElement {
    val (icon, label) = when (appearance) {
        se.soderbjorn.darkness.core.Appearance.Light -> ICON_APPEARANCE_LIGHT to "Appearance: Light"
        se.soderbjorn.darkness.core.Appearance.Dark -> ICON_APPEARANCE_DARK to "Appearance: Dark"
        se.soderbjorn.darkness.core.Appearance.Auto -> ICON_APPEARANCE_AUTO to "Appearance: Auto"
    }
    return buildTopbarIconButton(icon, label, onCycle)
}

/**
 * Theme manager toggle button. Renders termtastic's `#theme-button` icon
 * (palette outline + four color dots) and paints `.dt-active` while
 * [isOpen] is true so the button reads as pressed when the manager is
 * showing.
 *
 * @param isOpen   whether the theme manager / sidebar is currently open.
 * @param tooltip  text used for both `title` and `aria-label`.
 * @param onToggle invoked when the button is clicked.
 * @return a freshly-built button element.
 */
fun buildThemeManagerButton(
    isOpen: Boolean = false,
    tooltip: String = "Theme manager",
    onToggle: () -> Unit,
): HTMLElement {
    val btn = buildTopbarIconButton(ICON_THEME_MANAGER, tooltip, onToggle)
    if (isOpen) btn.classList.add("dt-active")
    return btn
}

/**
 * Inline SVG: a typography "Aa" mark — a large serif uppercase A next to
 * a smaller lowercase a sharing a common baseline. Cues the Appearance
 * sidebar's contents (font family, pane/UI text size, look-and-feel
 * pills) without overloading the gear glyph used by app-level
 * preferences elsewhere. Sized to match the other 16×16 top-bar icons.
 */
private const val ICON_APPEARANCE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"currentColor\" " +
        "aria-hidden=\"true\">" +
        "<text x=\"1\" y=\"18\" font-family=\"-apple-system, BlinkMacSystemFont, " +
        "'Segoe UI', system-ui, sans-serif\" font-size=\"14\" font-weight=\"700\" " +
        "letter-spacing=\"-0.5\">A</text>" +
        "<text x=\"12\" y=\"18\" font-family=\"-apple-system, BlinkMacSystemFont, " +
        "'Segoe UI', system-ui, sans-serif\" font-size=\"10\" font-weight=\"500\" " +
        "letter-spacing=\"-0.3\">a</text>" +
        "</svg>"

/**
 * Appearance sidebar toggle button (typography "Aa" icon). Mirrors
 * [buildThemeManagerButton]: paints `.dt-active` while [isOpen] is true so
 * the button reads as pressed when the panel is showing. Previously
 * rendered as a settings gear; the sidebar's contents are dominated by
 * appearance controls (typography, look-and-feel, pane theme) rather
 * than generic preferences, so the icon and default tooltip both lean
 * into "Appearance" instead.
 *
 * @param isOpen   whether the sidebar is currently open.
 * @param tooltip  text used for both `title` and `aria-label`.
 * @param onToggle invoked when the button is clicked.
 * @return a freshly-built button element.
 */
fun buildSettingsGearButton(
    isOpen: Boolean = false,
    tooltip: String = "Appearance",
    onToggle: () -> Unit,
): HTMLElement {
    val btn = buildTopbarIconButton(ICON_APPEARANCE, tooltip, onToggle)
    if (isOpen) btn.classList.add("dt-active")
    return btn
}

/**
 * Inline SVG: a classic cog / gear glyph (eight notched teeth around a
 * central hub) for the App Settings sidebar toggle. Visually distinct
 * from the palette icon used by the Theme Manager and the "Aa"
 * typography mark used by the Appearance sidebar so the three trailing
 * right-sidebar toggles read as separate surfaces at a glance. Sized to
 * match the family (16×16, 24-unit viewbox, 2px currentColor stroke).
 */
private const val ICON_APP_SETTINGS: String =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" " +
        "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
        "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"3\"/>" +
        "<path d=\"M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 " +
        "2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 " +
        "2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 " +
        "2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 " +
        "0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 " +
        "0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 " +
        "1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 " +
        "0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 " +
        "1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 " +
        "1.65 0 0 0-1.51 1z\"/></svg>"

/**
 * App Settings sidebar toggle button (gear icon). Mirrors
 * [buildSettingsGearButton] / [buildThemeManagerButton]: paints
 * `.dt-active` while [isOpen] is true so the button reads as pressed when
 * the panel is showing.
 *
 * The "App settings" sidebar is the host-supplied counterpart to the
 * toolkit-owned Appearance sidebar — apps that opt in by setting
 * `AppShellSpec.appSettingsContent` get this button rendered immediately
 * to the right of the Appearance gear. Apps that don't opt in suppress
 * the button entirely (the toolkit skips the factory call).
 *
 * @param isOpen   whether the sidebar is currently open.
 * @param tooltip  text used for both `title` and `aria-label`.
 * @param onToggle invoked when the button is clicked.
 * @return a freshly-built button element.
 */
fun buildAppSettingsButton(
    isOpen: Boolean = false,
    tooltip: String = "App settings",
    onToggle: () -> Unit,
): HTMLElement {
    val btn = buildTopbarIconButton(ICON_APP_SETTINGS, tooltip, onToggle)
    if (isOpen) btn.classList.add("dt-active")
    return btn
}

/**
 * Toggle button for the **left** sidebar. Apps own the sidebar's
 * visibility state; this primitive just renders the button. The
 * sidebar's "currently open" state is already self-evident from the
 * sidebar itself, so the button does not paint an `.dt-active`
 * accent — it stays the same muted tone as every other top-bar icon
 * (termtastic parity).
 *
 * @param isOpen   current visibility — kept in the signature so call
 *   sites can be migrated incrementally; not currently used.
 * @param onToggle invoked when the button is clicked
 */
@Suppress("UNUSED_PARAMETER")
fun buildLeftSidebarToggleButton(isOpen: Boolean, onToggle: () -> Unit): HTMLElement =
    buildTopbarIconButton(ICON_LEFT_SIDEBAR, "Toggle left sidebar", onToggle)

/**
 * Toggle button for the **right** sidebar (typically the theme manager).
 * Mirrors [buildLeftSidebarToggleButton] for the right panel.
 *
 * @param isOpen   current visibility — drives the active styling
 * @param onToggle invoked when the button is clicked
 */
fun buildRightSidebarToggleButton(isOpen: Boolean, onToggle: () -> Unit): HTMLElement {
    val btn = buildTopbarIconButton(ICON_RIGHT_SIDEBAR, "Toggle right sidebar", onToggle)
    if (isOpen) btn.classList.add("dt-active")
    return btn
}

/**
 * "Split / new pane" button. Apps wire [onSplit] to a handler that
 * creates a new pane (splitting the active pane, opening a floating
 * overlay, etc. — the toolkit doesn't care). The icon is the universal
 * split-pane glyph; the tooltip can be customised so apps that use the
 * button for floating-pane spawn read correctly.
 *
 * @param tooltip text used for both `title` and `aria-label`. Defaults
 *   to "New pane (split)" for split-tree consumers.
 * @param onSplit invoked when the button is clicked.
 * @return a freshly-built button element.
 */
fun buildSplitPaneButton(
    tooltip: String = "New pane (split)",
    onSplit: () -> Unit,
): HTMLElement =
    buildTopbarIconButton(ICON_SPLIT_PANE, tooltip, onSplit)

/**
 * "Layout" dropdown button: clicking opens a popover with one tile per
 * [LayoutPreset]. Each tile renders a miniature SVG with the **same
 * number of slots as the active tab currently has panes**, and the slot
 * the active pane will land in (slot 0) is highlighted in the accent
 * colour — so the user can see at a glance both which arrangement they're
 * picking and which of its boxes their focused pane will move into.
 *
 * Mirrors termtastic's `LayoutMenu` UX exactly; both apps in the family
 * share this primitive (per the toolkit's "uniformity" guideline).
 *
 * @param paneCount  callback evaluated each time the dropdown opens so the
 *   miniatures reflect the current pane count of the active tab. Returning
 *   `0` collapses the menu to an "empty" message.
 * @param onSelect   callback invoked with the user's chosen preset. Hosts
 *   typically rebuild their pane tree via
 *   [se.soderbjorn.darkness.web.layout.PaneTreeOps.fromPreset].
 * @return a freshly-built dropdown button
 */
fun buildLayoutPresetButton(
    paneCount: () -> Int,
    onSelect: (LayoutPreset) -> Unit,
): HTMLElement {
    val btn = buildTopbarIconButton(ICON_LAYOUT, "Layout", { /* re-bound below */ })
    btn.onclick = { _: Event ->
        openLayoutPresetGrid(anchor = btn, paneCount = paneCount(), onSelect = onSelect)
    }
    return btn
}

/**
 * Opens the layout-preset preview-tile grid as a popover anchored at
 * [anchor]. Built directly (not via [openPaneMenu]) because the grid
 * needs a 3-column tile layout rather than the linear menu rows.
 *
 * Outside-click + Escape both dismiss; clicking a tile fires [onSelect]
 * and closes.
 *
 * @param paneCount number of panes the active tab currently contains;
 *   each tile's miniature renders that many boxes so the preview matches
 *   what the user will actually get on apply. `0` shows an "empty" hint.
 */
private fun openLayoutPresetGrid(
    anchor: HTMLElement,
    paneCount: Int,
    onSelect: (LayoutPreset) -> Unit,
) {
    // Tear down any previously-open instance first so we never stack two.
    val existing = document.querySelectorAll(".dt-layout-preset-grid")
    for (i in 0 until existing.length) {
        (existing.item(i) as HTMLElement).remove()
    }
    val grid = document.createElement("div") as HTMLElement
    grid.className = "dt-layout-preset-grid"

    fun closeGrid() { grid.remove() }

    if (paneCount <= 0) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "dt-layout-preset-empty"
        empty.textContent = "No panes in this tab"
        grid.appendChild(empty)
    } else {
        fun tile(preset: LayoutPreset) {
            val cell = document.createElement("button") as HTMLElement
            cell.setAttribute("type", "button")
            cell.className = "dt-layout-preset-tile"
            cell.title = preset.label
            cell.setAttribute("aria-label", preset.label)
            cell.innerHTML = renderPresetMiniatureSvg(preset.computeBoxes(paneCount))
            cell.addEventListener("click", { ev: Event ->
                ev.stopPropagation()
                closeGrid()
                onSelect(preset)
            })
            grid.appendChild(cell)
        }
        // Skip `Custom` — that's a sentinel for "user has hand-tweaked
        // geometry, no preset driving"; its `computeBoxes` returns an
        // empty list, so a tile would render as a blank cell. Apps
        // already enter Custom mode automatically when the user drags
        // a pane; the dropdown is for picking a preset to leave it.
        for (preset in LayoutPreset.values()) {
            if (preset == LayoutPreset.Custom) continue
            tile(preset)
        }
    }

    document.body?.appendChild(grid)
    // Position under the anchor's bottom-right, clamped to the viewport.
    val rect = anchor.getBoundingClientRect()
    val gridRect = grid.getBoundingClientRect()
    val left = (rect.right - gridRect.width).coerceAtLeast(4.0)
    grid.style.left = "${left}px"
    grid.style.top = "${rect.bottom + 4}px"

    // Outside-click + Escape dismissal — handlers self-detach on close.
    val outsideClick: (Event) -> Unit = handler@{ ev ->
        val target = ev.target as? HTMLElement ?: return@handler
        if (grid.contains(target) || anchor.contains(target)) return@handler
        closeGrid()
    }
    val esc: (Event) -> Unit = handler@{ ev ->
        if ((ev as? org.w3c.dom.events.KeyboardEvent)?.key == "Escape") closeGrid()
    }
    document.addEventListener("click", outsideClick)
    document.addEventListener("keydown", esc)
}

/**
 * Builds the inline SVG preview for a layout's pre-computed [boxes].
 * Slot 0 paints with `.dt-layout-preview-primary` (the accent fill),
 * remaining slots with `.dt-layout-preview-other` (a muted fill) — the
 * primary/active-pane convention used both by termtastic and the
 * toolkit's stylesheet.
 *
 * The 36×24 viewBox + 1px padding matches termtastic's tile dimensions
 * so apps in the family render the dropdown identically.
 */
private fun renderPresetMiniatureSvg(boxes: List<LayoutBox>): String {
    val w = 36
    val h = 24
    val pad = 1.0
    val sb = StringBuilder()
    sb.append(
        "<svg viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\" " +
            "class=\"dt-layout-preview\" aria-hidden=\"true\">",
    )
    for ((i, b) in boxes.withIndex()) {
        val bx = b.x * w + pad
        val by = b.y * h + pad
        val bw = (b.width * w - pad * 2).coerceAtLeast(2.0)
        val bh = (b.height * h - pad * 2).coerceAtLeast(2.0)
        val cls = if (i == 0) "dt-layout-preview-primary" else "dt-layout-preview-other"
        sb.append(
            "<rect x=\"$bx\" y=\"$by\" width=\"$bw\" height=\"$bh\" rx=\"1.2\" class=\"$cls\"/>",
        )
    }
    sb.append("</svg>")
    return sb.toString()
}
