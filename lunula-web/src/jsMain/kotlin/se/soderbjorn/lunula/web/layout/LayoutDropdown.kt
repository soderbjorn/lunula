/**
 * Public, keyboard-navigable layout-preset dropdown shared by every
 * Lunula app's topbar.
 *
 * The dropdown is owned by the toolkit so notegrow and termtastic
 * present the same control with the same affordances. Apps construct
 * a [LayoutDropdown], append [triggerButton] to their topbar, and
 * supply two callbacks: [paneCount] (read each time the popover
 * opens) and [onSelect] (fired with the user's pick). The host
 * typically routes [onSelect] into a [LayoutController.setPreset].
 *
 * Affordances:
 * - Click the trigger to open / close.
 * - Click a tile to pick.
 * - Arrow keys navigate the grid; mouse-move tracks focus too.
 * - Enter / Space invoke the focused tile.
 * - Escape closes.
 * - Outside click dismisses.
 *
 * Tiles render miniature SVG previews of each preset's geometry for
 * the current pane count via [LayoutPreset.computeBoxes]. The
 * preview viewBox and class names match the toolkit's bundled CSS
 * (`.dt-layout-preset-*`, `.dt-layout-preview-*`) so the dropdown
 * looks identical across apps without per-app styling.
 *
 * @see LayoutPreset
 * @see LayoutPreset.DROPDOWN_ORDER
 * @see LayoutController.setPreset
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.shell.ICON_LAYOUT

/**
 * One layout dropdown for an app's topbar. The dropdown owns its own
 * trigger button so callers don't have to wire open/close — they just
 * append [triggerButton] to the topbar.
 *
 * @param paneCount Re-evaluated each time the popover opens so the
 *   miniature tiles render with the current pane count of the active
 *   tab.
 * @param onSelect Fired with the picked preset; the host applies it
 *   (typically by calling [LayoutController.setPreset] and re-rendering).
 *   When the user clicks the tile that is already the active preset the
 *   dropdown fires [onSelect] with [LayoutPreset.Custom] — i.e. it
 *   toggles the preset off and returns the tab to "no preset driving"
 *   (geometry frozen wherever it last was).
 * @param activePreset Re-evaluated each time the popover opens to
 *   decide which tile, if any, gets the `is-active` accent. Return
 *   `null` (or [LayoutPreset.Custom]) when no preset is currently
 *   driving the tab. Default returns `null`.
 * @param presets The presets to show, in display order. Defaults to
 *   [LayoutPreset.DROPDOWN_ORDER] which starts with [LayoutPreset.Auto]
 *   and excludes [LayoutPreset.Custom] (a sentinel mode, not a target).
 */
class LayoutDropdown(
    private val paneCount: () -> Int,
    private val onSelect: (LayoutPreset) -> Unit,
    private val activePreset: () -> LayoutPreset? = { null },
    private val presets: List<LayoutPreset> = LayoutPreset.DROPDOWN_ORDER,
) {

    /** The toolbar trigger element. Stable across opens so callers can
     *  re-anchor popovers to the same element (e.g. a command-palette
     *  command "Layout" can call [openAnchoredTo] with [triggerButton]). */
    val triggerButton: HTMLElement by lazy { buildTrigger() }

    private var gridEl: HTMLElement? = null
    private var tiles: List<HTMLElement> = emptyList()
    private var focusedIndex: Int = 0
    /** Active preset captured at the moment the popover opened. Drives
     *  both the `is-active` tile marker and the click-to-toggle-off
     *  decision; null when no preset is currently driving the tab. */
    private var activeAtOpen: LayoutPreset? = null

    private var documentClickHandler: ((Event) -> Unit)? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    /** `true` while the popover is mounted. Used by the trigger's click
     *  handler to toggle. */
    fun isOpen(): Boolean = gridEl != null

    /**
     * Open the popover anchored under [anchor], with the first tile
     * focused so arrow keys are immediately useful. Idempotent —
     * calling while already open re-anchors and re-focuses.
     */
    fun openAnchoredTo(anchor: HTMLElement) {
        if (gridEl != null) close()
        val n = paneCount()
        val grid = document.createElement("div") as HTMLElement
        grid.className = "dt-layout-preset-grid"
        // Outline the focus ring on the focused tile so keyboard users can
        // see where they are without us having to manage focus outlines
        // manually.
        grid.tabIndex = -1

        // Snapshot the active preset for this open so click handlers
        // and the keyboard activation path see the same value the tile
        // marker was painted with — avoids a stale-decision footgun if
        // the active preset changed between open and click.
        //
        // Only Auto is treated as "active". Every other preset is a
        // one-shot that applies geometry once and then doesn't enforce
        // anything further, so marking it active in the dropdown would
        // mislead users into thinking the layout is still being held.
        activeAtOpen = activePreset()?.takeIf { it == LayoutPreset.Auto }

        val tileList = mutableListOf<HTMLElement>()
        if (n <= 0) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "dt-layout-preset-empty"
            empty.textContent = "No panes in this tab"
            grid.appendChild(empty)
        } else {
            for (preset in presets) {
                val tile = document.createElement("button") as HTMLElement
                tile.setAttribute("type", "button")
                tile.className = "dt-layout-preset-tile"
                if (preset == LayoutPreset.Auto) tile.classList.add("dt-layout-preset-tile-auto")
                if (preset == activeAtOpen) {
                    tile.classList.add("is-active")
                    // aria-pressed lets screen readers announce the
                    // toggle state — clicking again will turn the
                    // preset off (return to Custom).
                    tile.setAttribute("aria-pressed", "true")
                }
                tile.title = preset.label
                tile.setAttribute("aria-label", preset.label)
                // Auto gets a distinctive magic-wand glyph so it can't be
                // confused with the geometry-preview tiles around it.
                // Other presets render their per-pane-count miniature.
                tile.innerHTML = if (preset == LayoutPreset.Auto) {
                    AUTO_TILE_GLYPH
                } else {
                    renderPresetMiniatureSvg(preset.computeBoxes(n), preset.emphasizedSlotCount)
                }
                tile.addEventListener("click", { e: Event ->
                    e.stopPropagation()
                    selectOrToggleAndClose(preset, activeAtOpen)
                })
                tile.addEventListener("mousemove", { _: Event ->
                    val idx = tileList.indexOf(tile)
                    if (idx >= 0 && idx != focusedIndex) {
                        focusedIndex = idx
                        repaintFocusRing()
                    }
                })
                grid.appendChild(tile)
                tileList += tile
            }
        }

        document.body?.appendChild(grid)
        gridEl = grid
        tiles = tileList

        positionGrid(grid, anchor)
        focusedIndex = 0
        repaintFocusRing()

        attachDocumentDismiss(anchor)
    }

    /** Close the popover. Idempotent. */
    fun close() {
        gridEl?.let { it.parentNode?.removeChild(it) }
        gridEl = null
        tiles = emptyList()
        focusedIndex = 0
        activeAtOpen = null
        detachDocumentDismiss()
    }

    private fun buildTrigger(): HTMLElement {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.title = "Layout"
        btn.className = "dt-topbar-icon-button"
        btn.innerHTML = ICON_LAYOUT
        btn.addEventListener("click", { _: Event ->
            if (isOpen()) close() else openAnchoredTo(btn)
        })
        return btn
    }

    private fun positionGrid(grid: HTMLElement, anchor: HTMLElement) {
        val rect = anchor.getBoundingClientRect()
        val gridRect = grid.getBoundingClientRect()
        val left = (rect.right - gridRect.width).coerceAtLeast(4.0)
        grid.style.left = "${left}px"
        grid.style.top = "${rect.bottom + 4}px"
    }

    private fun repaintFocusRing() {
        for ((i, tile) in tiles.withIndex()) {
            val focused = i == focusedIndex
            // Toggle only the is-focused class — overwriting className
            // would strip the per-tile state classes
            // (`dt-layout-preset-tile-auto`, `is-active`).
            tile.classList.toggle("is-focused", focused)
            if (focused) {
                tile.focus()
                tile.scrollIntoView(js("({block:'nearest'})"))
            }
        }
    }

    /**
     * Closes the popover and fires [onSelect]. When [preset] equals
     * [activeAtOpen] the user is clicking the already-active tile —
     * fire [onSelect] with [LayoutPreset.Custom] instead so the host
     * toggles the preset off (geometry frozen wherever it last landed)
     * rather than re-applying the same layout. In practice this only
     * triggers for Auto, since one-shot presets aren't tracked as
     * active and so will never compare equal to [activeAtOpen].
     */
    private fun selectOrToggleAndClose(preset: LayoutPreset, activeAtOpen: LayoutPreset?) {
        val toFire = if (preset == activeAtOpen) LayoutPreset.Custom else preset
        close()
        onSelect(toFire)
    }

    private fun attachDocumentDismiss(anchor: HTMLElement) {
        // Capture phase + mousedown so a click landing anywhere outside the
        // popover dismisses *before* the target's own handler can call
        // `stopPropagation()`. The previous bubble-phase listener missed
        // dismissals when other top-bar buttons (sidebar toggle, theme
        // picker, …) stopped propagation in their own handlers, leaving
        // the popover stuck open while the user clearly meant to dismiss.
        val clickHandler: (Event) -> Unit = handler@{ e ->
            val target = e.target as? HTMLElement ?: return@handler
            val grid = gridEl ?: return@handler
            if (grid.contains(target)) return@handler
            if (anchor.contains(target)) return@handler
            close()
        }
        val keyHandler: (Event) -> Unit = handler@{ e ->
            val ke = e as? KeyboardEvent ?: return@handler
            when (ke.key) {
                "Escape" -> {
                    ke.preventDefault()
                    close()
                }
                "ArrowRight" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex + 1).coerceAtMost(tiles.lastIndex)
                        repaintFocusRing()
                    }
                }
                "ArrowLeft" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                        repaintFocusRing()
                    }
                }
                "ArrowDown" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex + GRID_COLUMNS).coerceAtMost(tiles.lastIndex)
                        repaintFocusRing()
                    }
                }
                "ArrowUp" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex - GRID_COLUMNS).coerceAtLeast(0)
                        repaintFocusRing()
                    }
                }
                "Enter", " " -> {
                    if (tiles.isEmpty()) return@handler
                    val preset = presets.getOrNull(focusedIndex) ?: return@handler
                    ke.preventDefault()
                    selectOrToggleAndClose(preset, activeAtOpen)
                }
            }
        }
        documentClickHandler = clickHandler
        documentKeyHandler = keyHandler
        // Capture phase for both. Listening to `mousedown` (instead of
        // `click`) means we run before any sibling handler can call
        // `stopPropagation()`, so clicks on other top-bar buttons (sidebar
        // toggle, theme picker, …) reliably dismiss the popover. The
        // tile's own click handler still fires after dismissal — but at
        // that point we've already closed and `selectOrToggleAndClose`'s
        // close() is a no-op, so there's no double-close risk.
        document.addEventListener("mousedown", clickHandler, /* capture = */ true)
        document.addEventListener("keydown", keyHandler, /* capture = */ true)
    }

    private fun detachDocumentDismiss() {
        documentClickHandler?.let {
            document.removeEventListener("mousedown", it, /* capture = */ true)
        }
        documentKeyHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentClickHandler = null
        documentKeyHandler = null
    }

    companion object {
        /** Matches the toolkit's `.dt-layout-preset-grid`'s 3-column grid. */
        private const val GRID_COLUMNS = 3

        /**
         * Magic-wand + sparkles glyph used in place of the geometry
         * miniature on the Auto preset tile. Drawn with the same stroke
         * weight and viewBox as the trigger icon so the dropdown reads
         * as a single visual family. The wand says "the toolkit picks
         * for you" without committing to a specific tiling shape.
         */
        private const val AUTO_TILE_GLYPH: String =
            "<svg viewBox=\"0 0 36 24\" width=\"36\" height=\"24\" " +
                "class=\"dt-layout-preview dt-layout-preview-auto\" aria-hidden=\"true\">" +
                // Wand body, lower-left to upper-right
                "<line x1=\"6\" y1=\"19\" x2=\"22\" y2=\"7\" stroke=\"currentColor\" " +
                "stroke-width=\"1.6\" stroke-linecap=\"round\"/>" +
                // Wand tip diamond (filled with primary accent)
                "<path d=\"M22 5 L25 8 L22 11 L19 8 Z\" class=\"dt-layout-preview-primary\"/>" +
                // Sparkle 1: small star top-right
                "<path d=\"M30 4 L31 6 L33 7 L31 8 L30 10 L29 8 L27 7 L29 6 Z\" " +
                "class=\"dt-layout-preview-primary\"/>" +
                // Sparkle 2: tiny star middle-right
                "<path d=\"M28 14 L28.6 15.2 L29.8 15.8 L28.6 16.4 L28 17.6 L27.4 16.4 " +
                "L26.2 15.8 L27.4 15.2 Z\" class=\"dt-layout-preview-other\"/>" +
                "</svg>"
    }
}

/**
 * Renders a tile-sized SVG miniature for [boxes]. Mirrors the
 * toolkit's CSS rules `.dt-layout-preview`,
 * `.dt-layout-preview-primary`, `.dt-layout-preview-secondary`,
 * `.dt-layout-preview-other` so every tile in every app picks up the
 * same colours.
 *
 * @param boxes geometry to draw, slot 0 first.
 * @param emphasized how many leading slots get a "ranked" fill — slot
 *   0 always gets `-primary`, slots `1..emphasized-1` get `-secondary`
 *   (translucent accent), and the rest get `-other` (grey filler).
 *   Pass `LayoutPreset.emphasizedSlotCount`. Defaults to `1` so callers
 *   without rank metadata fall back to the single-primary behaviour.
 */
private fun renderPresetMiniatureSvg(boxes: List<LayoutBox>, emphasized: Int = 1): String {
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
        val cls = when {
            i == 0 -> "dt-layout-preview-primary"
            i < emphasized -> "dt-layout-preview-secondary"
            else -> "dt-layout-preview-other"
        }
        sb.append(
            "<rect x=\"$bx\" y=\"$by\" width=\"$bw\" height=\"$bh\" rx=\"1.2\" class=\"$cls\"/>",
        )
    }
    sb.append("</svg>")
    return sb.toString()
}
