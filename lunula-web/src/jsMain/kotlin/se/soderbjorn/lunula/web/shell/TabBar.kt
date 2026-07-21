/**
 * Tab-bar primitive for the toolkit's app shell.
 *
 * Renders a horizontal strip of tab buttons with optional per-tab close
 * affordance, optional trailing `+` (add) button, drag-to-reorder between
 * positions, drop-target indicators, inline rename on double-click. When
 * more tabs are present than fit on one row, the strip wraps onto
 * additional rows (see `.dt-tabbar-strip { flex-wrap: wrap }` in
 * lunula.css) and the surrounding chrome grows vertically to
 * accommodate.
 *
 * A strip can also be **app-defined** ([TabBarSpec.isFixed]): the same
 * primitive, with every editing affordance withheld, for hosts whose tab
 * set is part of the application rather than a document the user
 * arranges. Tabs may then carry a declarative [TabBadge] — a capped count
 * or a bare dot — which the toolkit draws itself so unread indicators
 * look the same in every consumer.
 *
 * The toolkit owns rendering and gesture wiring only; tab state (the list,
 * active id, ordering) lives in the host app, which mutates its model
 * inside the callbacks and re-renders by calling [renderTabBar] again or
 * by passing a fresh [TabBarSpec] through [TopBarSpec.tabBar].
 *
 * Visual styles ride on the `.dt-tabbar*` and `.dt-tab*` classes shipped
 * in `lunula.css`; consumers must call `injectLunulaStyles()`
 * once at boot.
 *
 * @see TabBarSpec
 * @see renderTabBar
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.escapeHtmlForConfirm
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.hotkey.HotkeyRegistry
import se.soderbjorn.lunula.web.hotkey.StandardHotkeys
import se.soderbjorn.lunula.web.hotkey.ToolkitHotkeyIds
import se.soderbjorn.lunula.web.hotkey.isElectronPlatform
import se.soderbjorn.lunula.web.showConfirmDialog

/**
 * MIME type used in HTML5 drag-and-drop transfers to identify a tab drag
 * payload. Kept distinct from `text/plain` so foreign drags (text from
 * elsewhere on the page, files dropped by the OS) cannot be mistaken for
 * tab reordering.
 */
const val DT_TAB_DRAG_MIME: String = "application/x-lunula-tab"

/**
 * A declarative unread/attention marker rendered at the trailing edge of a
 * tab.
 *
 * Distinct from [TabSpec.trailingBadge], which takes a host-built
 * `HTMLElement` and is deliberately opaque: that slot exists for live
 * widgets the host mutates in place (spinners, state dots it repaints
 * itself), and it makes the host responsible for the badge's *looks* as
 * well as its contents. An unread count is the opposite kind of thing —
 * every host wants it to look the same, and every host would otherwise
 * reinvent the same pill in its own markup and drift from the toolkit's
 * chrome. So this variant is a value, not an element: the host says
 * *what* the badge means and the toolkit decides how it is drawn.
 *
 * Two shapes, because two questions are worth answering differently:
 *
 * - [Count] — "how many", for a bounded backlog the user is expected to
 *   work through (unread direct messages). Rendered as a number, capped
 *   (see [formatTabBadgeCount]) so a runaway count cannot widen the tab.
 * - [Dot] — "something is new", for a feed whose volume is unbounded and
 *   which nobody is obliged to read to the end. A number there creates
 *   inbox-zero pressure for a stream that has no zero.
 *
 * @see TabSpec.badge
 * @see TabSnapshotEntry.badge
 * @see formatTabBadgeCount
 */
sealed class TabBadge {
    /**
     * A numeric badge showing [value], capped for display.
     *
     * A [value] of zero or less renders nothing at all, so hosts can push
     * the live count unconditionally on every snapshot rather than
     * branching on emptiness at the call site — "no unread" and "no badge"
     * are the same statement.
     *
     * @property value the count to show. `<= 0` renders no badge.
     * @property cap   the largest number rendered literally; anything above
     *   it renders as `"<cap>+"`. Defaults to [DEFAULT_TAB_BADGE_CAP] (99),
     *   giving the conventional `99+`.
     */
    data class Count(val value: Int, val cap: Int = DEFAULT_TAB_BADGE_CAP) : TabBadge()

    /** A dot with no number — "there is something new here". */
    data object Dot : TabBadge()
}

/**
 * Default cap for [TabBadge.Count]: counts above 99 render as `99+`.
 *
 * Chosen as the widest value that still fits the tab pill without the
 * strip reflowing, and it is what every mail client and chat app trains
 * users to read.
 */
const val DEFAULT_TAB_BADGE_CAP: Int = 99

/**
 * Pure formatter behind [TabBadge.Count]: renders [count] as badge text,
 * or `null` when no badge should be drawn.
 *
 * Kept free of any DOM dependency so the capping rule — the part with an
 * off-by-one worth getting wrong — can be unit-tested directly, the same
 * way [resolveTabSwitchIndex] is.
 *
 * @param count the live count the host pushed.
 * @param cap the largest number rendered literally.
 * @return the badge text, or `null` when [count] is zero or negative (no
 *   badge) — note that a non-positive [cap] degrades to `"0+"` rather
 *   than being rejected, since a host that asks for a nonsense cap has a
 *   bug in its own call and silently drawing nothing would hide it.
 */
internal fun formatTabBadgeCount(count: Int, cap: Int = DEFAULT_TAB_BADGE_CAP): String? = when {
    count <= 0 -> null
    count > cap -> "$cap+"
    else -> "$count"
}

/**
 * Builds the DOM for a declarative [badge].
 *
 * @param badge the badge to render.
 * @return the badge element, or `null` when the badge resolves to nothing
 *   visible (a [TabBadge.Count] of zero).
 */
internal fun buildTabBadgeElement(badge: TabBadge): HTMLElement? {
    val el = document.createElement("span") as HTMLElement
    when (badge) {
        is TabBadge.Dot -> {
            el.className = "${TabBarClassNames.TAB_BADGE} ${TabBarClassNames.TAB_BADGE_DOT}"
            // Purely decorative next to the tab's own label: a screen
            // reader gets the meaning from aria-label, not from a bullet.
            el.setAttribute("aria-label", "unread")
        }
        is TabBadge.Count -> {
            val text = formatTabBadgeCount(badge.value, badge.cap) ?: return null
            el.className = "${TabBarClassNames.TAB_BADGE} ${TabBarClassNames.TAB_BADGE_COUNT}"
            el.textContent = text
            // The capped text ("99+") is what is drawn; the label carries
            // the uncapped truth so assistive tech isn't lied to.
            el.setAttribute("aria-label", "${badge.value} unread")
        }
    }
    return el
}

/**
 * One tab in the [TabBarSpec.tabs] list.
 *
 * @property id            stable id used by the host to identify the tab in
 *   callbacks and `activeTabId`. Must be unique within the list.
 * @property label         visible tab label.
 * @property isClosable    when `true`, a small × button is rendered next to
 *   the label and clicking it fires [TabBarCallbacks.onClose]. Defaults to
 *   `false` so single-tab apps don't show a close affordance.
 * @property isDraggable   when `true`, the tab is HTML5-draggable and can
 *   participate in reorder gestures (firing [TabBarCallbacks.onReorder]).
 *   Defaults to `false` so apps must opt in.
 * @property isRenamable   when `true`, double-clicking the label swaps it
 *   for an inline text input. Commit fires [TabBarCallbacks.onRename];
 *   Esc cancels. Defaults to `false`.
 * @property trailingBadge optional element rendered after the label
 *   (e.g. a status spinner or icon). The toolkit appends it as-is.
 * @property badge optional declarative unread/attention badge rendered in
 *   the same trailing slot, drawn by the toolkit rather than the host.
 *   [trailingBadge] wins when both are set — an explicit element is the
 *   more specific instruction, and stacking two badges in one slot reads
 *   as a rendering bug rather than as two facts. Defaults to `null`.
 */
data class TabSpec(
    val id: String,
    val label: String,
    val isClosable: Boolean = false,
    val isDraggable: Boolean = false,
    val isRenamable: Boolean = false,
    val trailingBadge: HTMLElement? = null,
    val badge: TabBadge? = null,
    /**
     * When `true`, the tab is omitted from the visible strip but still
     * known to the spec. Surfaces in the [TabBarOverflowMenu] under
     * "Unlisted tabs" so the user can re-activate it. Toggled by
     * [TabBarCallbacks.onSetHidden] (see termtastic for the canonical use
     * case: temporarily hiding a noisy terminal from the strip).
     */
    val isHidden: Boolean = false,
    /**
     * When `true`, the tab is omitted from the host's left sidebar tree
     * but still rendered in the tab strip and still owns its panes.
     * Orthogonal to [isHidden] — apps can hide a tab from one surface
     * without hiding it from the other. The toolkit only reads this flag
     * to label the overflow-menu row ("Hide in side bar" vs. "Show in
     * side bar"); the actual sidebar suppression is the host's job.
     * Toggled by [TabBarCallbacks.onSetHiddenFromSidebar].
     */
    val isHiddenFromSidebar: Boolean = false,
)

/**
 * Host-supplied callbacks for [TabBarSpec]. The toolkit fires these when
 * the user interacts with a tab; the host updates its tab model and
 * re-renders. Every callback is optional except [onSelect].
 *
 * @property onSelect     fires when a tab is clicked. Receives the tab id.
 * @property onClose      fires when a closable tab's × button is clicked.
 *   `null` if no tab in the spec is closable.
 * @property onAdd        fires when the trailing `+` button is clicked.
 *   `null` (or paired with [TabBarSpec.showAddButton] = false) to hide
 *   the button.
 * @property onReorder    fires after a successful drag-drop tab reorder.
 *   `(sourceId, targetId, before)` — host should move `sourceId` to
 *   immediately before/after `targetId`. `null` to disable reordering.
 * @property onRename     fires when an inline rename is committed (Enter
 *   or blur with non-empty text). Receives `(id, newLabel)`. `null` to
 *   disable rename even if [TabSpec.isRenamable] is set.
 */
class TabBarCallbacks(
    val onSelect: (id: String) -> Unit = {},
    val onClose: ((id: String) -> Unit)? = null,
    val onAdd: (() -> Unit)? = null,
    val onReorder: ((sourceId: String, targetId: String, before: Boolean) -> Unit)? = null,
    val onRename: ((id: String, newLabel: String) -> Unit)? = null,
    /**
     * Toggles a tab's [TabSpec.isHidden] flag. Fired from the overflow
     * menu's "Hide / Show in tab bar" row. `null` to omit those rows.
     */
    val onSetHidden: ((id: String, hidden: Boolean) -> Unit)? = null,
    /**
     * Toggles a tab's [TabSpec.isHiddenFromSidebar] flag. Fired from the
     * overflow menu's "Hide / Show in side bar" row. `null` to omit those
     * rows. The toolkit only flips the label; hosts must persist the
     * change and re-render their sidebar to actually show / hide the tab.
     */
    val onSetHiddenFromSidebar: ((id: String, hidden: Boolean) -> Unit)? = null,
    /**
     * Fires when a pane is dropped onto a tab — the host should move the
     * pane identified by `sourcePaneId` into the tab identified by
     * `destTabId`. The tab bar only opts each tab into pane-drop-target
     * wiring when this callback is non-null, so pure-tab apps don't pay
     * for the listener overhead. Drop payload is the
     * `application/x-lunula-pane` MIME shipped by the toolkit's pane
     * header drag (`PaneHeader.wireHeaderIconDragSource` /
     * `wireHeaderDragSource`).
     */
    val onPaneDroppedOnTab: ((sourcePaneId: String, destTabId: String) -> Unit)? = null,
    /**
     * When `true` (the default), tab-close gestures (the per-tab × button
     * and the overflow menu's "Close" row) show a [showConfirmDialog]
     * before invoking [onClose]. Apps that already wrap their own
     * confirmation around close — like termtastic's `TabBarMenu` — should
     * pass `false`. Has no effect when [onClose] is `null`.
     */
    val confirmTabClose: Boolean = true,
    /**
     * The other worlds a tab can be moved into — every world except the one
     * currently shown — as `(id, label)` pairs. Populated by the assembler
     * from the [WorldSource] snapshot. When non-empty and [onMoveToWorld] is
     * set, each tab's dot menu grows a "Move to world" submenu listing these.
     * Empty (the default) omits the submenu.
     */
    val moveToWorlds: List<WorldSnapshotEntry> = emptyList(),
    /**
     * Fires when the user picks a destination world from a tab's dot-menu
     * "Move to world" submenu — `(tabId, worldId)`. `null` (the default), or
     * an empty [moveToWorlds], omits the submenu. Wired by the assembler to
     * [WorldSource.onMoveTab].
     */
    val onMoveToWorld: ((tabId: String, worldId: String) -> Unit)? = null,
)

/**
 * Tab-bar configuration passed to [renderTabBar].
 *
 * @property tabs          ordered list of tabs to render. Pass an empty
 *   list to show only the optional add button (or nothing at all).
 * @property activeTabId   id of the currently active tab, or `null` if no
 *   tab is selected.
 * @property showAddButton when `true`, a trailing `+` button is appended
 *   to the strip. Clicks fire [TabBarCallbacks.onAdd].
 * @property callbacks     host-supplied event handlers. See [TabBarCallbacks].
 */
class TabBarSpec(
    val tabs: List<TabSpec>,
    val activeTabId: String? = null,
    val showAddButton: Boolean = false,
    val callbacks: TabBarCallbacks = TabBarCallbacks(),
    /**
     * When `true`, append a far-right `⋮` overflow menu after the strip.
     * Since issue #65 it lists only the hidden ("Unlisted") tabs — each
     * row activates the tab and (when [TabBarCallbacks.onSetHidden] is
     * wired) offers a "Show in tab bar" affordance to un-hide it. The menu
     * renders no button at all when no tabs are hidden. Per-tab actions
     * (Rename / Close / Hide / Show) live in each tab's own dot menu
     * instead — see [appendTabDotMenu].
     */
    val showOverflowMenu: Boolean = false,
    /**
     * When `true`, the strip is **app-defined**: the tab set belongs to the
     * consuming app and the user may not add to it, remove from it, rename
     * it or reorder it. The bar renders selection and nothing else — no `+`
     * button, no per-tab `⋮` dot menu, no drag handles, no far-right
     * overflow list — and carries [TabBarClassNames.BAR_FIXED] so CSS can
     * give it the lighter, chromeless pill treatment such a strip wants.
     *
     * Enforced here rather than left to the caller passing the right
     * combination of nulls. A host *can* already suppress each affordance
     * individually by wiring no callback, but then "fixed" is an emergent
     * property of five separate omissions that nothing states and nothing
     * checks — add one callback for an unrelated reason and a control the
     * app never intended reappears. This flag is the statement, and the
     * bar honours it even if callbacks are wired: the flag wins.
     *
     * Selection affordances are deliberately untouched. Clicking a tab,
     * the Next/Previous chords and Cmd/Ctrl+`<digit>` all keep working —
     * a fixed strip constrains what the tab set *is*, not which member of
     * it the user is looking at.
     *
     * Defaults to `false`: every existing consumer keeps the fully
     * user-editable strip it has today.
     *
     * @see TabSource.fixed
     */
    val isFixed: Boolean = false,
)

/** Toolkit DOM class names used by [renderTabBar]. */
object TabBarClassNames {
    const val BAR = "dt-tabbar"

    /**
     * Added alongside [BAR] when [TabBarSpec.isFixed] is set. Selects the
     * app-defined strip's **layout** rules in `lunula.css`: it sits beside
     * the topbar's leading slot instead of growing to fill the middle one.
     * The tabs themselves are styled by the plain `.dt-tab` rules — a fixed
     * tab is meant to look exactly like an editable one, so that an app
     * built on the toolkit has one kind of tab rather than two dialects of
     * one. Scoped as an extra class rather than a replacement so a fixed
     * bar still inherits every `.dt-tabbar` rule (fonts, wrapping) it has
     * no reason to differ on.
     */
    const val BAR_FIXED = "dt-tabbar-fixed"
    const val STRIP = "dt-tabbar-strip"
    const val TAB = "dt-tab"
    const val TAB_SELECTED = "dt-selected"
    const val TAB_UNLISTED = "dt-tab-unlisted"
    const val TAB_DRAGGING = "dt-dragging"
    const val TAB_DROP_BEFORE = "dt-drop-before"
    const val TAB_DROP_AFTER = "dt-drop-after"
    const val TAB_LABEL = "dt-tab-label"
    const val TAB_LABEL_INPUT = "dt-tab-label-input"
    const val TAB_TRAILING_BADGE = "dt-tab-trailing-badge"

    /** Base class on a toolkit-drawn [TabBadge] element. */
    const val TAB_BADGE = "dt-tab-badge"

    /** Modifier on a [TabBadge.Count] badge (numeric pill). */
    const val TAB_BADGE_COUNT = "dt-tab-badge-count"

    /** Modifier on a [TabBadge.Dot] badge (bare dot, no number). */
    const val TAB_BADGE_DOT = "dt-tab-badge-dot"
    const val TAB_CLOSE = "dt-tab-close"
    const val TAB_ADD = "dt-tab-add"
}

/**
 * Builds the tab-bar element for the given [spec]. Returned element is a
 * sibling-style flex row that the host inserts into the DOM (typically
 * via [TopBarSpec.tabBar]).
 *
 * The bar is stateless — drag and rename state live on individual tab
 * elements via class names and inline editors that are torn down on
 * commit/cancel. Re-rendering with a fresh spec is safe.
 *
 * @param spec the tab-bar specification
 * @return a fresh tab-bar [HTMLElement] ready to be appended to the host
 */
fun renderTabBar(spec: TabBarSpec): HTMLElement {
    // Each tab's dot menu and the far-right overflow menu mount their
    // dropdown list on `document.body` (so the strip's overflow-x scroll
    // can't clip it). Re-rendering builds fresh lists, so purge any from a
    // prior render first — otherwise they accumulate as orphans, one per
    // tab per render.
    val staleLists = document.querySelectorAll(".dt-tabbar-menu-list")
    for (i in 0 until staleLists.length) (staleLists.item(i) as HTMLElement).remove()

    val bar = document.createElement("div") as HTMLElement
    bar.className = TabBarClassNames.BAR +
        (if (spec.isFixed) " ${TabBarClassNames.BAR_FIXED}" else "")

    val strip = document.createElement("div") as HTMLElement
    strip.className = TabBarClassNames.STRIP
    bar.appendChild(strip)

    for (tab in spec.tabs) {
        // Hidden ("unlisted") tabs are normally skipped from the strip (the
        // overflow menu lists them instead). Exception: a hidden tab that is
        // currently active is shown temporarily, so the user can always see
        // the tab they're actually looking at. It drops back out of the strip
        // as soon as another tab is activated.
        if (tab.isHidden && tab.id != spec.activeTabId) continue
        strip.appendChild(buildTabElement(tab, spec))
    }

    // A fixed strip never grows: the `+` is suppressed even if the host
    // asked for it, because [TabBarSpec.isFixed] is the stronger statement
    // of the two and a button that adds a tab to a set the app declared
    // would contradict it.
    if (!spec.isFixed && spec.showAddButton && spec.callbacks.onAdd != null) {
        val addBtn = document.createElement("button") as HTMLButtonElement
        addBtn.type = "button"
        addBtn.className = TabBarClassNames.TAB_ADD
        addBtn.setAttribute("aria-label", "New tab")
        addBtn.title = "New tab"
        addBtn.textContent = "+"
        addBtn.addEventListener("click", { spec.callbacks.onAdd.invoke() })
        strip.appendChild(addBtn)
    }

    // Likewise the far-right `⋮`: since issue #65 it lists only hidden
    // ("Unlisted") tabs, and a fixed strip offers no way to hide one, so
    // the menu could only ever be empty here.
    if (!spec.isFixed && spec.showOverflowMenu) {
        // The `⋮` overflow menu is appended into the strip itself so it sits
        // right after the last tab (part of the tab bar), and lists the
        // hidden ("Unlisted") tabs (click to activate, or un-hide). It
        // self-suppresses when none are hidden. Per-tab actions live in
        // each tab's dot menu (appendTabDotMenu, wired in buildTabElement).
        // Implementation lives in a sibling file so this primitive stays
        // focused on the strip itself.
        appendTabBarOverflowMenu(strip, spec)
    }

    installTabNavigationHotkeys(spec)

    return bar
}

/**
 * Bind [StandardHotkeys.NextTab] / [StandardHotkeys.PreviousTab] against
 * [spec], cycling through the spec's non-hidden tabs and firing
 * [TabBarCallbacks.onSelect] for the new id.
 *
 * Uses [HotkeyRegistry]'s replace-on-register semantics: each call to
 * [renderTabBar] overwrites the prior binding so the action always
 * closes over the latest spec. No explicit unregister is needed when
 * the bar is replaced — the next render rebinds; if the host stops
 * rendering a bar, the binding's closure still works (it just calls
 * onSelect on a stale spec) but does no harm because the host's
 * onSelect handler will be a no-op once it has dropped its tab model.
 *
 * Skipped:
 * - empty tab list (no targets) — early return.
 * - single visible tab — early return; the chord is a no-op.
 *
 * Wrap: pressing Next on the last visible tab activates the first;
 * Previous on the first activates the last.
 *
 * Anchorless start: when [TabBarSpec.activeTabId] is null or refers to
 * a hidden tab, Next picks the first visible tab and Previous picks
 * the last.
 */
private fun installTabNavigationHotkeys(spec: TabBarSpec) {
    // Registered through [HotkeyBindings] (not the raw registry) so the
    // user's custom chords override the defaults; re-registration on
    // every render keeps the handler closing over the latest spec.
    HotkeyBindings.registerAction(
        HotkeyActionSpec(ToolkitHotkeyIds.TAB_NEXT, "Next tab", listOf(StandardHotkeys.NextTab))
    ) { cycleTab(spec, forward = true) }
    HotkeyBindings.registerAction(
        HotkeyActionSpec(ToolkitHotkeyIds.TAB_PREVIOUS, "Previous tab", listOf(StandardHotkeys.PreviousTab))
    ) { cycleTab(spec, forward = false) }
    installTabNumberHotkeys(spec)
}

private fun cycleTab(spec: TabBarSpec, forward: Boolean) {
    val visible = spec.tabs.filterNot { it.isHidden }
    if (visible.size < 2) return
    val activeIdx = visible.indexOfFirst { it.id == spec.activeTabId }
    val nextIdx = when {
        activeIdx < 0 -> if (forward) 0 else visible.lastIndex
        forward -> (activeIdx + 1) % visible.size
        else -> (activeIdx - 1 + visible.size) % visible.size
    }
    val target = visible[nextIdx].id
    if (target == spec.activeTabId) return
    spec.callbacks.onSelect(target)
}

/**
 * Bind the positional "jump to tab N" chords (Cmd/Ctrl+1..9, where 9
 * always selects the last tab — the Safari / Chrome / iTerm convention)
 * against [spec].
 *
 * Like [installTabNavigationHotkeys], this re-registers on every
 * [renderTabBar] so each chord closes over the latest spec
 * ([HotkeyRegistry]'s replace-on-register semantics).
 *
 * Chord per platform:
 * - **Electron:** plain Cmd/Ctrl+`<digit>` ([StandardHotkeys.tabSwitchHotkey])
 *   — the desktop shell owns the chord, so no conflict.
 * - **Browser:** Cmd+Opt/Ctrl+Alt+`<digit>`
 *   ([StandardHotkeys.webTabSwitchHotkey]) — a real browser reserves plain
 *   Cmd/Ctrl+`<digit>` for switching *its own* tabs and a page `keydown`
 *   can't reliably override it, so we add an Alt/Option modifier to get a
 *   browser-safe chord that behaves identically. This gives web users the
 *   same positional tab switching Electron users have.
 *
 * @param spec the current tab-bar spec (its visible tabs and
 *   [TabBarCallbacks.onSelect] are read each time a chord fires).
 * @see resolveTabSwitchIndex
 */
private fun installTabNumberHotkeys(spec: TabBarSpec) {
    val electron = isElectronPlatform()
    for (position in 1..9) {
        val chord = if (electron) {
            StandardHotkeys.tabSwitchHotkey(position)
        } else {
            StandardHotkeys.webTabSwitchHotkey(position)
        }
        HotkeyRegistry.register(chord) {
            switchToTabByPosition(spec, position)
        }
        // Not user-configurable (nine chords behind one conceptual
        // action), but the config dialog must still refuse to hand these
        // chords to another action.
        HotkeyBindings.noteFixedChord(chord, "Switch to tab $position")
    }
}

/**
 * Resolve [digit] against the spec's *visible* (non-hidden) tabs and fire
 * [TabBarCallbacks.onSelect] for the target — the action behind a
 * Cmd/Ctrl+`<digit>` press.
 *
 * Mirrors [cycleTab]: hidden tabs are skipped so positions line up with
 * what the user sees in the strip, and a press resolving to the already
 * active tab is a no-op (avoids a redundant onSelect / server round-trip
 * in source-mode hosts).
 *
 * @param spec the current tab-bar spec.
 * @param digit the pressed number key, `1`..`9`.
 */
private fun switchToTabByPosition(spec: TabBarSpec, digit: Int) {
    val visible = spec.tabs.filterNot { it.isHidden }
    val index = resolveTabSwitchIndex(digit = digit, tabCount = visible.size) ?: return
    val target = visible[index].id
    if (target == spec.activeTabId) return
    spec.callbacks.onSelect(target)
}

/**
 * Pure mapping from a Cmd/Ctrl+`<digit>` shortcut to the tab index it
 * should activate, kept free of any DOM / spec dependency so it can be
 * unit-tested directly.
 *
 * Convention (matches Safari / Chrome / iTerm):
 * - Digits `1`–`8` select the 1st–8th tab by position (zero-based index
 *   `digit - 1`), or nothing if that position doesn't exist.
 * - Digit `9` always selects the **last** tab, regardless of how many
 *   tabs there are.
 *
 * Called by [switchToTabByPosition] against the live count of visible
 * tabs.
 *
 * @param digit the pressed number key, expected in `1..9`.
 * @param tabCount the number of (visible) tabs currently in the strip.
 * @return the zero-based tab index to activate, or `null` when the
 *   shortcut should be a no-op (no tabs, out-of-range position, or a
 *   digit outside `1..9`).
 */
internal fun resolveTabSwitchIndex(digit: Int, tabCount: Int): Int? {
    if (tabCount <= 0) return null
    return when (digit) {
        9 -> tabCount - 1
        in 1..8 -> (digit - 1).takeIf { it < tabCount }
        else -> null
    }
}

/**
 * Builds one `.dt-tab` element with click / close / drag / rename wiring.
 *
 * Public so hosts that need bespoke tab-strip layouts (e.g. termtastic's
 * sliding active indicator + far-right overflow menu) can call it
 * per-tab instead of using the all-in-one [renderTabBar]. The returned
 * element carries `data-tab-id` and the standard `.dt-tab*` class names;
 * the host may add its own classes / listeners on top.
 *
 * @param tab  the tab to render
 * @param spec the parent spec, used to resolve the active id and callbacks
 * @return the tab element, ready to append to the strip
 */
fun buildTabElement(tab: TabSpec, spec: TabBarSpec): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = TabBarClassNames.TAB +
        (if (tab.id == spec.activeTabId) " ${TabBarClassNames.TAB_SELECTED}" else "") +
        // A hidden tab only reaches the strip while it's the active tab
        // (shown temporarily so its dot menu is reachable). Mark it so CSS
        // can hint — via a dashed outline — that it's an unlisted tab.
        (if (tab.isHidden) " ${TabBarClassNames.TAB_UNLISTED}" else "")
    el.setAttribute("data-tab-id", tab.id)
    el.setAttribute("role", "tab")
    el.setAttribute("tabindex", "0")
    if (tab.id == spec.activeTabId) el.setAttribute("aria-selected", "true")

    val label = document.createElement("span") as HTMLElement
    label.className = TabBarClassNames.TAB_LABEL
    label.textContent = tab.label
    el.appendChild(label)

    // One trailing slot, two ways to fill it. A host-built element is the
    // more specific instruction so it wins outright; the declarative badge
    // is only consulted when there is no element to defer to.
    val badgeContent = tab.trailingBadge ?: tab.badge?.let { buildTabBadgeElement(it) }
    badgeContent?.let {
        val slot = document.createElement("span") as HTMLElement
        slot.className = TabBarClassNames.TAB_TRAILING_BADGE
        slot.appendChild(it)
        el.appendChild(slot)
    }

    el.addEventListener("click", { ev ->
        ev.stopPropagation()
        if (tab.id != spec.activeTabId) spec.callbacks.onSelect(tab.id)
    })

    // Everything below this line is a way to *edit* the tab set, so a
    // fixed strip stops here: selection is wired, nothing else is. The
    // guards are repeated per affordance rather than hoisted into one
    // early return because the pane-drop target below is not an edit of
    // the tab set — it moves a pane between tabs the app already declared
    // — and should keep working on a fixed strip.
    if (!spec.isFixed && tab.isClosable && spec.callbacks.onClose != null) {
        val closeBtn = document.createElement("button") as HTMLButtonElement
        closeBtn.type = "button"
        closeBtn.className = TabBarClassNames.TAB_CLOSE
        closeBtn.setAttribute("aria-label", "Close tab")
        closeBtn.title = "Close"
        closeBtn.textContent = "×"
        closeBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            requestTabClose(tab, spec.callbacks)
        })
        // Don't let mousedown on the close button initiate a drag of the
        // parent tab; otherwise click is swallowed by the dragstart path.
        closeBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        el.appendChild(closeBtn)
    }

    if (!spec.isFixed && tab.isDraggable && spec.callbacks.onReorder != null) {
        wireTabDragAndDrop(el, tab.id, spec)
    }

    if (spec.callbacks.onPaneDroppedOnTab != null) {
        wireTabAsPaneDropTarget(el, tab.id, spec)
    }

    if (!spec.isFixed && tab.isRenamable && spec.callbacks.onRename != null) {
        wireInlineRename(label, tab, spec)
    }

    // Per-tab `⋮` dot menu at the tab's right corner, holding the actions
    // scoped to this tab (Rename / Close / Hide in tab bar / Hide in side
    // bar). Self-gates: renders nothing when no relevant callback is wired.
    // Issue #65 moved these out of the far-right `⋮` overflow menu so the
    // overflow only carries cross-tab concerns (the hidden-tabs list).
    // Every row it can offer is an edit, so a fixed strip skips it whole.
    if (!spec.isFixed) appendTabDotMenu(el, tab, spec)

    return el
}

/**
 * Attaches HTML5 drag-and-drop listeners that let one tab be reordered
 * relative to another by dragging it horizontally. Drop-position is
 * decided by which half of the target tab the cursor is over.
 *
 * Uses [DT_TAB_DRAG_MIME] so the bar ignores foreign drags (file drops,
 * text selections from elsewhere on the page).
 *
 * @param el    the tab DOM element to wire
 * @param tabId the tab's id, used as the drag payload
 * @param spec  parent spec, used to fire [TabBarCallbacks.onReorder]
 */
private fun wireTabDragAndDrop(el: HTMLElement, tabId: String, spec: TabBarSpec) {
    el.setAttribute("draggable", "true")

    el.addEventListener("dragstart", { ev ->
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData(DT_TAB_DRAG_MIME, tabId)
        // text/plain fallback so platforms that strip custom MIME types in
        // some scenarios still see *something*. Receivers always check the
        // custom MIME first.
        dt.setData("text/plain", tabId)
        el.classList.add(TabBarClassNames.TAB_DRAGGING)
    })

    el.addEventListener("dragend", {
        el.classList.remove(TabBarClassNames.TAB_DRAGGING)
        clearDropIndicators(el)
    })

    el.addEventListener("dragover", { ev ->
        if (!isTabDrag(ev.asDynamic())) return@addEventListener
        ev.preventDefault()
        ev.asDynamic().dataTransfer.dropEffect = "move"
        val before = isCursorBeforeMidpoint(el, ev.asDynamic())
        if (before) {
            el.classList.add(TabBarClassNames.TAB_DROP_BEFORE)
            el.classList.remove(TabBarClassNames.TAB_DROP_AFTER)
        } else {
            el.classList.add(TabBarClassNames.TAB_DROP_AFTER)
            el.classList.remove(TabBarClassNames.TAB_DROP_BEFORE)
        }
    })

    el.addEventListener("dragleave", {
        el.classList.remove(TabBarClassNames.TAB_DROP_BEFORE, TabBarClassNames.TAB_DROP_AFTER)
    })

    el.addEventListener("drop", { ev ->
        if (!isTabDrag(ev.asDynamic())) return@addEventListener
        ev.preventDefault()
        ev.stopPropagation()
        el.classList.remove(TabBarClassNames.TAB_DROP_BEFORE, TabBarClassNames.TAB_DROP_AFTER)
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        val sourceId = (dt.getData(DT_TAB_DRAG_MIME) as? String)
            ?: (dt.getData("text/plain") as? String)
        if (sourceId.isNullOrEmpty() || sourceId == tabId) return@addEventListener
        val before = isCursorBeforeMidpoint(el, ev.asDynamic())
        spec.callbacks.onReorder?.invoke(sourceId, tabId, before)
    })
}

/**
 * Wires HTML5 drop listeners on a tab element so a pane dragged from a
 * pane header (carrying `DT_PANE_DRAG_MIME`) can be dropped onto the
 * tab. On valid drop fires [TabBarCallbacks.onPaneDroppedOnTab] with the
 * source pane id and this tab's id; the host is responsible for moving
 * the pane into the destination tab and re-rendering.
 *
 * Does NOT switch the active tab — that's the host's call.
 *
 * @param el    the `.dt-tab` element to wire.
 * @param tabId this tab's id, sent to the callback as the destination.
 * @param spec  parent spec; supplies the pane-drop callback.
 */
private fun wireTabAsPaneDropTarget(el: HTMLElement, tabId: String, spec: TabBarSpec) {
    el.addEventListener("dragover", { ev ->
        if (!isPaneDragOver(ev.asDynamic())) return@addEventListener
        ev.preventDefault()
        ev.asDynamic().dataTransfer.dropEffect = "move"
        el.classList.add(TabBarClassNames.TAB_DROP_BEFORE)
    })
    el.addEventListener("dragleave", { ev ->
        // Only clear the indicator when the pointer has actually left the
        // tab itself — child boundary crossings would otherwise flicker.
        val related = ev.asDynamic().relatedTarget as? HTMLElement
        if (related != null && el.asDynamic().contains(related) as Boolean) return@addEventListener
        el.classList.remove(TabBarClassNames.TAB_DROP_BEFORE)
    })
    el.addEventListener("drop", { ev ->
        if (!isPaneDragOver(ev.asDynamic())) return@addEventListener
        ev.preventDefault()
        ev.stopPropagation()
        el.classList.remove(TabBarClassNames.TAB_DROP_BEFORE)
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        val sourceId = (dt.getData("application/x-lunula-pane") as? String)
        if (sourceId.isNullOrEmpty()) return@addEventListener
        spec.callbacks.onPaneDroppedOnTab?.invoke(sourceId, tabId)
    })
}

/**
 * Mirror of `isPaneDrag` (in `layout/PaneHeader.kt`) so the tab-bar can
 * filter pane drags without reaching across the package layering. Tabs
 * also accept tab-reorder drags via `wireTabDragAndDrop`; the two filters
 * keep their respective drop logic separate.
 */
private fun isPaneDragOver(dynamicEv: dynamic): Boolean {
    val types = dynamicEv.dataTransfer?.types ?: return false
    val len = (types.length as? Number)?.toInt() ?: return false
    for (i in 0 until len) if (types[i] == "application/x-lunula-pane") return true
    return false
}

/**
 * Returns `true` when the drag event's `dataTransfer.types` includes the
 * toolkit's tab-drag MIME type. Used to filter out foreign drags so
 * dragging text or files into the tab bar doesn't trigger reorder
 * indicators.
 */
private fun isTabDrag(dynamicEv: dynamic): Boolean {
    val types = dynamicEv.dataTransfer?.types ?: return false
    val len = (types.length as? Number)?.toInt() ?: return false
    for (i in 0 until len) if (types[i] == DT_TAB_DRAG_MIME) return true
    return false
}

/**
 * Returns `true` if the drag event's clientX is in the left half of [el].
 * Used to decide whether a drop should land before or after the target.
 */
private fun isCursorBeforeMidpoint(el: HTMLElement, dynamicEv: dynamic): Boolean {
    val rect = el.asDynamic().getBoundingClientRect()
    val midpoint = (rect.left as Double) + (rect.width as Double) / 2.0
    return (dynamicEv.clientX as Double) < midpoint
}

/**
 * Removes drop-target indicator classes from every sibling of [el] in the
 * tab strip. Called on `dragend` so the bar doesn't keep stale insertion
 * markers after a drop completes (browser fires `dragleave` for the
 * source-side tabs but not always for tabs the cursor moved across last).
 */
private fun clearDropIndicators(el: HTMLElement) {
    val strip = el.parentElement ?: return
    val tabs = strip.querySelectorAll(".${TabBarClassNames.TAB}")
    for (i in 0 until tabs.length) {
        val sibling = tabs.item(i) as HTMLElement
        sibling.classList.remove(
            TabBarClassNames.TAB_DROP_BEFORE,
            TabBarClassNames.TAB_DROP_AFTER,
        )
    }
}

/**
 * Wires double-click on the label to swap it for an inline `<input>` and
 * commit / cancel the new value via [TabBarCallbacks.onRename].
 *
 * Commit triggers: Enter or blur (with non-empty, changed text).
 * Cancel triggers: Escape, or blur with empty / unchanged text.
 *
 * The toolkit doesn't mutate the host's tab list; it only fires the
 * callback. The host is expected to update its model and re-render the
 * bar, which discards this transient input.
 *
 * @param label the `.dt-tab-label` span to rebind on double-click
 * @param tab   tab metadata, used to seed the input and identify the tab
 * @param spec  parent spec, used to fire [TabBarCallbacks.onRename]
 */
private fun wireInlineRename(label: HTMLElement, tab: TabSpec, spec: TabBarSpec) {
    label.addEventListener("dblclick", { ev ->
        ev.stopPropagation()
        triggerInlineRename(label, tab, spec)
    })
}

/**
 * Swap [label] for an editable input, seeded with [tab]'s current label.
 * Commit (Enter / blur) fires [TabBarCallbacks.onRename]; Escape cancels
 * and restores the original label. Extracted from [wireInlineRename] so
 * external callers (overflow menu's "Rename" row, command palette, etc.)
 * can start the rename gesture without simulating a double-click.
 *
 * @param label the `.dt-tab-label` span to swap
 * @param tab   tab metadata for seeding the input + identifying the tab
 *   in the rename callback
 * @param spec  parent spec, used to fire [TabBarCallbacks.onRename]
 */
internal fun triggerInlineRename(label: HTMLElement, tab: TabSpec, spec: TabBarSpec) {
    val parent = label.parentElement ?: return
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = TabBarClassNames.TAB_LABEL_INPUT
    input.value = tab.label
    // Suppress the parent .dt-tab's drag handler while editing so a
    // mouse-down inside the input doesn't initiate a drag.
    input.setAttribute("draggable", "false")
    input.addEventListener("mousedown", { e -> e.stopPropagation() })
    input.addEventListener("click", { e -> e.stopPropagation() })

    var committed = false
    fun commit(value: String) {
        if (committed) return
        committed = true
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && trimmed != tab.label) {
            spec.callbacks.onRename?.invoke(tab.id, trimmed)
        } else {
            // Restore the label in place since the host won't
            // re-render for a no-op rename.
            input.replaceWith(label)
        }
    }

    input.addEventListener("keydown", { e ->
        val ke = e as KeyboardEvent
        when (ke.key) {
            "Enter" -> { ke.preventDefault(); commit(input.value) }
            "Escape" -> { ke.preventDefault(); committed = true; input.replaceWith(label) }
        }
    })
    input.addEventListener("blur", { commit(input.value) })

    parent.replaceChild(input, label)
    input.focus()
    input.select()
}

/**
 * Programmatically start the inline-rename gesture on the tab with [tabId]
 * inside the given tab-bar [bar]. No-op if the tab isn't present, isn't
 * renamable, or has no `onRename` callback wired.
 *
 * Used by [TabBarOverflowMenu]'s "Rename" row but exposed publicly so apps
 * can also surface rename via keyboard shortcuts, command palettes, etc.
 *
 * @param bar    the element returned by [renderTabBar]
 * @param tabId  the tab to rename
 * @param spec   the spec used to render [bar] (needed for the active
 *   callback + label seed)
 */
fun startTabInlineRename(bar: HTMLElement, tabId: String, spec: TabBarSpec) {
    val tab = spec.tabs.firstOrNull { it.id == tabId } ?: return
    if (!tab.isRenamable || spec.callbacks.onRename == null) return
    val tabEl = bar.querySelector(".${TabBarClassNames.TAB}[data-tab-id=\"$tabId\"]") as? HTMLElement
        ?: return
    val label = tabEl.querySelector(".${TabBarClassNames.TAB_LABEL}") as? HTMLElement ?: return
    triggerInlineRename(label, tab, spec)
}

/**
 * Routes a tab-close gesture through [TabBarCallbacks.confirmTabClose].
 * When the flag is set, opens a [showConfirmDialog] using the tab's label
 * before invoking [TabBarCallbacks.onClose]; otherwise calls onClose
 * directly. Used by both the per-tab × button and the overflow menu's
 * "Close" row so confirmation is consistent across entry points.
 *
 * The dialog uses the standard accent-styled confirm button (not the
 * `.dt-destructive` variant): closing a tab is a routine action, not a
 * dangerous one, so it matches the pane-close / quit / theme-delete
 * confirmations rather than reading as a red warning (termtastic issue #90).
 *
 * @param tab        the tab to close
 * @param callbacks  the active [TabBarCallbacks]; must have non-null onClose
 */
internal fun requestTabClose(tab: TabSpec, callbacks: TabBarCallbacks) {
    val onClose = callbacks.onClose ?: return
    if (!callbacks.confirmTabClose) {
        onClose(tab.id)
        return
    }
    val label = tab.label.takeIf { it.isNotBlank() } ?: "this tab"
    showConfirmDialog(
        title = "Close tab",
        message = "Are you sure you want to close <strong>" +
            escapeHtmlForConfirm(label) + "</strong>?",
        confirmLabel = "Close",
        // Deliberately non-destructive: tab close isn't considered a
        // dangerous action (issue #90 follow-up), so the confirm button
        // keeps the standard accent styling.
        destructive = false,
        messageIsHtml = true,
        onConfirm = { onClose(tab.id) },
    )
}
