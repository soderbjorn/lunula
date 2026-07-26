/**
 * Built-in factories for the most common pane-header action buttons.
 *
 * A [PaneAction] is the minimal data the toolkit needs to render one icon
 * button into a [PaneHeaderSpec.actions] slot: SVG markup, tooltip text,
 * a click handler, an optional `isActive` toggle for state-bearing buttons
 * (e.g. an "expanded" toggle), and an optional CSS class for theming.
 *
 * The [PaneActions] object provides one-line factories for actions every
 * Lunula app is expected to need — [expand], [restore], and [close] —
 * so consumers can wire a default header in a single line:
 *
 * ```
 * PaneHeaderSpec(
 *     title = leaf.title,
 *     actions = listOf(
 *         PaneActions.expand { ops.expand(leaf.id) },
 *         PaneActions.close  { ops.close(leaf.id) },
 *     ),
 * )
 * ```
 *
 * Apps that need bespoke icons (a colour-scheme picker, a "create
 * worktree" button, etc.) construct [PaneAction] directly and pass any
 * SVG string in [PaneAction.iconHtml].
 *
 * @see PaneHeaderSpec
 * @see renderPaneHeader
 */
package se.soderbjorn.lunula.web.layout

/**
 * One icon button rendered into a pane header's trailing action strip.
 *
 * The button's DOM is built by [renderPaneHeader] and gets the
 * `.dt-pane-action` class plus any [extraClass]; click delegates to
 * [handler] after `stopPropagation()` so the click does not bubble up
 * into the header's drag/rename gestures.
 *
 * @property iconHtml   raw SVG (or any HTML) markup placed inside the
 *   button via `innerHTML`. The toolkit ships no icons — every consumer
 *   supplies its own glyphs (the [PaneActions] factories below carry
 *   simple defaults).
 * @property tooltip    text used for both `title` and `aria-label`.
 * @property handler    invoked when the button is clicked.
 * @property isActive   when `true` the button gets the
 *   `.dt-pane-action.dt-active` class so the host stylesheet can render
 *   a "pressed" / "on" state. Used by toggle actions (e.g. expand/restore).
 * @property extraClass optional additional CSS class(es) appended to the
 *   button. The built-in factories use this to tag their buttons
 *   (`dt-pane-action-close`, `dt-pane-action-expand`, ...).
 */
data class PaneAction(
    val iconHtml: String,
    val tooltip: String,
    val handler: () -> Unit,
    val isActive: Boolean = false,
    val extraClass: String = "",
    /**
     * Optional click handler that receives the rendered button element
     * as its argument — typically used to anchor a popover (kebab menu,
     * picker) to the exact button the user clicked. When non-null, this
     * is invoked instead of [handler]; pass `handler = {}` in that case.
     *
     * Defaults `null` so existing callers (every built-in factory)
     * continue using the simple `handler` form.
     */
    val handlerWithAnchor: ((anchor: org.w3c.dom.HTMLElement) -> Unit)? = null,
)

/**
 * One-line factories for the toolkit's standard pane-header actions.
 *
 * The icons are minimal stroke-based SVGs sized 14×14 to match the
 * `.dt-pane-header` row height (~22px). Apps that want pixel-perfect
 * Material Symbols, Heroicons, etc. should construct [PaneAction]
 * directly with their own [PaneAction.iconHtml].
 */
object PaneActions {

    // ── Glyph set ────────────────────────────────────────────────────
    //
    // Drawn from the Framna concept: 1.8 stroke on a 24 viewBox, round caps,
    // rendered at 14×14. The previous set was 2.0-weight with mitred joins,
    // which at 14px put visibly more ink in the header than the title beside
    // it — the strip read as heavier than the thing it belonged to.
    //
    // Only the paths changed. The action set itself — which buttons exist,
    // what they do, where the drag handle lives — is untouched.

    /** Diagonal arrows pointing outward — the "maximize within tab" affordance. */
    const val ICON_EXPAND: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M14 5h5v5M10 19H5v-5\"/>" +
            "<path d=\"M19 5l-6 6M5 19l6-6\"/></svg>"

    /** Diagonal arrows pointing inward — the "restore from expanded" affordance. */
    const val ICON_RESTORE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M19 10h-5V5M5 14h5v5\"/>" +
            "<path d=\"M13 11l6-6M11 13l-6 6\"/></svg>"

    /** Plain × glyph for the close affordance. */
    const val ICON_CLOSE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M6 6l12 12M18 6L6 18\"/></svg>"

    /**
     * Three dots in a row — the canonical overflow ("kebab") glyph.
     *
     * Filled rather than stroked, because three 1.8-weight rings at 14px read
     * as noise where three solid dots read as three dots.
     *
     * The toolkit renders no kebab of its own — the overflow button is the
     * host's, since only the host knows what belongs in the menu (see
     * [PaneMenuItem]). This constant exists so that the hosts which do build
     * one stop each inventing a slightly different glyph for the same control.
     */
    const val ICON_MENU: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<circle cx=\"5\" cy=\"12\" r=\"1.7\"/>" +
            "<circle cx=\"12\" cy=\"12\" r=\"1.7\"/>" +
            "<circle cx=\"19\" cy=\"12\" r=\"1.7\"/></svg>"

    /** Empty rectangle — "make this pane fill the container". */
    const val ICON_MAXIMIZE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<rect x=\"3.5\" y=\"4.5\" width=\"17\" height=\"15\" rx=\"2.5\"/></svg>"

    /** Two stacked rectangles — "restore from maximised". */
    const val ICON_UNMAXIMIZE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<rect x=\"7\" y=\"8\" width=\"13.5\" height=\"11.5\" rx=\"2.5\"/>" +
            "<path d=\"M3.5 15V7a2.5 2.5 0 0 1 2.5-2.5h9\"/></svg>"

    /**
     * Short horizontal line — minimise / hide affordance.
     *
     * Deliberately short and inset (6→14 rather than edge to edge): at 14px a
     * full-width rule is indistinguishable from a divider, and it sat beside a
     * full-width maximize rectangle that it then appeared to underline.
     */
    const val ICON_MINIMIZE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<line x1=\"6\" y1=\"17\" x2=\"14\" y2=\"17\"/></svg>"

    /** Up-arrow for "navigate one level up" in path/tree-style pane content. */
    const val ICON_UP: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<line x1=\"12\" y1=\"19\" x2=\"12\" y2=\"5\"/>" +
            "<polyline points=\"5 12 12 5 19 12\"/></svg>"

    /** House glyph for "go to root" navigation. */
    const val ICON_HOME: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M3 11l9-8 9 8\"/>" +
            "<path d=\"M5 10v10h14V10\"/></svg>"

    /**
     * Expand the pane to fill its tab. The host typically wires this to a
     * `PaneTreeOps.expand(leafId)` operation (Phase 4) plus a re-render.
     *
     * @param handler invoked on click
     * @return a [PaneAction] tagged with class `dt-pane-action-expand`
     */
    fun expand(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_EXPAND,
        tooltip = "Expand pane",
        handler = handler,
        extraClass = "dt-pane-action-expand",
    )

    /**
     * Restore the pane from an expanded state to its prior tree position.
     * Hosts that share the expand/restore gesture on a single button should
     * pass `PaneActions.expand(...)` with `isActive = true` instead of
     * swapping the icon — this factory is for headers that prefer two
     * distinct buttons.
     *
     * @param handler invoked on click
     * @return a [PaneAction] tagged with class `dt-pane-action-restore`
     */
    fun restore(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_RESTORE,
        tooltip = "Restore pane",
        handler = handler,
        extraClass = "dt-pane-action-restore",
    )

    /**
     * Close the pane. Hosts wire this to `PaneTreeOps.close(leafId)` plus
     * a re-render; the toolkit does not own the tree state.
     *
     * @param handler invoked on click
     * @return a [PaneAction] tagged with class `dt-pane-action-close`
     */
    fun close(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_CLOSE,
        tooltip = "Close pane",
        handler = handler,
        extraClass = "dt-pane-action-close",
    )

    /**
     * Toggle maximised on a floating pane. Hosts mutate the matching
     * [FloatingPaneSpec.isMaximized] flag; the renderer paints
     * `.dt-pane-floating.dt-maximized` (full-bleed, top z-index) when
     * the flag is set. The icon swaps to the restore glyph automatically
     * when [isMaximized] is true so a single button can host both states.
     *
     * @param isMaximized current state — drives icon + tooltip choice.
     * @param handler     invoked on click.
     */
    fun maximize(isMaximized: Boolean, handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = if (isMaximized) ICON_UNMAXIMIZE else ICON_MAXIMIZE,
        tooltip = if (isMaximized) "Restore pane" else "Maximize pane",
        handler = handler,
        isActive = isMaximized,
        extraClass = "dt-pane-action-maximize",
    )

    /**
     * Minimise (hide) a floating pane. Hosts set the matching
     * [FloatingPaneSpec.isMinimized] flag and surface the pane in their
     * sidebar / overflow menu so the user can restore it.
     *
     * @param handler invoked on click.
     */
    fun minimize(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_MINIMIZE,
        tooltip = "Minimize pane",
        handler = handler,
        extraClass = "dt-pane-action-minimize",
    )

    /**
     * "Up one level" — navigate the pane's content one step toward the
     * root (notegrow's bullet zoom, file browsers, hierarchical content).
     * The toolkit doesn't own what "one level up" means; it just renders
     * the icon and fires the handler.
     *
     * @param handler invoked on click.
     */
    fun up(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_UP,
        tooltip = "Up one level",
        handler = handler,
        extraClass = "dt-pane-action-up",
    )

    /**
     * "Home" — navigate the pane's content all the way to the root.
     *
     * @param handler invoked on click.
     */
    fun home(handler: () -> Unit): PaneAction = PaneAction(
        iconHtml = ICON_HOME,
        tooltip = "Go to root",
        handler = handler,
        extraClass = "dt-pane-action-home",
    )

    /**
     * Visual separator between two action groups in the trailing strip.
     *
     * Renders as a non-interactive `<span class="dt-pane-action-separator">`
     * with a small horizontal width — used to break up navigation actions
     * (`up`, `home`) from window controls (`maximize`, `close`) so the two
     * groups read as distinct. Carries no handler and no tooltip.
     *
     * Detection in [renderPaneHeader] keys off [SEPARATOR_CLASS] in
     * [PaneAction.extraClass]; the click handler attached here is never
     * invoked because the renderer emits a span, not a button, for it.
     */
    const val SEPARATOR_CLASS: String = "dt-pane-action-separator"

    /** @return a sentinel [PaneAction] rendered as a non-interactive spacer. */
    fun separator(): PaneAction = PaneAction(
        iconHtml = "",
        tooltip = "",
        handler = {},
        extraClass = SEPARATOR_CLASS,
    )
}
