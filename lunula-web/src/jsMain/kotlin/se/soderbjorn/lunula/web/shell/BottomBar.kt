/*
 * BottomBar.kt (jsMain)
 * ----------------------
 * Status row primitive anchored to the bottom of the AppFrame. Mirrors
 * the spirit of [renderTopBar]: leading slot, trailing slot, themed
 * chrome via `.dt-bottombar*` classes.
 *
 * Termtastic's "Claude Code usage: Session 7% … Termtastic ●" status row
 * is the canonical use case; any consumer wanting a persistent footer of
 * status indicators should compose a [BottomBarSpec] and pass it to
 * `AppFrameSpec(bottomBar = renderBottomBar(spec))`.
 *
 * Visual styles ride on the `.dt-bottombar*` classes shipped in
 * `lunula.css`; consumers must call `injectLunulaStyles()`
 * once at boot.
 *
 * @see BottomBarSpec
 * @see renderBottomBar
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Slot configuration for the bottom status bar.
 *
 * @property leadingContent  element placed at the left edge of the bar
 *   (e.g. usage stats, app health indicators). May be null.
 * @property trailingContent element placed at the right edge (e.g. an
 *   app-name badge with a status dot). May be null.
 */
data class BottomBarSpec(
    val leadingContent: HTMLElement? = null,
    val trailingContent: HTMLElement? = null,
    /**
     * When `true`, attach a vertical resize handle on the bar's top edge
     * so the user can drag to grow/shrink the bottom-bar height. With
     * [minHeightPx] = 0 the bar collapses on drag (effectively hiding
     * itself); the host can read that in [onResize] to flip its
     * persisted visibility state.
     */
    val isResizable: Boolean = false,
    /** Minimum height the resize gesture allows. Defaults to 0 (drag-to-hide). */
    val minHeightPx: Int = 0,
    /** Maximum height the resize gesture allows. Defaults to 200. */
    val maxHeightPx: Int = 200,
    /**
     * When non-null, the bar's natural height. Drag releases snap to 0,
     * to this default, or — if [allowGrowBeyondDefault] — to the user's
     * chosen larger height. Half of this value is the snap threshold.
     */
    val defaultHeightPx: Int? = null,
    /**
     * When `false` (the default), drag is capped at [defaultHeightPx];
     * the bar can shrink to 0 but never grow taller than its default.
     * Set `true` to let users widen the bar past its default.
     */
    val allowGrowBeyondDefault: Boolean = false,
    /** Fired once on mouseup with the user's chosen height. */
    val onResize: ((heightPx: Int) -> Unit)? = null,
)

/** Toolkit DOM class names used by [renderBottomBar]. */
object BottomBarClassNames {
    const val BAR = "dt-bottombar"
    const val LEADING = "dt-bottombar-leading"
    const val TRAILING = "dt-bottombar-trailing"
}

/**
 * Build the bottom-bar element for the given [spec].
 *
 * Apps usually place the result in [AppFrameSpec.bottomBar]; callers
 * needing a free-standing footer can append it anywhere.
 *
 * @param spec slot configuration
 * @return a fresh `<footer>` element ready to be appended
 */
fun renderBottomBar(spec: BottomBarSpec): HTMLElement {
    val bar = document.createElement("footer") as HTMLElement
    bar.className = BottomBarClassNames.BAR

    val leading = document.createElement("div") as HTMLElement
    leading.className = BottomBarClassNames.LEADING
    spec.leadingContent?.let { leading.appendChild(it) }
    bar.appendChild(leading)

    val trailing = document.createElement("div") as HTMLElement
    trailing.className = BottomBarClassNames.TRAILING
    spec.trailingContent?.let { trailing.appendChild(it) }
    bar.appendChild(trailing)

    if (spec.isResizable) {
        attachBarVerticalResizeHandle(
            bar = bar,
            edge = BarResizeEdge.TOP,
            minHeightPx = spec.minHeightPx,
            maxHeightPx = spec.maxHeightPx,
            defaultHeightPx = spec.defaultHeightPx,
            allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
            onResize = spec.onResize,
        )
    }

    return bar
}
