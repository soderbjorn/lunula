/**
 * Drop-in left/right sidebar shell components.
 *
 * Both sidebars are a vertical column with an optional header and a
 * content slot, plus an opt-in inside-edge resize handle. Apps fill the
 * slots; the toolkit only owns the layout, sizing, theming, and the
 * resize gesture.
 *
 * Visual styles ride on the `.dt-sidebar*` classes shipped in
 * `lunula.css`; consumers must call `injectLunulaStyles()`
 * once at boot.
 *
 * @see SidebarSpec
 * @see SidebarSectionSpec
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent

/**
 * Sidebar configuration shared by both [renderLeftSidebar] and [renderRightSidebar].
 *
 * @property widthPx      initial sidebar width in pixels. Defaults to 240.
 *   When [isResizable] is true this is the *starting* width — subsequent
 *   widths come from the user dragging the resize handle and are reported
 *   via [onResize].
 * @property header       element shown at the top of the sidebar (label,
 *   search box, …). May be null.
 * @property content      main scrollable content of the sidebar.
 * @property footer       element pinned to the bottom of the sidebar, below
 *   the scrollable [content] (status footer, action buttons, app signature,
 *   …). The content area flex-grows so the footer stays anchored at the
 *   bottom regardless of how short or tall the content is. May be null.
 * @property visible      whether the sidebar should be rendered at all. When
 *   false, the element returned by the render functions has `display: none`.
 * @property isResizable  when true, an inside-edge drag handle is appended
 *   that adjusts the sidebar's CSS width on drag and emits the final width
 *   via [onResize] on mouseup. Persistence is the host's job — the toolkit
 *   only emits.
 * @property minWidthPx   minimum width the resize gesture allows. Defaults
 *   to 120. Drags below this clamp to [minWidthPx]; the toolkit does not
 *   collapse the sidebar to zero — hosts that want collapse-on-drag should
 *   detect the boundary in [onResize] themselves.
 * @property maxWidthPx   maximum width the resize gesture allows.
 * @property onResize     fired once on mouseup with the user's chosen width
 *   (after clamping). Not fired during the drag — hosts that want live
 *   persistence should debounce themselves. May be null when [isResizable]
 *   is false (or when the host doesn't care to persist).
 */
class SidebarSpec(
    val widthPx: Int = 240,
    val header: HTMLElement? = null,
    val content: HTMLElement? = null,
    val footer: HTMLElement? = null,
    val visible: Boolean = true,
    val isResizable: Boolean = false,
    val minWidthPx: Int = 0,
    val maxWidthPx: Int = 600,
    /**
     * Soft maximum width for the resize gesture when [allowGrowBeyondDefault]
     * is `false`. Has no effect on sidebars when growth is allowed —
     * sidebars resize freely without snap-to-default behaviour.
     */
    val defaultWidthPx: Int? = null,
    /**
     * When `true` (the default for sidebars) the user can drag the bar
     * to any width up to [maxWidthPx] and that width is honored on
     * release as-is. When `false` drag is capped at [defaultWidthPx].
     */
    val allowGrowBeyondDefault: Boolean = true,
    val onResize: ((widthPx: Int) -> Unit)? = null,
)

/**
 * Builds a left-sidebar element. The bar paints with `--t-sidebar-*`
 * theme variables and has a right edge border. When [SidebarSpec.isResizable]
 * is true the resize handle is appended on the right (inside) edge.
 *
 * @param spec sidebar configuration
 * @return a fresh sidebar [HTMLElement]
 */
fun renderLeftSidebar(spec: SidebarSpec): HTMLElement = renderSidebar(spec, isLeft = true)

/**
 * Builds a right-sidebar element. Same as [renderLeftSidebar] but with a
 * left edge border and the resize handle on the left (inside) edge.
 *
 * @param spec sidebar configuration
 * @return a fresh sidebar [HTMLElement]
 */
fun renderRightSidebar(spec: SidebarSpec): HTMLElement = renderSidebar(spec, isLeft = false)

private fun renderSidebar(spec: SidebarSpec, isLeft: Boolean): HTMLElement {
    val bar = document.createElement("aside") as HTMLElement
    val sideClass = if (isLeft) "dt-sidebar-left" else "dt-sidebar-right"
    val visClass = if (spec.visible) "" else " dt-hidden"
    bar.className = "dt-sidebar $sideClass$visClass"
    // Per-instance width — kept inline; no CSS variable needed since the value
    // is a one-off literal supplied by the host app.
    bar.style.width = "${spec.widthPx}px"

    spec.header?.let {
        val headerWrap = document.createElement("div") as HTMLElement
        headerWrap.className = "dt-sidebar-header"
        headerWrap.appendChild(it)
        bar.appendChild(headerWrap)
    }
    spec.content?.let {
        val contentWrap = document.createElement("div") as HTMLElement
        contentWrap.className = "dt-sidebar-content"
        contentWrap.appendChild(it)
        bar.appendChild(contentWrap)
    }
    spec.footer?.let {
        val footerWrap = document.createElement("div") as HTMLElement
        footerWrap.className = "dt-sidebar-footer"
        footerWrap.appendChild(it)
        bar.appendChild(footerWrap)
    }
    if (spec.isResizable) {
        attachSidebarResizeHandle(
            bar = bar,
            edge = if (isLeft) SidebarResizeEdge.RIGHT else SidebarResizeEdge.LEFT,
            minWidthPx = spec.minWidthPx,
            maxWidthPx = spec.maxWidthPx,
            defaultWidthPx = spec.defaultWidthPx,
            allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
            onResize = spec.onResize,
        )
    }
    return bar
}

/**
 * Which inside edge the resize handle decorates. For a left sidebar the
 * inside edge is its right side; for a right sidebar it's its left side.
 *
 * @see attachSidebarResizeHandle
 */
enum class SidebarResizeEdge { LEFT, RIGHT }

/**
 * Which inside edge of a horizontal bar (top bar / bottom bar) the
 * vertical resize handle decorates. For a top bar the inside edge is
 * its bottom; for a bottom bar it's its top.
 *
 * @see attachBarVerticalResizeHandle
 */
enum class BarResizeEdge { BOTTOM, TOP }

/**
 * Mounts an inside-edge resize handle on an arbitrary sidebar-like [bar]
 * and wires the document-level mouse listeners that drive the drag gesture.
 * The drag adjusts `bar.style.width` live; [onResize] fires once on mouseup
 * with the final clamped width.
 *
 * This is the shared primitive behind `SidebarSpec(isResizable = true)`,
 * exposed so hosts whose sidebar is rendered outside the toolkit (e.g. an
 * `<aside>` declared in `index.html` and looked up by id) can adopt the
 * same gesture without rebuilding the bar through `renderLeftSidebar`.
 * The toolkit's `.dt-sidebar-resize-handle*` CSS classes are reused, so
 * the visual treatment matches a fully toolkit-rendered sidebar provided
 * [bar] has `position: relative` (or any positioned ancestor) so the
 * handle's `position: absolute` resolves against the right element.
 *
 * Implementation notes:
 *  - listeners are attached to `document` (not [bar]) so dragging past the
 *    sidebar edge keeps tracking; they remove themselves on mouseup so we
 *    don't leak per-render.
 *  - `body { user-select: none; cursor: col-resize }` is set during the
 *    drag so accidental text selection across the page doesn't fire.
 *  - the handle is appended as the last child of [bar]; callers that
 *    re-render [bar]'s contents must call [attachSidebarResizeHandle] again
 *    after the rebuild (the handle is a sibling of whatever else lives
 *    inside the bar, not a layout participant).
 *
 * @param bar          the sidebar element to decorate — typically `<aside>`
 *   or `<div>`, must have a positioned ancestor for the handle to anchor to.
 * @param edge         which inside edge the handle sits on. See [SidebarResizeEdge].
 * @param minWidthPx   minimum width the gesture allows. Defaults to 0 so
 *   hosts that want collapse-on-drag can detect the boundary in [onResize];
 *   pass a higher value (e.g. 120) to enforce a hard minimum.
 * @param maxWidthPx   maximum width the gesture allows. Defaults to 600.
 * @param defaultWidthPx soft cap used when [allowGrowBeyondDefault] is
 *   false (e.g. for top/bottom bars repurposing this primitive). Sidebars
 *   leave growth allowed and resize freely — there is no snap-to-zero or
 *   snap-to-default applied to the sidebar handle.
 * @param allowGrowBeyondDefault when true the drag is capped at
 *   [maxWidthPx]; when false it is capped at [defaultWidthPx] (or
 *   [maxWidthPx] if null).
 * @param onResize     fired once on mouseup with the user's chosen width
 *   (after clamping). Not fired during the drag — hosts that want live
 *   persistence should debounce themselves. May be null when the host
 *   doesn't care to persist.
 * @return the freshly-mounted handle [HTMLElement], so callers can detach
 *   it (`handle.remove()`) if the bar is later torn down.
 * @see SidebarSpec
 */
fun attachSidebarResizeHandle(
    bar: HTMLElement,
    edge: SidebarResizeEdge,
    minWidthPx: Int = 0,
    maxWidthPx: Int = 600,
    defaultWidthPx: Int? = null,
    allowGrowBeyondDefault: Boolean = true,
    onResize: ((widthPx: Int) -> Unit)? = null,
): HTMLElement {
    val isLeft = edge == SidebarResizeEdge.RIGHT
    val handle = document.createElement("div") as HTMLElement
    handle.className = "dt-sidebar-resize-handle " +
        if (isLeft) "dt-sidebar-resize-handle-right" else "dt-sidebar-resize-handle-left"
    bar.appendChild(handle)

    var dragging = false
    var startX = 0.0
    var startWidth = 0.0
    var moveListener: EventListener? = null
    var upListener: EventListener? = null

    handle.addEventListener("mousedown", { ev: Event ->
        ev.preventDefault()
        dragging = true
        startX = (ev as MouseEvent).clientX.toDouble()
        startWidth = bar.getBoundingClientRect().width
        handle.classList.add("dt-dragging")
        // Suppress CSS transitions on the bar during the drag so width follows
        // the cursor without easing — re-enabled on mouseup.
        bar.style.transition = "none"
        // Override stylesheet `min-width` so dragging below it actually shrinks
        // the bar (mirrors the bar-vertical handle's min-height override).
        bar.style.setProperty("min-width", "${minWidthPx}px")
        document.body?.style?.cursor = "col-resize"
        document.body?.style?.setProperty("user-select", "none")

        val effectiveMax = if (allowGrowBeyondDefault) {
            maxWidthPx
        } else {
            (defaultWidthPx ?: maxWidthPx)
        }

        val onMove = EventListener { mv: Event ->
            if (!dragging) return@EventListener
            val dx = (mv as MouseEvent).clientX.toDouble() - startX
            // Left sidebar (handle on right edge) grows when cursor moves
            // right; right sidebar (handle on left edge) grows when cursor
            // moves left. Same gesture, mirrored axis.
            val raw = if (isLeft) startWidth + dx else startWidth - dx
            val clamped = raw.coerceIn(minWidthPx.toDouble(), effectiveMax.toDouble())
            bar.style.width = "${clamped}px"
        }
        val onUp = EventListener { _: Event ->
            if (!dragging) return@EventListener
            dragging = false
            handle.classList.remove("dt-dragging")
            bar.style.transition = ""
            document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            moveListener?.let { document.removeEventListener("mousemove", it) }
            upListener?.let { document.removeEventListener("mouseup", it) }
            moveListener = null
            upListener = null
            // Sidebars resize freely — no snap-to-zero or snap-to-default.
            // The bar-vertical handle (top/bottom bars) keeps its own snap
            // behaviour; only the sidebar handle drags as a plain splitter.
            val rawFinal = bar.getBoundingClientRect().width.toInt()
            bar.style.width = "${rawFinal}px"
            bar.style.removeProperty("min-width")
            onResize?.invoke(rawFinal)
        }
        moveListener = onMove
        upListener = onUp
        document.addEventListener("mousemove", onMove)
        document.addEventListener("mouseup", onUp)
    })
    return handle
}

/**
 * Mount a vertical-axis resize handle on the inside edge of a horizontal
 * bar (typically the top bar or the bottom bar). Drag adjusts
 * `bar.style.height` live; [onResize] fires once on mouseup with the
 * final clamped height.
 *
 * Visually the handle is a thin invisible strip on the bar's inside
 * edge that becomes a 1-px hairline on hover and accent-coloured while
 * dragging — same treatment as [attachSidebarResizeHandle], rotated 90°.
 *
 * Pass [minHeightPx] = 0 if dragging-to-hide is desired (the bar
 * collapses to zero pixels and the host can read that in [onResize] to
 * also flip its visibility state).
 *
 * @param bar         the bar element to decorate
 * @param edge        which inside edge the handle sits on. See [BarResizeEdge].
 * @param minHeightPx minimum height the gesture allows. Defaults to 0
 *   so apps can collapse-on-drag.
 * @param maxHeightPx maximum height the gesture allows. Defaults to 240.
 * @param onResize    fired once on mouseup with the user's chosen height
 *   (after clamping).
 * @return the freshly-mounted handle [HTMLElement]
 */
fun attachBarVerticalResizeHandle(
    bar: HTMLElement,
    edge: BarResizeEdge,
    minHeightPx: Int = 0,
    maxHeightPx: Int = 240,
    defaultHeightPx: Int? = null,
    allowGrowBeyondDefault: Boolean = false,
    onResize: ((heightPx: Int) -> Unit)? = null,
): HTMLElement {
    val isTopBar = edge == BarResizeEdge.BOTTOM
    val handle = document.createElement("div") as HTMLElement
    handle.className = "dt-bar-resize-handle " +
        if (isTopBar) "dt-bar-resize-handle-bottom" else "dt-bar-resize-handle-top"
    bar.appendChild(handle)

    var dragging = false
    var startY = 0.0
    var startHeight = 0.0
    var moveListener: EventListener? = null
    var upListener: EventListener? = null

    handle.addEventListener("mousedown", { ev: Event ->
        ev.preventDefault()
        dragging = true
        startY = (ev as MouseEvent).clientY.toDouble()
        startHeight = bar.getBoundingClientRect().height
        handle.classList.add("dt-dragging")
        bar.style.transition = "none"
        // Override the bar's stylesheet `min-height` (e.g. `.dt-topbar`'s
        // 48px, `.dt-bottombar`'s 30px) so the inline `height` set during
        // drag can actually shrink the bar below its natural minimum.
        // Without this the bar can only ever grow — `style.height` below
        // the CSS min-height is silently ignored, which is the symptom
        // users see as "drag up does nothing on the top bar".
        bar.style.setProperty("min-height", "${minHeightPx}px")
        document.body?.style?.cursor = "row-resize"
        document.body?.style?.setProperty("user-select", "none")

        val effectiveMax = if (allowGrowBeyondDefault) {
            maxHeightPx
        } else {
            (defaultHeightPx ?: maxHeightPx)
        }

        val onMove = EventListener { mv: Event ->
            if (!dragging) return@EventListener
            val dy = (mv as MouseEvent).clientY.toDouble() - startY
            // Top bar (handle on bottom) grows when cursor moves down;
            // bottom bar (handle on top) grows when cursor moves up.
            val raw = if (isTopBar) startHeight + dy else startHeight - dy
            val clamped = raw.coerceIn(minHeightPx.toDouble(), effectiveMax.toDouble())
            bar.style.height = "${clamped}px"
        }
        val onUp = EventListener { _: Event ->
            if (!dragging) return@EventListener
            dragging = false
            handle.classList.remove("dt-dragging")
            bar.style.transition = ""
            document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            moveListener?.let { document.removeEventListener("mousemove", it) }
            upListener?.let { document.removeEventListener("mouseup", it) }
            moveListener = null
            upListener = null
            val rawFinal = bar.getBoundingClientRect().height.toInt()
            // Snap rule: under half-default → collapse to 0; under default
            // → snap up to default; at-or-above default → keep raw height
            // only when growth beyond default is allowed, else snap back
            // to default (the common case for top/bottom bars).
            val snapped = if (defaultHeightPx != null) {
                when {
                    rawFinal < defaultHeightPx / 2 -> 0
                    rawFinal < defaultHeightPx -> defaultHeightPx
                    allowGrowBeyondDefault -> rawFinal
                    else -> defaultHeightPx
                }
            } else rawFinal
            if (snapped == 0) {
                bar.style.height = "0px"
            } else if (defaultHeightPx != null && snapped == defaultHeightPx) {
                bar.style.removeProperty("height")
                bar.style.removeProperty("min-height")
            } else {
                bar.style.height = "${snapped}px"
                bar.style.removeProperty("min-height")
            }
            onResize?.invoke(snapped)
        }
        moveListener = onMove
        upListener = onUp
        document.addEventListener("mousemove", onMove)
        document.addEventListener("mouseup", onUp)
    })
    return handle
}
