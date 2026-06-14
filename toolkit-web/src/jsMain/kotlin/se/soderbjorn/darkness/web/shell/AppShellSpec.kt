/* AppShellSpec.kt
 * Spec + handle types for `mountAppShell`. Together they define the
 * minimum surface a consuming app fills in: where to mount, what
 * persistence backend to use, how to render pane content, and any
 * extra sidebar sections / topbar trailing actions / hotkeys the app
 * wants on top of the toolkit defaults. Anything not listed here is
 * driven by toolkit defaults — that's the whole point of the
 * assembler. */
package se.soderbjorn.darkness.web.shell

import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.Persister
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.web.layout.PaneAction
import se.soderbjorn.darkness.web.layout.PaneTitleSegment
import se.soderbjorn.darkness.web.themeeditor.ThemeManagerHost

/**
 * Per-pane content factory contract.
 *
 * Apps return the element that should fill the body of the pane with
 * the given [paneId]. The toolkit owns the pane chrome (header, focus
 * ring, drag/resize, close affordance); the app owns the body.
 *
 * @param paneId stable identifier for the pane (matches the one in
 *   the toolkit's persisted layout state).
 * @return root element of the pane's content. Toolkit will append it
 *   into the toolkit-managed `.dt-pane-content` element.
 */
typealias PaneContentFactory = (paneId: String) -> HTMLElement

/**
 * One trailing-slot top-bar action.
 *
 * Two construction shapes:
 *
 * - **Declarative** (the common case): supply [id] + [iconHtml] + [label]
 *   + [onActivate] and the toolkit synthesizes a button in the canonical
 *   chrome style. Use this for ordinary "icon + click" actions.
 * - **Custom element** (escape hatch): supply a fully-built [element]
 *   and the toolkit appends it as-is. Use this when the app already
 *   has a rich pre-built widget (a popover trigger with managed state,
 *   a multi-button group, a custom dropdown) that doesn't fit the
 *   declarative shape.
 *
 * Exactly one of [element] vs the icon-trio fields should be set.
 *
 * @property id        stable element id; used by the toolkit to update
 *   icon / disabled state in place. Must be unique within the topbar.
 *   Ignored when [element] is supplied (the app owns the element id).
 * @property iconHtml  raw inline-SVG markup for the button icon.
 *   Required when [element] is null.
 * @property label     accessible label / tooltip text. Required when
 *   [element] is null.
 * @property onActivate invoked on click. Required when [element] is null.
 * @property element   pre-built element to append verbatim. When non-null,
 *   the icon-trio fields are ignored.
 */
data class TopbarAction(
    val id: String = "",
    val iconHtml: String = "",
    val label: String = "",
    val onActivate: () -> Unit = {},
    val element: HTMLElement? = null,
) {
    companion object {
        /** Convenience: wrap an existing element as a TopbarAction. */
        fun custom(element: HTMLElement): TopbarAction = TopbarAction(element = element)
    }
}

/**
 * One declarative sidebar section the app contributes (in addition to
 * the toolkit-supplied theme cards + hotkeys help sections).
 *
 * Distinct from the toolkit-internal [SidebarSectionSpec] (which is
 * the imperative `renderSidebarSection` input shape with explicit
 * isOpen/items/onToggle); this is the higher-level *declarative*
 * shape `mountAppShell` consumes — the assembler manages open state
 * and rendering on the app's behalf.
 *
 * @property id          stable id (becomes part of the section's DOM id).
 * @property title       header label.
 * @property bodyFactory invoked on first mount; returns the section's
 *   body element. Toolkit handles open/close chevron + persistence of
 *   the open state.
 */
data class AppShellSidebarSection(
    val id: String,
    val title: String,
    val bodyFactory: () -> HTMLElement,
)

/**
 * Theme bootstrap policy. Default seeds the toolkit's built-in themes
 * (dark + light variants of the default palette) and applies whatever
 * the user previously picked, falling back to the dark default if the
 * persister is empty.
 */
class ThemeBootstrap private constructor(internal val seedDefaults: Boolean) {
    companion object {
        /** Standard policy: seed the default themes, restore the picked one. */
        fun default(): ThemeBootstrap = ThemeBootstrap(seedDefaults = true)

        /** Custom: caller will seed themes manually after mount. */
        fun manual(): ThemeBootstrap = ThemeBootstrap(seedDefaults = false)
    }
}

/**
 * App-supplied snapshot of the current tab + pane structure.
 *
 * Apps push a fresh snapshot every time their model changes; the
 * assembler diffs against the previous snapshot and re-renders. Apps
 * own the source of truth — the assembler never invents or persists
 * tabs when a [TabSource] is supplied.
 *
 * @property tabs ordered tab list.
 * @property activeTabId the currently active tab's id (must be a member
 *   of [tabs] or `null` if the list is empty).
 */
data class TabListSnapshot(
    val tabs: List<TabSnapshotEntry>,
    val activeTabId: String?,
)

/**
 * One tab in [TabListSnapshot.tabs].
 *
 * @property id stable tab identifier (matches what's reported back via
 *   [TabSource.onSelect] / [TabSource.onClose] / etc.).
 * @property label visible tab label.
 * @property panes panes that belong to this tab (rendered in the main
 *   slot when this tab is active; listed under the tab in the default
 *   sidebar tree regardless of which tab is active).
 * @property activePaneId the focused pane within this tab, or `null`
 *   for tabs with no pane focused.
 * @property isHidden when `true`, the tab is omitted from the visible
 *   strip but still rendered in the overflow menu's "Unlisted tabs"
 *   section so the user can re-activate it. Toggled by the host through
 *   [TabSource.onSetHidden]. Defaults to `false`.
 * @property isHiddenFromSidebar when `true`, the tab is omitted from
 *   the default sidebar tree (but remains in the strip unless
 *   [isHidden] is also set). Orthogonal to [isHidden]. Toggled by the
 *   host through [TabSource.onSetHiddenFromSidebar]. Defaults to
 *   `false`.
 */
data class TabSnapshotEntry(
    val id: String,
    val label: String,
    val panes: List<PaneSnapshotEntry> = emptyList(),
    val activePaneId: String? = null,
    val isHidden: Boolean = false,
    val isHiddenFromSidebar: Boolean = false,
)

/**
 * One pane in [TabSnapshotEntry.panes].
 *
 * Identity-only: pane *geometry* (position, size, z-order, maximized
 * state) is owned by the toolkit and persisted under
 * [se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE], not by the
 * app's snapshot. Apps push the *set* of panes that exist; the toolkit
 * decides where they sit on screen and how they re-tile when the active
 * preset is auto.
 *
 * @property id stable pane identifier. Passed back to
 *   [AppShellSpec.paneContent] when rendering the body, and to
 *   [TabSource.onPaneSelect] on sidebar clicks.
 */
data class PaneSnapshotEntry(
    val id: String,
)

/**
 * One row of the topbar "New pane" split-button's hover dropdown.
 *
 * Hosts populate [TabSource.paneAddMenuItems] with a list of these so
 * the toolkit can render the secondary-action menu without knowing
 * which pane flavours the host supports. The icon click on the host
 * button itself routes to [TabSource.onPaneAdd] (the default action);
 * an item's [onSelect] fires only when that specific row is clicked.
 *
 * @property id stable identifier — used as the menu row's `data-id` and
 *   for diagnostics. Not shown to the user.
 * @property label visible text rendered next to the icon.
 * @property iconHtml inline SVG (or other markup) shown in the row's
 *   icon slot. The toolkit slots it into a fixed-size container.
 * @property onSelect invoked when the user clicks this row. The
 *   toolkit closes the menu first.
 * @see TabSource.paneAddMenuItems
 */
data class PaneAddMenuItem(
    val id: String,
    val label: String,
    val iconHtml: String,
    val onSelect: () -> Unit,
)

/**
 * Push-based source of the app's tab + pane structure. Supply this
 * (instead of relying on the assembler's local tab list) when the
 * tabs come from somewhere the toolkit cannot own — a server feed
 * (termtastic), a typed app document with migration logic
 * (notegrow's `LayoutState`), an external sync source, etc.
 *
 * ### Push contract
 * The assembler invokes [subscribe] once during mount, handing the
 * app a `push` callback. The app calls `push(snapshot)` whenever its
 * model changes; the assembler re-renders. The first call should
 * pass the current snapshot (so the assembler has a starting state);
 * subsequent calls reflect ongoing edits.
 *
 * ### Persistence
 * When a `TabSource` is supplied, the assembler does NOT read or
 * write [se.soderbjorn.darkness.core.PersistKeys.LAYOUT] for the tab
 * list. The app's source is authoritative. Per-pane geometry inside
 * each tab is also app-owned (the toolkit's pane move/resize
 * callbacks fire through to the app, which decides whether and how
 * to persist).
 *
 * @property subscribe invoked once at mount with a `push` callback.
 *   Apps capture `push` and call it whenever their snapshot changes.
 * @property onSelect fires when the user activates a tab (click or
 *   keyboard).
 * @property onAdd fires when the user clicks the trailing `+` button.
 *   `null` hides the button.
 * @property onClose fires when the user closes a tab. `null` makes
 *   tabs non-closable.
 * @property onRename fires when an inline rename is committed. `null`
 *   makes tabs non-renamable.
 * @property onReorder fires when a tab is dragged onto another. `null`
 *   disables drag-reorder.
 * @property onPaneSelect fires when the user clicks a pane in the
 *   default sidebar tree. The app should activate that pane (and its
 *   tab if not already active). `null` makes pane rows non-clickable.
 * @property onPaneClose fires when the user closes a pane via the
 *   pane chrome's × button (after the toolkit's confirm dialog). The
 *   app should drop the pane from its model and push a new snapshot
 *   so the toolkit re-renders without it. `null` makes panes
 *   non-closable in source mode.
 * @property onPaneAdd fires when the user clicks the topbar "New
 *   pane" button. The host should append a pane to its model for the
 *   given tab and push a new snapshot. `null` disables the new-pane
 *   button in source mode.
 *
 * Pane geometry callbacks (move/resize/maximize) are intentionally
 * absent: the toolkit owns pane geometry under
 * [se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE] and persists
 * through the app's [Persister]. Apps don't need to mirror geometry in
 * their own model.
 */
class TabSource(
    val subscribe: (push: (TabListSnapshot) -> Unit) -> Unit,
    val onSelect: (id: String) -> Unit,
    val onAdd: (() -> Unit)? = null,
    val onClose: ((id: String) -> Unit)? = null,
    val onRename: ((id: String, newLabel: String) -> Unit)? = null,
    val onReorder: ((sourceId: String, targetId: String, before: Boolean) -> Unit)? = null,
    val onPaneSelect: ((tabId: String, paneId: String) -> Unit)? = null,
    val onPaneClose: ((tabId: String, paneId: String) -> Unit)? = null,
    val onPaneAdd: ((tabId: String) -> Unit)? = null,
    /**
     * Optional secondary actions for the topbar "New pane" button. When
     * non-null, the toolkit renders the button as a split-button: the
     * icon click still routes to [onPaneAdd] (the host's default
     * creation), but hovering reveals a dropdown of the returned items,
     * each of which commits its own action when clicked.
     *
     * Termtastic uses this to surface Terminal-link / File Browser /
     * Git as one-hover-away choices while keeping the default
     * single-click behaviour for a plain terminal. Apps that don't set
     * this property keep the historical plain-button UX.
     *
     * The callback receives the active tab id and is evaluated every
     * time the menu opens, so it can return contextual items.
     */
    val paneAddMenuItems: ((tabId: String) -> List<PaneAddMenuItem>)? = null,
    /**
     * Fires when the user toggles a tab's "Hide / Show in tab bar"
     * entry in the overflow menu. The host should flip the tab's
     * hidden state in its own model and push a fresh snapshot with
     * [TabSnapshotEntry.isHidden] reflecting the new value. `null`
     * suppresses the menu item entirely.
     */
    val onSetHidden: ((id: String, hidden: Boolean) -> Unit)? = null,
    /**
     * Fires when the user toggles a tab's "Hide / Show in side bar"
     * entry in the overflow menu. Orthogonal to [onSetHidden] — apps
     * may surface either, both, or neither. The host should flip the
     * tab's sidebar-hidden state in its own model and push a fresh
     * snapshot with [TabSnapshotEntry.isHiddenFromSidebar] reflecting
     * the new value. `null` suppresses the menu item entirely.
     */
    val onSetHiddenFromSidebar: ((id: String, hidden: Boolean) -> Unit)? = null,
)

/**
 * Configuration handed to [mountAppShell]. Anything an app needs to
 * customize lives here; everything else is toolkit-owned.
 *
 * Hotkeys and command-palette wiring are deliberately **not** part of
 * this spec — both are app concerns. Apps that want shortcuts attach
 * their own DOM listeners; apps that want a palette mount their own
 * overlay.
 *
 * @property rootContainer where to mount the frame (e.g.
 *   `document.getElementById("app")`).
 * @property title initial window-chrome title; updated later via
 *   [AppShellHandle.setTitle].
 * @property persister durable read/write store for theme / layout /
 *   ui-settings (see [Persister]).
 * @property paneContent factory invoked once per pane to fill its body.
 * @property tabSource optional push-based source of the app's tab +
 *   pane structure. When supplied, the assembler renders whatever the
 *   source pushes and forwards user gestures back through the source's
 *   callbacks; the assembler stops reading or writing
 *   [se.soderbjorn.darkness.core.PersistKeys.LAYOUT] for tabs. When
 *   `null` (the default), the assembler uses its own local tab list,
 *   reading and writing `PersistKeys.LAYOUT` from the [persister] —
 *   suitable for simple apps where the tab list IS the persisted state
 *   (the `darkness-demo` reference is the canonical example).
 * @property paneLabel optional override for the visible label of each
 *   pane in the default sidebar tree. Defaults to the pane id.
 * @property paneIcon optional override for the per-pane chrome icon
 *   rendered to the left of the pane title (and the sidebar row label).
 *   The callback returns the icon as inline SVG/HTML, or `null` to omit
 *   the icon entirely. Defaults to a generic "window" glyph from the
 *   toolkit so apps that don't supply one still get a visible icon.
 *   Apps can return per-pane content-type icons (file/note/terminal/
 *   git/...) to mirror notegrow's pattern.
 * @property paneActions per-pane trailing-action buttons, prepended to
 *   the toolkit's standard window controls (maximize/close) in the
 *   pane chrome header. Apps return a fresh `List<PaneAction>` per
 *   call; the toolkit invokes this on every pane-header rerender, so
 *   values can reflect live state (e.g. zoom history availability).
 *   Empty by default — apps that need only the toolkit-supplied
 *   maximize/close need not supply this. Apps push fresh state
 *   through [AppShellHandle.refresh] when the inputs to this callback
 *   change.
 * @property paneSidebarBadge optional factory for a per-pane status
 *   badge inserted into the pane's sidebar row, between the row icon
 *   and the row label. Mirrors [se.soderbjorn.darkness.web.layout.PaneHeaderSpec.leadingBadge]
 *   and [TabSpec.trailingBadge] — apps return a pre-built `HTMLElement`
 *   the toolkit appends inside a `.dt-sidebar-row-badge` slot, or
 *   `null` to omit the badge entirely. The toolkit is intentionally
 *   opaque about contents: apps use this for spinners, attention dots,
 *   unread counts, and similar live-state indicators they update
 *   in-place by their own means (e.g. termtastic's
 *   `.pane-status-spinner` mutated by `updateStateIndicators`).
 *   Invoked on every sidebar rerender; defaults to returning `null`.
 * @property tabSidebarHeaderBadge optional factory for a per-tab status
 *   badge attached to the tab's header row in the default sidebar
 *   tree (the row that names the tab and groups its panes). Same
 *   contract as [paneSidebarBadge] — used to surface a tab-aggregated
 *   indicator when any pane in the tab has a notable state. Defaults
 *   to returning `null`.
 * @property tabTrailingBadge optional factory for a per-tab trailing
 *   badge in the tab strip itself (rendered after the tab label by
 *   the toolkit's [TabBar]). Same contract as the sidebar badge slots
 *   — apps return a pre-built `HTMLElement` they update in place, or
 *   `null` to omit the badge. The toolkit forwards the returned
 *   element to [TabSpec.trailingBadge] verbatim. Defaults to returning
 *   `null`.
 * @property paneHeaderBadge optional factory for a per-pane status
 *   badge in the pane chrome's header, between the leading icon
 *   ([paneIcon]) and the title. Forwarded verbatim to
 *   [se.soderbjorn.darkness.web.layout.PaneHeaderSpec.leadingBadge].
 *   Same contract as the other badge slots — apps return a pre-built
 *   `HTMLElement` they update in place, or `null` to omit the badge.
 *   Defaults to returning `null`.
 * @property sidebarSections optional app-specific sidebar sections,
 *   appended after the toolkit's default tabs/panes tree.
 * @property extraTopbarTrailing optional app-specific trailing topbar
 *   actions, appended after the toolkit's default actions
 *   (themes-toggle / theme-manager). A small visual gap separates the
 *   toolkit cluster from these extras.
 * @property extraTopbarBeforeStandard optional app-specific actions
 *   inserted at the leading edge of the trailing slot — i.e. *before*
 *   the toolkit's standard cluster (Layout / New pane / themes-toggle
 *   / theme-manager). A small visual gap separates them from the
 *   standard cluster. Use for app-level actions that conceptually live
 *   alongside the layout/theme controls but aren't toolkit-supplied
 *   (e.g. notegrow's "Starred bookmarks" tab-level button).
 * @property appPanes optional map from app-specific pane identifiers to
 *   their universal section ([se.soderbjorn.darkness.core.Sections]).
 *   Forwarded to the theme manager so it can present per-pane override
 *   rows for panes the app actually renders (e.g. notegrow's `starred`
 *   and `outline` panes mapping to `Auxiliary`). Defaults to empty —
 *   apps that only use the toolkit's universal sections need not
 *   supply this.
 * @property bottomBarLeading optional factory that returns custom DOM
 *   for the leading (left) slot of the toolkit's bottom bar. Used by
 *   apps that need a richer status footer than the default empty leading
 *   slot — e.g. termtastic's Claude API usage bar. Returning `null` from
 *   the factory leaves the slot empty (the toolkit default). The factory
 *   is invoked on each shell rerender; cache the element on the app side
 *   if its identity matters for event listeners.
 * @property bottomBarTrailing optional factory that returns custom DOM
 *   for the trailing (right) slot of the toolkit's bottom bar, replacing
 *   the toolkit's default app-name label. Use to surface app-specific
 *   trailing status (e.g. termtastic's connection-state dot). Returning
 *   `null` from the factory falls back to the toolkit default (the app
 *   name from [title]).
 * @property settingsHost optional [ThemeManagerHost] consumed by the
 *   toolkit-supplied Settings sidebar (gear icon in the topbar). When
 *   non-null, [mountAppShell] auto-adds the gear button to the trailing
 *   topbar cluster and mounts the Settings sidebar in the right slot
 *   while it is open. When `null`, the gear is suppressed — apps that
 *   don't want the toolkit settings panel can opt out by leaving this
 *   unset (notegrow / termtastic typically supply the same host they
 *   pass to the theme manager so settings round-trip through their
 *   existing persistence).
 * @property isElectron when `true`, the Settings sidebar shows the
 *   Custom title bar section (a no-op outside Electron). Defaults to
 *   `false`; apps detect their environment and pass the right value.
 * @property theme theme bootstrap policy (default = standard).
 */
data class AppShellSpec(
    val rootContainer: HTMLElement,
    val title: String,
    val persister: Persister,
    val paneContent: PaneContentFactory,
    val tabSource: TabSource? = null,
    val paneLabel: (tabId: String, paneId: String) -> String = { _, paneId -> paneId },
    val paneIcon: (tabId: String, paneId: String) -> String? = { _, _ -> DefaultPaneGlyph },
    val paneActions: (tabId: String, paneId: String) -> List<PaneAction> = { _, _ -> emptyList() },
    /**
     * Optional per-pane breadcrumb segments rendered in the chrome title
     * in place of the joined-string title from [paneLabel]. When the
     * returned list is non-empty the toolkit switches the title to
     * breadcrumb mode (one clickable `<span>` per segment, separated by
     * inert `/` spans); a segment with a non-null
     * [PaneTitleSegment.onClick] gets a link affordance and a click
     * handler that calls the closure. When empty (the default) the
     * toolkit falls through to the plain-string title, preserving the
     * inline-rename hover gesture for hosts that opt into it.
     *
     * Apps that surface a navigable path in their pane chrome
     * (notegrow's zoom path, file-browser cwd, etc.) supply this so
     * each ancestor segment becomes a direct jump target. The path text
     * still flows through [paneLabel] for the tooltip + sidebar row,
     * keeping the two views in sync.
     */
    val paneTitleSegments: (tabId: String, paneId: String) -> List<PaneTitleSegment> = { _, _ -> emptyList() },
    val paneSidebarBadge: (tabId: String, paneId: String) -> HTMLElement? = { _, _ -> null },
    val tabSidebarHeaderBadge: (tabId: String) -> HTMLElement? = { _ -> null },
    val tabTrailingBadge: (tabId: String) -> HTMLElement? = { _ -> null },
    val paneHeaderBadge: (tabId: String, paneId: String) -> HTMLElement? = { _, _ -> null },
    /**
     * Returns a 1-based pane slot index that the toolkit renders as an
     * encircled glyph (`①..⑨`, `Ⓐ..Ⓩ`) at the trailing edge of both the
     * pane header and the pane's sidebar row. The toolkit forces start-clip
     * (RTL truncation) on the title/label whenever this is non-null and
     * within the renderable range, so the badge and the title's
     * informative tail (e.g. current directory) both stay visible when
     * space is tight.
     *
     * Apps typically back this with
     * [se.soderbjorn.darkness.web.util.PaneSlotAssigner], which keeps the
     * same number on a pane for its entire lifetime. Returning `null`
     * (the default) hides the badge and leaves the chrome unchanged.
     */
    val paneIndex: (tabId: String, paneId: String) -> Int? = { _, _ -> null },
    val sidebarSections: List<AppShellSidebarSection> = emptyList(),
    val extraTopbarTrailing: List<TopbarAction> = emptyList(),
    val extraTopbarBeforeStandard: List<TopbarAction> = emptyList(),
    val appPanes: Map<String, String> = emptyMap(),
    val bottomBarLeading: (() -> HTMLElement?)? = null,
    val bottomBarTrailing: (() -> HTMLElement?)? = null,
    val settingsHost: ThemeManagerHost? = null,
    /**
     * Optional factory returning the body element of an app-supplied
     * "App settings" sidebar. When non-null, [mountAppShell] adds a
     * gear-style icon to the trailing topbar cluster, immediately to
     * the right of the Appearance gear; clicking it opens a right-side
     * sidebar whose body is whatever this factory returns. The factory
     * is invoked each time the sidebar opens, so apps can rebuild the
     * body against live state.
     *
     * When `null` (the default), the icon is suppressed entirely — apps
     * that don't want an app-settings panel simply don't supply one.
     * Mutual exclusion with the Theme Manager and Appearance Settings
     * sidebars is handled by the mount: opening this one animates
     * whichever sibling is open closed first.
     *
     * @see se.soderbjorn.darkness.web.settings.buildAppSettingsSidebar
     */
    val appSettingsContent: (() -> HTMLElement)? = null,
    val isElectron: Boolean = false,
    val theme: ThemeBootstrap = ThemeBootstrap.default(),
    /**
     * Optional callback fired when an inline rename of a pane title is
     * committed. Receives the active tab id, the pane id, and the new
     * label string (already trimmed by the toolkit's input wiring; an
     * empty string means "clear the override and revert to whatever the
     * label resolves to next time"). When `null` (the default) the
     * toolkit will not wire `PaneHeaderSpec.onRename` and any rename
     * gestures or programmatic triggers are silently no-ops.
     *
     * Hosts that want a rename UX should:
     * 1. Supply this callback (commit the new label to their model and
     *    push the resulting state back through the normal pane-label
     *    update path).
     * 2. Trigger the rename programmatically via
     *    [AppShellHandle.beginPaneRename] — for example from a context
     *    menu or kebab-menu item. The toolkit's hover-arm gesture is
     *    not auto-enabled by setting this callback.
     *
     * @see AppShellHandle.beginPaneRename
     * @see se.soderbjorn.darkness.web.layout.PaneHeaderSpec.onRename
     */
    val paneRename: ((tabId: String, paneId: String, newLabel: String) -> Unit)? = null,
    /**
     * Optional callback fired after pane geometry has been committed to
     * the DOM in response to a user gesture: split-bar / pane-corner
     * resize end, maximize / restore toggle, focus-driven auto-restore
     * of a maximized pane, or layout-preset apply (including Auto
     * re-tile on membership change). Fires exactly once per committed
     * change, at the end of the gesture / preset application — never
     * during drag. Receives the tab id whose geometry changed.
     *
     * Hosts that render their own pane bodies (text editors with
     * manual soft-wrap, virtualized lists, anything that measures the
     * container at paint time and caches the result) use this to
     * re-run their layout pass against the new container size. Browser
     * CSS reflow handles `display` / flex sizing on its own; this
     * callback is for caches that the browser can't refresh for the
     * host.
     *
     * The handler typically iterates the host's panes for the given
     * tab and, for each one, captures scroll anchor state, repaints
     * against the new geometry, and restores the anchor (e.g. "was at
     * bottom → pin to new bottom" rather than the pixel restore the
     * normal reconcile path does).
     *
     * `null` (the default) is the no-op default — the toolkit's own
     * chrome is fully refreshed by the surrounding [refresh] call
     * regardless of whether a host wires this.
     */
    val onGeometryChanged: ((tabId: String) -> Unit)? = null,

    /**
     * Fired at the end of every internal [AppShellHandle.refresh] /
     * rerender pass, after the toolkit has rebuilt its chrome slots
     * and re-applied [applyUiSettings] (Pass 1 + 2 + 3).
     *
     * **Why:** the toolkit's Pass 3 only paints the section selectors
     * it knows about (`.dt-pane`, `.dt-sidebar`, `.dt-topbar`,
     * `.dt-bottombar`). Hosts that layer their own per-section paint
     * on top — termtastic stamps the full `terminal` / `fileBrowser` /
     * `git` palettes onto `.terminal-cell > .terminal` etc., because
     * those are app-specific DOM that the toolkit can't target by
     * default — lose those inline overrides on every rerender
     * (LayoutRenderer wipes & rebuilds the pane subtrees). Without a
     * reapply hook the user sees the host-layered colours flip to the
     * scheme `.dt-pane` was painted with (the "windows" scheme),
     * instead of the dedicated terminal / fileBrowser / git schemes
     * the user picked. Symptom: the pane interior padding around the
     * terminal canvas shows the chrome scheme's surface colour
     * (cream/beige) instead of the terminal scheme's bg.
     *
     * **How to apply:** Register a handler that re-runs the host's
     * per-section paint pass. Cheap to no-op — the toolkit makes no
     * guarantees about call frequency, so handlers should be
     * idempotent. Fires AFTER the toolkit's own paint, so handlers
     * can safely overwrite anything the toolkit just stamped.
     */
    val onAfterRefresh: (() -> Unit)? = null,
)

/**
 * Default per-pane glyph used when [AppShellSpec.paneIcon] isn't
 * overridden. Matches the size + stroke conventions of the toolkit's
 * other 14×14 chrome icons so it composes cleanly with sidebar rows
 * and pane headers. A simple window-with-titlebar so apps that haven't
 * decided on a content-type icon still get a coherent default.
 */
const val DefaultPaneGlyph: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/>" +
        "<line x1=\"3\" y1=\"9\" x2=\"21\" y2=\"9\"/></svg>"

/**
 * Lifecycle handle returned by [mountAppShell]. Exposes only the
 * hooks an app legitimately needs post-mount.
 */
interface AppShellHandle {
    /** Updates the chrome title (e.g. when the active document changes). */
    fun setTitle(title: String)

    /** Programmatically focuses the active pane (used after route changes). */
    fun focusActivePane()

    /** Toggles the left sidebar's open state. */
    fun setSidebarOpen(open: Boolean)

    /**
     * Triggers a full shell rerender — top bar, sidebars, bottom bar
     * and pane chrome are rebuilt; cached pane content elements are
     * re-attached, not rebuilt, so host pane bodies (caret, scroll,
     * IME composition) survive. Apps call this when state that feeds
     * [AppShellSpec.paneActions], [AppShellSpec.paneLabel] or
     * [AppShellSpec.paneIcon] changes (e.g. zoom-history depth, the
     * pane's active file). Cheap to call repeatedly — the renderer
     * diffs against existing DOM where it can.
     */
    fun refresh()

    /**
     * Programmatically begins inline renaming of the pane with the given
     * id. The pane's title element is swapped for an input field
     * pre-filled with the current label; pressing Enter (or blurring
     * with a non-empty change) commits via [AppShellSpec.paneRename],
     * Escape (or blur with empty/unchanged text) cancels.
     *
     * Hosts wire this from a discoverable affordance such as a kebab
     * menu item — the toolkit deliberately does not auto-arm rename on
     * hover for pane headers. No-op when [AppShellSpec.paneRename] is
     * `null`, the pane id is unknown, or its title isn't currently
     * mounted (e.g. not in the active tab).
     *
     * @param paneId the unique pane id whose title should enter rename
     *   mode.
     */
    fun beginPaneRename(paneId: String)

    /**
     * Pushes an app-resolved [UiSettings] into the toolkit so subsequent
     * rerenders paint with it. Updates the toolkit's stored snapshot,
     * repaints `:root` CSS vars + per-section paint on the live chrome,
     * and reapplies host font vars.
     *
     * Use this when the app owns theme resolution outside the toolkit's
     * own theme manager (e.g. termtastic's `TermtasticThemeManagerHost`,
     * which writes through `appVm` and bypasses
     * [DefaultThemeManagerHost.onChange]). Without a sync call, the
     * toolkit's stored UI stays at whatever was loaded from the
     * persister at mount, and the next [refresh] (e.g. on tab/pane
     * switch) repaints chrome with that stale snapshot — clobbering the
     * app's own paint.
     *
     * Does NOT call [refresh]: it paints in place over the existing
     * chrome, mirroring the toolkit's internal `onThemeManagerChanged`
     * path so a rerender doesn't tear down anything the user is
     * currently interacting with (theme editor, open menu).
     */
    fun setUiSettings(ui: UiSettings)

    /** Tears down the shell, releasing toolkit-owned resources. */
    fun dispose()
}
