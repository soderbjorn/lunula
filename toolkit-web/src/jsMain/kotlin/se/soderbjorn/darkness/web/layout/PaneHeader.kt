/**
 * Pane chrome header primitive — title, optional leading badge, an action
 * button strip, and (optional) inline-rename and HTML5 drag gestures.
 *
 * Used by [LayoutRenderer] for every leaf pane it builds. The renderer
 * obtains a [PaneHeaderSpec] from [PaneCallbacks.paneHeader] and passes
 * it to [renderPaneHeader] together with the leaf's [PaneId]; the
 * resulting [HTMLElement] is appended above the pane's content slot.
 *
 * The toolkit owns rendering and gesture wiring only; pane state (titles,
 * action visibility, expanded leaf id) lives in the host app, which
 * mutates its model inside the callbacks and re-renders by calling
 * [LayoutRenderer.render] again.
 *
 * Visual styles ride on the `.dt-pane-header*` and `.dt-pane-action*`
 * classes shipped in `darkness-toolkit.css`; consumers must call
 * `injectDarknessToolkitStyles()` once at boot.
 *
 * @see PaneHeaderSpec
 * @see renderPaneHeader
 * @see PaneActions
 */
package se.soderbjorn.darkness.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.darkness.web.util.encircledIndexGlyph

/**
 * MIME type used in HTML5 drag-and-drop transfers to identify a pane drag
 * payload. Kept distinct from `text/plain` and from the tab-bar's
 * [se.soderbjorn.darkness.web.shell.DT_TAB_DRAG_MIME] so foreign drags
 * (text, files) and tab reorders cannot be mistaken for pane reorders.
 */
const val DT_PANE_DRAG_MIME: String = "application/x-darkness-pane"

/**
 * Data attribute set on the rename-target element (the title or the
 * breadcrumb leaf, depending on title mode) when [PaneHeaderSpec.onRename]
 * is non-null. Lets [triggerPaneRename] find the element by pane id
 * without walking the entire DOM. Internal — exposed at top level only
 * because Kotlin object members aren't usable in attribute selectors.
 */
private const val DT_PANE_RENAME_TARGET_ATTR: String = "data-dt-pane-rename-target"

/**
 * Property name on the rename-target element that carries the
 * `() -> Unit` start-rename closure stashed by [renderPaneHeader]. Read
 * by [triggerPaneRename]; written via `asDynamic()` so the closure
 * isn't serialized into a DOM attribute string. Held by the element so
 * gc cleans it up on detach.
 */
private const val DT_PANE_RENAME_FN_PROP: String = "__dtBeginRename"

/**
 * Toolkit DOM class names used by [renderPaneHeader] and the pane drop
 * target wired by [LayoutRenderer]. Stable so the host stylesheet can
 * theme them without depending on internal node structure.
 */
object PaneHeaderClassNames {
    const val HEADER = "dt-pane-header"
    const val LEADING_BADGE = "dt-pane-leading-badge"
    /**
     * Wrapper for [PaneHeaderSpec.leadingIcon]. Carries the per-pane SVG and
     * doubles as the drag handle for cross-tab pane drags (HTML5-draggable
     * when the spec opts in via [PaneHeaderSpec.isDraggable]).
     */
    const val LEADING_ICON = "dt-pane-header-icon"
    const val TITLE = "dt-pane-title"
    const val TITLE_ARMED = "dt-pane-title-armed"
    const val TITLE_INPUT = "dt-pane-title-input"
    /** Container modifier applied to `.dt-pane-title` when rendering breadcrumb segments. */
    const val TITLE_BREADCRUMBS = "dt-pane-title-breadcrumbs"
    /** A single breadcrumb segment span inside a breadcrumb-mode title. */
    const val BREADCRUMB_SEGMENT = "dt-pane-breadcrumb-segment"
    /** Click-enabled breadcrumb segment (carries an `onClick`). */
    const val BREADCRUMB_SEGMENT_LINK = "dt-pane-breadcrumb-segment-link"
    /** Trailing leaf segment — renamable when the spec supplies `onRename`. */
    const val BREADCRUMB_SEGMENT_LEAF = "dt-pane-breadcrumb-segment-leaf"
    /** Inert separator between breadcrumb segments. */
    const val BREADCRUMB_SEPARATOR = "dt-pane-breadcrumb-separator"
    /**
     * Secondary label pinned to the trailing edge, just before [ACTIONS].
     * Present iff the spec carried a non-blank
     * [PaneHeaderSpec.trailingLabel].
     */
    const val TRAILING_LABEL = "dt-pane-trailing-label"
    const val ACTIONS = "dt-pane-actions"
    const val ACTION = "dt-pane-action"
    const val ACTION_ACTIVE = "dt-active"
    const val PANE_DRAGGING = "dt-pane-dragging"
    const val PANE_DROP_TARGET = "dt-pane-drop-target"
    /**
     * Trailing pane-index badge (encircled digit / letter). Present iff the
     * spec carried a non-null [PaneHeaderSpec.paneIndex] within the
     * renderable range. The CSS uses `:has(.dt-pane-index)` to flip the
     * title to start-clip mode so the title's tail and the badge both stay
     * visible when space is tight.
     */
    const val INDEX = "dt-pane-index"
}

/**
 * One segment of a breadcrumb-style pane title. Hosts assemble a
 * [PaneHeaderSpec.titleSegments] list (root → leaf) and the toolkit renders
 * each segment as an inline span, separated by `/` separators.
 *
 * @property label   the visible segment text (e.g. one ancestor name).
 * @property onClick fires when the user clicks this segment. `null` makes
 *   the segment inert (typical for the trailing leaf, or for the implicit
 *   "current location" segment that has nowhere to navigate to). Click
 *   events have `stopPropagation()` applied by the toolkit so a segment
 *   click never starts the header's drag gesture.
 */
data class PaneTitleSegment(
    val label: String,
    val onClick: (() -> Unit)? = null,
)

/**
 * Declarative description of a pane header.
 *
 * The renderer turns this into DOM in [renderPaneHeader]; rebuilding the
 * spec with new values and re-rendering is the supported way to update.
 *
 * @property title         the visible title string; `null` shows a single
 *   em-dash placeholder.
 * @property leadingBadge  optional element rendered before the title (a
 *   status spinner, a connection dot, an icon). The toolkit appends it
 *   as-is — the host owns its lifecycle.
 * @property actions       trailing icon-button strip. Each [PaneAction]
 *   carries its own click handler; the toolkit wires `stopPropagation()`
 *   so action clicks don't bubble into the header's drag gesture.
 * @property onRename      when non-null, the title supports inline rename
 *   via the hover-arm gesture (1s hover → click) or double-click. Commit
 *   (Enter / blur with non-empty changed text) fires this callback with
 *   the new title; Escape cancels.
 * @property isDraggable   when `true`, the header is HTML5-draggable and
 *   carries the pane id as [DT_PANE_DRAG_MIME] payload. The drop target
 *   is the destination pane's `.dt-pane` element, wired by the renderer
 *   when [PaneCallbacks.onPaneDragged] is provided.
 */
data class PaneHeaderSpec(
    val title: String?,
    val leadingBadge: HTMLElement? = null,
    val actions: List<PaneAction> = emptyList(),
    val onRename: ((newTitle: String) -> Unit)? = null,
    /**
     * When `true`, an **empty** rename commit fires [onRename] with the
     * empty string instead of being discarded as a cancel. Hosts whose
     * label model treats "" as a distinct state — clearing a user-set
     * name so the title reverts to a derived default — opt in. A commit
     * equal to the current title stays a no-op regardless of this flag.
     *
     * Defaults `false`: an empty commit restores the original title in
     * place (the legacy behaviour, correct for labels that must never be
     * blank). Wired from [se.soderbjorn.darkness.web.shell.AppShellSpec.allowEmptyPaneRename].
     */
    val allowEmptyRename: Boolean = false,
    val isDraggable: Boolean = false,
    /**
     * Optional inline SVG (or HTML) rendered before [leadingBadge] as the
     * per-pane content-type icon (file/note/terminal/git/...). Doubles as
     * the cross-tab drag handle when [isDraggable] is set: dragging it
     * carries the pane id as [DT_PANE_DRAG_MIME] so a tab in the
     * `TabBar` can accept the drop and the host can move the pane to that
     * tab. The toolkit assigns the [PaneHeaderClassNames.LEADING_ICON]
     * class to the wrapper.
     */
    val leadingIcon: String? = null,
    /**
     * When `true`, the title overflows from the LEFT instead of the right
     * (CSS `direction: rtl; text-align: left`) so long titles whose
     * informative tail is on the right end (file paths, bullet trails)
     * keep the rightmost segment visible. The full title remains
     * accessible via the element's `title` tooltip. Defaults `false` so
     * existing consumers see the standard tail-truncation.
     */
    val titleAlignRight: Boolean = false,
    /**
     * Optional breadcrumb-style title. When non-empty, the toolkit renders
     * each segment as its own span (separated by `/`) instead of the plain
     * [title] string; segments with a non-null [PaneTitleSegment.onClick]
     * are clickable and navigate to that ancestor. The list runs root →
     * leaf.
     *
     * When the rendered breadcrumb row overflows, the toolkit collapses
     * the **leading** segments first (e.g. `… / läxor / matteläxan / x`),
     * keeping the leaf and its closest ancestors visible — the reverse of
     * the right-truncation done for plain string titles.
     *
     * Rename behaviour in breadcrumb mode is opt-in via
     * [renameLeafSegment]; defaults to off so the leaf click is free to
     * act like the other segments (typically a navigation no-op or a
     * focus restore).
     *
     * [title] is still required (used as the tooltip and as the fallback
     * when this list is empty) so consumers don't need to assemble both
     * the segments and a joined string.
     */
    val titleSegments: List<PaneTitleSegment> = emptyList(),
    /**
     * Controls whether the inline-rename gesture wires to the trailing
     * leaf segment when [titleSegments] is non-empty. Has no effect in
     * plain-title mode, where [onRename] always targets the whole title.
     *
     * Defaults `false`: breadcrumb leaf is purely a label / navigation
     * target, and rename happens elsewhere (e.g. on the parent's child
     * list, as in notegrow). Hosts that want path-style titles where the
     * filename is editable in place — termtastic's file-path headers,
     * for example — set this to `true` together with [onRename].
     */
    val renameLeafSegment: Boolean = false,
    /**
     * Controls whether the title also auto-arms the rename gesture on
     * hover (1s hover → cursor changes → click swaps in the input).
     * Independent of [onRename] being set: a host can wire `onRename`
     * for menu-driven rename only, leaving hover inert.
     *
     * Defaults `false` so the rename UX is opt-in. Hosts that wire only
     * a kebab-menu-driven rename leave it off and trigger via
     * [triggerPaneRename]; hosts that want the legacy hover-arm gesture
     * (e.g. notegrow file titles) set it to `true`.
     */
    val armRenameOnHover: Boolean = false,
    /**
     * Optional 1-based pane index. When non-null and within the renderable
     * range (1..35), the toolkit appends an encircled-digit-or-letter
     * badge after the actions strip — `①..⑨` then `Ⓐ..Ⓩ` — and forces the
     * title to start-clip (CSS `direction: rtl`) so the badge and the
     * title's informative tail (typically the current directory or note
     * name) both stay visible when space is tight. Out-of-range or null
     * indices render the header exactly as before.
     *
     * Apps maintain the index via
     * [se.soderbjorn.darkness.web.util.PaneSlotAssigner], which keeps the
     * number stable for the pane's lifetime.
     */
    val paneIndex: Int? = null,
    /**
     * Optional secondary label pinned to the header's trailing edge, just
     * before the [actions] strip. Rendered dimmed and at normal weight so
     * it reads as context *about* the pane rather than part of its title —
     * the pane's owning tab name, a branch, a host, a connection label.
     *
     * Distinct from [titleSegments]: a breadcrumb describes the path *to*
     * this pane's content and shares the title's emphasis; a trailing label
     * is unrelated context that must not compete with the title for the
     * left edge. Blank / null renders no element at all.
     *
     * Lunamux's 3D world uses this for the pane's tab name, which its 2D
     * headers omit (in 2D the tab bar already shows it, but a pane floating
     * in the 3D ring has no such surrounding context).
     */
    val trailingLabel: String? = null,
)

/**
 * Default header-spec factory used by [PaneCallbacks.paneHeader] when the
 * host doesn't override it. Renders the pane's title with no actions, no
 * rename, and no drag — the absolute minimum that still passes the
 * "header-on-top-of-content" visual contract. Hosts that want a closable
 * pane add `PaneActions.close { ... }` to a custom factory.
 *
 * @param paneId    the pane's id (unused by the default implementation but
 *                  passed through for parity with the callback signature).
 * @param paneTitle optional pane title.
 * @return a minimal [PaneHeaderSpec] suitable as a sensible default
 */
@Suppress("UNUSED_PARAMETER")
fun defaultPaneHeader(paneId: PaneId, paneTitle: String?): PaneHeaderSpec =
    PaneHeaderSpec(title = paneTitle)

/**
 * Renders [spec] into a fresh [HTMLElement] tagged with the leaf's
 * [paneId] (via `data-pane-id`) and the `.dt-pane-header` class.
 *
 * The element is *not* attached to the document — callers (typically
 * [LayoutRenderer]) mount it under the pane's `.dt-pane` element. The
 * function wires every gesture in the spec (action clicks, rename,
 * drag-source attribute) before returning, so the caller doesn't need
 * to do post-processing.
 *
 * @param paneId the leaf id; used as the rename target, the drag
 *   payload, and the value of the header's `data-pane-id` attribute.
 * @param spec   the declarative header description.
 * @return the header [HTMLElement] ready to append.
 */
fun renderPaneHeader(paneId: PaneId, spec: PaneHeaderSpec): HTMLElement {
    val header = document.createElement("div") as HTMLElement
    header.className = PaneHeaderClassNames.HEADER
    header.setAttribute("data-pane-id", paneId)

    spec.leadingIcon?.let { iconHtml ->
        val icon = document.createElement("span") as HTMLElement
        icon.className = PaneHeaderClassNames.LEADING_ICON
        icon.innerHTML = iconHtml
        // The icon is the cross-tab drag handle when the spec opts in.
        // Wired here (not on the whole header) so the user has an obvious
        // grab-target separate from the title's hover-arm rename gesture
        // — termtastic's `.pane-header-icon` follows the same convention.
        if (spec.isDraggable) wireHeaderIconDragSource(icon, paneId)
        header.appendChild(icon)
    }

    spec.leadingBadge?.let {
        val slot = document.createElement("span") as HTMLElement
        slot.className = PaneHeaderClassNames.LEADING_BADGE
        slot.appendChild(it)
        header.appendChild(slot)
    }

    val title = document.createElement("div") as HTMLElement
    val breadcrumbs = spec.titleSegments
    val indexGlyph = spec.paneIndex?.let { encircledIndexGlyph(it) }
    title.className = buildString {
        append(PaneHeaderClassNames.TITLE)
        if (spec.titleAlignRight && breadcrumbs.isEmpty()) {
            append(' ').append("dt-pane-title-rtl")
        }
        if (breadcrumbs.isNotEmpty()) append(' ').append(PaneHeaderClassNames.TITLE_BREADCRUMBS)
    }
    if (breadcrumbs.isEmpty()) {
        // Wrap the title text in a `<bdi>` so the text run's own direction
        // (LTR for latin, RTL for e.g. Arabic — <bdi> defaults to dir=auto)
        // is isolated from the element's base direction. The start-clip modes
        // — the `.dt-pane-title-rtl` opt-in and any host-side `direction: rtl`
        // override — set the ELEMENT direction to RTL purely so long titles
        // clip from the left (keeping the informative tail visible). Without
        // this isolation the RTL base would also reorder leading NEUTRAL
        // characters: a channel name like "#commodore" renders "commodore#",
        // a path "/a/b" renders "a/b/". The `<bdi>` keeps the glyph order
        // intact while the outer element still clips from the left.
        val bdi = document.createElement("bdi") as HTMLElement
        bdi.textContent = spec.title ?: "—"
        title.appendChild(bdi)
        // RTL truncation surfaces the rightmost segment of long titles (file
        // paths, bullet trails). Tooltip exposes the full text so users can
        // still read what's clipped.
        if (spec.titleAlignRight && spec.title != null) {
            title.setAttribute("title", spec.title)
        }
    } else {
        renderBreadcrumbSegments(title, breadcrumbs)
        // Full path stays in the tooltip so users can still see the parts
        // that get collapsed when the row overflows.
        spec.title?.let { title.setAttribute("title", it) }
    }
    header.appendChild(title)

    // Pane-index badge sits IMMEDIATELY after the title text (and before any
    // actions strip) so the digit reads as part of the title, not an item
    // in the close-button row. The title's `flex: 0 1 auto` keeps it sized
    // to its text so the badge nestles against the last character; actions
    // (rendered below) pin to the trailing edge via `margin-left: auto`.
    if (indexGlyph != null) {
        val badge = document.createElement("span") as HTMLElement
        badge.className = PaneHeaderClassNames.INDEX
        badge.textContent = indexGlyph
        badge.setAttribute("aria-label", "Pane ${spec.paneIndex}")
        header.appendChild(badge)
    }

    spec.onRename?.let { onRename ->
        // Rename target depends on title mode + opt-in:
        //  - plain-string titles always wire rename on the whole title;
        //  - breadcrumb mode only wires it when the host opts in via
        //    `renameLeafSegment = true`, in which case the leaf span is
        //    the target. Defaulting breadcrumb mode to no-rename keeps
        //    the leaf click free for navigation (notegrow's case);
        //    consumers like termtastic that want editable filenames in
        //    a path-style title set the flag and get leaf rename.
        val renameTarget: HTMLElement
        val renameSeed: String?
        if (breadcrumbs.isEmpty()) {
            renameTarget = title
            renameSeed = spec.title
        } else if (spec.renameLeafSegment) {
            renameTarget = title
                .querySelector(".${PaneHeaderClassNames.BREADCRUMB_SEGMENT_LEAF}") as? HTMLElement
                ?: return@let
            renameSeed = breadcrumbs.last().label
        } else {
            return@let
        }
        // Tag the target so external triggers (kebab menus, command
        // palettes) can find it via `triggerPaneRename(paneId)`.
        renameTarget.setAttribute(DT_PANE_RENAME_TARGET_ATTR, paneId)
        // Stash the start-rename closure on the element itself; cheap
        // and avoids a module-level map (no leak on detach — gc'd with
        // the element).
        renameTarget.asDynamic()[DT_PANE_RENAME_FN_PROP] = {
            startRename(renameTarget, paneId, renameSeed, onRename, spec.allowEmptyRename)
        }
        if (spec.armRenameOnHover) {
            // Use a data attribute (read by .dt-pane-title[data-dt-tooltip]:hover::after
            // in the toolkit stylesheet) instead of the native `title` attribute,
            // so the hint renders with chrome theme colours instead of the OS's
            // unstyled gray-on-light tooltip. We also drop any pre-existing
            // `title` attribute set above (RTL truncation case) so the native
            // tooltip doesn't double up on the styled one.
            renameTarget.removeAttribute("title")
            renameTarget.setAttribute("data-dt-tooltip", "Hover, then click to rename")
            wireInlineRename(renameTarget, paneId, renameSeed, onRename, spec.allowEmptyRename)
        }
    }

    // Trailing context label sits between the title and the actions strip.
    // Its `margin-left: auto` (see the stylesheet) absorbs the row's free space
    // so it lands flush against the actions rather than beside the title. The
    // stylesheet also zeroes the actions strip's own auto margin whenever this
    // label is present — two auto margins would split the free space between
    // them and strand the label mid-row.
    spec.trailingLabel?.takeIf { it.isNotBlank() }?.let { labelText ->
        val label = document.createElement("span") as HTMLElement
        label.className = PaneHeaderClassNames.TRAILING_LABEL
        // <bdi> for the same reason as the title: isolate the run's own
        // direction so a non-latin tab name doesn't reorder against the
        // header's base direction.
        val bdi = document.createElement("bdi") as HTMLElement
        bdi.textContent = labelText
        label.appendChild(bdi)
        label.setAttribute("title", labelText)
        header.appendChild(label)
    }

    if (spec.actions.isNotEmpty()) {
        val actions = document.createElement("div") as HTMLElement
        actions.className = PaneHeaderClassNames.ACTIONS
        // Don't let mousedown on the action strip start the parent's
        // drag gesture; otherwise an action click can be swallowed by
        // a competing dragstart on the same mousedown.
        actions.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        for (action in spec.actions) {
            actions.appendChild(
                if (action.extraClass.contains(PaneActions.SEPARATOR_CLASS))
                    buildActionSeparator(action)
                else
                    buildActionButton(action)
            )
        }
        header.appendChild(actions)
    }

    if (spec.isDraggable) wireHeaderDragSource(header, paneId)

    return header
}

/**
 * Populates [container] with one span per [segments] entry, separated by
 * inert `/` separator spans. Segments with a non-null
 * [PaneTitleSegment.onClick] get the link modifier class and a stop-
 * propagation click handler so the click can't bubble into the header's
 * drag gesture or the leaf-rename hover-arm. The last segment is tagged
 * with [PaneHeaderClassNames.BREADCRUMB_SEGMENT_LEAF] so the rename
 * gesture wiring can find it.
 *
 * @param container the `.dt-pane-title.dt-pane-title-breadcrumbs` element
 *   that should host the segment spans.
 * @param segments  the breadcrumb list, root → leaf. Must be non-empty;
 *   empty lists are handled by the plain-text path in [renderPaneHeader].
 */
private fun renderBreadcrumbSegments(container: HTMLElement, segments: List<PaneTitleSegment>) {
    val lastIndex = segments.lastIndex
    for ((i, segment) in segments.withIndex()) {
        val span = document.createElement("span") as HTMLElement
        val classes = buildString {
            append(PaneHeaderClassNames.BREADCRUMB_SEGMENT)
            if (segment.onClick != null) append(' ').append(PaneHeaderClassNames.BREADCRUMB_SEGMENT_LINK)
            if (i == lastIndex) append(' ').append(PaneHeaderClassNames.BREADCRUMB_SEGMENT_LEAF)
        }
        span.className = classes
        span.textContent = segment.label
        segment.onClick?.let { handler ->
            // Opt the link span out of HTML5 drag inheritance from the
            // (draggable) header. `<span>` inside a `draggable="true"`
            // parent inherits the drag source — pressing on the span
            // would arm a drag and silently swallow the subsequent click.
            // `<button>` and `<input>` are immune by virtue of being form
            // controls; spans need this explicit opt-out (mirrors the
            // rename input's own `draggable="false"`). Stopping mousedown
            // alone is not enough — `dragstart` is not a bubbled mousedown
            // event and fires on the draggable ancestor regardless.
            span.setAttribute("draggable", "false")
            span.addEventListener("click", { ev ->
                ev.stopPropagation()
                ev.preventDefault()
                handler()
            })
            // Belt-and-braces: also stop mousedown from reaching the
            // floating-pane drag listener on the header, so a tiny press
            // can't be interpreted as the start of a move-pane drag.
            span.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        }
        container.appendChild(span)
        if (i != lastIndex) {
            val sep = document.createElement("span") as HTMLElement
            sep.className = PaneHeaderClassNames.BREADCRUMB_SEPARATOR
            sep.setAttribute("aria-hidden", "true")
            sep.textContent = "/"
            container.appendChild(sep)
        }
    }
}

/**
 * Builds one icon-button DOM node for [PaneHeaderSpec.actions]. The button
 * carries `.dt-pane-action`, the action's [PaneAction.extraClass], and —
 * when [PaneAction.isActive] is set — the `.dt-active` modifier so the
 * stylesheet can paint a pressed state.
 */
/**
 * Builds the non-interactive spacer DOM node for a [PaneActions.separator]
 * entry. Renders a `<span>` (not a `<button>`) so it cannot be focused or
 * clicked; the [PaneActions.SEPARATOR_CLASS] CSS rule supplies the width.
 */
private fun buildActionSeparator(action: PaneAction): HTMLElement {
    val sep = document.createElement("span") as HTMLElement
    val classes = buildString {
        append(PaneActions.SEPARATOR_CLASS)
        // Preserve any extra classes the consumer added beyond the marker
        // so themers can target a specific separator instance if needed.
        action.extraClass
            .split(' ')
            .filter { it.isNotEmpty() && it != PaneActions.SEPARATOR_CLASS }
            .forEach { append(' ').append(it) }
    }
    sep.className = classes
    sep.setAttribute("aria-hidden", "true")
    return sep
}

private fun buildActionButton(action: PaneAction): HTMLElement {
    val btn = document.createElement("button") as HTMLButtonElement
    btn.type = "button"
    val classes = buildString {
        append(PaneHeaderClassNames.ACTION)
        if (action.extraClass.isNotEmpty()) append(' ').append(action.extraClass)
        if (action.isActive) append(' ').append(PaneHeaderClassNames.ACTION_ACTIVE)
    }
    btn.className = classes
    btn.title = action.tooltip
    btn.setAttribute("aria-label", action.tooltip)
    if (action.isActive) btn.setAttribute("aria-pressed", "true")
    btn.innerHTML = action.iconHtml
    btn.addEventListener("click", { ev ->
        ev.stopPropagation()
        ev.preventDefault()
        action.handlerWithAnchor?.invoke(btn) ?: action.handler()
    })
    return btn
}

/**
 * Wires the hover-arm → click → input rename gesture on the title element.
 *
 * Mechanics (ported verbatim from termtastic so the muscle memory matches):
 *  - `mouseenter` schedules a 1-second timer that sets `.dt-pane-title-armed`.
 *  - `mouseleave` (or any `click` while not armed) cancels the timer.
 *  - A `click` while armed swaps the title for an `<input>` pre-filled with
 *    the current title.
 *  - `dblclick` short-circuits the hover-arm — useful for power users who
 *    don't want to wait the full second.
 *  - The input commits on Enter or blur (with non-empty, changed text) and
 *    cancels on Escape.
 *
 * The host owns the title state. A successful commit fires [onRename] with
 * the new value; the host updates its model and re-renders, which discards
 * the transient input. Cancel restores the original title in place because
 * the host won't re-render for a no-op.
 *
 * @param titleEl     the `.dt-pane-title` element to rebind
 * @param paneId      pane id; passed back through [onRename]
 * @param currentTitle the title value used to detect "no-op" commits
 * @param onRename    fires on a changed commit; receives the new title
 * @param allowEmpty  when `true`, an empty commit is forwarded (clears the
 *   name) rather than treated as a cancel; see [PaneHeaderSpec.allowEmptyRename]
 */
private fun wireInlineRename(
    titleEl: HTMLElement,
    paneId: PaneId,
    currentTitle: String?,
    onRename: (String) -> Unit,
    allowEmpty: Boolean,
) {
    var armTimer: Int = -1
    fun disarm() {
        if (armTimer != -1) {
            window.clearTimeout(armTimer)
            armTimer = -1
        }
        titleEl.classList.remove(PaneHeaderClassNames.TITLE_ARMED)
    }
    titleEl.addEventListener("mouseenter", {
        disarm()
        armTimer = window.setTimeout({
            armTimer = -1
            titleEl.classList.add(PaneHeaderClassNames.TITLE_ARMED)
        }, 1000)
    })
    titleEl.addEventListener("mouseleave", { disarm() })
    titleEl.addEventListener("click", { ev ->
        if (!titleEl.classList.contains(PaneHeaderClassNames.TITLE_ARMED)) return@addEventListener
        ev.stopPropagation()
        disarm()
        startRename(titleEl, paneId, currentTitle, onRename, allowEmpty)
    })
    titleEl.addEventListener("dblclick", { ev ->
        ev.stopPropagation()
        ev.preventDefault()
        disarm()
        startRename(titleEl, paneId, currentTitle, onRename, allowEmpty)
    })
}

/**
 * Replaces [titleEl] with a focused, pre-selected `<input>` and wires
 * commit/cancel. Idempotent: a settled commit/cancel never re-fires even
 * if both Enter and blur arrive in quick succession.
 */
private fun startRename(
    titleEl: HTMLElement,
    paneId: PaneId,
    currentTitle: String?,
    onRename: (String) -> Unit,
    allowEmpty: Boolean,
) {
    val parent = titleEl.parentElement ?: return
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = PaneHeaderClassNames.TITLE_INPUT
    input.value = currentTitle ?: ""
    input.setAttribute("draggable", "false")
    input.setAttribute("data-pane-id", paneId)
    // Don't let mouse interactions on the input bubble into the
    // (potentially draggable) header.
    input.addEventListener("mousedown", { e -> e.stopPropagation() })
    input.addEventListener("click", { e -> e.stopPropagation() })
    input.addEventListener("dblclick", { e -> e.stopPropagation() })

    parent.replaceChild(input, titleEl)
    input.focus()
    input.select()

    var settled = false
    fun cancel() {
        if (settled) return
        settled = true
        if (input.parentElement === parent) parent.replaceChild(titleEl, input)
    }
    fun commit() {
        if (settled) return
        settled = true
        val newTitle = input.value.trim()
        // Discard an empty commit unless the host opted into empty renames
        // (clearing the name), and always discard a commit that just repeats
        // the current title — in both cases the host won't re-render, so
        // restore the original element in place.
        val emptyAndDisallowed = newTitle.isEmpty() && !allowEmpty
        if (emptyAndDisallowed || newTitle == (currentTitle ?: "")) {
            if (input.parentElement === parent) parent.replaceChild(titleEl, input)
            return
        }
        onRename(newTitle)
        // Don't restore — the host is expected to re-render with the new
        // title, which replaces the entire header.
    }

    input.addEventListener("blur", { commit() })
    input.addEventListener("keydown", { ev ->
        when ((ev as KeyboardEvent).key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); cancel() }
        }
    })
}

/**
 * Programmatically begins inline renaming of the pane with the given
 * [paneId]. Looks up the rename target [renderPaneHeader] tagged with
 * [DT_PANE_RENAME_TARGET_ATTR] and invokes the start-rename closure
 * stashed at [DT_PANE_RENAME_FN_PROP]. Hosts call this from kebab
 * menu items, command palettes, or keybindings to expose rename
 * without enabling the toolkit's hover-arm gesture.
 *
 * No-op when:
 *  - no rename target with that pane id is mounted (e.g. the pane
 *    isn't in the active tab),
 *  - the spec didn't supply [PaneHeaderSpec.onRename] (the closure
 *    isn't stashed).
 *
 * @param paneId pane id whose title should enter rename mode.
 * @return `true` when an input swap was started, `false` on no-op.
 * @see PaneHeaderSpec.onRename
 * @see PaneHeaderSpec.armRenameOnHover
 */
fun triggerPaneRename(paneId: PaneId): Boolean {
    val target = document.querySelector("[$DT_PANE_RENAME_TARGET_ATTR=\"$paneId\"]") as? HTMLElement
        ?: return false
    val starter = target.asDynamic()[DT_PANE_RENAME_FN_PROP]
    // The closure is only set by `renderPaneHeader` for panes whose
    // spec carries `onRename`; absent property means rename isn't wired.
    if (starter == null || starter == undefined) return false
    starter()
    return true
}

/**
 * Marks [header] as a HTML5 drag source for the pane's id. Dragstart
 * adds the [PaneHeaderClassNames.PANE_DRAGGING] class to the parent
 * `.dt-pane` so the stylesheet can dim the source during the drag.
 *
 * The renderer wires the matching drop target on each `.dt-pane` cell —
 * see [LayoutRenderer]'s `wirePaneDropTarget`.
 *
 * @param header the header element to make draggable
 * @param paneId the pane id, sent as the [DT_PANE_DRAG_MIME] payload
 */
private fun wireHeaderDragSource(header: HTMLElement, paneId: PaneId) {
    header.setAttribute("draggable", "true")
    header.addEventListener("dragstart", { ev ->
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData(DT_PANE_DRAG_MIME, paneId)
        // text/plain fallback for platforms that strip custom MIME types
        // in cross-window drags. Receivers always check the custom MIME
        // first.
        dt.setData("text/plain", "pane:$paneId")
        val pane = header.parentElement as? HTMLElement
        pane?.classList?.add(PaneHeaderClassNames.PANE_DRAGGING)
    })
    header.addEventListener("dragend", {
        val pane = header.parentElement as? HTMLElement ?: return@addEventListener
        pane.classList.remove(PaneHeaderClassNames.PANE_DRAGGING)
        // Clear stale drop indicators on every other pane in the document.
        val highlighted = document.querySelectorAll(".${PaneHeaderClassNames.PANE_DROP_TARGET}")
        for (i in 0 until highlighted.length) {
            (highlighted.item(i) as HTMLElement).classList.remove(PaneHeaderClassNames.PANE_DROP_TARGET)
        }
    })
}

/**
 * Marks [icon] (the leading content-type SVG wrapper) as a HTML5 drag
 * source for the pane id. Same payload as [wireHeaderDragSource] so any
 * existing drop target — pane cells, tab buttons — accepts both.
 *
 * Wired separately from the title-area drag so the user has a clear
 * grab-target distinct from the rename-on-hover gesture (mirrors
 * termtastic's `.pane-header-icon` behaviour).
 *
 * @param icon   the `.dt-pane-header-icon` element to make draggable.
 * @param paneId the pane id, sent as the [DT_PANE_DRAG_MIME] payload.
 */
private fun wireHeaderIconDragSource(icon: HTMLElement, paneId: PaneId) {
    icon.setAttribute("draggable", "true")
    icon.style.cursor = "grab"
    icon.addEventListener("dragstart", { ev ->
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData(DT_PANE_DRAG_MIME, paneId)
        dt.setData("text/plain", "pane:$paneId")
        // Walk up to the .dt-pane so the dragging dim class lands on the
        // outer chrome — same UX as wireHeaderDragSource.
        var cur: HTMLElement? = icon.parentElement as? HTMLElement
        while (cur != null && !cur.classList.contains("dt-pane")) {
            cur = cur.parentElement as? HTMLElement
        }
        cur?.classList?.add(PaneHeaderClassNames.PANE_DRAGGING)
    })
    icon.addEventListener("dragend", {
        var cur: HTMLElement? = icon.parentElement as? HTMLElement
        while (cur != null && !cur.classList.contains("dt-pane")) {
            cur = cur.parentElement as? HTMLElement
        }
        cur?.classList?.remove(PaneHeaderClassNames.PANE_DRAGGING)
        val highlighted = document.querySelectorAll(".${PaneHeaderClassNames.PANE_DROP_TARGET}")
        for (i in 0 until highlighted.length) {
            (highlighted.item(i) as HTMLElement).classList.remove(PaneHeaderClassNames.PANE_DROP_TARGET)
        }
    })
}

/**
 * Returns `true` when the drag event's `dataTransfer.types` includes
 * [DT_PANE_DRAG_MIME]. Used by drop targets to filter out foreign drags
 * (file drops, text selections, tab reorders) so the pane chrome doesn't
 * react to them.
 *
 * Public so hosts that wire bespoke drop targets (e.g. dropping a pane
 * onto a tab in the tab strip) can reuse the same filter.
 *
 * @param dynamicEvent the drag/drop event (passed via `asDynamic()`)
 * @return whether the drag carries a pane payload
 */
fun isPaneDrag(dynamicEvent: dynamic): Boolean {
    val types = dynamicEvent.dataTransfer?.types ?: return false
    val len = (types.length as? Number)?.toInt() ?: return false
    for (i in 0 until len) if (types[i] == DT_PANE_DRAG_MIME) return true
    return false
}
