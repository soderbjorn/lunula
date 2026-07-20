/* AppShellSpec.kt
 * Spec + handle types for `mountAppShell`. Together they define the
 * minimum surface a consuming app fills in: where to mount, what
 * persistence backend to use, how to render pane content, and any
 * extra sidebar sections / topbar trailing actions / hotkeys the app
 * wants on top of the toolkit defaults. Anything not listed here is
 * driven by toolkit defaults — that's the whole point of the
 * assembler. */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.web.layout.PaneAction
import se.soderbjorn.lunula.web.layout.PaneTitleSegment
import se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost

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
 * @property worldId the id of the world these tabs belong to, when the
 *   host is world-aware (i.e. supplies [AppShellSpec.worldLayoutProvider]).
 *   The assembler keys pane geometry per world off this value: when it
 *   changes between snapshots the toolkit flushes the outgoing world's
 *   layout to its own persistence key and loads the incoming world's,
 *   so each world keeps an independent pane arrangement. `null` (the
 *   default) means "single-world" — geometry lives under the flat
 *   [se.soderbjorn.lunula.core.PersistKeys.LAYOUT_STATE] key as before.
 */
data class TabListSnapshot(
    val tabs: List<TabSnapshotEntry>,
    val activeTabId: String?,
    val worldId: String? = null,
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
 * [se.soderbjorn.lunula.core.PersistKeys.LAYOUT_STATE], not by the
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
 * write [se.soderbjorn.lunula.core.PersistKeys.LAYOUT] for the tab
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
 * [se.soderbjorn.lunula.core.PersistKeys.LAYOUT_STATE] and persists
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
 * One world in [WorldListSnapshot.worlds] — a named workspace one level
 * above tabs. Identity-only: the world's tabs come through the ordinary
 * [TabSource] / [TabListSnapshot] channel (the host swaps its tab feed
 * when the active world changes), so a world entry carries just its id
 * and label for the switcher.
 *
 * @property id    stable world identifier (matches what's reported back
 *   via [WorldSource.onSelect] / [WorldSource.onRename] / etc.).
 * @property label visible world name shown in the switcher.
 */
data class WorldSnapshotEntry(
    val id: String,
    val label: String,
)

/**
 * App-supplied snapshot of the current world list plus the active world.
 * Pushed through [WorldSource.subscribe] the same way [TabListSnapshot]
 * flows through [TabSource].
 *
 * @property worlds        ordered world list (first = default world).
 * @property activeWorldId the active world's id, or `null` when empty.
 */
data class WorldListSnapshot(
    val worlds: List<WorldSnapshotEntry>,
    val activeWorldId: String?,
)

/**
 * Push-based source of the app's **world** list — the container one level
 * above tabs. Mirrors [TabSource]: the assembler subscribes once at mount,
 * the host pushes a fresh [WorldListSnapshot] whenever its world model
 * changes, and user gestures on the toolkit's globe switcher route back
 * through these callbacks.
 *
 * Supplying a `worldSource` makes the toolkit render a globe world
 * switcher in the topbar leading cluster (left of the tab strip) and add
 * a "New world" entry to the "+" split-button dropdown. Leaving it `null`
 * (the default) suppresses the switcher entirely — single-world apps are
 * unaffected.
 *
 * Local-mode consumers (the toolkit's own demo) drive this from a
 * [se.soderbjorn.lunula.store.WorldsState]; source-mode consumers
 * (Lunamux) feed it from server state and forward the callbacks as world
 * commands.
 *
 * @property subscribe invoked once at mount with a `push` callback the
 *   host calls whenever its world snapshot changes. The first call should
 *   pass the current snapshot.
 * @property onSelect fires when the user activates a world in the switcher
 *   list. The host makes that world active and swaps its tab feed.
 * @property onAdd    fires when the user picks "New world" and confirms a
 *   name. `null` hides the entry.
 * @property onRename fires when a world rename is committed. `null` makes
 *   worlds non-renamable.
 * @property onClose  fires when the user closes a world (after the
 *   toolkit's confirm dialog). `null` makes worlds non-closable. The host
 *   is responsible for refusing to close the last world.
 * @property onMoveTab fires when the user picks a destination world from a
 *   tab's dot-menu "Move to world" submenu, with the tab id and the target
 *   world id. The host moves the tab (with its panes/sessions) into that
 *   world and pushes a fresh tab snapshot back. `null` hides the submenu.
 */
class WorldSource(
    val subscribe: (push: (WorldListSnapshot) -> Unit) -> Unit,
    val onSelect: (id: String) -> Unit,
    val onAdd: ((name: String) -> Unit)? = null,
    val onRename: ((id: String, newLabel: String) -> Unit)? = null,
    val onClose: ((id: String) -> Unit)? = null,
    val onMoveTab: ((tabId: String, worldId: String) -> Unit)? = null,
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
 *   [se.soderbjorn.lunula.core.PersistKeys.LAYOUT] for tabs. When
 *   `null` (the default), the assembler uses its own local tab list,
 *   reading and writing `PersistKeys.LAYOUT` from the [persister] —
 *   suitable for simple apps where the tab list IS the persisted state
 *   (the `lunula-demo` reference is the canonical example).
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
 *   and the row label. Mirrors [se.soderbjorn.lunula.web.layout.PaneHeaderSpec.leadingBadge]
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
 *   [se.soderbjorn.lunula.web.layout.PaneHeaderSpec.leadingBadge].
 *   Same contract as the other badge slots — apps return a pre-built
 *   `HTMLElement` they update in place, or `null` to omit the badge.
 *   Defaults to returning `null`.
 * @property sidebarSections optional app-specific sidebar sections,
 *   appended after the toolkit's default tabs/panes tree.
 * @property sidebarHeader optional factory returning custom DOM pinned to
 *   the TOP of the left sidebar, above the scrollable tabs/panes tree.
 *   Apps use this to surface a header that should ride along with the
 *   sessions list — e.g. termtastic's app logo + work-state dot. Returning
 *   `null` (the default) leaves the sidebar with no header. The factory is
 *   invoked on each shell rerender; apps that need stable element identity
 *   (event listeners, in-place state mutation) should cache and return the
 *   same element each call — the toolkit re-parents it into the freshly
 *   built sidebar rather than cloning it.
 * @property sidebarFooter optional factory returning custom DOM pinned to
 *   the BOTTOM of the left sidebar, below the scrollable tabs/panes tree.
 *   The content area flex-grows so this footer stays anchored to the
 *   bottom. Apps use it for a persistent status/action footer that belongs
 *   with the sessions list — e.g. termtastic's Claude usage rows plus the
 *   update / news pills. Same identity/caching contract and rerender cadence
 *   as [sidebarHeader]; `null` (the default) leaves no footer.
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
 * @property topbarLeading optional factory returning custom DOM for the
 *   LEADING (left) edge of the topbar, after the sidebar toggle and world
 *   switcher and before the tab strip. The counterpart to
 *   [bottomBarLeading], and the only way to put app content on that side:
 *   the leading cluster is otherwise built entirely from toolkit chrome, so
 *   an app with [showSidebar] `false` and no [worldSource] has an empty left
 *   edge it cannot fill. Apps use it for a persistent brand/identity line —
 *   e.g. lunicle's "Lunicle — an issue tracker by …", moved off a bottom bar
 *   that existed only to carry it. Returning `null` leaves the slot empty
 *   (the toolkit default). Same identity/caching contract and rerender
 *   cadence as [sidebarHeader]: the factory is invoked on each shell
 *   rerender, so cache the element on the app side if its identity matters
 *   for event listeners.
 * @property appPanes retained for call-site compatibility only. Under the
 *   post-revamp theme system there are no per-pane sections, so this map is
 *   no longer consumed by the theme manager. Existing apps may keep passing
 *   it (it is ignored); new apps should leave it empty. Defaults to empty.
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
    /**
     * Optional push-based source of the app's **world** list. When
     * supplied, the toolkit renders a globe world switcher in the topbar
     * leading cluster (left of the tab strip) and a "New world" entry in
     * the "+" dropdown; user gestures route back through the source's
     * callbacks. When `null` (the default), no switcher is shown and the
     * app behaves as a single-world app.
     *
     * @see WorldSource
     */
    val worldSource: WorldSource? = null,
    /**
     * Optional synchronous provider of a world's persisted layout blob,
     * used to make pane geometry per-world. When supplied (alongside a
     * [worldSource] and a [TabListSnapshot.worldId] on each snapshot), the
     * toolkit calls this the instant the active world changes to load that
     * world's saved layout — the same JSON shape [currentLayoutStateJson]
     * returns and [applyExternalLayoutState] accepts. Returning `null` (or
     * a blob for an unknown world) yields an empty layout, so that world's
     * panes Auto-tile on first show.
     *
     * The host reads it from its own already-cached settings snapshot, so
     * the call is synchronous and cheap; it must not block. Leaving this
     * `null` (the default) keeps the flat single-world model — geometry
     * stays under [se.soderbjorn.lunula.core.PersistKeys.LAYOUT_STATE]
     * and [TabListSnapshot.worldId] is ignored.
     *
     * Writes remain the toolkit's job: the assembler persists each world's
     * layout through [persister] under a per-world key derived from the
     * world id (the default/first world keeps the flat LAYOUT_STATE key so
     * pre-world clients and existing saved data keep working); the host's
     * persister adapter is responsible for routing the default world's
     * per-world key back onto LAYOUT_STATE if it needs old-client parity.
     */
    val worldLayoutProvider: ((worldId: String) -> String?)? = null,
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
     * [se.soderbjorn.lunula.web.util.PaneSlotAssigner], which keeps the
     * same number on a pane for its entire lifetime. Returning `null`
     * (the default) hides the badge and leaves the chrome unchanged.
     */
    val paneIndex: (tabId: String, paneId: String) -> Int? = { _, _ -> null },
    val sidebarSections: List<AppShellSidebarSection> = emptyList(),
    val sidebarHeader: (() -> HTMLElement?)? = null,
    val sidebarFooter: (() -> HTMLElement?)? = null,
    val extraTopbarTrailing: List<TopbarAction> = emptyList(),
    val extraTopbarBeforeStandard: List<TopbarAction> = emptyList(),
    val topbarLeading: (() -> HTMLElement?)? = null,
    val appPanes: Map<String, String> = emptyMap(),
    val bottomBarLeading: (() -> HTMLElement?)? = null,
    val bottomBarTrailing: (() -> HTMLElement?)? = null,
    /**
     * Whether the toolkit's bottom status bar is rendered at all. When
     * `true` (the default) the toolkit mounts its standard bottom bar —
     * the slim chrome strip whose slots are filled by [bottomBarLeading]
     * / [bottomBarTrailing] (or the app-name fallback). Set to `false` to
     * omit the bar entirely: the bottom-bar slot stays in the frame but
     * is left empty, so the app frame is just top bar + sidebars + main
     * with no footer chrome and no draggable bottom edge.
     *
     * Apps that have migrated all their footer status content elsewhere
     * (e.g. termtastic moved its Claude-usage rows into the left-sidebar
     * footer) opt out here so an otherwise-empty bar doesn't sit at the
     * bottom of the window. When `false`, [bottomBarLeading] /
     * [bottomBarTrailing] are never invoked.
     */
    val showBottomBar: Boolean = true,
    /**
     * Whether the toolkit's left sidebar exists at all. When `true` (the
     * default) the shell renders the sidebar-toggle button in the topbar
     * leading cluster and mounts the sidebar (or its collapsed
     * placeholder) in the left slot, exactly as before. Set to `false`
     * to omit BOTH: no toggle button, no sidebar, no drag-to-restore
     * placeholder — the left slot stays empty and
     * [AppShellHandle.setSidebarOpen] becomes a no-op. For apps whose
     * window model is simple enough that a tabs→panes tree adds nothing
     * (single-tab apps with few panes).
     *
     * Distinct from [AppShellHandle.setSidebarOpen]`(false)`, which
     * merely collapses a sidebar that still exists and can be re-opened.
     */
    val showSidebar: Boolean = true,
    /**
     * Whether the tab strip is rendered in the topbar's middle slot.
     * When `true` (the default) the full tab bar renders as before. Set
     * to `false` for apps whose tab model is a single implicit tab: the
     * tab (and its panes) still exists and renders in the main slot,
     * but no strip appears — the topbar shows only the leading cluster
     * and the trailing actions. Tab-related affordances that live in
     * the strip (dot menus, overflow, drag-reorder) are unreachable
     * while hidden, which is the point.
     */
    val showTabStrip: Boolean = true,
    /**
     * When `true` (the default) the pane chrome's close button routes
     * through the toolkit's [se.soderbjorn.lunula.web.confirmClosePane]
     * dialog before firing the close callback — the historical
     * behaviour. Apps that wrap their OWN confirmation around pane
     * close (e.g. an unsaved-changes Save/Discard dialog shown from
     * [TabSource.onPaneClose] before the pane is dropped from the
     * snapshot) set this to `false` so the user isn't asked twice.
     */
    val confirmPaneClose: Boolean = true,
    /**
     * Per-pane close affordance. Invoked when building each pane's
     * chrome; returning `false` omits the close button for that pane
     * entirely (minimize/maximize are unaffected). The default returns
     * `true` for every pane — the historical chrome. Apps with a
     * permanent "home" pane (a board, a dashboard) gate it here.
     *
     * UI-affordance only: it removes the button, it does not guard
     * programmatic close paths.
     */
    val paneClosable: (tabId: String, paneId: String) -> Boolean = { _, _ -> true },
    /**
     * Whether a brand-new pane (one with no persisted geometry yet)
     * spawns maximized. Consulted once when the pane's geometry entry is
     * first seeded; the user can restore/re-maximize freely afterwards
     * and persistence takes over as usual. The default returns `false`
     * for every pane — the historical cascade-spawn behaviour. Apps use
     * this for a primary pane that should fill the window on first
     * launch (with an in-memory persister: on every launch).
     */
    val paneOpensMaximized: (tabId: String, paneId: String) -> Boolean = { _, _ -> false },
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
     * that don't want an app-settings panel simply don't supply one —
     * unless [onAppSettingsActivate] is supplied instead, which is the
     * other way to ask for the same icon.
     * Mutual exclusion with the Theme Manager and Appearance Settings
     * sidebars is handled by the mount: opening this one animates
     * whichever sibling is open closed first.
     *
     * @see se.soderbjorn.lunula.web.settings.buildAppSettingsSidebar
     */
    val appSettingsContent: (() -> HTMLElement)? = null,
    /**
     * Optional click behaviour for the app-settings gear, *instead of*
     * the toolkit's built-in sidebar.
     *
     * The gear is the canonical place for "settings for this app", and
     * apps that already own such a surface — a modal, a route, a window
     * of their own — should be able to reach it from that icon rather
     * than rebuild it as a sidebar body or clone the glyph into their
     * own [extraTopbarTrailing] button (where it would drift from the
     * toolkit's icon and sit on the wrong side of the divider).
     *
     * When non-null the icon appears exactly as it does for
     * [appSettingsContent] and clicking it invokes this instead of
     * toggling the sidebar. Supplying both is not meaningful — this one
     * wins, and the sidebar is never opened — so supply one or the
     * other. The button never paints `.dt-active` in this mode: there
     * is no panel whose open state it could reflect.
     */
    val onAppSettingsActivate: (() -> Unit)? = null,
    /**
     * Whether the app-settings gear should be shown at all, evaluated on
     * every topbar rebuild.
     *
     * For apps whose settings surface is permission-gated: Lunicle's is
     * admin-only, and an icon that opens a dialog the server will refuse
     * is worse than no icon. Returning `false` omits the button outright
     * rather than disabling it — the same rule the "+" button follows,
     * and for the same reason: a control that cannot do anything reads
     * as broken rather than as forbidden.
     *
     * `null` (the default) means "always shown", so apps that do not
     * gate their settings need say nothing. Because this is re-read on
     * every rebuild, an app whose answer changes (a sign-in, a role
     * change) gets the button back on the next
     * [AppShellHandle.refresh].
     */
    val isAppSettingsAvailable: (() -> Boolean)? = null,
    /**
     * Optional factory returning the body element of an app-supplied
     * "Hotkeys" sidebar — a keyboard-shortcut reference. Unlike
     * [appSettingsContent] this does NOT add a topbar button; the host opens
     * the panel programmatically via [AppShellHandle.openHotkeysSidebar]
     * (e.g. from an in-app "Hotkeys" button or an Electron menu item). The
     * factory is invoked each time the sidebar opens, so apps can rebuild
     * the body against live state (e.g. the current platform).
     *
     * When `null` (the default) [AppShellHandle.openHotkeysSidebar] is a
     * no-op. Mutual exclusion with the Theme Manager, Appearance and App
     * Settings sidebars is handled by the mount: opening any one animates
     * whichever sibling is open closed first.
     *
     * @see se.soderbjorn.lunula.web.settings.buildHotkeysSidebar
     */
    val hotkeysContent: (() -> HTMLElement)? = null,
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
     * @see se.soderbjorn.lunula.web.layout.PaneHeaderSpec.onRename
     */
    val paneRename: ((tabId: String, paneId: String, newLabel: String) -> Unit)? = null,
    /**
     * When `true`, the inline pane-header rename forwards an **empty**
     * commit to [paneRename] instead of treating it as a cancel. Hosts
     * whose label model has a meaningful "no custom name" state (e.g.
     * termtastic, where an empty commit clears the user-set name and the
     * title reverts to the working directory / program title) opt in so
     * users can clear a name by emptying the field. A commit that merely
     * repeats the current title is still a no-op regardless of this flag.
     *
     * Defaults `false`, matching the historical behaviour where an empty
     * commit is discarded — appropriate for hosts (notegrow file titles,
     * the demo) whose labels must never be blank.
     *
     * @see paneRename
     * @see se.soderbjorn.lunula.web.layout.PaneHeaderSpec.allowEmptyRename
     */
    val allowEmptyPaneRename: Boolean = false,
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
     * rerender pass, after the toolkit has rebuilt its chrome slots and
     * re-applied the active theme (via [applyTheme]).
     *
     * **Why:** the toolkit paints the flat `--t-*` palette on `:root` and
     * relies on the CSS cascade for its own chrome. Hosts that paint their
     * own app-specific DOM directly (e.g. termtastic stamps colours onto
     * `.terminal-cell > .terminal`, which the toolkit can't target) lose
     * those inline overrides on every rerender (LayoutRenderer wipes &
     * rebuilds the pane subtrees). This hook lets them re-stamp after the
     * toolkit's paint.
     *
     * **How to apply:** Register a handler that re-runs the host's own paint
     * pass. Cheap to no-op — the toolkit makes no guarantees about call
     * frequency, so handlers should be idempotent. Fires AFTER the toolkit's
     * own paint, so handlers can safely overwrite anything just stamped.
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
     * Pushes an app-resolved [ThemeSnapshotV2] into the toolkit so subsequent
     * rerenders paint with it. Updates the toolkit's stored snapshot and the
     * default theme-manager state, repaints `:root` CSS vars on the live
     * chrome, reapplies host font vars, and repaints an open theme manager.
     *
     * Use this when the app owns theme resolution outside the toolkit's own
     * theme manager (e.g. termtastic's `TermtasticThemeManagerHost`, which
     * writes through `appVm`). Without a sync call, the toolkit's stored
     * snapshot stays at whatever was loaded from the persister at mount, and
     * the next [refresh] (e.g. on tab/pane switch) repaints chrome with that
     * stale snapshot — clobbering the app's own paint.
     *
     * Equally usable by an app that supplies **no** [AppShellSpec.settingsHost]
     * and so leaves the toolkit's own theme manager in charge — for instance to
     * change theme when the signed-in user changes, under a shell that was
     * mounted once and outlives the session. Such an app has no host state to
     * be the truth instead, which is why the push updates the theme manager's
     * own state too: what is pushed here is the whole answer afterwards, not a
     * paint laid over a state that still disagrees and would win back the
     * moment the user opened the theme manager.
     *
     * Does NOT call [refresh]: it paints in place over the existing chrome,
     * mirroring the toolkit's internal `onThemeManagerChanged` path so a
     * rerender doesn't tear down anything the user is currently interacting
     * with (theme editor, open menu).
     */
    fun setThemeSnapshot(snapshot: ThemeSnapshotV2)

    /**
     * Adopts an externally-authored layout-state blob (the toolkit's
     * `LAYOUT_STATE` persistence string) into the already-mounted shell:
     * updates pane geometry / z-order / maximize / minimize and the per-tab
     * preset + order, then re-renders so the change shows immediately.
     *
     * Use this when another client (e.g. a phone on the same server) changes
     * the layout and the host wants this live shell to reflect it without a
     * reload. No-op when the blob matches the current state, and it does NOT
     * re-persist — so adopting a pushed change can't loop back through the
     * broadcast that delivered it.
     *
     * @param layoutStateJson the `LAYOUT_STATE` blob string, as produced by the
     *   toolkit's own persistence.
     */
    fun applyExternalLayoutState(layoutStateJson: String)

    /**
     * Returns the shell's *live* layout state — per-tab preset, pane order,
     * and pane geometry (fractional rects, z-order, maximize/minimize) — as
     * the same `LAYOUT_STATE` JSON blob the toolkit persists. This is the
     * read counterpart of [applyExternalLayoutState]: it reflects in-session
     * drags/resizes immediately, without waiting for a persistence
     * round-trip.
     *
     * Hosts use this when they need the actual pane arrangement outside the
     * shell's own DOM — e.g. termtastic's 3D tab overview mirrors each tab's
     * pane rectangles onto its 3D cards.
     *
     * @return the layout-state blob string, in the exact format accepted by
     *   [applyExternalLayoutState].
     */
    fun currentLayoutStateJson(): String

    /**
     * Opens the app-supplied Hotkeys sidebar (see
     * [AppShellSpec.hotkeysContent]), animating any other right-side panel
     * (Theme Manager / Appearance / App Settings) closed first so only one
     * is ever mounted. Idempotent — a no-op when the Hotkeys sidebar is
     * already open, and a no-op entirely when [AppShellSpec.hotkeysContent]
     * is `null`.
     *
     * Hosts wire this to whatever entry points make sense — e.g. an in-app
     * "Hotkeys" button and, on Electron, an application-menu item.
     */
    fun openHotkeysSidebar()

    /** Tears down the shell, releasing toolkit-owned resources. */
    fun dispose()
}
