/* AppShellMount.kt
 * One-call assembler that produces the standard lunula shell
 * from an [AppShellSpec]: app frame + topbar + tab bar + left sidebar
 * (with theme toggle + extra sections) + bottombar + LayoutRenderer
 * mount + persister-backed tab/layout state. Apps call
 * `mountAppShell(spec)` once at boot; everything chrome- and
 * persistence-related is driven from spec defaults. */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.MediaQueryList
import org.w3c.dom.events.Event
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.web.applyTheme
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.injectLunulaStyles
import se.soderbjorn.lunula.web.isDarkActive
import se.soderbjorn.lunula.web.layout.DEFAULT_LAYOUT_GRID
import se.soderbjorn.lunula.web.layout.FloatingPaneSpec
import se.soderbjorn.lunula.web.layout.LayoutBox
import se.soderbjorn.lunula.web.layout.LayoutController
import se.soderbjorn.lunula.web.layout.LayoutDropdown
import se.soderbjorn.lunula.web.layout.LayoutPreset
import se.soderbjorn.lunula.web.layout.LayoutRenderer
import se.soderbjorn.lunula.web.layout.PaneCallbacks
import se.soderbjorn.lunula.web.layout.PaneHeaderSpec
import se.soderbjorn.lunula.web.layout.triggerPaneRename
import se.soderbjorn.lunula.web.layout.PaneLayout
import se.soderbjorn.lunula.web.applyMonoFontFamily
import se.soderbjorn.lunula.web.applyMonoFontSizePx
import se.soderbjorn.lunula.web.applyProportionalFontFamily
import se.soderbjorn.lunula.web.applyProportionalFontSizePx
import se.soderbjorn.lunula.web.applySidebarFontFamily
import se.soderbjorn.lunula.web.applySidebarFontSizePx
import se.soderbjorn.lunula.web.applyTabbarFontFamily
import se.soderbjorn.lunula.web.applyTabbarFontSizePx
import se.soderbjorn.lunula.web.applyPaneHeaderFontFamily
import se.soderbjorn.lunula.web.applyPaneHeaderFontSizePx
import se.soderbjorn.lunula.web.setDtCustomTitleBarBodyClass
import se.soderbjorn.lunula.web.settings.AppSettingsSidebarSpec
import se.soderbjorn.lunula.web.settings.HotkeysSidebarSpec
import se.soderbjorn.lunula.web.settings.NotificationsSidebarSpec
import se.soderbjorn.lunula.web.settings.SettingsSidebarSpec
import se.soderbjorn.lunula.web.settings.buildAppSettingsSidebar
import se.soderbjorn.lunula.web.settings.buildHotkeysSidebar
import se.soderbjorn.lunula.web.settings.buildNotificationsSidebar
import se.soderbjorn.lunula.web.settings.buildSettingsSidebar
import se.soderbjorn.lunula.web.settings.closeAppSettingsSidebar
import se.soderbjorn.lunula.web.settings.closeSettingsSidebar
import se.soderbjorn.lunula.web.settings.isAppSettingsSidebarOpen
import se.soderbjorn.lunula.web.settings.isHotkeysSidebarOpen
import se.soderbjorn.lunula.web.settings.isNotificationsSidebarOpen
import se.soderbjorn.lunula.web.settings.isSettingsSidebarOpen
import se.soderbjorn.lunula.web.settings.toggleAppSettingsSidebar
import se.soderbjorn.lunula.web.settings.toggleHotkeysSidebar
import se.soderbjorn.lunula.web.settings.toggleNotificationsSidebar
import se.soderbjorn.lunula.web.settings.toggleSettingsSidebar
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerState
import se.soderbjorn.lunula.web.themeeditor.applySnapshotV2
import se.soderbjorn.lunula.web.themeeditor.toSnapshotV2
import se.soderbjorn.lunula.web.themeeditor.buildThemeManagerSidebar
import se.soderbjorn.lunula.web.themeeditor.closeThemeManager
import se.soderbjorn.lunula.web.themeeditor.isThemeManagerSidebarOpen
import se.soderbjorn.lunula.web.themeeditor.refreshThemeManager
import se.soderbjorn.lunula.web.themeeditor.toggleThemeManagerSidebar

/**
 * Kotlin/JS external declaration for the browser's `ResizeObserver` API.
 *
 * Used by [AppShellMount.attach] to watch the main content slot for
 * size changes (window resize, sidebar open/close/drag) and emit a single
 * debounced `onGeometryChanged` after the size settles — so hosts can run
 * the same per-pane reformat path they already run after a pane drag-resize.
 *
 * Declared `private` to the file because no other toolkit module needs it;
 * if a second consumer appears it can be lifted to a shared module.
 */
@JsName("ResizeObserver")
private external class ResizeObserver(callback: (dynamic, dynamic) -> Unit) {
    fun observe(target: dynamic): Unit
    fun disconnect(): Unit
}

/**
 * On-disk shape for the assembler's tab + per-tab pane *identity* state.
 * Persisted under [PersistKeys.LAYOUT] in local mode (no [TabSource]).
 * Apps with their own tab source own identity persistence themselves.
 *
 * Pane *geometry* (position, size, z-order, maximized/minimized state)
 * lives separately under [PersistKeys.LAYOUT_STATE] as
 * [PersistedLayoutState], shared by both local and source mode.
 *
 * @property tabs list of tab ids in display order.
 * @property tabLabels parallel map of tab id → user-facing label.
 * @property activeTabId the currently active tab.
 * @property panesByTab per-tab list of pane id entries (one
 *   floating pane per tab in the default seed; apps can spawn more).
 */
internal data class PersistedShellLayout(
    val tabs: List<String> = emptyList(),
    val tabLabels: Map<String, String> = emptyMap(),
    val activeTabId: String? = null,
    val panesByTab: Map<String, List<PersistedPane>> = emptyMap(),
    /** Per-tab `isHidden` flag (skip tab in tab strip). */
    val tabsHidden: Set<String> = emptySet(),
    /** Per-tab `isHiddenFromSidebar` flag (skip tab in sidebar tree). */
    val tabsHiddenFromSidebar: Set<String> = emptySet(),
)

/**
 * Per-pane identity in [PersistedShellLayout.panesByTab]. Identity-only:
 * geometry has moved to [PersistedPaneGeometry] under
 * [PersistKeys.LAYOUT_STATE].
 *
 * @property id stable pane identifier.
 * @property title optional user-set per-pane title; falls back to
 *   [AppShellSpec.paneLabel] when null/blank.
 */
internal data class PersistedPane(
    val id: String,
    val title: String? = null,
)

/**
 * Toolkit-owned layout state shared across local + source mode. Persisted
 * under [PersistKeys.LAYOUT_STATE] regardless of which mode the shell is
 * running in.
 *
 * @property presetByTab per-tab active [LayoutPreset] (key form). Tabs
 *   without an entry start in [LayoutPreset.Auto] (see [controllerFor]) —
 *   brand-new tabs tile automatically until the user picks another preset.
 * @property paneOrderByTab per-tab pane importance order, head = primary
 *   slot when a preset is applied. Mutated by focus / create / remove
 *   events fed to [LayoutController].
 * @property geometryByTab per-tab map from pane id to its rendered
 *   geometry. Updated by drag/resize/maximize and by preset apply.
 */
internal data class PersistedLayoutState(
    val presetByTab: Map<String, String> = emptyMap(),
    val paneOrderByTab: Map<String, List<String>> = emptyMap(),
    val geometryByTab: Map<String, Map<String, PersistedPaneGeometry>> = emptyMap(),
)

internal data class PersistedPaneGeometry(
    val xPct: Double,
    val yPct: Double,
    val widthPct: Double,
    val heightPct: Double,
    val zIndex: Int = 1,
    val isMaximized: Boolean = false,
    val isMinimized: Boolean = false,
)

/**
 * How many of this client's own recent layout writes are remembered so their
 * server echoes can be recognised and ignored. A single geometry gesture can
 * fan out into a handful of sequential persists (the action itself, then the
 * post-render refit, then an Auto re-tile), so the window must be a little
 * larger than one. 16 comfortably covers the longest such burst while staying
 * tiny in memory.
 */
private const val RECENT_LAYOUT_WRITES = 16

/**
 * Test seam invoked at the end of every [ShellState.rerender]. Lets a test
 * observe each individual render pass of a multi-pass update (e.g. a world
 * switch) and detect transient chrome churn. Never assigned in production.
 */
internal var onAppShellRenderedForTest: (() -> Unit)? = null

/**
 * Encodes [layout] as a JSON string via `JSON.stringify` so lunula-web
 * doesn't take a hard dep on the kotlinx.serialization plugin.
 */
internal fun encodeShellLayoutJson(layout: PersistedShellLayout): String {
    val obj = js("({})")
    obj.tabs = layout.tabs.toTypedArray()
    val labels = js("({})")
    for ((k, v) in layout.tabLabels) labels[k] = v
    obj.tabLabels = labels
    obj.activeTabId = layout.activeTabId
    val panes = js("({})")
    for ((tabId, list) in layout.panesByTab) {
        panes[tabId] = list.map { p ->
            val o = js("({})")
            o.id = p.id
            o.title = p.title
            o
        }.toTypedArray()
    }
    obj.panesByTab = panes
    obj.tabsHidden = layout.tabsHidden.toTypedArray()
    obj.tabsHiddenFromSidebar = layout.tabsHiddenFromSidebar.toTypedArray()
    return js("JSON.stringify(obj)") as String
}

/** Encodes [state] for [PersistKeys.LAYOUT_STATE]. */
internal fun encodeLayoutStateJson(state: PersistedLayoutState): String {
    val obj = js("({})")
    val presets = js("({})")
    for ((k, v) in state.presetByTab) presets[k] = v
    obj.presetByTab = presets
    val orders = js("({})")
    for ((k, v) in state.paneOrderByTab) orders[k] = v.toTypedArray()
    obj.paneOrderByTab = orders
    val geom = js("({})")
    for ((tabId, paneMap) in state.geometryByTab) {
        val tabObj = js("({})")
        for ((paneId, g) in paneMap) {
            val o = js("({})")
            o.xPct = g.xPct
            o.yPct = g.yPct
            o.widthPct = g.widthPct
            o.heightPct = g.heightPct
            o.zIndex = g.zIndex
            o.isMaximized = g.isMaximized
            o.isMinimized = g.isMinimized
            tabObj[paneId] = o
        }
        geom[tabId] = tabObj
    }
    obj.geometryByTab = geom
    return js("JSON.stringify(obj)") as String
}

/** Parses what [encodeLayoutStateJson] produced. Returns an empty state on malformed input. */
internal fun decodeLayoutStateJson(json: String): PersistedLayoutState {
    return try {
        val parsed = js("JSON.parse(json)")
        val presetByTab = mutableMapOf<String, String>()
        val presetRaw = parsed.presetByTab
        if (presetRaw != null) {
            val keys = js("Object.keys(presetRaw)") as Array<String>
            for (k in keys) presetByTab[k] = presetRaw[k] as String
        }
        val orderByTab = mutableMapOf<String, List<String>>()
        val orderRaw = parsed.paneOrderByTab
        if (orderRaw != null) {
            val keys = js("Object.keys(orderRaw)") as Array<String>
            for (k in keys) orderByTab[k] = (orderRaw[k] as Array<String>).toList()
        }
        val geomByTab = mutableMapOf<String, Map<String, PersistedPaneGeometry>>()
        val geomRaw = parsed.geometryByTab
        if (geomRaw != null) {
            val tabKeys = js("Object.keys(geomRaw)") as Array<String>
            for (tabId in tabKeys) {
                val paneObj = geomRaw[tabId]
                val paneKeys = js("Object.keys(paneObj)") as Array<String>
                val paneMap = mutableMapOf<String, PersistedPaneGeometry>()
                for (paneId in paneKeys) {
                    val g = paneObj[paneId]
                    paneMap[paneId] = PersistedPaneGeometry(
                        xPct = (g.xPct as? Number)?.toDouble() ?: 0.0,
                        yPct = (g.yPct as? Number)?.toDouble() ?: 0.0,
                        widthPct = (g.widthPct as? Number)?.toDouble() ?: 0.5,
                        heightPct = (g.heightPct as? Number)?.toDouble() ?: 0.5,
                        zIndex = (g.zIndex as? Number)?.toInt() ?: 1,
                        isMaximized = (g.isMaximized as? Boolean) ?: false,
                        isMinimized = (g.isMinimized as? Boolean) ?: false,
                    )
                }
                geomByTab[tabId] = paneMap
            }
        }
        PersistedLayoutState(presetByTab, orderByTab, geomByTab)
    } catch (_: Throwable) {
        PersistedLayoutState()
    }
}

/**
 * Encodes a set of collapsed-section ids as a JSON array string. Used to
 * persist the user's open/closed sidebar-section state under
 * [PersistKeys.SIDEBAR_STATE]. The set is intentionally tiny so a
 * round-trip JSON.stringify avoids pulling in `kotlinx.serialization`.
 */
internal fun encodeCollapsedSectionsJson(ids: Set<String>): String =
    // `toTypedArray()` (not raw `js("ids.toArray()")`) — the runtime
    // object behind an empty Kotlin set (`EmptySet`) carries no
    // `toArray` method, so the raw-JS form threw exactly when the user
    // expanded their last collapsed section, aborting the toggle before
    // its rerender and leaving the section stuck collapsed.
    JSON.stringify(ids.toTypedArray())

/**
 * Parses what [encodeCollapsedSectionsJson] produced. Returns an empty
 * set on malformed or empty input — missing data should never surface
 * as "every section collapsed" (which would hide the whole tree).
 */
internal fun decodeCollapsedSectionsJson(json: String): Set<String> = try {
    val parsed = js("JSON.parse(json)")
    val arr = parsed as? Array<String>
    arr?.toSet() ?: emptySet()
} catch (_: Throwable) {
    emptySet()
}

/** Parses what [encodeShellLayoutJson] produced. Returns null on malformed input. */
internal fun decodeShellLayoutJson(json: String): PersistedShellLayout? {
    return try {
        val parsed = js("JSON.parse(json)")
        val tabs = (parsed.tabs as? Array<String>)?.toList() ?: emptyList()
        val labelsRaw = parsed.tabLabels
        val tabLabels = mutableMapOf<String, String>()
        if (labelsRaw != null) {
            val keys = js("Object.keys(labelsRaw)") as Array<String>
            for (k in keys) tabLabels[k] = labelsRaw[k] as String
        }
        val activeTabId = parsed.activeTabId as? String
        val panesByTab = mutableMapOf<String, List<PersistedPane>>()
        val pbtRaw = parsed.panesByTab
        if (pbtRaw != null) {
            val keys = js("Object.keys(pbtRaw)") as Array<String>
            for (k in keys) {
                val arr = pbtRaw[k] as Array<dynamic>
                panesByTab[k] = arr.map { p ->
                    PersistedPane(
                        id = p.id as String,
                        title = p.title as? String,
                    )
                }
            }
        }
        val hidden = (parsed.tabsHidden as? Array<String>)?.toSet().orEmpty()
        val hiddenFromSidebar = (parsed.tabsHiddenFromSidebar as? Array<String>)?.toSet().orEmpty()
        PersistedShellLayout(
            tabs = tabs,
            tabLabels = tabLabels,
            activeTabId = activeTabId,
            panesByTab = panesByTab,
            tabsHidden = hidden,
            tabsHiddenFromSidebar = hiddenFromSidebar,
        )
    } catch (_: Throwable) {
        null
    }
}

/**
 * Mounts the standard lunula app shell into [spec]'s
 * `rootContainer`.
 *
 * Composition (all toolkit-supplied unless overridden via [spec]):
 * - injects `lunula.css` if not already present
 * - reads [PersistKeys.THEME_V2_SELECTION] / [PersistKeys.THEME_V2_CUSTOM] /
 *   [PersistKeys.LAYOUT] from the persister (or seeds defaults)
 * - applies the resolved theme via [applyTheme]
 * - builds the [renderAppFrame] with top bar, body (left sidebar +
 *   pane main), and an optional bottom bar
 * - constructs the standard top bar with the toolkit's tab-bar
 *   (new/close/rename/reorder) + a trailing actions row holding the
 *   sidebar toggle, theme toggle, plus `spec.extraTopbarTrailing`
 * - builds a left sidebar containing the host-supplied
 *   `spec.sidebarSections` (rendered via [renderSidebarSection])
 * - mounts a [LayoutRenderer] in the main slot and renders the active
 *   tab's pane list, delegating each pane's body to [spec.paneContent]
 *
 * State changes (tab open/close/rename, theme picks, layout edits) are
 * written back through the persister with no app involvement.
 *
 * @param spec configuration describing what the app contributes on top of the toolkit defaults.
 * @param scope coroutine scope for persister IO; defaults to [GlobalScope].
 * @return [AppShellHandle] for post-mount control.
 */
fun mountAppShell(
    spec: AppShellSpec,
    scope: CoroutineScope = GlobalScope,
): AppShellHandle {
    injectLunulaStyles()
    document.title = spec.title

    // Theme manager state — constructed up-front so the right sidebar
    // and the trailing "themes" button can both reference it. Apps can
    // ignore the theme manager (no spec field needed); the toolkit
    // mounts it for free as part of the canonical chrome.
    val themeState = DefaultThemeManagerState()
    // `onChange` fires each time the user picks a theme / appearance in the
    // manager. We capture a forward reference to the ShellState so the closure
    // can resolve the new snapshot, apply the resulting palette to the DOM,
    // persist, and re-render. Late binding because ShellState needs themeHost
    // in its constructor.
    var stateRef: ShellState? = null
    val themeHost: DefaultThemeManagerHost = object : DefaultThemeManagerHost(
        state = themeState,
        onChange = { stateRef?.onThemeManagerChanged() },
    ) {}

    val state = ShellState(spec, scope, themeHost, themeState)
    stateRef = state

    // Build the chrome elements that survive across re-renders. Each is
    // a stable parent slot; their interiors are rebuilt on every state
    // change via [ShellState.rerender].
    val topBarSlot = document.createElement("div") as HTMLElement
    topBarSlot.style.display = "contents"

    val leftSidebarSlot = document.createElement("div") as HTMLElement
    leftSidebarSlot.style.display = "contents"

    val rightSidebarSlot = document.createElement("div") as HTMLElement
    rightSidebarSlot.style.display = "contents"

    val main = document.createElement("div") as HTMLElement
    main.style.flex = "1 1 auto"
    main.style.position = "relative"

    val bottomBarSlot = document.createElement("div") as HTMLElement
    bottomBarSlot.style.display = "contents"

    val frame = renderAppFrame(
        AppFrameSpec(
            topBar = topBarSlot,
            leftSidebar = leftSidebarSlot,
            main = main,
            rightSidebar = rightSidebarSlot,
            bottomBar = bottomBarSlot,
        )
    )
    // Pin the host element to viewport height so the AppFrame's
    // `height: 100%` chain has something to resolve against. The
    // toolkit stylesheet sets `html, body { height: 100% }` for the
    // outer chain; this is the last link, applied inline so apps
    // that ship a plain `<body><div id="app"></div></body>` shell
    // don't have to remember to add `height: 100vh` themselves.
    // Apps that need a different sizing model can override this
    // after [mountAppShell] returns.
    spec.rootContainer.style.height = "100vh"
    spec.rootContainer.style.margin = "0"
    spec.rootContainer.innerHTML = ""
    spec.rootContainer.appendChild(frame)

    state.attach(frame, topBarSlot, leftSidebarSlot, rightSidebarSlot, main, bottomBarSlot)
    state.watchSystemAppearance()

    val initJob: Job = scope.launch {
        // 1. Restore the v2 theme snapshot from its two persisted parts:
        //    THEME_V2_SELECTION (per-app: slots + appearance) and
        //    THEME_V2_CUSTOM (shared: custom themes). Hydrate the toolkit's
        //    themeState BEFORE applyTheme so `applyHostFontVars()` paints the
        //    persisted fonts onto `--dt-font-*` on the first frame.
        val selectionRaw = spec.persister.read(PersistKeys.THEME_V2_SELECTION)
        val customRaw = spec.persister.read(PersistKeys.THEME_V2_CUSTOM)
        val snapshot = ThemeSnapshotV2.fromStrings(selectionRaw, customRaw)
        themeState.applySnapshotV2(snapshot)

        // Seed the custom-titlebar flag from the Electron bridge when present
        // so the renderer's state matches the BrowserWindow's actual
        // `titleBarStyle` (the v2 snapshot doesn't carry titlebar prefs).
        val bridgeCustomTitleBar: dynamic = js(
            """
            (function() {
                try {
                    if (typeof globalThis !== 'undefined'
                        && globalThis.darknessApi
                        && typeof globalThis.darknessApi.customTitleBar === 'boolean') {
                        return globalThis.darknessApi.customTitleBar;
                    }
                } catch (e) { }
                return null;
            })()
            """
        )
        if (bridgeCustomTitleBar != null) {
            themeState.useCustomTitleBar = bridgeCustomTitleBar as Boolean
        }

        state.applyThemeSnapshot(snapshot)

        // 1b. Restore collapsed-sidebar-section ids. Stored as a JSON
        //     array of strings (or empty / missing — defaults to all open).
        val sidebarRaw = spec.persister.read(PersistKeys.SIDEBAR_STATE)
        if (sidebarRaw != null) state.applyCollapsedSections(decodeCollapsedSectionsJson(sidebarRaw))

        // 1b'. Restore the user's custom hotkey bindings and install the
        //      write-back hook. `applyCustomBindingsJson` rebinds every
        //      action registered so far and any action registered later
        //      resolves its effective chords against this custom map, so
        //      ordering vs. component registration doesn't matter. The
        //      persist hook writes through the app's Persister — for
        //      server-backed apps (termtastic) that lands the blob in the
        //      same server-managed settings file as theme/appearance;
        //      such apps should ALSO call `applyCustomBindingsJson` when
        //      a live settings push carries a new value.
        val hotkeysRaw = spec.persister.read(PersistKeys.HOTKEY_BINDINGS)
        HotkeyBindings.applyCustomBindingsJson(hotkeysRaw)
        HotkeyBindings.onPersist = { json ->
            scope.launch { spec.persister.write(PersistKeys.HOTKEY_BINDINGS, json) }
        }

        // 1c. Restore toolkit-owned layout state (preset + paneOrder +
        //     geometry per tab). Mode-agnostic — the toolkit owns this
        //     across local and source mode. Must precede the tab/pane
        //     hydration below so the first snapshot/local layout finds
        //     each tab's controller already seeded.
        val layoutStateRaw = spec.persister.read(PersistKeys.LAYOUT_STATE)
        val parsedLayoutState = layoutStateRaw?.let { decodeLayoutStateJson(it) }
            ?: PersistedLayoutState()
        state.applyPersistedLayoutState(parsedLayoutState)

        // 2. Tab list + per-tab pane state. Two paths:
        //    - With a TabSource: app pushes snapshots; assembler renders
        //      whatever it gets and forwards user gestures back through
        //      the source's callbacks. The toolkit reads/writes nothing
        //      under PersistKeys.LAYOUT.
        //    - Without a TabSource: assembler owns the tab list locally,
        //      backed by PersistKeys.LAYOUT. Suitable for `lunula-demo`
        //      and other apps with no app-side tab model.
        if (spec.tabSource != null) {
            state.bindTabSource(spec.tabSource)
        } else {
            val layoutRaw = spec.persister.read(PersistKeys.LAYOUT)
            val layout = layoutRaw?.let { decodeShellLayoutJson(it) } ?: seedDefaultLayout()
            state.applyLocalLayout(layout)
        }
        // Worlds sit above tabs and are optional: only wire the switcher
        // when the app supplies a world source. Subscribed after the tab
        // source so the first world push renders against a populated tab
        // model.
        if (spec.worldSource != null) {
            state.bindWorldSource(spec.worldSource)
        }
    }

    return object : AppShellHandle {
        override fun setTitle(title: String) {
            document.title = title
        }

        override fun focusActivePane() {
            (main.querySelector(".dt-pane.dt-pane-focused") as? HTMLElement)?.focus()
        }

        override fun setSidebarOpen(open: Boolean) {
            state.setSidebarOpen(open)
        }

        override fun refresh() {
            state.rerender()
        }

        override fun beginPaneRename(paneId: String) {
            triggerPaneRename(paneId)
        }

        override fun setThemeSnapshot(snapshot: ThemeSnapshotV2) {
            state.syncThemeFromHost(snapshot)
        }

        override fun applyExternalLayoutState(layoutStateJson: String) {
            state.applyExternalLayoutState(layoutStateJson)
        }

        override fun currentLayoutStateJson(): String =
            state.currentLayoutStateJson()

        override fun bringPaneToFront(paneId: String) {
            state.bringPaneToFrontFromHost(paneId)
        }

        override fun openHotkeysSidebar() {
            state.openHotkeysSidebar()
        }

        override fun openNotificationsSidebar() {
            state.openNotificationsSidebar()
        }

        override fun dispose() {
            initJob.cancel()
            state.unwatchSystemAppearance()
            spec.rootContainer.innerHTML = ""
        }
    }
}

/**
 * Seeds an empty persister with a single tab + a single floating pane
 * sized roughly to mid-window so the user immediately sees the pane
 * is a draggable, resizable window (with a maximize affordance that
 * flips it to full-bleed). Without a non-maximized seed, the very
 * first paint is full-bleed and clicking "maximize" toggles a flag
 * with no visible geometry change — there's nothing to animate from.
 *
 * Apps can override the seed by writing their own
 * [PersistKeys.LAYOUT] before mount.
 */
private fun seedDefaultLayout(): PersistedShellLayout {
    val tabId = "tab-1"
    val paneId = "pane-1"
    return PersistedShellLayout(
        tabs = listOf(tabId),
        tabLabels = mapOf(tabId to "Untitled"),
        activeTabId = tabId,
        panesByTab = mapOf(tabId to listOf(PersistedPane(id = paneId))),
    )
}

/**
 * Mutable shell-mount state. Operates in two mutually exclusive modes:
 *
 * - **Local mode** (`spec.tabSource == null`): the `local` field is the
 *   source of truth for the tab list and per-tab pane geometry; tab
 *   callbacks mutate it and persist via [PersistKeys.LAYOUT]. Used by
 *   `lunula-demo` and any app whose tab list IS its persisted state.
 * - **Source mode** (`spec.tabSource != null`): the `external` field
 *   holds the most recent snapshot the app pushed; tab callbacks just
 *   forward to the source's callbacks and the app pushes a new snapshot
 *   in response. The toolkit reads/writes nothing under
 *   `PersistKeys.LAYOUT`. Used by notegrow / termtastic.
 *
 * In both modes the assembler renders the same chrome (top bar with
 * full tab bar, default sidebar with a tabs→panes tree) so apps that
 * adopt `mountAppShell` get a uniform look regardless of where their
 * tab list comes from.
 */
private class ShellState(
    private val spec: AppShellSpec,
    private val scope: CoroutineScope,
    private val themeHost: DefaultThemeManagerHost,
    private val themeState: DefaultThemeManagerState,
) {
    private var frame: HTMLElement? = null
    private var topBarSlot: HTMLElement? = null
    private var leftSidebarSlot: HTMLElement? = null
    private var rightSidebarSlot: HTMLElement? = null
    private var main: HTMLElement? = null
    private var bottomBarSlot: HTMLElement? = null
    private var renderer: LayoutRenderer? = null

    /**
     * Watches the [main] content slot for size changes that the existing
     * pane-gesture path doesn't already cover: window/Electron-window
     * resize, left sidebar open/close/drag, right settings/appearance/theme
     * sidebar open/close. Installed in [attach]; never torn down (the
     * mount lives for the page lifetime). See [attach] for the rationale.
     */
    private var mainResizeObserver: ResizeObserver? = null

    /**
     * Debounce handle for the [mainResizeObserver] callback. `0` means
     * "no pending fire." `ResizeObserver` fires every animation frame
     * during a gesture or CSS transition, so we coalesce into one
     * `onGeometryChanged` invocation ~[MAIN_RESIZE_DEBOUNCE_MS] after the
     * size settles.
     */
    private var mainResizeDebounceHandle: Int = 0

    private var snapshot: ThemeSnapshotV2 = ThemeSnapshotV2()
    /** Non-null only in local mode. */
    private var local: PersistedShellLayout? = null
    /** Non-null only in source mode. */
    private var external: TabListSnapshot? = null

    /**
     * Latest world list pushed by [AppShellSpec.worldSource], or `null`
     * when no world source is wired. Drives the topbar globe switcher and
     * the "New world" dropdown entry; a push replaces it and re-renders.
     */
    private var worldSnapshot: WorldListSnapshot? = null

    /**
     * Source-mode only: the tab the user last optimistically activated
     * (via [TabBarCallbacks.onSelect]) that the app hasn't yet confirmed by
     * pushing a matching snapshot. While set, incoming snapshots keep this
     * as the displayed active tab instead of a stale intermediate value —
     * so a burst of fast tab switches never visibly reverts before the
     * server catches up. Cleared once a pushed snapshot's `activeTabId`
     * matches it (server confirmed) or the tab disappears (activation
     * rejected / tab closed). See [bindTabSource].
     */
    private var pendingActiveTabId: String? = null

    /**
     * Source-mode only: per-tab, the pane the user last optimistically
     * focused (Ctrl+Opt+Arrow spatial nav, a pane mousedown, a sidebar pane
     * click) that the app hasn't yet confirmed by pushing a snapshot whose
     * `activePaneId` matches. While an entry is set, incoming snapshots keep
     * that pane displayed/focused for the tab instead of the stale server
     * `activePaneId` they still carry mid-round-trip — so a pane switch never
     * visibly reverts before the server catches up.
     *
     * The pane analogue of [pendingActiveTabId]. Needed because a host can
     * re-broadcast its layout on high-frequency, focus-irrelevant events —
     * termtastic pushes a fresh config on every program-set OSC title tick
     * (~once per debounce interval while a terminal task runs). Such a push
     * still carries the pre-switch `activePaneId`, and without this hold
     * [syncControllersWithSnapshot]'s `setActive` would fire the controller's
     * `onChange` → [rerender] → refocus the old pane, yanking the user back to
     * the pane they just navigated away from.
     *
     * Cleared for a tab once a pushed snapshot's `activePaneId` matches the
     * held value (server confirmed), the held pane leaves the tab
     * (focus rejected / pane closed), or the hold outlives
     * [PENDING_ACTIVE_PANE_EXPIRY_MS]. See [applyPendingActivePaneHold].
     *
     * The expiry exists because a hold can be seeded by a gesture that never
     * produces a confirming push: clicking a pane the server ALREADY considers
     * focused sends no focus command (hosts dedupe no-op focus changes), so no
     * snapshot ever arrives whose `activePaneId` "catches up" — the hold would
     * otherwise sit forever and permanently veto the next focus change that
     * arrives from another surface (e.g. a sidebar pane click's round-trip, or
     * another client). A confirming round-trip completes in milliseconds, so
     * any hold older than a few seconds is stale by construction and is
     * dropped rather than applied.
     */
    private val pendingActivePaneId: MutableMap<String, PendingActivePane> = HashMap()

    /**
     * One optimistic focus hold: the pane the user just picked in [tabId → this]
     * ([pendingActivePaneId]) plus when it was seeded, so
     * [applyPendingActivePaneHold] can age it out via
     * [PENDING_ACTIVE_PANE_EXPIRY_MS].
     *
     * @property paneId the optimistically focused pane.
     * @property seededAtMs [kotlin.js.Date.now] timestamp at seed time.
     */
    private class PendingActivePane(val paneId: String, val seededAtMs: Double)

    /**
     * Source-mode only: record that the user just optimistically focused
     * [paneId] in [tabId], replacing any earlier (possibly stale) hold for the
     * tab. Called from every user focus gesture — the renderer's capture-phase
     * pane mousedown / spatial nav (via `onPaneFocused`) and the sidebar pane
     * row click / dock-chip restore (via [bringPaneToFront]) — so a new
     * gesture always supersedes an old hold instead of being vetoed by it.
     *
     * @param tabId the tab owning the pane.
     * @param paneId the pane the user picked.
     * @see pendingActivePaneId
     * @see applyPendingActivePaneHold
     */
    private fun recordPendingActivePane(tabId: String, paneId: String) {
        pendingActivePaneId[tabId] = PendingActivePane(paneId, kotlin.js.Date.now())
    }

    /**
     * Reentrancy guard: true only while [rerender]'s reconciliation
     * `focusPane` call (which re-asserts the server/held active pane) is
     * running, so [bindTabSource]'s `onPaneFocused` can tell that focus event
     * apart from a genuine user gesture and NOT record it as a fresh optimistic
     * pending. Without this, every rerender would re-seed [pendingActivePaneId]
     * with the current server value and then block a later focus change pushed
     * by another client. See [pendingActivePaneId].
     */
    private var reconcilingActivePane: Boolean = false

    /**
     * Toolkit-owned layout state — per-tab active preset, pane importance
     * order, and pane geometry. Persisted under [PersistKeys.LAYOUT_STATE].
     * Single source of truth in both local and source mode; the tab-source
     * snapshot only carries pane *identity*.
     */
    private var geometryState: PersistedLayoutState = PersistedLayoutState()

    /**
     * The id of the world whose layout [geometryState] currently holds, or
     * `null` in single-world mode (no [AppShellSpec.worldLayoutProvider]).
     *
     * Pane geometry is namespaced per world: [geometryState] is only ever
     * the *active* world's slice, persisted under [layoutKeyForWorld]. When
     * a tab snapshot arrives carrying a different [TabListSnapshot.worldId]
     * (a world switch), [syncControllersWithSnapshot] flushes the outgoing
     * world's layout to its key and loads the incoming world's via
     * [AppShellSpec.worldLayoutProvider] — so an inactive world's pane
     * positions survive untouched instead of being pruned as "closed tabs".
     */
    private var activeWorldId: String? = null

    /**
     * In-memory, per-world layout cache (worldId → that world's
     * [PersistedLayoutState]) for every world visited this session. Captured
     * on switch-*away* (with the outgoing world's freshest, controller-merged
     * state) and consulted first on switch-*back*, ahead of the async
     * [AppShellSpec.worldLayoutProvider] server read — so rapid A→B→A
     * switching can never lose an edit that hasn't round-tripped through the
     * server yet. A world not present here (never visited this session) falls
     * back to the provider (the server's persisted copy).
     */
    private val worldLayouts: MutableMap<String, PersistedLayoutState> = HashMap()

    /**
     * Persistence key holding [worldId]'s pane layout. The **default (first)**
     * world keeps the flat [PersistKeys.LAYOUT_STATE] key — so pre-world
     * clients and every existing saved layout keep working with no
     * migration — while other worlds live under a per-world suffix that old
     * clients simply ignore. The host's persister adapter is what decides
     * which id is "default" (it aliases that world's suffixed key back onto
     * LAYOUT_STATE); here we always suffix and let the adapter route.
     */
    private fun layoutKeyForWorld(worldId: String): String =
        "${PersistKeys.LAYOUT_STATE}.world.$worldId"

    /** The key [persistLayoutState] writes for the current world (flat when single-world). */
    private fun activeLayoutKey(): String =
        activeWorldId?.let { layoutKeyForWorld(it) } ?: PersistKeys.LAYOUT_STATE

    /**
     * `true` while [applyExternalLayoutState] is adopting a layout-state blob
     * pushed from another client. Suppresses [persistLayoutState] so adopting
     * a remote change doesn't write it straight back out (which would loop
     * through the broadcast that delivered it).
     */
    private var applyingExternal: Boolean = false

    /**
     * The most recent layout-states this client has itself written via
     * [persistLayoutState], newest last, capped at [RECENT_LAYOUT_WRITES].
     *
     * Used by [applyExternalLayoutState] to recognise — and ignore — the
     * server's echo of our *own* writes. A single geometry action often
     * triggers several sequential persists (e.g. a maximize followed by the
     * post-render terminal refit), so by the time the first echo arrives
     * [geometryState] has already advanced past it. Comparing only against the
     * live [geometryState] therefore mistakes a stale self-echo for a remote
     * change, adopts it, refits, re-persists, and loops forever (the flicker
     * bug). Matching against the recent-writes set instead catches the echo of
     * any of them, so only genuinely remote blobs are adopted.
     */
    private val recentLayoutWrites: ArrayDeque<PersistedLayoutState> = ArrayDeque()

    /**
     * The last layout-state value actually written out by
     * [persistLayoutState] (or adopted from persistence/a remote client —
     * see [applyPersistedLayoutState]). Used as a value-level dedup: a
     * persist request whose merged state equals this is dropped without
     * touching the persister.
     *
     * This is the choke point that stops the settings write storm behind
     * termtastic#93: [persistLayoutState] is invoked very liberally — once
     * per tab-source snapshot from [syncControllersWithSnapshot], from
     * every controller `onChange` (including pure focus changes via
     * `setActive`), and once per pane from [maybeReapplyPreset]'s
     * re-tile loop — and before this guard each of those calls produced an
     * outbound write even when nothing had changed. For server-backed
     * persisters (termtastic's `POST /api/ui-settings` bridge) that meant
     * every config broadcast made every connected desktop client re-POST
     * an identical LAYOUT_STATE blob, which the server then re-broadcast
     * to every client (including mobile) — thousands of redundant
     * round-trips per hour that starved the mobile clients' connect path.
     */
    private var lastPersistedLayoutState: PersistedLayoutState? = null

    /**
     * Per-tab [LayoutController] keyed by tab id. Lazily populated by
     * [controllerFor]; populated controllers fire [persistLayoutState] on
     * every state mutation through their `onChange` hook.
     */
    private val layoutControllers: MutableMap<String, LayoutController> = HashMap()

    /**
     * The most recent snapshot the source mode has seen (or a synthetic
     * snapshot derived from `local` after a local mutation). Used by
     * [syncControllersWithSnapshot] to diff create/remove events.
     */
    private var lastSnapshot: TabListSnapshot = TabListSnapshot(emptyList(), null)

    /**
     * Section ids the user has collapsed in the default left-sidebar
     * tabs→panes tree. Persisted under [PersistKeys.SIDEBAR_STATE] so
     * the open/closed state of each per-tab section survives reloads.
     * Section ids are tab ids in the default tree (one section per tab);
     * app-supplied [AppShellSidebarSection] entries are keyed by their
     * own title for the same purpose.
     */
    private val collapsedSections: MutableSet<String> = mutableSetOf()

    /**
     * Cache of the elements the host's [AppShellSpec.paneContent] factory
     * produced, keyed by pane id. The [LayoutRenderer]'s `contentRenderer`
     * is invoked on every rerender (geometry change, focus change, layout
     * preset apply, snapshot push…); without this cache the host factory
     * would be called repeatedly and any DOM the host built (a contenteditable
     * editor, a terminal canvas, an in-flight file load) would be torn down
     * and recreated on every paint — losing caret state, selection, IME
     * composition, and racing async content loads.
     *
     * The cache is invalidated only when a pane is removed from the
     * snapshot (see [pruneStalePaneContentCache]). On a fresh snapshot the
     * existing cached element is re-mounted into the rebuilt slot.
     */
    private val paneContentCache: MutableMap<String, HTMLElement> = HashMap()

    /**
     * Returns the host-produced pane body for [paneId], building it lazily
     * on first request. Subsequent requests return the same element so the
     * host's view-model + DOM survive toolkit-driven re-renders.
     */
    private fun getOrBuildPaneContent(paneId: String): HTMLElement =
        paneContentCache.getOrPut(paneId) { spec.paneContent(paneId) }

    /**
     * Drop cached content for any pane that no longer exists in the
     * current snapshot/local layout. Called from [rerender] right before
     * the layout renderer's render pass so closed panes free their host
     * DOM (and any view-model bag the host attaches to it).
     */
    private fun pruneStalePaneContentCache() {
        val live = HashSet<String>()
        when {
            external != null -> external!!.tabs.forEach { tab ->
                tab.panes.forEach { live += it.id }
            }
            local != null -> local!!.panesByTab.values.forEach { panes ->
                panes.forEach { live += it.id }
            }
        }
        val stale = paneContentCache.keys - live
        for (id in stale) paneContentCache.remove(id)
    }

    init {
        // Seed the SidebarController so it's "open" by default; the very
        // first rerender then mounts the sidebar at its target width
        // without animation (skipNextOpenAnimation). When the app opts
        // out of the sidebar entirely (`showSidebar = false`) seed it
        // closed — the slot is never mounted, but a coherent controller
        // state costs nothing and guards any stray isOpen reads.
        leftSidebarController.setInitial(open = spec.showSidebar, widthPx = 240)
    }

    fun attach(
        frame: HTMLElement,
        topBarSlot: HTMLElement,
        leftSidebarSlot: HTMLElement,
        rightSidebarSlot: HTMLElement,
        main: HTMLElement,
        bottomBarSlot: HTMLElement,
    ) {
        this.frame = frame
        this.topBarSlot = topBarSlot
        this.leftSidebarSlot = leftSidebarSlot
        this.rightSidebarSlot = rightSidebarSlot
        this.main = main
        this.bottomBarSlot = bottomBarSlot

        // Reformat trigger for everything that resizes the main content
        // slot without going through a pane gesture: window/Electron-window
        // resize, left sidebar open/close/drag (incl. mid-CSS-transition),
        // right settings/appearance/theme sidebar open/close, devtools dock
        // toggle. Pane drag-resize is already covered by the host's own
        // `onGeometryChanged` callsites (drag end, maximize toggle, preset
        // apply / Auto re-tile on membership change) and resizes the pane
        // *inside* main without changing main's own size, so it does not
        // double-fire here.
        //
        // We complement the per-pane ResizeObserver in app consumers
        // (e.g. termtastic's `LayoutBuilder.kt`) which already refits the
        // local widget (xterm grid, etc.) on every observation but
        // intentionally does NOT reassert any out-of-process state (PTY
        // cols/rows) — that observer fires per animation frame during a
        // gesture, and hammering the backend with one resize ioctl per
        // frame is exactly what the debounced `onGeometryChanged` contract
        // is here to avoid. Observing at the slot level + debouncing
        // settle to one fire dispatches `onGeometryChanged` once after
        // the size truly stops changing, which is the agreed signal for
        // "re-measure now."
        //
        // Why `main` and not `frame`: `main` is the slot whose width
        // directly drives the per-pane containers; observing `frame`
        // would also fire on topbar/bottombar height changes, which do
        // not change pane *width*. Sidebar transitions and window resize
        // both flex `main`, so this single observer covers both.
        mainResizeObserver?.disconnect()
        if (mainResizeDebounceHandle != 0) {
            kotlinx.browser.window.clearTimeout(mainResizeDebounceHandle)
            mainResizeDebounceHandle = 0
        }
        val obs = ResizeObserver { _, _ ->
            if (mainResizeDebounceHandle != 0) {
                kotlinx.browser.window.clearTimeout(mainResizeDebounceHandle)
            }
            mainResizeDebounceHandle = kotlinx.browser.window.setTimeout({
                mainResizeDebounceHandle = 0
                viewActiveTabId()?.let { spec.onGeometryChanged?.invoke(it) }
            }, timeout = MAIN_RESIZE_DEBOUNCE_MS)
        }
        obs.observe(main)
        mainResizeObserver = obs
    }

    /**
     * The `prefers-color-scheme: dark` query this shell is listening to, and
     * the listener on it. Null when nothing is installed — either because the
     * app owns theme resolution (see [watchSystemAppearance]) or because the
     * shell has been disposed.
     */
    private var systemAppearanceQuery: MediaQueryList? = null
    private var systemAppearanceListener: ((Event) -> Unit)? = null

    /**
     * Repaint when the OS switches between light and dark while the user's
     * appearance preference is [Appearance.Auto].
     *
     * Auto means "whatever the system is doing", and [isDarkActive] answers
     * that by reading `prefers-color-scheme` — but it was only ever *read*, at
     * paint time, and nothing subscribed to it. A tab left open across the
     * evening switchover (macOS's scheduled Auto Appearance, a user flipping it
     * by hand) went on wearing the slot it had resolved at load, until some
     * unrelated rerender happened to repaint it. The preference said "follow
     * the system" and the window did not follow.
     *
     * A change is handled by re-applying the snapshot the shell already holds:
     * nothing about the user's stored preference changed, only which of its two
     * slots is live, so this paints and rerenders but deliberately does not
     * persist. Routing through [applyThemeSnapshot] also rebinds the theme
     * manager's cards, which read the active appearance the same way the paint
     * does and would otherwise be left filling the slot the OS just stopped
     * showing.
     *
     * Not installed when the app supplies a [AppShellSpec.settingsHost]: those
     * apps own theme resolution outside the toolkit and already watch the media
     * query themselves (Lunamux repaints through its own
     * `refreshAndApplyActiveTheme`), so a second listener here would paint over
     * their answer with the toolkit's.
     */
    fun watchSystemAppearance() {
        if (spec.settingsHost != null) return
        if (systemAppearanceQuery != null) return
        val query = kotlinx.browser.window.matchMedia("(prefers-color-scheme: dark)")
        val listener: (Event) -> Unit = {
            // Dark and Light pin the slot regardless of the OS, so only Auto
            // has anything to react to. Guarding here rather than relying on
            // the repaint being a no-op keeps a pinned appearance genuinely
            // inert — no rerender, no `onAfterRefresh` into host code.
            if (snapshot.appearance == Appearance.Auto) applyThemeSnapshot(snapshot)
        }
        // `addListener` is the pre-2020 spelling, still the only one Safari
        // understood until 14. Preferred form first, fall back rather than
        // leave older engines silently unsubscribed.
        val dyn = query.asDynamic()
        if (dyn.addEventListener != undefined) query.addEventListener("change", listener)
        else dyn.addListener(listener)
        systemAppearanceQuery = query
        systemAppearanceListener = listener
    }

    /** Undo [watchSystemAppearance]. Safe to call when nothing is installed. */
    fun unwatchSystemAppearance() {
        val query = systemAppearanceQuery ?: return
        val listener = systemAppearanceListener
        if (listener != null) {
            val dyn = query.asDynamic()
            if (dyn.removeEventListener != undefined) query.removeEventListener("change", listener)
            else dyn.removeListener(listener)
        }
        systemAppearanceQuery = null
        systemAppearanceListener = null
    }

    fun applyThemeSnapshot(snap: ThemeSnapshotV2) {
        this.snapshot = snap
        // Mirror into the default theme-manager state so the theme list's
        // selection highlight reads the live values after the topbar cycle
        // button flips Auto/Dark/Light. Apps that supply their own
        // [ThemeManagerHost] (e.g. termtastic) bypass [themeState] entirely
        // and aren't affected.
        themeState.applySnapshotV2(snap)
        val docEl = document.documentElement as? HTMLElement ?: return
        // Seed `:root` with the active theme so any new elements that
        // [rerender] produces have a baseline `var(--t-*)` chain to read from
        // on their first frame.
        val isDark = isDarkActive(snap.appearance)
        applyTheme(docEl, snap.resolve(isDark), isDark)
        applyHostFontVars()
        applyCustomTitleBar()
        rerender()
        // [rerender] rebuilds the right-sidebar slot, but NOT the theme manager
        // inside it: [showThemeManager] is idempotent and re-appends the same
        // panel element it built when the manager was opened (the orphan-
        // recovery branch that keeps scroll position across unrelated
        // rerenders). Its theme cards therefore keep the closures they were
        // built with — and each card captured `isDarkActive(host.appearance)`
        // at build time, because that is the slot a click fills.
        //
        // So an appearance flip through this path left every card still writing
        // the *previous* appearance's slot. Toggling dark→light and picking a
        // theme wrote the dark slot: nothing visibly happened, the pick was
        // silently lost, and the click's own [pokeManager] then rebuilt the
        // cards — which is why a second click on the same card "worked". The
        // slots also drifted apart, the light one keeping whatever was chosen
        // while dark. Repainting the manager here binds the cards to the
        // appearance the user is now looking at.
        //
        // Gated like the mirror in [syncThemeFromHost]: an app that owns a
        // [ThemeManagerHost] renders its cards against that host, which this
        // button does not touch, so its cards were never stale and an
        // unsolicited repaint could only tear down an editor mid-interaction.
        if (spec.settingsHost == null) refreshThemeManager()
    }

    /**
     * Sync path for apps that own theme resolution outside the toolkit
     * (e.g. termtastic's `TermtasticThemeManagerHost`). Updates the stored
     * snapshot + paints in place; deliberately omits [rerender] for the same
     * reason [onThemeManagerChanged] does — the user may be mid-interaction in
     * the theme editor.
     *
     * Called by [AppShellHandle.setThemeSnapshot].
     */
    fun syncThemeFromHost(snap: ThemeSnapshotV2) {
        // For an app that supplies no [AppShellSpec.settingsHost], mirror into
        // the default theme-manager state as [applyThemeSnapshot] does — a
        // sharper version of the same reason. Without a host, [themeState] is
        // not merely the selection highlight: it *is* the theme manager's
        // state, and it is what [onThemeManagerChanged] rebuilds the snapshot
        // from. Leaving it behind meant a pushed snapshot painted correctly and
        // was then lost the moment the user touched the theme manager, which
        // rebuilt from the stale state and silently reverted the push — the
        // appearance being the visible casualty.
        //
        // Deliberately NOT done when the app owns a [ThemeManagerHost]. It
        // would be inert for them — their manager reads the host, never
        // [themeState] — but "inert" is the whole argument for leaving it
        // alone: those are precisely the hosts that bridge `onAfterRefresh`
        // back into [AppShellHandle.setThemeSnapshot], and the appearance-cycle
        // button above documents how such a bridge can land here holding a
        // *stale* snapshot mid-rerender. Widening what a stale call overwrites
        // buys nothing for them and could only cost.
        if (spec.settingsHost == null) themeState.applySnapshotV2(snap)
        // The topbar appearance-cycle button's icon (sun / moon / half-disc) is
        // painted only when the chrome is rebuilt by [rerender]. A slot/colour
        // sync repaints `:root` in place and needs no rebuild, but an
        // *appearance* change pushed from the host (e.g. another device toggled
        // Auto/Dark/Light, or the host's own appearance state changed) would
        // otherwise leave the button frozen on the old icon. Detect that and
        // rerender so the icon tracks the live appearance — while colour-only
        // syncs still paint in place, so an open theme editor / menu isn't torn
        // down mid-interaction.
        val appearanceChanged = this.snapshot.appearance != snap.appearance
        this.snapshot = snap
        val docEl = document.documentElement as? HTMLElement ?: return
        val isDark = isDarkActive(snap.appearance)
        applyTheme(docEl, snap.resolve(isDark), isDark)
        applyHostFontVars()
        applyCustomTitleBar()
        if (appearanceChanged) rerender()
        // Repaint an open theme manager against the state just mirrored. A
        // no-op when none is open (a null-safe call on the open manager's
        // rerender hook), and the same call [onThemeManagerChanged] already
        // makes after a theme change — so a manager rebuilt in response to a
        // theme change is established behaviour, not something introduced here.
        // The alternative is a manager left showing the themes and selection of
        // whoever was signed in a moment ago.
        //
        // Gated with the mirror above so that an app owning a
        // [ThemeManagerHost] sees no behaviour change at all from this path:
        // such an app already calls [refreshThemeManager] itself when its own
        // state moves, and an extra unsolicited repaint could tear down an
        // editor its user is mid-interaction in.
        if (spec.settingsHost == null) refreshThemeManager()
    }

    /**
     * Pushes the host's currently-persisted font preferences onto the
     * document root via the toolkit's `--dt-font-*` CSS variables. Called
     * after [applyUi] (which establishes the palette) and after any
     * theme-manager change.
     *
     * Reads from `spec.settingsHost ?: themeHost` — the same `settingsHost ?:
     * themeHost` ladder the Settings sidebar's pill rows read/mutate — so app
     * surfaces wired to the `var(--dt-font-*)` chain pick up persisted values
     * on first paint without an explicit settings sync from the host.
     *
     * Reading the resolved host (not the internal [themeHost]) matters for
     * apps that own theme resolution outside the toolkit and supply their own
     * [AppShellSpec.settingsHost] (e.g. termtastic): the internal [themeHost]
     * never receives their font preferences, so reading it here would clear
     * the `--dt-font-*` vars (via the `removeProperty` branch) and clobber the
     * chrome fonts on first paint, leaving the tab bar / sidebars on defaults
     * until the user re-picks a font in Appearance settings.
     */
    private fun applyHostFontVars() {
        val host = spec.settingsHost ?: themeHost
        // The app's fallback chrome font (e.g. a deploy-time brand font), applied
        // to the chrome/prose surfaces only when the user has selected none of
        // their own — so it survives every reapply here, and the user's own pick
        // (a non-null host family) still wins. Content mono is never touched by it.
        val chromeFallback = spec.defaultChromeFontFamily()
        applyMonoFontFamily(host.monoFontFamily)
        applyMonoFontSizePx(host.monoFontSizePx)
        applyProportionalFontFamily(host.proportionalFontFamily ?: chromeFallback)
        applyProportionalFontSizePx(host.proportionalFontSizePx)
        applySidebarFontFamily(host.sidebarFontFamily ?: chromeFallback)
        applySidebarFontSizePx(host.sidebarFontSizePx)
        applyTabbarFontFamily(host.tabbarFontFamily ?: chromeFallback)
        applyTabbarFontSizePx(host.tabbarFontSizePx)
        applyPaneHeaderFontFamily(host.paneHeaderFontFamily ?: chromeFallback)
        applyPaneHeaderFontSizePx(host.paneHeaderFontSizePx)
    }

    /** Last value pushed to the Electron main process; lets us skip
     *  redundant IPC traffic on every theme rerender. `null` until the
     *  first application. */
    private var lastAppliedCustomTitleBar: Boolean? = null

    /**
     * Reflects the host's persisted `useCustomTitleBar` value onto both
     * the renderer (via the `dt-custom-titlebar` body class) and the
     * Electron main process (via `globalThis.darknessApi.setCustomTitleBar`
     * if the host app exposes it). Called at boot and after every
     * theme-host change so the chrome stays in sync with the snapshot
     * without each app having to wire its own subscriber.
     *
     * The IPC call is best-effort: in a plain browser (no `darknessApi`)
     * it short-circuits to a no-op, and in Electron apps that already own
     * an independent subscriber (e.g. termtastic's server-driven
     * `main.kt` subscriber) the handler dedupes redundant calls. We still
     * gate on a last-value cache to keep the channel quiet across
     * theme-only changes.
     */
    private fun applyCustomTitleBar() {
        val value = themeHost.useCustomTitleBar
        setDtCustomTitleBarBodyClass(value)
        val first = (lastAppliedCustomTitleBar == null)
        if (value != lastAppliedCustomTitleBar) {
            lastAppliedCustomTitleBar = value
            // Skip the IPC on the very first call: chromePrefs on disk in the
            // Electron main process is already the source of the current
            // window's titleBarStyle, so pushing the renderer's initial value
            // back is at best a no-op and — for hosts whose host-side value
            // hydrates asynchronously (e.g. termtastic reading
            // electronCustomTitleBar over the window socket) — a stale
            // overwrite that ping-pongs the BrowserWindow rebuild. Subsequent
            // transitions (user toggle in Settings, server push from another
            // client) still fire through the channel below.
            if (first) return
            val darknessApi = js("globalThis.darknessApi").unsafeCast<dynamic>()
            val setter = darknessApi?.setCustomTitleBar
            if (setter != null) {
                try { setter(value) } catch (_: Throwable) { /* best-effort */ }
            }
        }
    }

    /**
     * Invoked by [DefaultThemeManagerHost.onChange] whenever the user picks a
     * theme / appearance in the theme manager sidebar. Reads the manager's
     * typed state into a fresh [ThemeSnapshotV2], paints it onto the live
     * document via [applyTheme] (updating root CSS vars in place), persists,
     * and asks the theme manager to refresh its own selection highlight.
     *
     * Critically, this path does NOT call [rerender]. A full rerender would
     * tear down the right-side theme-manager panel mid-interaction (the user
     * is still hovering a row), and would also discard the paint that
     * [applyTheme] just installed on the existing chrome elements.
     */
    fun onThemeManagerChanged() {
        val resolved = themeState.toSnapshotV2()
        this.snapshot = resolved
        val docEl = document.documentElement as? HTMLElement
        if (docEl != null) {
            val isDark = isDarkActive(resolved.appearance)
            applyTheme(docEl, resolved.resolve(isDark), isDark)
        }
        applyHostFontVars()
        applyCustomTitleBar()
        persistUi()
        refreshThemeManager()
    }

    /** Local-mode entry point. */
    fun applyLocalLayout(layout: PersistedShellLayout) {
        this.local = layout
        this.external = null
        // Drive controller + geometry state through the same diff path
        // source mode uses, so add/remove of locally-seeded panes seeds
        // default geometry and triggers Auto reflow if active.
        val changedTabs = syncControllersWithSnapshot(localSyntheticSnapshot())
        rerender()
        // Auto is the only preset that re-tiles on membership change —
        // its contract (see [LayoutPreset.Auto]) is "the toolkit picks
        // geometry for the current pane count." Other presets are
        // applied once when the user picks them from the dropdown and
        // then geometry is sticky; focus-only snapshots must not move
        // panes around. Every changed tab re-tiles — not just the active
        // one — so panes that appear in a background tab land tiled.
        changedTabs.forEach { tabId ->
            if (presetIsAuto(tabId)) maybeReapplyPreset(tabId)
        }
    }

    /**
     * Hydrate the toolkit's layout state from persistence. Called once
     * during [mountAppShell]'s init job, before any tab/pane snapshot
     * lands. Pre-populates [layoutControllers] for every tab the persisted
     * state knows about so the first snapshot doesn't reset their preset
     * back to [LayoutPreset.Custom].
     *
     * Writes are suppressed while hydrating (via [applyingExternal]):
     * `reset` / `setPreset` fire the controllers' `onChange`, which is
     * wired to [persistLayoutState] — re-persisting what was just read
     * is pure churn, and for server-backed persisters it used to fire a
     * burst of redundant round-trips on every page load (observed while
     * debugging termtastic#86).
     */
    fun applyPersistedLayoutState(s: PersistedLayoutState) {
        val wasApplying = applyingExternal
        applyingExternal = true
        try {
            geometryState = s
            // Seed the persist-dedup baseline with the hydrated/adopted
            // state: this blob is by definition already persisted (we just
            // read it, or another client just wrote it), so a subsequent
            // [persistLayoutState] whose merged state still equals it must
            // not round-trip it back to the persister (termtastic#93).
            lastPersistedLayoutState = s
            s.paneOrderByTab.forEach { (tabId, order) ->
                controllerFor(tabId).reset(order)
            }
            s.presetByTab.forEach { (tabId, key) ->
                LayoutPreset.fromKey(key)?.let { controllerFor(tabId).setPreset(it) }
            }
        } finally {
            applyingExternal = wasApplying
        }
        // Also mark the state as "already known" for the external-adopt
        // dedup: the server pushes the persisted blob once on connect, and
        // that push can arrive AFTER the first snapshot has advanced
        // [geometryState] past it (new pane seeded + Auto-tiled). Without
        // this entry the boot push fails the `parsed == geometryState`
        // check in [applyExternalLayoutState], is mistaken for a remote
        // change, and re-adopts the stale pre-boot blob — wiping the
        // freshly tiled tab back to (per-render random) default geometry.
        // Observed while verifying termtastic#86 on a fresh database.
        recentLayoutWrites.addLast(s)
        while (recentLayoutWrites.size > RECENT_LAYOUT_WRITES) recentLayoutWrites.removeFirst()
    }

    /**
     * Adopt an externally-authored `LAYOUT_STATE` blob into the live shell and
     * re-render so the change is visible immediately. Backs
     * [AppShellHandle.applyExternalLayoutState].
     *
     * No-op when the decoded state equals the current one (so a client's own
     * change, echoed back through the server broadcast, doesn't churn). While
     * applying, [persistLayoutState] is suppressed via [applyingExternal] so
     * adopting a remote change never writes it back out.
     *
     * After adopting, the blob is validated against the live snapshot via
     * [reconcileAdoptedLayoutState]: an externally-authored blob can be
     * stale relative to tabs/panes this client already renders (authored
     * before they existed, or keyed by colliding ids from another server
     * database), and adopting it verbatim would leave live panes without
     * geometry — which the renderer papers over with a fresh random
     * default on every paint.
     *
     * @param layoutStateJson the blob string as produced by [encodeLayoutStateJson].
     */
    fun applyExternalLayoutState(layoutStateJson: String) {
        val parsed = decodeLayoutStateJson(layoutStateJson)
        // Ignore the server's echo of our own writes. `geometryState` may have
        // already advanced past the echoed blob (a single gesture fans out into
        // several persists), so also match against the recent-writes window —
        // otherwise a stale self-echo is mistaken for a remote change and the
        // adopt → refit → re-persist → echo cycle never settles (flicker bug).
        if (parsed == geometryState || recentLayoutWrites.contains(parsed)) return
        applyingExternal = true
        try {
            applyPersistedLayoutState(parsed)
        } finally {
            applyingExternal = false
        }
        reconcileAdoptedLayoutState()
        rerender()
    }

    /**
     * Encodes the live [geometryState] as the persisted `LAYOUT_STATE` blob
     * format. Read counterpart of [applyExternalLayoutState] — backs
     * [AppShellHandle.currentLayoutStateJson] so hosts can mirror the actual
     * pane arrangement (including in-session drags not yet round-tripped
     * through persistence) outside the shell's DOM.
     *
     * @return the blob string as produced by [encodeLayoutStateJson].
     */
    fun currentLayoutStateJson(): String = encodeLayoutStateJson(geometryState)

    /**
     * Repairs [geometryState] after an external blob was adopted so it is
     * consistent with the tabs/panes this client is actually rendering
     * (from [lastSnapshot]).
     *
     * Two repairs, mirroring what the snapshot-diff path does for fresh
     * data:
     *  1. [reconcilePersistedTabState] per tab — drops adopted per-tab
     *     state that belongs to a dead namesake tab (colliding ids from
     *     another server database; see its kdoc).
     *  2. Seeds default geometry for any live pane the adopted blob has
     *     no entry for (the blob may predate the pane), then Auto-retiles
     *     the affected tab when its preset is Auto. Without the seed the
     *     pane has no persisted geometry at all and [geometryFor] hands
     *     the renderer a NEW random default rectangle on every paint.
     *
     * Called only from [applyExternalLayoutState], after the adopt.
     */
    private fun reconcileAdoptedLayoutState() {
        lastSnapshot.tabs.forEach { tab ->
            val curr = tab.panes.map { it.id }.toSet()
            reconcilePersistedTabState(tab.id, curr)
            val tabMap = geometryState.geometryByTab[tab.id].orEmpty()
            val missing = curr.filter { tabMap[it] == null }
            if (missing.isEmpty()) return@forEach
            var topZ = tabMap.values.maxOfOrNull { it.zIndex } ?: 0
            var seeded = tabMap
            missing.forEach { id ->
                topZ += 1
                // Adopted-blob repair keeps the historical "never seed
                // maximized" rule even for opens-maximized panes: the
                // adopted layout is another client's arrangement and a
                // surprise full-bleed pane would stomp it.
                seeded = seeded + (id to defaultGeometryForNewPane(tab.id, id)
                    .copy(zIndex = topZ, isMaximized = false))
            }
            geometryState = geometryState.copy(
                geometryByTab = geometryState.geometryByTab + (tab.id to seeded),
            )
            if (presetIsAuto(tab.id)) maybeReapplyPreset(tab.id)
        }
    }

    /**
     * Replaces the in-memory collapsed-section set with [ids]. Called
     * once at boot from [mountAppShell]'s initJob after reading the
     * persisted JSON; not called again during normal use (toggles go
     * through [toggleSection], which mutates in place).
     */
    fun applyCollapsedSections(ids: Set<String>) {
        collapsedSections.clear()
        collapsedSections.addAll(ids)
        rerender()
    }

    /**
     * Flips collapsed/expanded state for the section with [sectionId],
     * persists the new set under [PersistKeys.SIDEBAR_STATE], and
     * re-renders so the chevron rotation and section-body visibility
     * update. Wired as the `onToggle` callback on every section the
     * default sidebar builder produces.
     */
    private fun toggleSection(sectionId: String) {
        if (sectionId in collapsedSections) {
            collapsedSections.remove(sectionId)
        } else {
            collapsedSections.add(sectionId)
        }
        val json = encodeCollapsedSectionsJson(collapsedSections.toSet())
        scope.launch { spec.persister.write(PersistKeys.SIDEBAR_STATE, json) }
        rerender()
    }

    /**
     * Subscribes to the app's world push channel. Each push replaces
     * [worldSnapshot] and re-renders so the globe switcher reflects the
     * new world list / active world. Called from mount when
     * [AppShellSpec.worldSource] is non-null.
     *
     * @param source the host's world callbacks + push channel.
     */
    fun bindWorldSource(source: WorldSource) {
        source.subscribe { snap ->
            val prev = worldSnapshot
            worldSnapshot = snap
            // Skip a full rerender when nothing the switcher shows changed.
            if (prev != null &&
                prev.activeWorldId == snap.activeWorldId &&
                prev.worlds == snap.worlds
            ) {
                return@subscribe
            }
            rerender()
        }
    }

    /** Source-mode entry point: subscribes to the app's push channel. */
    fun bindTabSource(source: TabSource) {
        this.local = null
        source.subscribe { rawSnapshot ->
            // Hold any pane the user just optimistically focused against the
            // stale `activePaneId` an in-flight push still carries, so a pane
            // switch isn't reverted mid-round-trip. All downstream reconciliation
            // (controllers, `external`, focus routing) sees the held snapshot.
            val snapshot = applyPendingActivePaneHold(rawSnapshot)
            val changedTabs = syncControllersWithSnapshot(snapshot)
            // Honour an in-flight optimistic activation: until the app
            // confirms it by pushing a snapshot whose activeTabId matches
            // the pending tab, keep displaying the pending tab rather than
            // whatever (possibly stale, mid-burst) activeTabId this snapshot
            // carries. This is what stops a fast Cmd+digit burst from
            // flickering back to an intermediate tab before it settles.
            val pending = pendingActiveTabId
            val prevExternal = this.external
            this.external = when {
                pending == null -> snapshot
                snapshot.activeTabId == pending -> {
                    pendingActiveTabId = null // server caught up
                    snapshot
                }
                snapshot.tabs.any { it.id == pending } ->
                    snapshot.copy(activeTabId = pending) // hold the user's target
                else -> {
                    pendingActiveTabId = null // pending tab gone (closed/rejected)
                    snapshot
                }
            }
            // Label-only fast path. A [TabListSnapshot] carries tab/pane
            // *identity*, focus, active tab and visibility — but NOT the
            // per-pane label text, which the chrome resolves separately via
            // [AppShellSpec.paneLabel]. So when a push produces a snapshot
            // structurally identical to the last one, the only render input
            // that can have changed is those labels. That happens a lot:
            // termtastic pushes a fresh snapshot on every program-set OSC
            // title tick while a terminal task runs (Claude Code rewrites its
            // title with a live task summary ~once per debounce interval).
            // A full [rerender] for that is wasteful and disruptive — it
            // rebuilds the whole tab strip + sidebar tree, recreating the
            // live-state badge elements apps mount in the row/header badge
            // slots (which restarts their CSS pulse animation, making every
            // status dot visibly "blip"), replaces the sidebar scroll
            // container, and re-runs every pane header's gesture wiring.
            // Refresh just the label text nodes in place instead.
            if (changedTabs.isEmpty() && this.external == prevExternal) {
                refreshPaneLabelsInPlace()
                return@subscribe
            }
            rerender()
            // Auto is the only preset that re-tiles on membership
            // change. Apps push a fresh snapshot for every model
            // change — including pure focus changes (sidebar clicks,
            // tab activation) — and a non-Auto preset must leave the
            // user's geometry alone, otherwise the focused pane bubbles
            // to slot 0 via paneOrder and the panes visibly swap.
            //
            // Re-tile every tab whose membership changed, not only the
            // active one: a new tab's first pane can arrive while this
            // client still displays another tab (server race, or the tab
            // was created by a different client), and the later switch to
            // it is a focus-only snapshot that never re-tiles — leaving
            // the pane at its cascading seed geometry instead of the
            // full-bleed Auto tile (termtastic#86).
            changedTabs.forEach { tabId ->
                if (presetIsAuto(tabId)) maybeReapplyPreset(tabId)
            }
            // Adding a pane to / removing one from the ACTIVE tab re-picks
            // which pane is active (the host re-points focus server-side; the
            // rerender above moved the focus RING to it). But that rerender
            // also detached the previously-focused terminal's <textarea>, and
            // — unlike a click or keyboard nav — no gesture moved real DOM
            // focus, so the new active pane shows its ring yet ignores
            // keystrokes. Hand DOM focus to its content, the same way
            // [applyLayoutPreset] and spatial nav do. Gated on the ACTIVE
            // tab's pane membership actually changing (see [changedTabs] —
            // set-of-pane-ids diff), so it never fires on focus-only pushes,
            // tab switches, or OSC-title ticks, and never steals focus for a
            // pane added to a background tab. Idempotent with any host-side
            // refocus (both target the same server-active pane).
            viewActiveTabId()?.let { if (it in changedTabs) renderer?.focusActivePaneContent() }
        }
    }

    /**
     * Refreshes every rendered pane's visible label — its sidebar row label
     * (and, in start-clip/index mode, the row tooltip) plus its pane-header
     * title — in place from [AppShellSpec.paneLabel], without a [rerender].
     *
     * Fast-path companion to [bindTabSource]: invoked when a pushed snapshot is
     * structurally identical to the last one, so the only render input that can
     * have changed is the per-pane label (snapshots don't carry pane titles).
     * Touches only the two text nodes that actually change, leaving the tab
     * strip, sidebar rows (and the live-state badges apps mount in them), pane
     * geometry, and pane content untouched.
     *
     * Robustness: only a pane's OWN header title is updated (the first
     * `.dt-pane-title` in document order under its `[data-pane-id]` wrapper —
     * the header precedes the content slot). A header currently swapped to an
     * inline-rename `<input>` has no `.dt-pane-title` child and is left alone;
     * a breadcrumb-mode title is skipped so its segment structure is preserved.
     */
    private fun refreshPaneLabelsInPlace() {
        leftSidebarSlot?.let { sidebar ->
            val rows = sidebar.querySelectorAll(".dt-sidebar-row")
            for (i in 0 until rows.length) {
                val row = rows.item(i) as? HTMLElement ?: continue
                val tabId = row.getAttribute("data-tab-id") ?: continue
                val paneId = row.getAttribute("data-pane-id") ?: continue
                val label = spec.paneLabel(tabId, paneId)
                (row.querySelector(".dt-sidebar-row-label") as? HTMLElement)?.let { labelEl ->
                    if (labelEl.textContent != label) labelEl.textContent = label
                }
                // No tooltip sync needed: `wireSidebarRowClipTooltip` re-reads
                // the label text (and re-measures the clip) on each hover, so
                // rewriting the text node above is enough to keep it honest.
            }
        }
        val mainEl = main ?: return
        val activeTab = viewActiveTabId() ?: return
        val panes = mainEl.querySelectorAll("[data-pane-id]")
        for (i in 0 until panes.length) {
            val pane = panes.item(i) as? HTMLElement ?: continue
            val paneId = pane.getAttribute("data-pane-id") ?: continue
            val titleEl = pane.querySelector(".dt-pane-title") as? HTMLElement ?: continue
            if (titleEl.classList.contains("dt-pane-title-breadcrumbs")) continue
            val label = spec.paneLabel(activeTab, paneId)
            if (titleEl.textContent != label) titleEl.textContent = label
        }
    }

    fun setSidebarOpen(open: Boolean) {
        // No sidebar exists when the app opted out — programmatic opens
        // (Electron menu items, host hotkeys) must not resurrect it.
        if (!spec.showSidebar) return
        // Route through the controller so the user-visible width
        // animation (0 ↔ widthPx) plays. The controller flips its
        // own state and calls our rerender once the transition has
        // played; we drive the rebuild from there.
        if (open != leftSidebarController.isOpen) {
            leftSidebarController.toggle(requestRebuild = ::rerender)
        }
    }

    /**
     * Opens the Hotkeys sidebar, animating any other right-side panel closed
     * first (same mutually-exclusive hand-off the topbar buttons use). No-op
     * when the host supplied no [AppShellSpec.hotkeysContent] or the panel is
     * already open. Backs [AppShellHandle.openHotkeysSidebar].
     */
    fun openHotkeysSidebar() {
        if (spec.hotkeysContent == null) return
        if (isHotkeysSidebarOpen()) return
        when {
            isThemeManagerSidebarOpen() -> toggleThemeManagerSidebar {
                rerender()
                toggleHotkeysSidebar(::rerender)
            }
            isSettingsSidebarOpen() -> toggleSettingsSidebar {
                rerender()
                toggleHotkeysSidebar(::rerender)
            }
            isAppSettingsSidebarOpen() -> toggleAppSettingsSidebar {
                rerender()
                toggleHotkeysSidebar(::rerender)
            }
            isNotificationsSidebarOpen() -> toggleNotificationsSidebar {
                rerender()
                toggleHotkeysSidebar(::rerender)
            }
            else -> toggleHotkeysSidebar(::rerender)
        }
    }

    /**
     * Opens the Notifications sidebar, animating any other right-side panel
     * closed first (same mutually-exclusive hand-off the topbar buttons use).
     * No-op when the host supplied no [AppShellSpec.notificationsContent] or the
     * panel is already open. Backs [AppShellHandle.openNotificationsSidebar].
     */
    fun openNotificationsSidebar() {
        if (spec.notificationsContent == null) return
        if (isNotificationsSidebarOpen()) return
        when {
            isThemeManagerSidebarOpen() -> toggleThemeManagerSidebar {
                rerender()
                toggleNotificationsSidebar(::rerender)
            }
            isSettingsSidebarOpen() -> toggleSettingsSidebar {
                rerender()
                toggleNotificationsSidebar(::rerender)
            }
            isAppSettingsSidebarOpen() -> toggleAppSettingsSidebar {
                rerender()
                toggleNotificationsSidebar(::rerender)
            }
            isHotkeysSidebarOpen() -> toggleHotkeysSidebar {
                rerender()
                toggleNotificationsSidebar(::rerender)
            }
            else -> toggleNotificationsSidebar(::rerender)
        }
    }

    fun rerender() {
        val topSlot = topBarSlot ?: return
        val leftSlot = leftSidebarSlot ?: return
        val rightSlot = rightSidebarSlot ?: return
        val bottomSlot = bottomBarSlot ?: return
        val mainEl = main ?: return

        // Top bar: tab bar with new/close/rename + trailing actions.
        topSlot.innerHTML = ""
        topSlot.appendChild(buildTopBar())

        // Left sidebar — always mount via mountSidebarOrPlaceholder so a
        // collapsed sidebar still leaves a draggable hairline strip in
        // the DOM (the user can grab it to drag the sidebar back open).
        // Without the placeholder path, dragging the sidebar to zero
        // leaves no visible affordance and the only way back is the
        // topbar's sidebar-toggle button — which the user reasonably
        // expected (and previously had) the drag handle to also provide.
        //
        // Preserve the tab/pane tree's scroll position across the rebuild.
        // rerender() fires on state changes unrelated to the sidebar (in
        // termtastic every chunk of terminal output pushes a snapshot), and
        // each pass replaces the `.dt-sidebar-content` scroll container with a
        // freshly-built element — which would otherwise snap the list of
        // session windows back to the top on every output tick (issue #106).
        // Capture the old offset before the slot is cleared, then reapply it to
        // the rebuilt content wrapper (same tree structure → same scrollHeight,
        // so the offset stays valid).
        val prevLeftScrollTop =
            (leftSlot.querySelector(".dt-sidebar-content") as? HTMLElement)?.scrollTop
        leftSlot.innerHTML = ""
        // `showSidebar = false` skips the mount entirely — no sidebar, no
        // collapsed drag-to-restore placeholder. The slot stays empty so
        // the main area starts flush at the frame's left edge.
        if (spec.showSidebar) {
            val sidebarEl = leftSidebarController.mountSidebarOrPlaceholder(
                spec = SidebarSpec(
                    // App-supplied header / footer pinned above and below the
                    // scrollable tabs/panes tree (e.g. termtastic's logo at the
                    // top and its Claude-usage + update/news footer at the
                    // bottom). Both factories are re-invoked on every rerender;
                    // apps that cache the element get it re-parented intact.
                    header = spec.sidebarHeader?.invoke(),
                    content = buildLeftSidebarContent(),
                    footer = spec.sidebarFooter?.invoke(),
                    visible = true,
                    isResizable = true,
                    // Floor the drag at the resize handle's own width: the user
                    // can pull the bar "pretty much all the way" against the
                    // window edge but a thin grabbable strip always remains so
                    // the in-bar handle stays reachable. Going to 0 here would
                    // hand the bar off to the collapsed placeholder, whose
                    // handle bleeds 30–44 px in from the edge (CSS rule
                    // `.dt-sidebar-collapsed > .dt-sidebar-resize-handle-right`
                    // — needed to dodge the OS window resize gutter), which
                    // reads as the sidebar popping back out some distance from
                    // where the user released. Toggling the bar fully closed
                    // is still available via the topbar button.
                    minWidthPx = 8,
                    maxWidthPx = 600,
                ),
                onLeft = true,
                requestRebuild = ::rerender,
            )
            leftSlot.appendChild(sidebarEl)
            // Reapply the pre-rebuild scroll offset now that the new content
            // wrapper is attached and laid out (see the capture above, issue #106).
            if (prevLeftScrollTop != null) {
                (sidebarEl.querySelector(".dt-sidebar-content") as? HTMLElement)
                    ?.let { it.scrollTop = prevLeftScrollTop }
            }
        }

        // Right sidebar — either the theme manager OR the settings
        // sidebar. Mutual exclusion is enforced at the topbar-button
        // level (opening one closes the other). Each owns its own
        // slide-in animation in its `build*Sidebar` factory.
        rightSlot.innerHTML = ""
        if (isThemeManagerSidebarOpen()) {
            // Same `settingsHost ?: themeHost` ladder as the settings
            // sidebar below — apps that supply their own host (e.g.
            // termtastic's [TermtasticThemeManagerHost] backed by appVm)
            // need it here too, otherwise the editor's `activeLight =
            // !isDarkActive(host.appearance)` reads the toolkit's
            // internal themeState, which is never updated by the
            // chrome's appearance-cycle button. Result: clicking a card
            // assigns to the wrong slot, group ordering and selection
            // highlight stay locked to the host's startup appearance.
            rightSlot.appendChild(buildThemeManagerSidebar(spec.settingsHost ?: themeHost))
        } else if (isSettingsSidebarOpen()) {
            rightSlot.appendChild(buildSettingsSidebar(
                SettingsSidebarSpec(
                    host = spec.settingsHost ?: themeHost,
                    isElectron = spec.isElectron,
                    onOpenThemeManager = {
                        closeSettingsSidebar()
                        toggleThemeManagerSidebar(::rerender)
                    },
                )
            ))
        } else if (isAppSettingsSidebarOpen() && spec.appSettingsContent != null) {
            // App-supplied sidebar — the toolkit owns the chrome
            // (header, close, slide-in), the app supplies the body.
            // `appSettingsContent` is invoked here, on every right-slot
            // rerender while the sidebar is open, so apps can rebuild
            // against live state (matches the [SettingsSidebar] rebuild
            // pattern). When `spec.appSettingsContent` is null we
            // intentionally do NOT mount this branch — apps that
            // haven't opted in shouldn't be able to enter this state in
            // the first place (the topbar button is suppressed), but
            // the null-check is a belt-and-braces guard for hot reload
            // / config-flip races.
            val factory = spec.appSettingsContent
            rightSlot.appendChild(buildAppSettingsSidebar(
                AppSettingsSidebarSpec(
                    title = "App settings",
                    bodyFactory = { factory() },
                )
            ))
        } else if (isHotkeysSidebarOpen() && spec.hotkeysContent != null) {
            // App-supplied Hotkeys reference — same chrome contract as the
            // App Settings panel above. Opened programmatically via
            // [AppShellHandle.openHotkeysSidebar] (no topbar button); the
            // null-check guards hot-reload / config-flip races the same way.
            val factory = spec.hotkeysContent
            rightSlot.appendChild(buildHotkeysSidebar(
                HotkeysSidebarSpec(
                    title = "Keyboard shortcuts",
                    bodyFactory = { factory() },
                )
            ))
        } else if (isNotificationsSidebarOpen() && spec.notificationsContent != null) {
            // App-supplied Notifications list — same chrome contract as the
            // Hotkeys panel above. Opened programmatically via
            // [AppShellHandle.openNotificationsSidebar] from the host's own
            // alarm-bell button; the null-check guards hot-reload / config-flip
            // races the same way.
            val factory = spec.notificationsContent
            rightSlot.appendChild(buildNotificationsSidebar(
                NotificationsSidebarSpec(
                    title = "Notifications",
                    bodyFactory = { factory() },
                )
            ))
        }

        // Bottom bar — minimal status row. Apps carry their own
        // status content via the future `bottomBarContent` slots if
        // we add them; for now the bar exists with the toolkit's
        // standard chrome so apps that mount through `mountAppShell`
        // get the same visual baseline as notegrow / termtastic.
        // Apps that opt out via `spec.showBottomBar = false` (e.g.
        // termtastic, which relocated its footer status content into
        // the left sidebar) get an empty slot and no footer chrome.
        bottomSlot.innerHTML = ""
        if (spec.showBottomBar) {
            bottomSlot.appendChild(buildBottomBar())
        }

        // Chrome paint — stamp Pass-3 per-section vars on the freshly
        // built `.dt-topbar` / `.dt-sidebar` / `.dt-bottombar` BEFORE
        // the layout renderer kicks in below. The renderer's
        // `.render()` / `focusPane()` path can force a layout flush
        // mid-task; if Pass 3 hasn't stamped these elements yet, the
        // intermediate paint shows them with whatever vars they
        // inherit from `:root` (e.g. the main scheme's surface-base on
        // a `.dt-topbar` whose section is "tabs" with a *different*
        // surface). Combined with the 120ms transition on `.dt-tab` /
        // `.dt-topbar-icon-button`, the user sees the topbar animate
        // from the main scheme into the tabs scheme on every rerender
        // — visible as a flicker in the tab bar after a pane drag.
        // Painting here removes the intermediate state; the .dt-pane
        // paint still happens at the end (panes don't exist yet).
        val chromeDocEl = document.documentElement as? HTMLElement
        if (chromeDocEl != null) {
            val isDark = isDarkActive(snapshot.appearance)
            applyTheme(chromeDocEl, snapshot.resolve(isDark), isDark)
        }

        // Main: ensure a LayoutRenderer is mounted and re-render its panes.
        if (renderer == null) {
            renderer = LayoutRenderer(
                container = mainEl,
                callbacks = PaneCallbacks(
                    contentRenderer = { paneId, slot ->
                        // Re-mount the cached element rather than rebuilding
                        // it: hosts' pane bodies (notegrow's contenteditable,
                        // termtastic's xterm canvas) carry mutable state
                        // (caret, IME composition, scroll, selection,
                        // in-flight async loads) that does not survive a
                        // teardown. The cache is invalidated when the pane
                        // disappears from the snapshot — see
                        // [pruneStalePaneContentCache] in [rerender].
                        val body = getOrBuildPaneContent(paneId)
                        if (body.parentElement !== slot) {
                            slot.innerHTML = ""
                            slot.appendChild(body)
                        }
                    },
                    // Every focus event — user click or our programmatic
                    // [LayoutRenderer.focusPane] after a tab switch — records
                    // the active pane on the [LayoutController]. This is a
                    // pure UI signal (sidebar highlight, focus routing); it
                    // does not mutate paneOrder and does not re-tile. Layout
                    // importance is owned by the user via the sidebar's
                    // drag-to-reorder. The separate `onFloatingFocused` hook
                    // below handles the click-to-raise zIndex bump on top.
                    //
                    // Critically, this fires from the renderer's capture-phase
                    // mousedown handler, so it runs while the user is still
                    // pressing the mouse button. Going through the controller's
                    // standard `setActive` would fire its `onChange` callback,
                    // which is wired to `persistLayoutState(); rerender()` —
                    // and `rerender` would tear down every pane header (via
                    // the `applyHeaderOnlyUpdate` fast path) including the
                    // close button the user is in the middle of pressing,
                    // dropping the subsequent click event entirely. Use the
                    // quiet variant + a targeted sidebar repaint instead.
                    onPaneFocused = { paneId ->
                        viewActiveTabId()?.let { tabId ->
                            controllerFor(tabId).setActiveQuiet(paneId)
                            // Optimistic-focus hold: a genuine user focus
                            // gesture (this fires from the capture-phase pane
                            // mousedown and from spatial Ctrl+Opt+Arrow nav)
                            // runs ahead of the app's SetFocusedPane round-trip
                            // in source mode. Record it so
                            // [applyPendingActivePaneHold] pins this pane
                            // against the stale `activePaneId` that in-flight
                            // config pushes still carry. Skip when
                            // [reconcilingActivePane] — that focus event is us
                            // re-asserting the server/held value, not a new user
                            // choice, and recording it would block later
                            // out-of-band focus changes. Local mode has no
                            // round-trip, so there is nothing to hold.
                            if (!reconcilingActivePane && spec.tabSource != null) {
                                recordPendingActivePane(tabId, paneId)
                            }
                            persistLayoutState()
                            repaintSidebarActiveMark(tabId, paneId)
                        }
                    },
                    // Build the pane header so it carries the toolkit's
                    // standard leading icon — `spec.paneIcon` lets apps
                    // override per pane (notegrow uses a content-type
                    // glyph; the demo gets the default window glyph).
                    // The title comes from `FloatingPaneSpec.title`,
                    // which `buildPaneLayout` populates via
                    // `spec.paneLabel`.
                    paneHeader = { paneId, paneTitle ->
                        val activeTab = viewActiveTabId() ?: ""
                        // Forward `paneRename` as the lower-level
                        // `onRename`. Hover-arm stays opt-out (the spec's
                        // `armRenameOnHover` defaults false) so the host
                        // chooses the trigger surface — typically a
                        // kebab menu via `AppShellHandle.beginPaneRename`.
                        val renameCb: ((String) -> Unit)? = spec.paneRename?.let { cb ->
                            { newLabel -> cb(activeTab, paneId, newLabel) }
                        }
                        // Breadcrumb-mode title: when the host returns a
                        // non-empty segment list, the toolkit switches to
                        // clickable breadcrumbs and skips the RTL clip
                        // (each segment lays out left-to-right). Empty
                        // list keeps the plain-string title path, which
                        // is what the toolkit demo and termtastic want.
                        val titleSegments = spec.paneTitleSegments(activeTab, paneId)
                        PaneHeaderSpec(
                            title = paneTitle,
                            titleSegments = titleSegments,
                            titleAlignRight = titleSegments.isEmpty(),
                            leadingIcon = spec.paneIcon(activeTab, paneId),
                            leadingBadge = spec.paneHeaderBadge(activeTab, paneId),
                            actions = spec.paneActions(activeTab, paneId),
                            onRename = renameCb,
                            allowEmptyRename = spec.allowEmptyPaneRename,
                            paneIndex = spec.paneIndex(activeTab, paneId),
                        )
                    },
                    onFloatingMoved = { paneId, x, y ->
                        viewActiveTabId()?.let { tabId ->
                            controllerFor(tabId).markCustom()
                            updateGeometry(tabId, paneId) {
                                it.copy(xPct = x, yPct = y)
                            }
                            // Rerender so the layout's separator bars are
                            // recomputed against the new geometry. Without
                            // this, the bars remain at their pre-drag
                            // positions and the user can't grab the new
                            // edge — they'd have to refresh / re-render
                            // some other way to reach the new gap. Fires
                            // once at mouseup, not during the drag, so
                            // there's no cost to live tracking.
                            rerender()
                        }
                    },
                    onFloatingResized = { paneId, w, h ->
                        viewActiveTabId()?.let { tabId ->
                            controllerFor(tabId).markCustom()
                            updateGeometry(tabId, paneId) {
                                it.copy(widthPct = w, heightPct = h)
                            }
                            rerender()
                            // Notify the host so it can re-run any
                            // per-pane layout that measured the old
                            // container size (manual soft-wrap caches,
                            // virtualized lists, etc.). Fires after
                            // rerender so the new geometry is already
                            // applied to the DOM the host inspects.
                            spec.onGeometryChanged?.invoke(tabId)
                        }
                    },
                    onFloatingMaximizeToggled = { paneId ->
                        viewActiveTabId()?.let { tabId ->
                            val nowMaximized = !geometryFor(tabId, paneId).isMaximized
                            updateGeometry(tabId, paneId) {
                                it.copy(isMaximized = !it.isMaximized)
                            }
                            // Maximizing is a focus gesture: the pane is about
                            // to fill the screen, so it must also become the
                            // active, focused pane — same treatment as a dock
                            // restore ([onFloatingRestored]). Without this,
                            // maximizing a non-active pane left focus (and
                            // keystrokes) on a pane now hidden behind it.
                            // Un-maximizing keeps focus where it is.
                            if (nowMaximized) bringPaneToFront(tabId, paneId, raise = true)
                            // Maximize has no inline live-update path
                            // (drag/resize update CSS vars inline during the
                            // gesture; maximize toggles a class flip that
                            // only fires on rerender). Without rerender the
                            // button would appear to do nothing.
                            rerender()
                            spec.onGeometryChanged?.invoke(tabId)
                            // The rerender detached the focused pane's
                            // <textarea>, and this toolkit-local geometry
                            // toggle produces no host focus round-trip when the
                            // pane was already active — so hand DOM focus back
                            // to the active pane's content. See
                            // [LayoutRenderer.focusActivePaneContent].
                            renderer?.focusActivePaneContent()
                        }
                    },
                    // Auto-unmaximize when focus moves to a different pane.
                    // The renderer fires this from `focusPane` whenever the
                    // target pane isn't the one currently maximized — without
                    // it, hotkey nav (Ctrl+Alt+→) or sidebar pane clicks
                    // would silently move focus behind the maximized pane,
                    // leaving the user staring at the wrong content. The
                    // existing maximize/restore CSS transition animates the
                    // unmaximize.
                    onFloatingMaximizeCleared = { paneId ->
                        viewActiveTabId()?.let { tabId ->
                            updateGeometry(tabId, paneId) {
                                if (it.isMaximized) it.copy(isMaximized = false) else it
                            }
                            rerender()
                            spec.onGeometryChanged?.invoke(tabId)
                        }
                    },
                    // Close affordance — toolkit owns the confirmation dialog.
                    // Local mode: drop the pane from the active tab's pane
                    // list (and the tab too if it was the last pane). Source
                    // mode: forward to the host via TabSource (but apps can
                    // opt out by leaving onClose null in their TabSource).
                    onFloatingClosed = { paneId ->
                        val src = spec.tabSource
                        if (src != null) {
                            // Source mode: forward to the host's close
                            // hook. The host drops the pane from its
                            // model and re-pushes a snapshot, which the
                            // toolkit re-renders. The cached pane content
                            // is freed by [pruneStalePaneContentCache]
                            // on the next [rerender].
                            val tabId = lastSnapshot.tabs.firstOrNull { tab ->
                                tab.panes.any { it.id == paneId }
                            }?.id
                            if (tabId != null) src.onPaneClose?.invoke(tabId, paneId)
                        } else {
                            removeLocalPane(paneId)
                        }
                    },
                    // Minimize: flip the pane's geometry `isMinimized` flag
                    // (un-maximizing first so a maximized pane docks as a
                    // plain title bar, not a flagged-but-hidden full-bleed
                    // pane). The renderer drops it from the layout and parks
                    // a chip in the dock; on an auto/preset layout the
                    // remaining panes re-tile. Persisted through
                    // `persistLayoutState` (via `updateGeometry`) into the
                    // LAYOUT_STATE blob — same path as maximize/position/size.
                    onFloatingMinimized = { paneId ->
                        viewActiveTabId()?.let { tabId ->
                            updateGeometry(tabId, paneId) {
                                it.copy(isMinimized = true, isMaximized = false)
                            }
                            reflowAfterMinimizeChange(tabId)
                            // Docking the focused pane hands active status to a
                            // visible sibling ([activePaneForActiveTab] excludes
                            // minimized panes); the reflow above moved the ring
                            // to it but not DOM focus. Focus its content so
                            // keystrokes follow. See
                            // [LayoutRenderer.focusActivePaneContent].
                            renderer?.focusActivePaneContent()
                        }
                    },
                    // Restore from the dock: clear `isMinimized`; the pane
                    // re-enters the layout at its preserved geometry and the
                    // remaining panes re-tile under a non-Custom preset.
                    // Then bring it to the front and make it the active pane
                    // — restoring from the dock should land the user on the
                    // pane they just un-minimized, matching the sidebar row
                    // restore.
                    onFloatingRestored = { paneId ->
                        viewActiveTabId()?.let { tabId ->
                            updateGeometry(tabId, paneId) {
                                it.copy(isMinimized = false)
                            }
                            bringPaneToFront(tabId, paneId, raise = true)
                            // Surface the pane past a full-bleed maximized
                            // sibling — under Custom nothing re-tiles, so
                            // without this the restored pane lands behind
                            // it. Same sweep as the sidebar-row restore.
                            clearMaximizedSiblings(tabId, paneId)
                            reflowAfterMinimizeChange(tabId)
                            // Land the user on the pane they just un-minimized:
                            // bringPaneToFront already made it active, and the
                            // reflow re-rendered it into the layout — now give
                            // its content real DOM focus. See
                            // [LayoutRenderer.focusActivePaneContent].
                            renderer?.focusActivePaneContent()
                        }
                    },
                    onFloatingFocused = { paneId ->
                        // Raise: bump the pane's zIndex to max+1, and mark
                        // the pane as active on the [LayoutController] so
                        // the sidebar highlight and any focus-aware UI
                        // pick it up. Active pane is decoupled from layout
                        // importance — paneOrder is only mutated by the
                        // sidebar drag-to-reorder, never by focus.
                        //
                        // Critically, we do NOT call `rerender()` here.
                        // A rerender would rebuild every pane element,
                        // which (a) is unnecessary for a pure z-order
                        // change and (b) interferes with any in-flight
                        // gesture whose handlers reference the original
                        // DOM nodes. Instead, update the live element's
                        // `--dt-fp-z` CSS var directly — z-index isn't
                        // transitioned, so the restack is instant.
                        viewActiveTabId()?.let { tabId ->
                            controllerFor(tabId).setActive(paneId)
                            val current = geometryState.geometryByTab[tabId].orEmpty()
                            val maxZ = current.values.maxOfOrNull { it.zIndex } ?: 0
                            val newZ = maxZ + 1
                            updateGeometry(tabId, paneId) { it.copy(zIndex = newZ) }
                            val live = main?.querySelector("[data-pane-id=\"$paneId\"]") as? HTMLElement
                            live?.style?.setProperty("--dt-fp-z", "$newZ")
                        }
                    },
                    // Confirm before closing a pane. The toolkit's
                    // `confirmClosePane` shows an accent-styled (non-
                    // destructive) dialog citing the pane title. Apps that
                    // wrap their own confirmation around the pane-close
                    // gesture (e.g. an unsaved-changes dialog shown from
                    // TabSource.onPaneClose) opt out via the spec.
                    confirmFloatingClose = spec.confirmPaneClose,
                ),
            )
        }
        // Free any pane content elements whose pane disappeared from the
        // current snapshot so closed panes don't pin their host DOM.
        pruneStalePaneContentCache()
        // Pass the active tab id as the renderer's context key so a
        // tab switch is recognised as a fresh-render boundary —
        // otherwise the renderer would animate every pane in the new
        // tab as a "newly added" pop-in.
        renderer!!.render(buildPaneLayout(), contextKey = viewActiveTabId())
        // Make sure exactly one pane in the active tab is the focused
        // one — on initial mount and on every tab switch. The renderer
        // auto-falls-back its internal focus to the first visible pane
        // on render, but does NOT fire `onPaneFocused` for that, so the
        // [LayoutController]'s paneOrder would never see programmatic
        // focus. Calling [LayoutRenderer.focusPane] explicitly fires the
        // callback and bubbles the active pane to the head of paneOrder
        // — so the next preset apply puts it in slot 0.
        //
        // `autoUnmaximize = false`: this is reconciliation, not a user
        // focus move. The host's source-mode `activePaneId` can lag the
        // controller's (the controller is updated via `setActiveQuiet`
        // on every pane mousedown so mid-gesture rerenders don't fire,
        // but no snapshot push goes back to the host); the default
        // `true` would let that stale id immediately un-maximize the
        // pane the user just toggled to maximized on the click that
        // followed the mousedown. See `LayoutRenderer.focusPane`.
        // Re-assert the server/held active pane. Flagged as reconciliation so
        // the `onPaneFocused` this fires does NOT capture it as a new optimistic
        // pending (see [reconcilingActivePane] / [pendingActivePaneId]).
        reconcilingActivePane = true
        try {
            activePaneForActiveTab()?.let { renderer!!.focusPane(it, autoUnmaximize = false) }
        } finally {
            reconcilingActivePane = false
        }
        // Theme repaint. Every rerender wipes & rebuilds the chrome slots
        // (`topSlot.innerHTML = ""`, ditto sidebar / bottom). Re-stamp the
        // flat `--t-*` palette on `:root` here so the freshly built chrome
        // resolves its `var(--t-*)` references against the active theme on
        // its first frame; this keeps `rerender` self-consistent so callers
        // don't need to remember to repaint after.
        val docEl = document.documentElement as? HTMLElement
        if (docEl != null) {
            val isDark = isDarkActive(snapshot.appearance)
            applyTheme(docEl, snapshot.resolve(isDark), isDark)
        }

        // Host-side paint reapply. The toolkit's Pass 3 above only
        // covers the section selectors it owns (`.dt-pane`, `.dt-sidebar`,
        // `.dt-topbar`, `.dt-bottombar`). Hosts that layer additional
        // per-section paint on top — termtastic stamps `terminal` /
        // `fileBrowser` / `git` palettes onto `.terminal-cell > .terminal`
        // and friends — need a hook here, because the slot rebuild a
        // few lines up replaced the host's previously-stamped elements
        // with fresh DOM that has no inline overrides. Fires last so
        // the host's paint wins over anything the toolkit just set.
        spec.onAfterRefresh?.invoke()
        // Test seam: fires after every completed render pass so tests can
        // snapshot the just-painted chrome (e.g. the sidebar pane-row order)
        // on each pass and detect transient churn across a multi-pass update.
        // No-op in production (never assigned outside tests).
        onAppShellRenderedForTest?.invoke()
    }

    /**
     * Resolves which pane should hold focus in the active tab.
     *
     * Source mode: prefer a live optimistic-focus hold
     * ([pendingActivePaneId]) over the snapshot's `activePaneId`, then the
     * snapshot's `activePaneId`. Otherwise (and in local mode) fall back to
     * the [LayoutController.activePaneId] (the last pane the user clicked /
     * activated), then to the first pane in the user's importance order,
     * then to the first visible pane. Returns `null` only when the active
     * tab has no panes at all.
     *
     * The hold must win over the snapshot: [applyPendingActivePaneHold]
     * only rewrites `activePaneId` on snapshots as they ARRIVE, so between
     * pushes [external] still carries the pre-gesture pane. A local
     * [rerender] in that window — e.g. the one [LayoutCallbacks
     * .onFloatingMaximizeToggled] fires right after [bringPaneToFront]
     * recorded the hold — would otherwise reconcile focus back to the stale
     * pane, visibly flashing the old pane's header active for the length of
     * the host round-trip before the confirming snapshot flips it again.
     * Same liveness rules as the snapshot path: an expired hold or one whose
     * pane is no longer visible is ignored (not cleared — snapshot arrival
     * owns the lifecycle; see [applyPendingActivePaneHold]).
     *
     * Note: this is a UI / focus-routing signal — it does not influence
     * layout. Layout slots are assigned from [LayoutController.paneOrder]
     * which the user controls explicitly via the sidebar drag-to-reorder.
     */
    private fun activePaneForActiveTab(): String? {
        val tabId = viewActiveTabId() ?: return null
        // Exclude minimized panes: a docked pane is not in the layout, so it
        // must never be resolved as the active/focused pane. (Local-mode
        // `viewPanesIn` already drops minimized; source mode does not, so
        // filter explicitly so a host-reported active pane that's been
        // minimized hands off to a visible sibling.)
        val visibleIds = viewPanesIn(tabId)
            .map { it.id }
            .filterNot { geometryFor(tabId, it).isMinimized }
        if (visibleIds.isEmpty()) return null
        pendingActivePaneId[tabId]?.let { hold ->
            val live = kotlin.js.Date.now() - hold.seededAtMs <= PENDING_ACTIVE_PANE_EXPIRY_MS
            if (live && hold.paneId in visibleIds) return hold.paneId
        }
        val sourceActive = external?.tabs?.firstOrNull { it.id == tabId }?.activePaneId
        if (sourceActive != null && sourceActive in visibleIds) return sourceActive
        val ctl = controllerFor(tabId)
        ctl.activePaneId?.takeIf { it in visibleIds }?.let { return it }
        return ctl.paneOrder.firstOrNull { it in visibleIds } ?: visibleIds.firstOrNull()
    }

    // ── Unified view helpers ────────────────────────────────────────

    private data class TabView(
        val id: String,
        val label: String,
        val activePaneId: String?,
        val isHidden: Boolean = false,
        val isHiddenFromSidebar: Boolean = false,
        /**
         * Declarative badge pushed with the snapshot. Always `null` in
         * local mode: the local tab list is the toolkit's own persisted
         * state and has no unread model to report — hosts with one are
         * source-mode hosts by definition.
         */
        val badge: TabBadge? = null,
    )
    private data class PaneView(val tabId: String, val id: String)

    private fun viewTabs(): List<TabView> = when {
        external != null -> external!!.tabs.map {
            TabView(
                id = it.id,
                label = it.label,
                activePaneId = it.activePaneId,
                isHidden = it.isHidden,
                isHiddenFromSidebar = it.isHiddenFromSidebar,
                badge = it.badge,
            )
        }
        local != null -> local!!.tabs.map { id ->
            TabView(
                id = id,
                label = local!!.tabLabels[id] ?: id,
                activePaneId = null,
                isHidden = id in local!!.tabsHidden,
                isHiddenFromSidebar = id in local!!.tabsHiddenFromSidebar,
            )
        }
        else -> emptyList()
    }

    private fun viewActiveTabId(): String? =
        external?.activeTabId ?: local?.activeTabId

    private fun viewPanesIn(tabId: String): List<PaneView> = when {
        external != null -> external!!.tabs.firstOrNull { it.id == tabId }
            ?.panes.orEmpty().map { PaneView(tabId, it.id) }
        local != null -> local!!.panesByTab[tabId].orEmpty()
            .filterNot { geometryFor(tabId, it.id).isMinimized }
            .map { PaneView(tabId, it.id) }
        else -> emptyList()
    }

    private fun buildTopBar(): HTMLElement {
        val view = viewTabs()
        val activeId = viewActiveTabId()
        val srcMode = spec.tabSource != null
        // An app-defined strip is only expressible through a TabSource —
        // the local tab list IS the user's own persisted arrangement, so
        // there is nothing for a local-mode host to have declared. That
        // also means this whole branch is unreachable for every consumer
        // that predates the flag.
        val fixedStrip = spec.tabSource?.isFixed == true

        val tabs = view.map { t ->
            TabSpec(
                id = t.id,
                label = t.label,
                // No inline × close button on tabs themselves — close lives
                // in the tab's own `⋮` dot menu (alongside Rename, Hide,
                // etc.; see appendTabDotMenu). One canonical place to close
                // a tab keeps the affordance discoverable without the visual
                // clutter of a per-tab cross.
                isClosable = false,
                isDraggable = !fixedStrip && (if (srcMode) spec.tabSource!!.onReorder != null else true),
                isRenamable = !fixedStrip && (if (srcMode) spec.tabSource!!.onRename != null else true),
                trailingBadge = spec.tabTrailingBadge(t.id),
                badge = t.badge,
                isHidden = t.isHidden,
                isHiddenFromSidebar = t.isHiddenFromSidebar,
            )
        }
        val callbacks = if (srcMode) {
            val src = spec.tabSource!!
            TabBarCallbacks(
                onSelect = { id ->
                    // Optimistic local activation: reflect the switch in the
                    // tab strip and swap the rendered pane layout IMMEDIATELY
                    // rather than waiting for the app's async round-trip to
                    // push a fresh snapshot back. Source-mode activation is
                    // app-owned and typically server-round-tripped; without
                    // this, a fast burst of tab-switch key presses shows
                    // nothing until each echo lands, which reads as flicker /
                    // a switch that "doesn't take". The next pushed snapshot
                    // (from bindTabSource) reconciles this value, so a
                    // rejected activation self-heals on the following push.
                    val snap = external
                    if (snap != null && snap.activeTabId != id && snap.tabs.any { it.id == id }) {
                        pendingActiveTabId = id
                        external = snap.copy(activeTabId = id)
                        rerender()
                    }
                    src.onSelect(id)
                },
                // Tab-mutation callbacks are dropped outright on a fixed
                // strip. TabBar re-checks the flag before rendering each
                // affordance, so this is belt-and-braces there — but it is
                // load-bearing for the surfaces OUTSIDE the strip that read
                // these callbacks to decide what to offer: the topbar "New"
                // (`+`) split-button asks `callbacks.onAdd != null` for its
                // "New tab" row, and would otherwise put back the very
                // gesture the flag exists to withhold.
                onClose = if (fixedStrip) null else src.onClose,
                onAdd = if (fixedStrip) null else src.onAdd,
                onRename = if (fixedStrip) null else src.onRename,
                onReorder = if (fixedStrip) null else src.onReorder,
                // Source-mode tabs are app-owned, so the toolkit forwards
                // the visibility toggles to the host's TabSource. The host
                // mutates its own model (and persists / pushes upstream as
                // appropriate) and sends a fresh snapshot back; we never
                // touch tab visibility locally in this branch. When the
                // host opts out by leaving these callbacks null, the
                // overflow menu omits the corresponding rows.
                onSetHidden = if (fixedStrip) null else src.onSetHidden,
                onSetHiddenFromSidebar = if (fixedStrip) null else src.onSetHiddenFromSidebar,
                // Confirm before closing a tab from the kebab. With the
                // inline × button removed, the kebab "Close" row is the
                // only entry point so a single confirmation is enough.
                confirmTabClose = true,
                // "Move to world" submenu: every world except the active one,
                // wired to the host's WorldSource. Empty (no submenu) when the
                // host doesn't support world moves or there's only one world.
                // "Move to world" relocates a tab out of the set the app
                // declared, so it is an edit of that set and goes with the
                // rest of them on a fixed strip.
                moveToWorlds = if (!fixedStrip && spec.worldSource?.onMoveTab != null) {
                    worldSnapshot?.let { snap -> snap.worlds.filter { it.id != snap.activeWorldId } }
                        ?: emptyList()
                } else {
                    emptyList()
                },
                onMoveToWorld = if (fixedStrip) null else spec.worldSource?.onMoveTab,
            )
        } else {
            TabBarCallbacks(
                onSelect = { id -> mutateLocal { it.copy(activeTabId = id) } },
                onClose = { id -> mutateLocal { current ->
                    val nextTabs = current.tabs - id
                    // When closing the active tab, prefer promoting a
                    // *listed* tab (one not hidden from the strip) so the
                    // user isn't dropped onto content with no visible strip
                    // entry; fall back to any surviving tab only when every
                    // remaining tab is unlisted (issue #88 in termtastic).
                    val nextActive = if (current.activeTabId == id) {
                        nextTabs.firstOrNull { it !in current.tabsHidden } ?: nextTabs.firstOrNull()
                    } else {
                        current.activeTabId
                    }
                    current.copy(
                        tabs = nextTabs,
                        tabLabels = current.tabLabels - id,
                        activeTabId = nextActive,
                        panesByTab = current.panesByTab - id,
                        tabsHidden = current.tabsHidden - id,
                        tabsHiddenFromSidebar = current.tabsHiddenFromSidebar - id,
                    )
                } },
                onAdd = {
                    mutateLocal { current ->
                        val newId = nextLocalTabId(current)
                        val newPaneId = "$newId-pane-1"
                        current.copy(
                            tabs = current.tabs + newId,
                            tabLabels = current.tabLabels + (newId to "Untitled"),
                            activeTabId = newId,
                            panesByTab = current.panesByTab + (newId to listOf(
                                PersistedPane(id = newPaneId)
                            )),
                        )
                    }
                },
                onReorder = { sourceId, targetId, before ->
                    mutateLocal { current ->
                        val without = current.tabs - sourceId
                        val targetIdx = without.indexOf(targetId).coerceAtLeast(0)
                        val insertAt = if (before) targetIdx else targetIdx + 1
                        val rebuilt = without.toMutableList().also { it.add(insertAt, sourceId) }
                        current.copy(tabs = rebuilt)
                    }
                },
                onRename = { id, label ->
                    mutateLocal { it.copy(tabLabels = it.tabLabels + (id to label)) }
                },
                onSetHidden = { id, hidden ->
                    mutateLocal { current ->
                        val nextHidden = if (hidden) current.tabsHidden + id else current.tabsHidden - id
                        // If hiding the active tab, advance activation to
                        // the next visible tab so the user always has a
                        // selection.
                        val activeStillVisible = current.activeTabId !in nextHidden
                        val nextActive = if (activeStillVisible) {
                            current.activeTabId
                        } else {
                            current.tabs.firstOrNull { it !in nextHidden } ?: current.activeTabId
                        }
                        current.copy(tabsHidden = nextHidden, activeTabId = nextActive)
                    }
                },
                onSetHiddenFromSidebar = { id, hidden ->
                    mutateLocal { current ->
                        val next = if (hidden) {
                            current.tabsHiddenFromSidebar + id
                        } else {
                            current.tabsHiddenFromSidebar - id
                        }
                        current.copy(tabsHiddenFromSidebar = next)
                    }
                },
                // Confirm before closing a tab from the kebab. With the
                // inline × button removed, the kebab "Close" row is the
                // only entry point so a single confirmation is enough.
                confirmTabClose = true,
            )
        }
        val tabBarSpec = TabBarSpec(
            tabs = tabs,
            activeTabId = activeId,
            // Hide the inline `+` add button — "New tab" lives in the
            // trailing "New" (`+`) menu now (issue #65).
            showAddButton = false,
            // Per-tab actions (Rename, Close, Hide/Show in tab bar,
            // Hide/Show in side bar) live in each tab's own dot menu at
            // its right corner. The far-right `⋮` overflow menu now holds
            // only the list of currently-hidden ("Unlisted") tabs to
            // re-activate / un-hide, and renders only when some exist.
            // Toolkit owns all of this rendering.
            showOverflowMenu = true,
            callbacks = callbacks,
            // App-defined strip: TabBar withholds the add button, the dot
            // menus, drag-reorder, inline rename and the overflow list, and
            // paints the lighter pill treatment.
            isFixed = fixedStrip,
        )

        // Leading: sidebar-toggle button to the LEFT of the tab strip
        // (matching termtastic / notegrow chrome layout). Omitted when
        // the app opts out of the sidebar entirely (`showSidebar =
        // false`) — a toggle for a bar that never mounts is dead chrome.
        val leading = document.createElement("div") as HTMLElement
        leading.style.display = "flex"
        leading.style.alignItems = "center"
        if (spec.showSidebar) {
            leading.appendChild(
                buildLeftSidebarToggleButton(
                    isOpen = leftSidebarController.isOpen,
                    onToggle = { setSidebarOpen(!leftSidebarController.isOpen) },
                )
            )
        }
        // World switcher globe — only when the app wires a world source and
        // has pushed at least one world. Sits right of the sidebar toggle
        // and left of the tab strip (the tab strip is the TopBarSpec.tabBar,
        // rendered after this leading cluster).
        spec.worldSource?.let { worldSource ->
            worldSnapshot?.takeIf { it.worlds.isNotEmpty() }?.let { worlds ->
                leading.appendChild(buildWorldSwitcher(worlds, worldSource))
            }
        }
        // App content on the leading edge, after the toolkit's own chrome and
        // before the tab strip. Everything above this point is toolkit-owned
        // and both parts are opt-out, so an app with no sidebar and no world
        // source had an empty left edge and no slot to fill it — see
        // AppShellSpec.topbarLeading.
        spec.topbarLeading?.invoke()?.let { leading.appendChild(it) }

        // Trailing slot order:
        //   spec.extraTopbarBeforeStandard …  ‖  NewPane · Layout · ThemeToggle · ThemeMgr  ‖  spec.extraTopbarTrailing …
        // The two ‖ markers are non-interactive `dt-topbar-trailing-divider`
        // spans rendered when both flanks are non-empty, giving the
        // standard cluster a small breathing margin from app-supplied
        // extras. The flex `gap: 4px` already separates adjacent
        // buttons; the divider adds an extra ~8px to make the boundary
        // visually distinct.
        val trailing = document.createElement("div") as HTMLElement
        trailing.style.display = "flex"
        trailing.style.alignItems = "center"
        trailing.style.setProperty("gap", "4px")

        for (extra in spec.extraTopbarBeforeStandard) {
            val node = extra.element
                ?: buildActionButtonHtml(extra.id, extra.iconHtml, extra.label, extra.onActivate)
            trailing.appendChild(node)
        }
        if (spec.extraTopbarBeforeStandard.isNotEmpty()) {
            trailing.appendChild(buildTopbarTrailingDivider())
        }

        // LayoutDropdown (vs the older buildLayoutPresetButton) gives us
        // the toolkit-canonical preset list — `LayoutPreset.DROPDOWN_ORDER`
        // — which leads with `Auto` and renders it with the magic-wand
        // glyph so users can pick "let the toolkit pick" without scanning
        // the geometry miniatures. The older button skipped Auto's
        // dedicated tile and always rendered raw geometry, which read as
        // a noise tile in the grid.
        val layoutDropdown = LayoutDropdown(
            paneCount = { activePaneCountForPresets() },
            onSelect = { preset -> applyLayoutPreset(preset) },
            activePreset = {
                viewActiveTabId()?.let { controllerFor(it).activePreset }
            },
        )
        // Trailing order: the "+" New button sits BEFORE the Layout button
        // (appended just below), so the layout dropdown's append is deferred.
        // The trailing "+" button is the general "New" menu (issue #65).
        // It is always a split-button: clicking the icon adds a pane to the
        // active tab, while the hover dropdown leads with "New tab" and then
        // lists whatever pane flavours the host supplies via
        // [TabSource.paneAddMenuItems] (termtastic: Terminal / Terminal link
        // / File Browser / Git — i.e. "New terminal window", etc.). Painted
        // with the plain-plus [ICON_NEW_TAB] glyph so it reads like the
        // Android/iOS overview `+`, not the old window-with-`+` mark. Hosts
        // with no pane flavours still get the plus + a "New tab" dropdown.
        // (Per-tab actions live in each tab's own dot menu now — see
        // TabBar.kt / TabBarOverflowMenu.kt.)
        val paneAddItemsProvider = spec.tabSource?.paneAddMenuItems
        // Whether the "+" button can currently DO anything: a default click
        // action, a "New tab"/"New workspace" row, or at least one host-
        // supplied menu item for the active tab. When none of these hold,
        // the button is omitted entirely rather than rendered dead — a "+"
        // whose hover menu is empty and whose click is a no-op reads as
        // broken, not as disabled. Evaluated on every topbar rebuild, so a
        // host whose item list is permission-gated (lunicle: New issue /
        // New project appear on sign-in) gets the button back on the
        // rerender that follows the permission change. The provider is a
        // cheap pure read by its own contract ("evaluated every time the
        // menu opens"), so probing it here adds nothing measurable.
        // The rule itself is [shouldShowNewPaneButton], which is pure so it
        // can be tested without a shell; everything here is the reading of
        // the live state it needs.
        val activeTabIdForAdd = external?.activeTabId ?: viewActiveTabId()
        val newButtonHasAnyAction = shouldShowNewPaneButton(
            hasNewTabAction = callbacks.onAdd != null,
            hasPaneAddAction = spec.tabSource?.onPaneAdd != null,
            hasNewWorldAction = spec.worldSource?.onAdd != null,
            describesPaneAddMenu = paneAddItemsProvider != null,
            paneAddItemCount = activeTabIdForAdd
                ?.let { paneAddItemsProvider?.invoke(it)?.size }
                ?: 0,
        )
        val newPaneButton = buildNewWindowSplitButton(
            tooltip = "New",
            iconHtml = ICON_NEW_TAB,
            items = {
                // "New tab" first, then the active tab's pane flavours,
                // then a "New world" entry when a world source with an
                // onAdd callback is wired (issue: Worlds).
                val newTabRow = callbacks.onAdd?.let { onAdd ->
                    listOf(HoverMenuItem("new-tab", "New tab", ICON_NEW_TAB) { onAdd() })
                } ?: emptyList()
                val activeTabId = external?.activeTabId ?: viewActiveTabId()
                val paneRows = if (paneAddItemsProvider == null || activeTabId == null) {
                    emptyList()
                } else {
                    paneAddItemsProvider(activeTabId).map { item ->
                        HoverMenuItem(item.id, item.label, item.iconHtml, onSelect = item.onSelect)
                    }
                }
                val worldAdd = spec.worldSource?.onAdd
                val newWorldRow = if (worldAdd != null) {
                    listOf(
                        HoverMenuItem("new-world", "New workspace", ICON_GLOBE) {
                            promptNewWorldName(worldAdd)
                        },
                    )
                } else {
                    emptyList()
                }
                // Group the menu with dividers: "New tab" | pane creators |
                // "New workspace". A single separator sits under "New tab" and
                // above "New workspace"; when there are pane rows between them,
                // each boundary gets its own separator. Empty groups add none,
                // so we never render a leading/trailing/doubled divider.
                buildList {
                    addAll(newTabRow)
                    if (newTabRow.isNotEmpty() && (paneRows.isNotEmpty() || newWorldRow.isNotEmpty())) {
                        add(hoverMenuSeparator("sep-after-new-tab"))
                    }
                    addAll(paneRows)
                    if (newWorldRow.isNotEmpty() && paneRows.isNotEmpty()) {
                        add(hoverMenuSeparator("sep-before-new-world"))
                    }
                    addAll(newWorldRow)
                }
            },
            onDefaultClick = { addPaneToActiveTab() },
        )
        if (newButtonHasAnyAction) trailing.appendChild(newPaneButton)
        trailing.appendChild(layoutDropdown.triggerButton)
        // Three-state cycle: Auto → Dark → Light → Auto. Helper paints
        // the per-state SVG (sun / moon / half-disc) so the icon
        // reflects the current appearance every rerender.
        trailing.appendChild(
            buildAppearanceCycleButton(
                appearance = snapshot.appearance,
                onCycle = {
                    val cur = snapshot.appearance
                    val next = when (cur) {
                        Appearance.Auto -> Appearance.Dark
                        Appearance.Dark -> Appearance.Light
                        Appearance.Light -> Appearance.Auto
                    }
                    // Flip the appearance on the stored snapshot; [resolve]
                    // picks the slot theme for the new appearance from the
                    // snapshot's own slot bindings (built-ins ∪ custom),
                    // falling back to the slot default when unbound.
                    val flipped = snapshot.copy(appearance = next)
                    applyThemeSnapshot(flipped)
                    // Pass [flipped] explicitly rather than relying on
                    // [persistUi] reading `this.snapshot`. The synchronous
                    // [applyThemeSnapshot] above runs `rerender()`, which
                    // fires `spec.onAfterRefresh`; hosts that bridge the
                    // toolkit's `setThemeSnapshot` back into their own state
                    // read from their backing model — which still holds the
                    // *previous* appearance at this point. That bridge call
                    // lands `syncThemeFromHost(staleSnapshot)` which would
                    // overwrite `this.snapshot` back to the previous value, so
                    // we persist the explicit `flipped` instead.
                    persistUi(flipped)
                },
            )
        )
        trailing.appendChild(
            buildThemeManagerButton(
                isOpen = isThemeManagerSidebarOpen(),
                onToggle = {
                    // Mutually exclusive with the other two right-side
                    // panels. If either Settings or App Settings is
                    // currently open, animate it closed first; the
                    // close-transition completion then opens the theme
                    // manager. Without the chained call, we'd either
                    // snap-close the open panel (no animation) or end
                    // up briefly painting two panels.
                    when {
                        isSettingsSidebarOpen() -> toggleSettingsSidebar {
                            rerender()
                            toggleThemeManagerSidebar(::rerender)
                        }
                        isAppSettingsSidebarOpen() -> toggleAppSettingsSidebar {
                            rerender()
                            toggleThemeManagerSidebar(::rerender)
                        }
                        isHotkeysSidebarOpen() -> toggleHotkeysSidebar {
                            rerender()
                            toggleThemeManagerSidebar(::rerender)
                        }
                        isNotificationsSidebarOpen() -> toggleNotificationsSidebar {
                            rerender()
                            toggleThemeManagerSidebar(::rerender)
                        }
                        else -> toggleThemeManagerSidebar(::rerender)
                    }
                },
            )
        )
        // Settings gear — always present. Defaults to the toolkit's
        // managed theme host so any app using `mountAppShell` gets the
        // settings sidebar for free; apps with their own host pass it
        // through `AppShellSpec.settingsHost` to override.
        trailing.appendChild(
            buildSettingsGearButton(
                isOpen = isSettingsSidebarOpen(),
                onToggle = {
                    // Same animated hand-off as the theme button: if
                    // either sibling sidebar is open, ride its close
                    // transition before opening settings.
                    when {
                        isThemeManagerSidebarOpen() -> toggleThemeManagerSidebar {
                            rerender()
                            toggleSettingsSidebar(::rerender)
                        }
                        isAppSettingsSidebarOpen() -> toggleAppSettingsSidebar {
                            rerender()
                            toggleSettingsSidebar(::rerender)
                        }
                        isHotkeysSidebarOpen() -> toggleHotkeysSidebar {
                            rerender()
                            toggleSettingsSidebar(::rerender)
                        }
                        isNotificationsSidebarOpen() -> toggleNotificationsSidebar {
                            rerender()
                            toggleSettingsSidebar(::rerender)
                        }
                        else -> toggleSettingsSidebar(::rerender)
                    }
                },
            )
        )
        // App Settings — host-supplied content, or a host-supplied action.
        // Suppressed entirely when the app asked for neither, so apps that
        // haven't opted in don't get a phantom icon, and suppressed again
        // when `isAppSettingsAvailable` says no (a permission-gated settings
        // surface: omitted, not disabled). Sits immediately to the right of
        // the Appearance gear so the toolkit's appearance-related cluster
        // (theme / appearance / app-settings) reads as a left-to-right
        // grouping.
        //
        // `onAppSettingsActivate` wins over `appSettingsContent` when both
        // are set: the app has told us where its settings live, and opening
        // a sidebar as well would put the same settings in two places.
        val appSettingsActivate = spec.onAppSettingsActivate
        val wantsAppSettings = spec.appSettingsContent != null || appSettingsActivate != null
        if (wantsAppSettings && spec.isAppSettingsAvailable?.invoke() != false) {
            trailing.appendChild(
                buildAppSettingsButton(
                    // Nothing to reflect when the app owns the surface — there
                    // is no toolkit sidebar whose open state this could track.
                    isOpen = appSettingsActivate == null && isAppSettingsSidebarOpen(),
                    onToggle = appSettingsActivate ?: {
                        when {
                            isThemeManagerSidebarOpen() -> toggleThemeManagerSidebar {
                                rerender()
                                toggleAppSettingsSidebar(::rerender)
                            }
                            isSettingsSidebarOpen() -> toggleSettingsSidebar {
                                rerender()
                                toggleAppSettingsSidebar(::rerender)
                            }
                            isHotkeysSidebarOpen() -> toggleHotkeysSidebar {
                                rerender()
                                toggleAppSettingsSidebar(::rerender)
                            }
                            isNotificationsSidebarOpen() -> toggleNotificationsSidebar {
                                rerender()
                                toggleAppSettingsSidebar(::rerender)
                            }
                            else -> toggleAppSettingsSidebar(::rerender)
                        }
                    },
                )
            )
        }
        if (spec.extraTopbarTrailing.isNotEmpty()) {
            trailing.appendChild(buildTopbarTrailingDivider())
        }
        for (extra in spec.extraTopbarTrailing) {
            val node = extra.element
                ?: buildActionButtonHtml(extra.id, extra.iconHtml, extra.label, extra.onActivate)
            trailing.appendChild(node)
        }

        // Route through topBarController so the snap-to-zero gesture
        // works: on drag release below `defaultHeightPx / 2` the
        // controller flips its `isVisible` flag, calls rerender, and the
        // next mount swaps the full bar for a chromeless placeholder
        // whose only affordance is the resize handle (drag back down to
        // restore). Without `defaultHeightPx`, the snap rule in
        // `attachBarVerticalResizeHandle` is dead and the bar can only
        // shrink to its raw release height — never collapse cleanly.
        return topBarController.mountTopBar(
            TopBarSpec(
                leadingContent = leading,
                // `showTabStrip = false` hides the whole strip: the middle
                // slot renders empty while the tab model (and its panes)
                // stays fully live. The TabBarCallbacks above are still
                // built — the "+" split-button menu reuses them.
                tabBar = if (spec.showTabStrip) tabBarSpec else null,
                // App content for the empty middle slot, centered between the
                // leading and trailing clusters. Ignored when the tab strip
                // above claims the slot (see TopBarSpec.centerContent).
                centerContent = spec.topbarCenter?.invoke(),
                trailingContent = trailing,
                isResizable = true,
                minHeightPx = 0,
                defaultHeightPx = 40,
                maxHeightPx = 240,
            ),
            requestRebuild = ::rerender,
        )
    }

    /**
     * Builds the toolkit's default bottom bar — a thin chrome strip
     * showing the app name in the trailing slot, with a draggable top
     * edge so the user can resize or drag-to-hide the bar (and drag the
     * placeholder back up to restore). Apps that want richer status
     * content today inject extra elements via [AppShellSpec.title]
     * (the trailing label is the only content) or layer their own
     * content on top of the bar element after mount.
     *
     * Routed through [bottomBarController] so the snap-to-zero rule
     * fires symmetrically with the top bar — on a release below
     * `defaultHeightPx / 2` the controller hides the bar; the
     * placeholder keeps the resize handle reachable so the user can
     * drag it back up.
     */
    private fun buildBottomBar(): HTMLElement {
        // App-supplied trailing slot (e.g. termtastic's connection-state
        // dot) wins over the toolkit's default app-name label. Apps that
        // return null from the factory fall back to the default.
        val trailing: HTMLElement = spec.bottomBarTrailing?.invoke() ?: run {
            val title = document.createElement("span") as HTMLElement
            title.textContent = spec.title
            title.style.fontSize = "11px"
            title.style.opacity = "0.7"
            title.style.padding = "0 12px"
            title
        }
        val leading: HTMLElement? = spec.bottomBarLeading?.invoke()
        // Apps that mount rich content (e.g. termtastic's Claude usage
        // bar) need a taller bar by default so the content is visible
        // without an explicit drag-up. With no app content the toolkit
        // keeps its slim 22px default.
        val hasAppContent = leading != null || spec.bottomBarTrailing != null
        return bottomBarController.mountBottomBar(
            BottomBarSpec(
                leadingContent = leading,
                trailingContent = trailing,
                isResizable = true,
                minHeightPx = 0,
                defaultHeightPx = if (hasAppContent) 32 else 22,
                maxHeightPx = if (hasAppContent) 200 else 80,
                allowGrowBeyondDefault = hasAppContent,
            ),
            requestRebuild = ::rerender,
        )
    }

    /** Pane count of the active tab — sizes the layout-preset miniatures. */
    /**
     * Re-tiles + repaints after a pane's `isMinimized` flag flipped
     * (minimize from the chrome header, or restore from the dock chip).
     *
     * Renders **exactly once** so the renderer's minimize/restore FLIP
     * animation isn't wiped by a second rebuild: under a non-Custom preset
     * [maybeReapplyPresetForActiveTab] does the single render (and fires
     * `onGeometryChanged`); under Custom we render directly. The active-pane
     * never lands on a docked pane because [activePaneForActiveTab] (which
     * the post-render focus reconciliation consults) filters minimized panes
     * out and the renderer re-focuses a visible one.
     *
     * @param tabId the tab whose minimized set just changed.
     */
    private fun reflowAfterMinimizeChange(tabId: String) {
        if (controllerFor(tabId).activePreset == LayoutPreset.Custom) {
            rerender()
            spec.onGeometryChanged?.invoke(tabId)
        } else {
            maybeReapplyPresetForActiveTab()
        }
        // The synchronous reflow above fires while a *restored* pane is still
        // mid-FLIP (growing out of its dock chip) and, on the very tick its
        // cached content is reattached, the pane container can still read
        // `offsetParent == null` until layout flushes — so a host that gates
        // its reflow on visibility (e.g. termtastic's `forceReassert`, which
        // skips terminals whose `offsetParent` is null) silently no-ops and
        // the restored pane keeps its stale PTY size. Fire one more
        // geometry-changed once the restore animation has settled and layout
        // has flushed, so the host re-fits the pane at its final on-screen
        // size. Harmless for the minimize direction (it just re-pings the
        // still-visible panes). Mirrors the host's existing "reflow after a
        // hidden→visible edge" behaviour.
        val cb = spec.onGeometryChanged ?: return
        kotlinx.browser.window.setTimeout({ cb.invoke(tabId) }, MINIMIZE_REFLOW_SETTLE_MS)
    }

    /**
     * Makes [paneId] the active pane of [tabId] and, when [raise] is set,
     * bumps its z-index above every sibling so it returns to the front.
     *
     * Called by both un-minimize affordances — the dock-chip restore
     * ([LayoutCallbacks.onFloatingRestored]) and the sidebar row click —
     * so a pane coming back from minimized lands on top *and* active no
     * matter which surface restored it.
     *
     * Activation is mode-specific: source mode activates optimistically —
     * quiet local mark + [recordPendingActivePane] focus hold for instant
     * feedback (and so a stale unconfirmable hold can't veto this pick) —
     * then notifies the host via [TabSource.onPaneSelect], whose snapshot
     * round-trip confirms and clears the hold; local mode marks it on the
     * tab's [LayoutController]. Neither branch switches the *active tab* —
     * callers handle that beforehand (the dock only shows active-tab panes;
     * the sidebar row click does its own cross-tab switch first).
     *
     * The z-raise mirrors the double-click raise in
     * [LayoutCallbacks.onFloatingFocused]; unlike that path this never
     * pokes the live `--dt-fp-z` var because every caller follows up with
     * a reflow/rerender that re-applies the geometry z-index.
     *
     * @param tabId  the tab owning the pane; must already be the active tab.
     * @param paneId the pane to activate (and optionally raise).
     * @param raise  when `true`, bump the pane's z-index to one past the
     *   current max so an overlapping (Custom-layout) restore lands in front.
     * @see reflowAfterMinimizeChange
     */
    /**
     * Clears `isMaximized` on every pane in [tabId] except [exceptPaneId],
     * returning whether any flag actually flipped.
     *
     * Called by the pane-surfacing gestures that bypass the renderer's
     * `focusPane(autoUnmaximize = true)` path — the sidebar row click and
     * the dock-chip restore ([LayoutCallbacks.onFloatingRestored]). Both
     * make a pane active from *outside* the pane area, so a full-bleed
     * maximized sibling would otherwise keep covering the pane the user
     * just picked. This is the ONLY thing that un-maximizes on those
     * gestures, under every preset: [maybeReapplyPreset] deliberately
     * leaves `isMaximized` alone (it is parallel state, orthogonal to the
     * layout preset), so a re-tile must never be relied on to clear it.
     * Mirrors the semantics of [LayoutCallbacks.onFloatingMaximizeCleared]
     * ("focus moved to a different pane → un-maximize") for these
     * out-of-pane gestures.
     *
     * Callers own the follow-up render: this only mutates geometry (via
     * [updateGeometry], which persists) so it can run before a single
     * reflow/rerender instead of forcing one per cleared pane.
     *
     * @param tabId        the tab whose panes to sweep.
     * @param exceptPaneId the pane being surfaced — its own maximize flag
     *   (if any) is left alone.
     * @return `true` when at least one sibling was un-maximized and the
     *   caller must re-render for the change to become visible.
     * @see bringPaneToFront
     * @see reflowAfterMinimizeChange
     */
    private fun clearMaximizedSiblings(tabId: String, exceptPaneId: String): Boolean {
        var cleared = false
        geometryState.geometryByTab[tabId].orEmpty().forEach { (paneId, g) ->
            if (paneId != exceptPaneId && g.isMaximized) {
                updateGeometry(tabId, paneId) { it.copy(isMaximized = false) }
                cleared = true
            }
        }
        return cleared
    }

    private fun bringPaneToFront(tabId: String, paneId: String, raise: Boolean) {
        if (raise) {
            val current = geometryState.geometryByTab[tabId].orEmpty()
            val maxZ = current.values.maxOfOrNull { it.zIndex } ?: 0
            updateGeometry(tabId, paneId) { it.copy(zIndex = maxZ + 1) }
        }
        if (spec.tabSource != null) {
            // A sidebar row click / dock-chip restore is just as much a user
            // focus gesture as the renderer's capture-phase pane mousedown, so
            // it gets the same optimistic treatment: mark the pane active
            // locally (quiet — no onChange → rerender storm mid-click) and
            // record the focus hold so (a) the row highlight moves instantly
            // instead of after the host round-trip, and (b) any stale hold
            // left by an earlier unconfirmable gesture (e.g. a click on the
            // already-server-focused pane, which hosts dedupe into no command;
            // see [pendingActivePaneId]) is REPLACED rather than allowed to
            // veto this selection when the confirming snapshot arrives.
            controllerFor(tabId).setActiveQuiet(paneId)
            recordPendingActivePane(tabId, paneId)
            persistLayoutState()
            repaintSidebarActiveMark(tabId, paneId)
            spec.tabSource.onPaneSelect?.invoke(tabId, paneId)
                ?: spec.tabSource.onSelect(tabId)
        } else {
            controllerFor(tabId).setActive(paneId)
        }
    }

    /**
     * Bring [paneId] to the front on behalf of the HOST — [AppShellHandle.bringPaneToFront].
     *
     * Raises the pane's z above every sibling, makes it the active pane, and
     * rerenders so both take effect. The z-raise is the point in a free-floating
     * layout: a snapshot's `activePaneId` only *focuses* a pane, it does not
     * re-stack it, so a host that re-selects an already-open window (clicking its
     * card while a full-area board sits on top) needs this to lift it out from
     * behind. The optimistic active-pane hold is recorded too, so the confirming
     * snapshot round-trip doesn't bounce focus back to whatever the last pane
     * mousedown left pending.
     *
     * Unlike the internal [bringPaneToFront], this does NOT call
     * [TabSource.onPaneSelect] / [TabSource.onSelect]: the host is the one asking,
     * so echoing a selection back to it would be circular. A no-op when [paneId]
     * is not a pane of the active tab — a host raising a pane on a tab the user
     * has since left must not yank the view.
     */
    fun bringPaneToFrontFromHost(paneId: String) {
        val tabId = viewActiveTabId() ?: return
        val panes = geometryState.geometryByTab[tabId].orEmpty()
        if (paneId !in panes) return
        val maxZ = panes.values.maxOfOrNull { it.zIndex } ?: 0
        updateGeometry(tabId, paneId) { it.copy(zIndex = maxZ + 1) }
        controllerFor(tabId).setActiveQuiet(paneId)
        recordPendingActivePane(tabId, paneId)
        rerender()
    }

    private fun activePaneCountForPresets(): Int {
        val activeTab = viewActiveTabId() ?: return 0
        // Minimized panes are docked and excluded from layout, so the preset
        // preview tiles must reflect the count of panes that actually tile.
        // (Local-mode `viewPanesIn` already drops minimized; source mode does
        // not, so filter explicitly here for both.)
        return viewPanesIn(activeTab).count { !geometryFor(activeTab, it.id).isMinimized }
    }

    /**
     * Applies [preset] to the active tab. Mode-agnostic — geometry is
     * toolkit-owned, so source-mode hosts get the same behaviour as the
     * demo. The active tab's [LayoutController] is the single source of
     * truth: setting the preset changes its `activePreset`, and the
     * subsequent reflow runs through [maybeReapplyPresetForActiveTab],
     * which honours `paneOrder` (head → slot 0 = primary).
     */
    private fun applyLayoutPreset(preset: LayoutPreset) {
        val tabId = viewActiveTabId() ?: return
        controllerFor(tabId).setPreset(preset)
        maybeReapplyPresetForActiveTab()
        // Re-tiling above re-rendered the panes, detaching (blurring) the
        // active pane's terminal, and the switcher click had moved DOM focus
        // onto the dropdown — so without this the active pane keeps its focus
        // ring but no longer accepts keystrokes. Hand DOM focus back to it,
        // the same way a click would. `maybeReapplyPreset` re-renders
        // synchronously, so the freshly-mounted content is in the document.
        renderer?.focusActivePaneContent()
    }

    /**
     * Adds a fresh pane to the active tab. Local mode only — picks a
     * unique pane id, mutates [local] for tab/pane *identity*, then
     * delegates to [applyLocalLayout] which routes the change through
     * the diff path so the new pane gets default geometry seeded and
     * Auto reflows around it.
     */
    private fun addPaneToActiveTab() {
        // Source mode: forward to the host. The host appends a pane to
        // its model and pushes a new snapshot.
        val src = spec.tabSource
        if (src != null) {
            val tabId = external?.activeTabId ?: return
            src.onPaneAdd?.invoke(tabId)
            return
        }
        // Local mode: assembler owns the tab list.
        val current = local ?: return
        val activeTab = current.activeTabId ?: return
        val panes = current.panesByTab[activeTab].orEmpty()
        var n = panes.size + 1
        val existingIds = panes.map { it.id }.toSet()
        while ("$activeTab-pane-$n" in existingIds) n++
        val newId = "$activeTab-pane-$n"
        val updated = panes + PersistedPane(id = newId)
        applyLocalLayout(current.copy(panesByTab = current.panesByTab + (activeTab to updated)))
        persistLocalLayout()
    }

    /**
     * Builds the inner content node for the left sidebar — the tabs→panes
     * tree plus any app-supplied [AppShellSidebarSection]s. The caller
     * (rerender) hands this to [SidebarController.mountSidebarOrPlaceholder]
     * which wraps it in the toolkit's `<aside class="dt-sidebar
     * dt-sidebar-left">` shell, attaches the resize handle, and animates
     * width 0 → target on the next frame. Splitting the content from
     * the shell lets the controller swap between the full sidebar and
     * a chromeless 0-width placeholder (drag-to-restore) without the
     * caller having to know which it's mounting.
     */
    private fun buildLeftSidebarContent(): HTMLElement {
        val content = document.createElement("div") as HTMLElement
        content.style.display = "flex"
        content.style.flexDirection = "column"
        content.style.setProperty("gap", "4px")

        // Default content: a tabs → panes tree. One section per tab,
        // each section's body listing the tab's panes; the active pane
        // row carries `dt-active`. Clicking a pane row activates that
        // tab/pane (in source mode it forwards to onPaneSelect; in
        // local mode it just flips activeTabId).
        // Skip tabs the user has hidden from the sidebar via the
        // tab-bar overflow menu's "Hide in side bar" entry.
        for (tab in viewTabs().filterNot { it.isHiddenFromSidebar }) {
            // Sort sidebar rows by the controller's paneOrder so the
            // list reflects the user's drag-to-reorder. Panes not yet
            // tracked by the controller (e.g. just-created in this
            // tick) are appended at the tail in their snapshot order.
            val ctl = controllerFor(tab.id)
            val orderIndex: Map<String, Int> =
                ctl.paneOrder.withIndex().associate { (i, id) -> id to i }
            val orderedPanes = viewPanesIn(tab.id).sortedBy {
                orderIndex[it.id] ?: Int.MAX_VALUE
            }
            val rows = orderedPanes.map { pane ->
                buildPaneRow(
                    tabId = tab.id,
                    paneId = pane.id,
                    isActive = (tab.id == viewActiveTabId() && pane.id == activePaneForActiveTab()),
                )
            }
            // Each tab section is keyed by tab id — that survives renames
            // (the user's collapse-by-tab decision tracks the conceptual
            // tab, not its current label).
            val sectionId = "tab:${tab.id}"
            // Per-tab status badge slot in the section header (e.g.
            // termtastic's tab-aggregated session spinner); wrapped in a
            // marker class so apps can find it after mount if they need to
            // mutate it in place.
            val tabBadge = spec.tabSidebarHeaderBadge(tab.id)
            val section = renderSidebarSection(
                SidebarSectionSpec(
                    title = tab.label,
                    isOpen = sectionId !in collapsedSections,
                    items = rows,
                    onToggle = { toggleSection(sectionId) },
                    trailingHeader = tabBadge,
                )
            )
            // Mark the section as active-tab when it matches the current
            // active tab so the toolkit's stylesheet paints the accent.
            if (tab.id == viewActiveTabId()) {
                section.classList.add("active-tab")
            }
            content.appendChild(section)
        }

        // App-supplied extra sections after the default tree.
        for (extra in spec.sidebarSections) {
            val sectionId = "extra:${extra.title}"
            val sec = renderSidebarSection(
                SidebarSectionSpec(
                    title = extra.title,
                    isOpen = sectionId !in collapsedSections,
                    items = listOf(extra.bodyFactory()),
                    onToggle = { toggleSection(sectionId) },
                )
            )
            content.appendChild(sec)
        }

        return content
    }

    private fun buildPaneRow(tabId: String, paneId: String, isActive: Boolean): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        // Minimized panes are parked in the dock and don't participate in
        // layout; dim their sidebar row (`.dt-sidebar-row-minimized`) so the
        // list visually distinguishes them from the live panes.
        val isMinimized = geometryFor(tabId, paneId).isMinimized
        row.className = "dt-sidebar-row" +
            (if (isActive) " dt-active" else "") +
            (if (isMinimized) " dt-sidebar-row-minimized" else "")
        // Identifying attributes so [repaintSidebarActiveMark] can flip
        // the active highlight imperatively, without rebuilding the
        // sidebar (which would race with an in-flight pane mousedown
        // and lose subsequent clicks on action buttons).
        row.setAttribute("data-tab-id", tabId)
        row.setAttribute("data-pane-id", paneId)
        // Drop targets stay row-wide so the user can release anywhere on
        // a sibling row, but the *drag source* is now the leading-edge
        // grip handle below — clicking the row activates the pane
        // without competing against a click-vs-drag heuristic.
        wireSidebarRowDropTarget(row, tabId, paneId)
        val handle = document.createElement("span") as HTMLElement
        handle.className = "dt-sidebar-row-handle"
        handle.setAttribute("aria-hidden", "true")
        handle.innerHTML = SIDEBAR_ROW_HANDLE_SVG
        row.appendChild(handle)
        wireSidebarRowDragSource(handle, row, tabId, paneId)
        // Mirror the pane chrome's leading icon in the sidebar row so a
        // user sees the same icon next to the pane row that they see in
        // the pane's title bar — one per-pane visual identity across
        // both surfaces.
        spec.paneIcon(tabId, paneId)?.let { iconHtml ->
            val icon = document.createElement("span") as HTMLElement
            icon.className = "dt-sidebar-row-icon"
            icon.innerHTML = iconHtml
            row.appendChild(icon)
        }
        // Per-pane status badge slot (between icon and label) — apps wire
        // live-state indicators (e.g. termtastic's session spinners) here
        // and update them in place outside the toolkit's render path.
        spec.paneSidebarBadge(tabId, paneId)?.let { badge ->
            val slot = document.createElement("span") as HTMLElement
            slot.className = "dt-sidebar-row-badge"
            slot.appendChild(badge)
            row.appendChild(slot)
        }
        // Pane-index badge mirrors the trailing badge on the pane header.
        // Resolve the glyph here so the label below knows whether to flip
        // to start-clip mode (the encircled badge sits at the trailing
        // edge with `flex: 0 0 auto`, and the label's informative tail
        // — typically the current directory — must stay visible).
        val indexValue = spec.paneIndex(tabId, paneId)
        val indexGlyph = indexValue?.let {
            se.soderbjorn.lunula.web.util.encircledIndexGlyph(it)
        }
        val label = document.createElement("span") as HTMLElement
        label.className = "dt-sidebar-row-label" +
            if (indexGlyph != null) " dt-sidebar-row-label-rtl" else ""
        label.textContent = spec.paneLabel(tabId, paneId)
        row.appendChild(label)
        // Sidebar width is user-controlled, so any label can end up clipped —
        // from the tail normally, from the head in start-clip (index) mode.
        // The tooltip carries the full label whenever that happens.
        wireSidebarRowClipTooltip(row, label)
        if (indexGlyph != null) {
            val badge = document.createElement("span") as HTMLElement
            badge.className = "dt-sidebar-row-index"
            badge.textContent = indexGlyph
            badge.setAttribute("aria-label", "Pane $indexValue")
            row.appendChild(badge)
        }
        row.addEventListener("click", {
            // Clicking a docked pane's row un-minimizes it first, so the
            // pane re-enters the layout (animating back from the dock) and
            // can then become active just like any other row click.
            val wasMinimized = geometryFor(tabId, paneId).isMinimized
            if (wasMinimized) {
                updateGeometry(tabId, paneId) { it.copy(isMinimized = false) }
            }
            // Switch the active tab to the clicked row's tab first. In
            // source mode `onPaneSelect` only changes pane focus inside
            // its tab and `buildPaneLayout` only renders the active tab,
            // so a click on a row in a non-active tab section would
            // otherwise appear to do nothing; in local mode `mutateLocal`
            // owns the active-tab switch. Neither touches paneOrder —
            // that order is owned by the user via drag-to-reorder.
            if (spec.tabSource != null) {
                if (tabId != viewActiveTabId()) {
                    spec.tabSource.onSelect(tabId)
                }
            } else {
                mutateLocal { it.copy(activeTabId = tabId) }
            }
            // Activate the clicked pane (drives the row's dt-active
            // highlight via [activePaneForActiveTab]) and raise it to the
            // front so clicking a sidebar row brings its pane forward —
            // exactly like the dock-chip restore, the double-click focus,
            // and the maximize toggle already do. Applies whether or not
            // the pane was minimized: a plain row click on an occluded
            // floating pane should surface it too.
            bringPaneToFront(tabId, paneId, raise = true)
            // Surface the pane: a maximized sibling is full-bleed and would
            // keep covering the pane the user just picked (the post-render
            // focus reconciliation deliberately passes autoUnmaximize=false,
            // so nothing else clears it — the restored pane silently landed
            // *behind* the maximized one). Under a non-Custom preset the
            // restore re-tile clears the flag anyway; this makes Custom —
            // and the plain row-click-while-a-sibling-is-maximized case —
            // behave the same. See [clearMaximizedSiblings].
            val unmaximized = clearMaximizedSiblings(tabId, paneId)
            // Reflow once after the host-select calls so the restore FLIP
            // plays; for source mode the host's activePaneId catches up on
            // its snapshot round-trip and focuses the freshly-restored pane.
            if (wasMinimized) {
                reflowAfterMinimizeChange(tabId)
            } else if (unmaximized) {
                // No minimize flip to reflow for, but the cleared maximize
                // still needs a repaint (same follow-up the maximize toggle
                // does: rerender + geometry-changed ping).
                rerender()
                spec.onGeometryChanged?.invoke(tabId)
            } else {
                // Plain row click on an already-visible pane: neither a
                // minimize flip nor a maximize clear rebuilt the DOM, so the
                // zIndex bump [bringPaneToFront] just wrote to geometry state
                // isn't reflected on screen yet. Mirror [onFloatingFocused]:
                // push the new z straight onto the live element's `--dt-fp-z`
                // CSS var for an instant, transition-free restack instead of a
                // full rerender (which would rebuild every pane element and is
                // wasteful for a pure z-order change). The host snapshot round-
                // trip that `onPaneSelect` kicked off would eventually restack
                // it too, but only after a network hop — this makes the raise
                // feel immediate.
                val z = geometryFor(tabId, paneId).zIndex
                (main?.querySelector("[data-pane-id=\"$paneId\"]") as? HTMLElement)
                    ?.style?.setProperty("--dt-fp-z", "$z")
            }
        })
        return row
    }

    /**
     * Wires the leading-edge grip element as the *drag source* for
     * sidebar pane reordering. Only the handle carries `draggable="true"`
     * so a press that lands on the icon or label of the row activates
     * the pane via the row's click listener instead of starting a drag.
     *
     * The drag still applies the `.dt-sidebar-row-dragging` opacity to
     * the parent row (not to the handle) so the visual feedback matches
     * what the user perceives as "the thing being moved". Mouse events
     * on the handle are stopped from bubbling so a press-and-release on
     * the handle (cancelled drag) doesn't fall through to the row's
     * click activator.
     *
     * @param handle the `.dt-sidebar-row-handle` span inside [row].
     * @param row    the parent `.dt-sidebar-row` whose dragging visual
     *   state is toggled.
     * @param tabId  the tab this row belongs to; encoded into the drag
     *   payload so the drop handler can gate intra-tab moves.
     * @param paneId the pane this row represents.
     * @see wireSidebarRowDropTarget
     * @see SIDEBAR_PANE_ROW_MIME
     */
    private fun wireSidebarRowDragSource(
        handle: HTMLElement,
        row: HTMLElement,
        tabId: String,
        paneId: String,
    ) {
        handle.setAttribute("draggable", "true")
        val payload = "$tabId|$paneId"

        handle.addEventListener("dragstart", { ev ->
            val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
            dt.effectAllowed = "move"
            dt.setData(SIDEBAR_PANE_ROW_MIME, payload)
            // text/plain fallback so platforms that strip custom MIME
            // types in some scenarios still see *something*. Receivers
            // always check the custom MIME first.
            dt.setData("text/plain", payload)
            row.classList.add("dt-sidebar-row-dragging")
        })

        handle.addEventListener("dragend", {
            row.classList.remove("dt-sidebar-row-dragging")
            clearSidebarRowDropIndicators(row)
        })

        // Stop pointer/click events on the handle from reaching the
        // row's click-to-activate listener. Without this, pressing the
        // handle and releasing without moving (a cancelled drag) would
        // also activate the pane.
        handle.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        handle.addEventListener("click", { ev -> ev.stopPropagation() })
    }

    /**
     * Wires a `.dt-sidebar-row` as a *drop target* for sibling pane-row
     * reorder drags. Filters by [SIDEBAR_PANE_ROW_MIME] so unrelated
     * drags (files, text, cross-tab pane-header drags) don't paint
     * insertion indicators on this row, and rejects drops whose source
     * `tabId` doesn't match this row's tab so reordering stays
     * intra-tab.
     *
     * The drop indicator (`::before`/`::after` 2px bar) is anchored to
     * this row, so leaving the drop target row-wide gives the user a
     * generous landing zone — only the *initiation* of a drag is
     * constrained to the handle.
     *
     * @param row    the `.dt-sidebar-row` element to wire.
     * @param tabId  the tab this row belongs to (used to gate cross-tab
     *   drops and resolve the [LayoutController]).
     * @param paneId the pane this row represents.
     * @see wireSidebarRowDragSource
     */
    private fun wireSidebarRowDropTarget(row: HTMLElement, tabId: String, paneId: String) {
        row.addEventListener("dragover", { ev ->
            if (!isSidebarPaneRowDrag(ev.asDynamic())) return@addEventListener
            ev.preventDefault()
            ev.asDynamic().dataTransfer.dropEffect = "move"
            val before = isCursorAboveMidpointY(row, ev.asDynamic())
            row.classList.toggle("dt-sidebar-row-drop-before", before)
            row.classList.toggle("dt-sidebar-row-drop-after", !before)
        })

        row.addEventListener("dragleave", { ev ->
            // Only clear the indicator when the pointer has actually
            // left the row itself — child boundary crossings would
            // otherwise flicker as the cursor moves between the icon
            // span and the label span.
            val related = ev.asDynamic().relatedTarget as? HTMLElement
            if (related != null && row.asDynamic().contains(related) as Boolean) return@addEventListener
            row.classList.remove("dt-sidebar-row-drop-before")
            row.classList.remove("dt-sidebar-row-drop-after")
        })

        row.addEventListener("drop", { ev ->
            if (!isSidebarPaneRowDrag(ev.asDynamic())) return@addEventListener
            ev.preventDefault()
            ev.stopPropagation()
            row.classList.remove("dt-sidebar-row-drop-before")
            row.classList.remove("dt-sidebar-row-drop-after")
            val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
            val raw = (dt.getData(SIDEBAR_PANE_ROW_MIME) as? String)
                ?: (dt.getData("text/plain") as? String)
                ?: return@addEventListener
            val parts = raw.split("|", limit = 2)
            if (parts.size != 2) return@addEventListener
            val sourceTab = parts[0]
            val sourcePane = parts[1]
            if (sourceTab != tabId) return@addEventListener  // intra-tab only
            if (sourcePane == paneId) return@addEventListener
            val before = isCursorAboveMidpointY(row, ev.asDynamic())
            applySidebarRowReorder(tabId, sourcePane, paneId, before)
        })
    }

    /**
     * Applies a sidebar drag-and-drop reorder to the active tab's
     * [LayoutController]. Computes the destination index from the
     * target pane's current index plus the drop side (before/after),
     * accounting for the source's own current position so that
     * dragging downward and dropping "after target" lands at the
     * intuitive spot.
     *
     * If the active preset is [LayoutPreset.Auto] the panes are
     * re-tiled immediately so Auto stays consistent with the new
     * importance order. Other presets stay sticky — the user picks
     * the layout from the dropdown again to apply the new order.
     *
     * Always triggers a [rerender] so the sidebar list reflects the
     * new sort.
     */
    private fun applySidebarRowReorder(
        tabId: String,
        sourcePane: String,
        targetPane: String,
        insertBefore: Boolean,
    ) {
        val ctl = controllerFor(tabId)
        val targetIdx = ctl.paneOrder.indexOf(targetPane)
        if (targetIdx < 0) return
        val sourceIdx = ctl.paneOrder.indexOf(sourcePane)
        var newIdx = if (insertBefore) targetIdx else targetIdx + 1
        // If the source is currently above the target, removing it
        // shifts the target up by one, so we have to compensate so
        // "drop on the bottom edge of target" lands just after target.
        if (sourceIdx in 0 until newIdx) newIdx -= 1
        ctl.reorderPane(sourcePane, newIdx)
        if (ctl.activePreset == LayoutPreset.Auto) {
            maybeReapplyPresetForActiveTab()
        }
        rerender()
    }

    private fun buildPaneLayout(): PaneLayout {
        val activeTab = viewActiveTabId() ?: return PaneLayout()
        val paneIds: List<String> = when {
            external != null -> external!!.tabs.firstOrNull { it.id == activeTab }
                ?.panes.orEmpty().map { it.id }
            local != null -> local!!.panesByTab[activeTab].orEmpty().map { it.id }
            else -> return PaneLayout()
        }
        if (paneIds.isEmpty()) return PaneLayout()
        // Pane titles come from `spec.paneLabel`, the same function the
        // sidebar uses, so the title shown in the pane's chrome
        // matches the sidebar entry the user clicked. Local mode may
        // also carry a host-set per-pane title via `PersistedPane.title`.
        val localTitleById = local?.panesByTab?.get(activeTab)
            ?.associateBy({ it.id }, { it.title })
            ?: emptyMap()
        return PaneLayout(
            floatingPanes = paneIds.map { id ->
                val g = geometryFor(activeTab, id)
                val title = localTitleById[id]?.takeIf { !it.isNullOrBlank() }
                    ?: spec.paneLabel(activeTab, id)
                FloatingPaneSpec(
                    id = id,
                    title = title,
                    xPct = g.xPct,
                    yPct = g.yPct,
                    widthPct = g.widthPct,
                    heightPct = g.heightPct,
                    zIndex = g.zIndex,
                    isMaximized = g.isMaximized,
                    isMinimized = g.isMinimized,
                    isClosable = spec.paneClosable(activeTab, id),
                )
            },
        )
    }

    // ── Local-mode mutation helpers (unused in source mode) ────────

    private fun mutateLocal(transform: (PersistedShellLayout) -> PersistedShellLayout) {
        val current = local ?: return
        applyLocalLayout(transform(current))
        persistLocalLayout()
    }

    /**
     * Drops [paneId] from the active tab's pane list. If the pane was
     * the last one, the tab itself is removed too (and the active tab
     * advances to the next remaining tab). Local mode only.
     *
     * Routes through [applyLocalLayout] so the diff path sees the
     * removal, drops the pane's geometry from [geometryState], and Auto
     * reflows around what's left.
     */
    private fun removeLocalPane(paneId: String) {
        val current = local ?: return
        val activeTab = current.activeTabId ?: return
        val panes = current.panesByTab[activeTab].orEmpty()
        val nextPanes = panes.filterNot { it.id == paneId }
        val next = if (nextPanes.isEmpty() && current.tabs.size > 1) {
            // Last pane gone → close the tab too. Pick the next tab as active.
            val nextTabs = current.tabs - activeTab
            current.copy(
                tabs = nextTabs,
                tabLabels = current.tabLabels - activeTab,
                activeTabId = nextTabs.firstOrNull(),
                panesByTab = current.panesByTab - activeTab,
            )
        } else {
            current.copy(panesByTab = current.panesByTab + (activeTab to nextPanes))
        }
        applyLocalLayout(next)
        persistLocalLayout()
    }

    private fun persistLocalLayout() {
        val current = local ?: return
        val json = encodeShellLayoutJson(current)
        scope.launch { spec.persister.write(PersistKeys.LAYOUT, json) }
    }

    /**
     * Snapshots [layoutControllers] + [geometryState] into a
     * [PersistedLayoutState] and writes it under [PersistKeys.LAYOUT_STATE].
     * Called on every controller `onChange` (preset / paneOrder mutations)
     * and on every [updateGeometry] call.
     *
     * Value-level dedup: when the merged state equals the last state this
     * client persisted (or hydrated — see [lastPersistedLayoutState]), the
     * write is skipped entirely. Callers are deliberately allowed to invoke
     * this liberally (per snapshot, per focus change, per re-tiled pane);
     * only *actual* state changes reach the persister. Without this guard
     * every server config broadcast made each desktop client re-POST an
     * identical blob, and the resulting merge → broadcast → merge traffic
     * storm starved mobile clients' connect path (termtastic#93).
     */
    private fun persistLayoutState() {
        if (applyingExternal) return
        val presetByTab = layoutControllers.mapValues { (_, c) -> c.activePreset.key }
        val paneOrderByTab = layoutControllers.mapValues { (_, c) -> c.paneOrder.toList() }
        val merged = geometryState.copy(
            presetByTab = presetByTab,
            paneOrderByTab = paneOrderByTab,
        )
        geometryState = merged
        // No-op writes stop here: identical state was already persisted.
        if (merged == lastPersistedLayoutState) return
        lastPersistedLayoutState = merged
        // Remember this write so [applyExternalLayoutState] can recognise (and
        // skip) its server echo instead of re-adopting it and looping.
        recentLayoutWrites.addLast(merged)
        while (recentLayoutWrites.size > RECENT_LAYOUT_WRITES) recentLayoutWrites.removeFirst()
        val json = encodeLayoutStateJson(merged)
        // Per-world key: the active world's layout is written to its own key
        // (the default world's routes back onto flat LAYOUT_STATE via the
        // host adapter). In single-world mode this is exactly LAYOUT_STATE.
        val key = activeLayoutKey()
        scope.launch { spec.persister.write(key, json) }
    }

    /**
     * Flips the `dt-active` class on the left sidebar's pane rows so
     * the row matching `(activeTabId, activePaneId)` is highlighted and
     * every other pane row is not. Used by [onPaneFocused] in place of
     * a full `rerender()` so the active highlight tracks focus without
     * detaching pane DOM nodes mid-mousedown — see the comment block on
     * `onPaneFocused` for why that matters. No-op if the sidebar has
     * not been built yet.
     *
     * @param activeTabId the tab the focused pane belongs to.
     * @param activePaneId the pane to mark as active. `null` clears the
     *   highlight from every row.
     */
    private fun repaintSidebarActiveMark(activeTabId: String, activePaneId: String?) {
        val sidebar = leftSidebarSlot ?: return
        val rows = sidebar.querySelectorAll(".dt-sidebar-row")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val rowTab = row.getAttribute("data-tab-id")
            val rowPane = row.getAttribute("data-pane-id")
            val isMatch = rowTab == activeTabId && rowPane == activePaneId
            if (isMatch) row.classList.add("dt-active")
            else row.classList.remove("dt-active")
        }
    }

    /**
     * Returns the [LayoutController] for [tabId], lazily creating one
     * with [DEFAULT_LAYOUT_GRID] snap and a persistence-firing onChange
     * hook on first access.
     */
    private fun controllerFor(tabId: String): LayoutController =
        layoutControllers.getOrPut(tabId) {
            LayoutController(
                // Brand-new tabs start in [AppShellSpec.defaultLayoutPreset]
                // (Auto unless the app opts into free-floating), so panes
                // tile themselves as they are added/removed without the user
                // picking a preset from the dropdown. Restored tabs are
                // unaffected: their controller is pre-seeded with the persisted
                // preset (including "custom") by [applyPersistedLayoutState]
                // before the first snapshot lands, so hand-placed geometry never
                // re-tiles on reload. Only tabs the persisted state has never
                // heard of — i.e. freshly created ones — fall through to this
                // default.
                initialPreset = spec.defaultLayoutPreset,
                grid = DEFAULT_LAYOUT_GRID,
                onChange = { persistLayoutState(); rerender() },
            )
        }

    /**
     * Returns the persisted geometry for `(tabId, paneId)`, or a
     * [defaultGeometryForNewPane] entry the next persist call will commit.
     */
    private fun geometryFor(tabId: String, paneId: String): PersistedPaneGeometry =
        geometryState.geometryByTab[tabId]?.get(paneId)
            ?: defaultGeometryForNewPane(tabId, paneId)

    /**
     * Default geometry for a brand-new pane the controller has not yet
     * placed: a 45 % × 55 % rectangle whose origin is jittered around
     * (10 %, 10 %) ± 20 %, snapped to [DEFAULT_LAYOUT_GRID]. Mirrors
     * [randomFloatingPaneSpec] so successive spawns cascade down-right
     * (macOS / Finder window-spawn convention) instead of stacking on
     * top of each other.
     *
     * The next Auto re-tile (if active) overrides this immediately; in
     * [LayoutPreset.Custom] mode it stays the landing geometry until
     * the user drags.
     *
     * [AppShellSpec.paneOpensMaximized] is consulted here — the seed is
     * the one moment "this pane starts maximized" can apply without
     * fighting persisted state: once an entry exists, the user's own
     * maximize/restore toggles own the flag.
     */
    private fun defaultGeometryForNewPane(tabId: String, paneId: String): PersistedPaneGeometry {
        // The app may override the seed size (and optionally pin the origin) via
        // [AppShellSpec.paneInitialGeometry]; null falls back to the historical
        // 45 % × 55 % cascade. The origin, whether the app's or the jittered
        // default, is clamped to the size so the rect stays on-screen — the fixed
        // 0.55 / 0.45 bounds the default used were the same clamp specialised to
        // 0.45 × 0.55.
        val override = spec.paneInitialGeometry(tabId, paneId)
        val width = override?.widthPct ?: 0.45
        val height = override?.heightPct ?: 0.55
        val rawX = override?.xPct ?: (0.10 + kotlin.random.Random.nextDouble() * 0.20)
        val rawY = override?.yPct ?: (0.10 + kotlin.random.Random.nextDouble() * 0.20)
        val x = rawX.coerceIn(0.0, (1.0 - width).coerceAtLeast(0.0))
        val y = rawY.coerceIn(0.0, (1.0 - height).coerceAtLeast(0.0))
        val snapped = DEFAULT_LAYOUT_GRID.snapBox(LayoutBox(x, y, width, height))
        return PersistedPaneGeometry(
            snapped.x, snapped.y, snapped.width, snapped.height,
            isMaximized = spec.paneOpensMaximized(tabId, paneId),
        )
    }

    /**
     * Updates the geometry entry for `(tabId, paneId)` and persists.
     * Creates the entry from defaults if it doesn't exist yet — used by
     * drag/resize/maximize handlers and by the snapshot-diff seeder.
     */
    private fun updateGeometry(
        tabId: String,
        paneId: String,
        transform: (PersistedPaneGeometry) -> PersistedPaneGeometry,
    ) {
        val tabMap = geometryState.geometryByTab[tabId].orEmpty()
        val current = tabMap[paneId] ?: defaultGeometryForNewPane(tabId, paneId)
        val updated = transform(current)
        // No-op guard: a re-tile that computes the exact geometry the pane
        // already has (the common case when Auto re-runs on a focus-only or
        // unrelated snapshot) must not rebuild state or ping the persister
        // (termtastic#93). Seeding a brand-new entry (pane absent from the
        // map) still falls through even when transform returns the default.
        if (updated == current && tabMap.containsKey(paneId)) return
        val nextTab = tabMap + (paneId to updated)
        geometryState = geometryState.copy(
            geometryByTab = geometryState.geometryByTab + (tabId to nextTab),
        )
        persistLayoutState()
    }

    /**
     * Applies the optimistic-focus hold to a freshly pushed [snapshot]: for
     * every tab with a live entry in [pendingActivePaneId], either
     * confirm-and-clear it (the snapshot now agrees), drop it (the held pane
     * is gone from the tab), or hold it by rewriting that tab's `activePaneId`
     * to the pending value so every downstream consumer
     * ([syncControllersWithSnapshot]'s `setActive`, [external], and
     * [activePaneForActiveTab]'s focus routing) sees the user's just-picked
     * pane rather than the stale server one still in flight.
     *
     * The pane analogue of the [pendingActiveTabId] reconciliation in
     * [bindTabSource]. Returns [snapshot] unchanged when no hold is active, so
     * the common no-pending case allocates nothing.
     *
     * @param snapshot the raw snapshot the app just pushed.
     * @return the snapshot with held tabs' `activePaneId` rewritten to the
     *   pending pane; identical to [snapshot] when nothing is held.
     * @see pendingActivePaneId
     */
    private fun applyPendingActivePaneHold(snapshot: TabListSnapshot): TabListSnapshot {
        if (pendingActivePaneId.isEmpty()) return snapshot
        // Age out stale holds before applying anything: a hold whose
        // confirming round-trip hasn't landed within the expiry window is
        // never going to be confirmed (see [PENDING_ACTIVE_PANE_EXPIRY_MS])
        // and must not veto the genuine focus change this snapshot may carry.
        val now = kotlin.js.Date.now()
        pendingActivePaneId.keys
            .filter { key ->
                val hold = pendingActivePaneId[key]
                hold != null && now - hold.seededAtMs > PENDING_ACTIVE_PANE_EXPIRY_MS
            }
            .forEach { pendingActivePaneId.remove(it) }
        // Forget holds for tabs the snapshot no longer carries.
        pendingActivePaneId.keys.retainAll(snapshot.tabs.map { it.id }.toSet())
        if (pendingActivePaneId.isEmpty()) return snapshot
        var changed = false
        val tabs = snapshot.tabs.map { tab ->
            val pending = pendingActivePaneId[tab.id]?.paneId ?: return@map tab
            when {
                // Server caught up to the user's choice — stop holding.
                tab.activePaneId == pending -> {
                    pendingActivePaneId.remove(tab.id)
                    tab
                }
                // Still pending and the pane is live — pin it.
                tab.panes.any { it.id == pending } -> {
                    changed = true
                    tab.copy(activePaneId = pending)
                }
                // The held pane vanished (closed / focus rejected) — release.
                else -> {
                    pendingActivePaneId.remove(tab.id)
                    tab
                }
            }
        }
        return if (changed) snapshot.copy(tabs = tabs) else snapshot
    }

    /**
     * Diffs [snapshot] against [lastSnapshot] and replays the per-tab
     * deltas onto [layoutControllers] and [geometryState]: closed tabs
     * drop their controller and geometry; new panes record-create on the
     * controller and seed default geometry; removed panes record-remove
     * and drop geometry; the snapshot's `activePaneId` is fed to
     * `setActive` (UI signal only — does not reorder). Mode-agnostic —
     * callers feed it either the source-mode snapshot or
     * [localSyntheticSnapshot] in local mode.
     *
     * @return the ids of every tab whose pane membership (add or remove)
     *   changed in this snapshot — not just the active tab's. Callers use
     *   this to decide which tabs to re-tile via [maybeReapplyPreset];
     *   a focus-only snapshot returns an empty set so the visible pane
     *   positions stay put. Background tabs matter here: a tab created
     *   (with its first pane) while another tab is displayed — e.g. by a
     *   second client on the same server — must still get its Auto
     *   re-tile, otherwise its panes keep the cascading seed geometry and
     *   the later tab *activation* (a focus-only snapshot) never re-tiles
     *   them (termtastic#86).
     *
     * **Stale-id collisions.** Persisted layout state is keyed by tab id,
     * but hosts may reuse ids across unrelated tab lifetimes: termtastic's
     * server allocates sequential ids (`t1`, `t2`, …) per database, so a
     * dev server, a packaged server, and a re-created database all mint
     * colliding ids into the same machine-global settings file. When a tab
     * id first appears in the snapshot stream, [reconcilePersistedTabState]
     * therefore checks whether the persisted state under that id actually
     * belongs to this tab (shared pane ids) and drops it when it belongs to
     * a dead namesake — otherwise a brand-new tab inherits a stale
     * `custom` preset and its first pane never Auto-tiles (termtastic#86).
     *
     * @see reconcilePersistedTabState
     */
    private fun syncControllersWithSnapshot(snapshot: TabListSnapshot): Set<String> {
        // Per-world layout swap — the fix for the "windows jump after a world
        // round-trip" bug. Before diffing tabs, if this snapshot belongs to a
        // different world than the one [geometryState] currently holds, flush
        // the outgoing world's layout to its own key and load the incoming
        // world's. This keeps [geometryState] scoped to the ACTIVE world, so
        // the prune below can only ever drop the active world's own closed
        // tabs — never another world's panes (which now live in a separate
        // key, not in this in-memory slice). Only engaged when the host is
        // world-aware ([worldLayoutProvider] set); single-world hosts keep the
        // flat model untouched.
        val incomingWorld = snapshot.worldId
        val provider = spec.worldLayoutProvider
        if (provider != null && incomingWorld != null && incomingWorld != activeWorldId) {
            val bootTransition = activeWorldId == null
            // Flush the world we're leaving: persist to its key (async) AND
            // capture its freshest, controller-merged state in the in-memory
            // cache so switching back is race-free. Skipped at boot.
            if (!bootTransition) {
                persistLayoutState()
                activeWorldId?.let { worldLayouts[it] = geometryState }
            }
            activeWorldId = incomingWorld
            // Prefer our own in-memory copy (freshest for a world we've edited
            // this session); fall back to the server's persisted blob on first
            // visit; else empty (a new world Auto-tiles). At boot with no
            // stored layout, keep what LAYOUT_STATE already hydrated.
            val loaded = worldLayouts[incomingWorld]
                ?: provider(incomingWorld)?.let { decodeLayoutStateJson(it) }
            when {
                loaded != null -> applyPersistedLayoutState(loaded)
                !bootTransition -> applyPersistedLayoutState(PersistedLayoutState())
            }
        }
        val previousByTab = lastSnapshot.tabs.associateBy { it.id }
        val knownTabs = snapshot.tabs.map { it.id }.toSet()
        // Drop state for tabs that are gone.
        layoutControllers.keys.retainAll(knownTabs)
        geometryState = geometryState.copy(
            presetByTab = geometryState.presetByTab.filterKeys { it in knownTabs },
            paneOrderByTab = geometryState.paneOrderByTab.filterKeys { it in knownTabs },
            geometryByTab = geometryState.geometryByTab.filterKeys { it in knownTabs },
        )
        val changedTabs = mutableSetOf<String>()
        snapshot.tabs.forEach { tab ->
            val curr = tab.panes.map { it.id }.toSet()
            // First time this tab id appears in the snapshot stream:
            // validate that any persisted state under the id belongs to
            // THIS tab before trusting it (see the function kdoc).
            if (previousByTab[tab.id] == null) {
                reconcilePersistedTabState(tab.id, curr)
            }
            val ctl = controllerFor(tab.id)
            val prev = previousByTab[tab.id]?.panes?.map { it.id }?.toSet() ?: emptySet()
            if (prev != curr) changedTabs.add(tab.id)
            // Removals: drop from controller + geometry.
            (prev - curr).forEach { id ->
                ctl.recordRemove(id)
                val tabMap = geometryState.geometryByTab[tab.id].orEmpty() - id
                geometryState = geometryState.copy(
                    geometryByTab = geometryState.geometryByTab + (tab.id to tabMap),
                )
            }
            // Hygiene: drop geometry entries for panes the tab no longer
            // contains. The `prev - curr` diff above only covers panes this
            // client saw disappear; entries hydrated from persistence for
            // panes that died while the client was away (or that belong to
            // a colliding pane id from another tab lifetime) would
            // otherwise linger in the persisted blob forever.
            val tabGeometry = geometryState.geometryByTab[tab.id].orEmpty()
            val deadGeometry = tabGeometry.keys - curr
            if (deadGeometry.isNotEmpty()) {
                geometryState = geometryState.copy(
                    geometryByTab = geometryState.geometryByTab +
                        (tab.id to (tabGeometry - deadGeometry)),
                )
            }
            // Additions: record on controller, and (only when at
            // least one pane is *genuinely* new — i.e. has no
            // persisted geometry) force-restore any pre-existing
            // maximized pane in this tab and seed each new pane
            // with `zIndex = topZ + 1, isMaximized = false` so it
            // visibly lands on top. Hydration of the persisted
            // snapshot at boot also routes through this branch
            // (prev = empty, curr = all persisted panes), so the
            // genuine-new check is what keeps maximized state /
            // z-order intact across restarts.
            val newIds = curr - prev
            if (newIds.isNotEmpty()) {
                val existingTabMap = geometryState.geometryByTab[tab.id].orEmpty()
                val genuinelyNew = newIds.filter { existingTabMap[it] == null }
                if (genuinelyNew.isNotEmpty()) {
                    var topZ = existingTabMap.values.maxOfOrNull { it.zIndex } ?: 0
                    val restored = existingTabMap.mapValues { (_, g) ->
                        if (g.isMaximized) g.copy(isMaximized = false) else g
                    }
                    var nextTabMap = restored
                    genuinelyNew.forEach { id ->
                        topZ += 1
                        // `paneOpensMaximized` panes keep the maximized
                        // flag their geometry seed carries — that's the
                        // whole point of the spec hook. Every other pane
                        // keeps the historical force-false so a freshly
                        // spawned pane lands on top *restored*, never
                        // surprise-full-bleed.
                        val seeded = defaultGeometryForNewPane(tab.id, id)
                            .copy(zIndex = topZ)
                            .let { g ->
                                if (spec.paneOpensMaximized(tab.id, id)) g
                                else g.copy(isMaximized = false)
                            }
                        nextTabMap = nextTabMap + (id to seeded)
                    }
                    geometryState = geometryState.copy(
                        geometryByTab = geometryState.geometryByTab + (tab.id to nextTabMap),
                    )
                }
                newIds.forEach { id ->
                    // Parent linkage: the pane the user was actively on
                    // when the new pane spawned. (paneOrder.firstOrNull
                    // would now be "user's most-important pane", which
                    // is not what `parentByPane` is meant to capture.)
                    val parent = ctl.activePaneId
                    ctl.recordCreate(id, parent)
                }
            }
            // Mark the snapshot's reported active pane as active on
            // the controller. UI signal only — does not reorder.
            tab.activePaneId?.takeIf { it in curr }?.let { ctl.setActive(it) }
        }
        persistLayoutState()
        lastSnapshot = snapshot
        return changedTabs
    }

    /**
     * Decides whether the persisted layout state stored under [tabId]
     * belongs to the tab that just appeared in the snapshot stream, and
     * drops it when it demonstrably belongs to a dead tab that happened
     * to share the id.
     *
     * Called by [syncControllersWithSnapshot] exactly once per tab id
     * per appearance (i.e. when the id is absent from `lastSnapshot`):
     * at boot for every restored tab, and mid-session when a tab is
     * created. The test is pane-id overlap: a genuinely restored tab
     * always shares at least one pane id with what was persisted for it,
     * while state recorded for a different tab lifetime (another server
     * database reusing sequential ids, or a long-closed tab whose id got
     * re-allocated) references only pane ids this tab has never
     * contained. On a mismatch the tab's persisted preset / pane order /
     * geometry are dropped and its controller is discarded, so
     * [controllerFor] re-creates it with the brand-new-tab default
     * ([LayoutPreset.Auto]) and the pane add path seeds + Auto-tiles it
     * like any other new tab (termtastic#86 — a stale `custom` entry
     * under a reused id was gating the Auto re-tile off, leaving the
     * first pane of a "new" tab as a small floating window).
     *
     * Tabs with no persisted pane ids are left untouched: there is no
     * evidence either way, and the no-entry case already defaults to
     * Auto via [controllerFor].
     *
     * @param tabId the tab id that just appeared in the snapshot stream.
     * @param currentPaneIds the pane ids the snapshot reports for it.
     */
    private fun reconcilePersistedTabState(tabId: String, currentPaneIds: Set<String>) {
        val persistedPaneIds = geometryState.paneOrderByTab[tabId].orEmpty().toSet() +
            geometryState.geometryByTab[tabId].orEmpty().keys
        if (persistedPaneIds.isEmpty()) return
        if (persistedPaneIds.any { it in currentPaneIds }) return
        geometryState = geometryState.copy(
            presetByTab = geometryState.presetByTab - tabId,
            paneOrderByTab = geometryState.paneOrderByTab - tabId,
            geometryByTab = geometryState.geometryByTab - tabId,
        )
        // Drop the (possibly hydration-pre-seeded) controller so the next
        // controllerFor() builds a fresh one with the new-tab Auto default.
        layoutControllers.remove(tabId)
    }

    /**
     * `true` when [tabId]'s preset is [LayoutPreset.Auto] — the only
     * preset that re-tiles on membership change. Other presets are
     * applied once at user request and then geometry is sticky; the
     * snapshot-driven retile path checks this gate per changed tab so
     * focus-only and other non-membership snapshots don't move panes.
     *
     * @param tabId the tab whose controller preset to check.
     * @return `true` if Auto is active in that tab; `false` for any
     *   other preset (including [LayoutPreset.Custom]).
     */
    private fun presetIsAuto(tabId: String): Boolean =
        controllerFor(tabId).activePreset == LayoutPreset.Auto

    /**
     * Re-applies the **active** tab's preset to its panes if the preset
     * is not [LayoutPreset.Custom]. Thin wrapper over [maybeReapplyPreset]
     * for the user-gesture call sites (preset pick from the dropdown,
     * sidebar reorder, minimize/restore) that by definition act on the
     * tab the user is looking at.
     */
    private fun maybeReapplyPresetForActiveTab() {
        val tabId = viewActiveTabId() ?: return
        maybeReapplyPreset(tabId)
    }

    /**
     * Re-applies [tabId]'s preset to its panes if the preset is not
     * [LayoutPreset.Custom]. Reads pane ids from the current view,
     * builds adapter [FloatingPaneSpec]s from [geometryState], runs them
     * through `controller.applyPresetToPanes`, and writes the laid-out
     * geometry back. Mode-agnostic.
     *
     * Writes **geometry only** — a maximized pane stays maximized through
     * a re-tile, and simply has its restore box updated underneath. A tab
     * on [LayoutPreset.Auto] can therefore still hold a maximized pane.
     * Callers wanting to un-maximize must do it explicitly; see
     * [clearMaximizedSiblings].
     *
     * Works for background tabs too: geometry lives in [geometryState]
     * (per tab, persistent), so re-tiling a tab that is not currently
     * displayed simply updates the geometry the next activation renders.
     * The snapshot-diff path relies on this to tile the first pane of a
     * freshly-created tab even when the snapshot carrying it arrives
     * while another tab is on screen (termtastic#86).
     *
     * @param tabId the tab whose panes should be re-laid-out.
     */
    private fun maybeReapplyPreset(tabId: String) {
        val ctl = controllerFor(tabId)
        if (ctl.activePreset == LayoutPreset.Custom) return
        val paneIds = viewPanesIn(tabId).map { it.id }
        if (paneIds.isEmpty()) return
        val asSpecs = paneIds.map { id ->
            val g = geometryFor(tabId, id)
            FloatingPaneSpec(
                id = id,
                title = null,
                xPct = g.xPct, yPct = g.yPct,
                widthPct = g.widthPct, heightPct = g.heightPct,
                zIndex = g.zIndex,
                isMaximized = g.isMaximized,
                // Carry the real minimized flag so `applyPresetToPanes`
                // excludes docked panes from tiling and returns them
                // unchanged. (In local mode `viewPanesIn` already filters
                // them out; source mode does not, so this is what keeps a
                // minimized pane out of the source-mode re-tile.)
                isMinimized = g.isMinimized,
            )
        }
        val laidOut = ctl.applyPresetToPanes(asSpecs)
        laidOut.forEach { laid ->
            // Geometry fields only: `isMaximized` is parallel state, not a
            // layout field. A maximized pane renders full-bleed regardless
            // of its box, so the tile computed here is its *restore*
            // geometry — writing it while the flag stands is correct and
            // invisible until the user restores. Clearing the flag here
            // would un-maximize on every membership change, including the
            // world switch that re-tiles the incoming world's tabs
            // (lunamux#127). Gestures that must un-maximize say so
            // explicitly — see [clearMaximizedSiblings].
            updateGeometry(tabId, laid.id) { existing ->
                existing.copy(
                    xPct = laid.xPct, yPct = laid.yPct,
                    widthPct = laid.widthPct, heightPct = laid.heightPct,
                )
            }
        }
        rerender()
        // Preset apply (user pick from dropdown) and Auto re-tile on
        // pane add/remove both reach here. Every pane in the tab
        // potentially received a new size — notify the host so it can
        // re-format pane bodies that cached the old geometry.
        spec.onGeometryChanged?.invoke(tabId)
    }

    /**
     * Builds a [TabListSnapshot] from the local-mode tab list so the
     * unified snapshot-diff path can run in local mode too. Source-mode
     * snapshots come from the [TabSource]; local mode synthesises one
     * from `local`.
     */
    private fun localSyntheticSnapshot(): TabListSnapshot {
        val l = local ?: return TabListSnapshot(emptyList(), null)
        return TabListSnapshot(
            tabs = l.tabs.map { tabId ->
                TabSnapshotEntry(
                    id = tabId,
                    label = l.tabLabels[tabId] ?: tabId,
                    panes = l.panesByTab[tabId].orEmpty().map { PaneSnapshotEntry(id = it.id) },
                    activePaneId = null,
                )
            },
            activeTabId = l.activeTabId,
        )
    }

    private fun persistUi(snap: ThemeSnapshotV2 = snapshot) {
        scope.launch {
            try {
                // Per-app selection (slots + appearance) and the shared custom
                // themes are written under their two v2 keys. Apps that bypass
                // `mountAppShell` and own their own persistence (termtastic's
                // TermtasticThemeManagerHost → flat-KV server settings) won't
                // go through here, since they pass an explicit `settingsHost`
                // whose setters route around DefaultThemeManagerState.
                spec.persister.write(PersistKeys.THEME_V2_SELECTION, snap.selectionJson())
                spec.persister.write(PersistKeys.THEME_V2_CUSTOM, snap.customThemesJson())
            } catch (t: Throwable) {
                kotlinx.browser.window.asDynamic().console.error(
                    "[persistUi] threw: ${t.message}"
                )
                throw t
            }
        }
    }

    private fun nextLocalTabId(current: PersistedShellLayout): String {
        var n = current.tabs.size + 1
        while (current.tabs.contains("tab-$n")) n++
        return "tab-$n"
    }

    private fun buildActionButton(
        id: String,
        glyph: String,
        label: String,
        onClick: () -> Unit,
    ): HTMLElement {
        val btn = document.createElement("button") as HTMLButtonElement
        btn.id = id
        btn.type = "button"
        btn.title = label
        btn.setAttribute("aria-label", label)
        btn.textContent = glyph
        btn.style.background = "transparent"
        btn.style.border = "0"
        btn.style.color = "inherit"
        btn.style.cursor = "pointer"
        btn.style.fontSize = "16px"
        btn.style.padding = "4px 8px"
        btn.addEventListener("click", { onClick() })
        return btn
    }

    /**
     * Inert spacer rendered between app-supplied topbar extras and the
     * toolkit's standard trailing cluster (Layout · NewPane ·
     * ThemeToggle · ThemeMgr). Width comes from CSS — see
     * `.dt-topbar-trailing-divider` in `lunula.css`.
     */
    private fun buildTopbarTrailingDivider(): HTMLElement {
        val span = document.createElement("span") as HTMLElement
        span.className = "dt-topbar-trailing-divider"
        span.setAttribute("aria-hidden", "true")
        return span
    }

    private fun buildActionButtonHtml(
        id: String,
        iconHtml: String,
        label: String,
        onClick: () -> Unit,
    ): HTMLElement {
        val btn = document.createElement("button") as HTMLButtonElement
        btn.id = id
        btn.type = "button"
        btn.title = label
        btn.setAttribute("aria-label", label)
        btn.innerHTML = iconHtml
        btn.style.background = "transparent"
        btn.style.border = "0"
        btn.style.color = "inherit"
        btn.style.cursor = "pointer"
        btn.style.padding = "4px 8px"
        btn.addEventListener("click", { onClick() })
        return btn
    }
}

/**
 * MIME type for sidebar pane-row reorder drags. Distinct from
 * `application/x-lunula-pane` (used by cross-tab pane drags from a
 * pane header) so a sidebar reorder drag is never accepted by tab-bar
 * pane-drop targets — and vice versa.
 */
/**
 * Debounce window for the [AppShellMount.mainResizeObserver]. The CSS
 * width transitions on left/right sidebars are on the order of 100–200 ms
 * in the toolkit's stylesheet; 150 ms is long enough that mid-transition
 * frames coalesce into one fire, short enough that the host's reformat
 * feels immediate on release.
 */
private const val MAIN_RESIZE_DEBOUNCE_MS = 150

/**
 * Delay before [AppShellMount]'s second, deferred geometry-changed fire
 * after a minimize/restore. Sits just past the renderer's 420 ms
 * minimize/restore FLIP so a restored pane is at its final on-screen size
 * (and its container's `offsetParent` has resolved) before the host
 * re-fits it — see `reflowAfterMinimizeChange`.
 */
private const val MINIMIZE_REFLOW_SETTLE_MS = 460

/**
 * Maximum age of an optimistic focus hold ([AppShellMount.pendingActivePaneId])
 * before [AppShellMount.applyPendingActivePaneHold] drops it instead of
 * applying it. A confirming focus round-trip (command out → snapshot back)
 * completes in milliseconds even over a remote link, so a hold this old can
 * only mean no confirmation is coming — e.g. the seed gesture was a click on
 * a pane the server already considered focused, which hosts dedupe into no
 * command at all. Keeping such a hold alive would permanently veto the next
 * genuine focus change (see the [AppShellMount.pendingActivePaneId] KDoc).
 */
private const val PENDING_ACTIVE_PANE_EXPIRY_MS = 3_000.0

private const val SIDEBAR_PANE_ROW_MIME = "application/x-lunula-sidebar-pane-row"

/**
 * Inline SVG for the sidebar row's drag-handle grip — six dots in two
 * columns of three. Sized 10×10 in the viewBox; the consuming
 * `.dt-sidebar-row-handle` rule renders it at the same pixel size and
 * tints it via `currentColor`. Inline SVG matches the existing
 * `paneIcon` convention (HTML strings injected via `innerHTML`).
 */
private const val SIDEBAR_ROW_HANDLE_SVG =
    "<svg viewBox=\"0 0 10 10\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\">" +
        "<circle cx=\"3\" cy=\"2\" r=\"1\" fill=\"currentColor\"/>" +
        "<circle cx=\"7\" cy=\"2\" r=\"1\" fill=\"currentColor\"/>" +
        "<circle cx=\"3\" cy=\"5\" r=\"1\" fill=\"currentColor\"/>" +
        "<circle cx=\"7\" cy=\"5\" r=\"1\" fill=\"currentColor\"/>" +
        "<circle cx=\"3\" cy=\"8\" r=\"1\" fill=\"currentColor\"/>" +
        "<circle cx=\"7\" cy=\"8\" r=\"1\" fill=\"currentColor\"/>" +
        "</svg>"

/**
 * Returns `true` when the drag event's `dataTransfer.types` includes
 * the sidebar pane-row MIME, so a `dragover`/`drop` handler can ignore
 * unrelated drags (file drops, text selections, cross-tab pane drags).
 */
private fun isSidebarPaneRowDrag(dynamicEv: dynamic): Boolean {
    val types = dynamicEv.dataTransfer?.types ?: return false
    val len = (types.length as? Number)?.toInt() ?: return false
    for (i in 0 until len) if (types[i] == SIDEBAR_PANE_ROW_MIME) return true
    return false
}

/**
 * Vertical-axis sibling of the tab bar's `isCursorBeforeMidpoint`:
 * decides whether the cursor is in the top half of [el] (drop *before*
 * this row) or the bottom half (drop *after*). Used by the sidebar
 * reorder drag to choose the insertion side.
 */
private fun isCursorAboveMidpointY(el: HTMLElement, dynamicEv: dynamic): Boolean {
    val rect = el.asDynamic().getBoundingClientRect()
    val midpoint = (rect.top as Double) + (rect.height as Double) / 2.0
    return (dynamicEv.clientY as Double) < midpoint
}

/**
 * Removes the `.dt-sidebar-row-drop-before` / `.dt-sidebar-row-drop-after`
 * classes from every sibling row in the same sidebar section. Called on
 * `dragend` so the sidebar doesn't keep stale insertion markers after
 * a drop completes (the browser fires `dragleave` for some siblings
 * inconsistently while the cursor moves around during the drag).
 */
private fun clearSidebarRowDropIndicators(el: HTMLElement) {
    val parent = el.parentElement ?: return
    val rows = parent.querySelectorAll(".dt-sidebar-row")
    for (i in 0 until rows.length) {
        val sibling = rows.item(i) as HTMLElement
        sibling.classList.remove("dt-sidebar-row-drop-before")
        sibling.classList.remove("dt-sidebar-row-drop-after")
    }
}

/**
 * Whether the topbar's "+" (New) button can currently do anything, and so
 * whether it should be rendered at all.
 *
 * The button is **omitted** rather than disabled when the answer is no: a
 * "+" whose hover menu is empty and whose click is a no-op reads as broken,
 * not as unavailable. Called on every topbar rebuild, so a host whose menu
 * is permission-gated gets the button back on the rerender that follows the
 * permission change.
 *
 * ── Why [hasPaneAddAction] is not enough on its own ─────────────────────
 *
 * A host that supplies both [TabSource.onPaneAdd] and
 * [TabSource.paneAddMenuItems] has described the "+" in one place, not two:
 * the callback is the *default* of the menu the provider fills, which is why
 * clicking the icon and picking the first row do the same thing everywhere.
 * So an empty item list is that host saying there is nothing to add right
 * now, and honouring it for the dropdown while ignoring it for the click
 * leaves exactly the dead button this function exists to prevent. Lunicle
 * hit that: a reader with no create-issue right saw a "+", got an empty
 * dropdown, and a click that silently did nothing.
 *
 * A host that supplies [TabSource.onPaneAdd] and **no** provider is
 * unaffected — it never described a menu, so there is no list to be empty
 * and the callback is the whole of what the button offers. That is what
 * [describesPaneAddMenu] distinguishes, and why it is a separate parameter
 * from [paneAddItemCount] being zero.
 *
 * @param hasNewTabAction whether a "New tab" row would be offered.
 * @param hasPaneAddAction whether [TabSource.onPaneAdd] is wired.
 * @param hasNewWorldAction whether a "New workspace" row would be offered.
 * @param describesPaneAddMenu whether [TabSource.paneAddMenuItems] is wired
 *   at all — distinct from it returning nothing.
 * @param paneAddItemCount how many pane flavours it returned for the active
 *   tab; zero when there is no provider or no active tab.
 * @return true to render the button.
 * @see TabSource.paneAddMenuItems
 */
internal fun shouldShowNewPaneButton(
    hasNewTabAction: Boolean,
    hasPaneAddAction: Boolean,
    hasNewWorldAction: Boolean,
    describesPaneAddMenu: Boolean,
    paneAddItemCount: Int,
): Boolean {
    val paneAddCounts = hasPaneAddAction && (!describesPaneAddMenu || paneAddItemCount > 0)
    return hasNewTabAction || paneAddCounts || hasNewWorldAction || paneAddItemCount > 0
}
