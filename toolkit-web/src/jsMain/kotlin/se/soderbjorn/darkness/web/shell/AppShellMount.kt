/* AppShellMount.kt
 * One-call assembler that produces the standard darkness-toolkit shell
 * from an [AppShellSpec]: app frame + topbar + tab bar + left sidebar
 * (with theme toggle + extra sections) + bottombar + LayoutRenderer
 * mount + persister-backed tab/layout state. Apps call
 * `mountAppShell(spec)` once at boot; everything chrome- and
 * persistence-related is driven from spec defaults. */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.web.applyUiSettings
import se.soderbjorn.darkness.web.injectDarknessToolkitStyles
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.layout.DEFAULT_LAYOUT_GRID
import se.soderbjorn.darkness.web.layout.FloatingPaneSpec
import se.soderbjorn.darkness.web.layout.LayoutBox
import se.soderbjorn.darkness.web.layout.LayoutController
import se.soderbjorn.darkness.web.layout.LayoutDropdown
import se.soderbjorn.darkness.web.layout.LayoutPreset
import se.soderbjorn.darkness.web.layout.LayoutRenderer
import se.soderbjorn.darkness.web.layout.PaneCallbacks
import se.soderbjorn.darkness.web.layout.PaneHeaderSpec
import se.soderbjorn.darkness.web.layout.triggerPaneRename
import se.soderbjorn.darkness.web.layout.PaneLayout
import se.soderbjorn.darkness.web.applyMonoFontFamily
import se.soderbjorn.darkness.web.applyMonoFontSizePx
import se.soderbjorn.darkness.web.applyProportionalFontFamily
import se.soderbjorn.darkness.web.applyProportionalFontSizePx
import se.soderbjorn.darkness.web.applySidebarFontFamily
import se.soderbjorn.darkness.web.applySidebarFontSizePx
import se.soderbjorn.darkness.web.applyTabbarFontFamily
import se.soderbjorn.darkness.web.applyTabbarFontSizePx
import se.soderbjorn.darkness.web.setDtCustomTitleBarBodyClass
import se.soderbjorn.darkness.web.settings.AppSettingsSidebarSpec
import se.soderbjorn.darkness.web.settings.SettingsSidebarSpec
import se.soderbjorn.darkness.web.settings.buildAppSettingsSidebar
import se.soderbjorn.darkness.web.settings.buildSettingsSidebar
import se.soderbjorn.darkness.web.settings.closeAppSettingsSidebar
import se.soderbjorn.darkness.web.settings.closeSettingsSidebar
import se.soderbjorn.darkness.web.settings.isAppSettingsSidebarOpen
import se.soderbjorn.darkness.web.settings.isSettingsSidebarOpen
import se.soderbjorn.darkness.web.settings.toggleAppSettingsSidebar
import se.soderbjorn.darkness.web.settings.toggleSettingsSidebar
import se.soderbjorn.darkness.web.themeeditor.DefaultThemeManagerHost
import se.soderbjorn.darkness.web.themeeditor.DefaultThemeManagerState
import se.soderbjorn.darkness.web.themeeditor.applySnapshot
import se.soderbjorn.darkness.web.themeeditor.toSnapshot
import se.soderbjorn.darkness.web.themeeditor.buildThemeManagerSidebar
import se.soderbjorn.darkness.web.themeeditor.closeThemeManager
import se.soderbjorn.darkness.web.themeeditor.isThemeManagerSidebarOpen
import se.soderbjorn.darkness.web.themeeditor.refreshThemeManager
import se.soderbjorn.darkness.web.themeeditor.resolveActiveUiSettings
import se.soderbjorn.darkness.web.themeeditor.toggleThemeManagerSidebar

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
 *   without an entry start in [LayoutPreset.Custom].
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
 * Encodes [layout] as a JSON string via `JSON.stringify` so toolkit-web
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
 * Mounts the standard darkness-toolkit app shell into [spec]'s
 * `rootContainer`.
 *
 * Composition (all toolkit-supplied unless overridden via [spec]):
 * - injects `darkness-toolkit.css` if not already present
 * - reads [PersistKeys.UI_SETTINGS] / [PersistKeys.LAYOUT] from the persister (or seeds defaults)
 * - applies the resolved theme via [applyUiSettings]
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
    injectDarknessToolkitStyles()
    document.title = spec.title

    // Theme manager state — constructed up-front so the right sidebar
    // and the trailing "themes" button can both reference it. Apps can
    // ignore the theme manager (no spec field needed); the toolkit
    // mounts it for free as part of the canonical chrome.
    val themeState = DefaultThemeManagerState()
    // `onChange` fires each time the user picks a theme / scheme /
    // appearance in the manager. We capture a forward reference to the
    // ShellState so the closure can resolve the new UiSettings, apply
    // the resulting palette to the DOM, persist, and re-render. Late
    // binding because ShellState needs themeHost in its constructor.
    var stateRef: ShellState? = null
    val themeHost: DefaultThemeManagerHost = object : DefaultThemeManagerHost(
        state = themeState,
        _appPanes = spec.appPanes,
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

    val initJob: Job = scope.launch {
        // 1. Restore UI settings (theme + appearance + per-section overrides).
        val uiRaw = spec.persister.read(PersistKeys.UI_SETTINGS)
        val ui = if (uiRaw != null) UiSettings.fromJsonString(uiRaw) else UiSettings.defaults()

        // 1a. Restore the ThemeSnapshot (font preferences, custom themes,
        //     favorites) into the toolkit's themeState BEFORE applyUi so
        //     `applyUi`'s `applyHostFontVars()` step paints the persisted
        //     fonts onto `--dt-font-*` on the first frame.
        val snapshotRaw = spec.persister.read(PersistKeys.THEME_SNAPSHOT)
        val haveSnapshot = snapshotRaw != null
        if (snapshotRaw != null) {
            val snapshot = se.soderbjorn.darkness.core.ThemeSnapshot.fromJsonString(snapshotRaw)
            themeState.applySnapshot(snapshot)
        } else {
            // No persisted snapshot (e.g. apps using the stock
            // ElectronIpcPersister, which doesn't round-trip THEME_SNAPSHOT)
            // would normally leave `state.useCustomTitleBar` at its default
            // `false` and cause [applyCustomTitleBar] below to strip
            // `dt-custom-titlebar` from `<body>` — clobbering the value
            // [autoApplyCustomTitleBarBodyClass] already set from the
            // host's authoritative `darknessApi.customTitleBar` flag.
            // Seed from the bridge so the renderer's state matches the
            // BrowserWindow's actual `titleBarStyle`.
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
        }

        // 1a'. Re-resolve the UiSettings against the snapshot's slot
        //      pointers + the persisted appearance before painting. The
        //      UI_SETTINGS blob caches `theme` (the resolved main scheme),
        //      but apps that mutate slot bindings outside the toolkit's
        //      DefaultThemeManagerHost path (notably termtastic, whose
        //      TermtasticThemeManagerHost writes through appVm and never
        //      back-fills UI_SETTINGS) leave that cache stale across
        //      restarts. Without this re-resolve the chrome would paint
        //      from the stale cached theme on first frame while the app's
        //      own painters paint from the fresh slot, splitting the UI
        //      into "chrome of last theme, content of current theme".
        val resolvedUi = if (haveSnapshot) {
            resolveActiveUiSettings(themeState, ui, paneToSection = spec.appPanes)
        } else ui
        state.applyUi(resolvedUi)

        // 1b. Restore collapsed-sidebar-section ids. Stored as a JSON
        //     array of strings (or empty / missing — defaults to all open).
        val sidebarRaw = spec.persister.read(PersistKeys.SIDEBAR_STATE)
        if (sidebarRaw != null) state.applyCollapsedSections(decodeCollapsedSectionsJson(sidebarRaw))

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
        //      backed by PersistKeys.LAYOUT. Suitable for `darkness-demo`
        //      and other apps with no app-side tab model.
        if (spec.tabSource != null) {
            state.bindTabSource(spec.tabSource)
        } else {
            val layoutRaw = spec.persister.read(PersistKeys.LAYOUT)
            val layout = layoutRaw?.let { decodeShellLayoutJson(it) } ?: seedDefaultLayout()
            state.applyLocalLayout(layout)
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

        override fun setUiSettings(ui: UiSettings) {
            state.syncUiFromHost(ui)
        }

        override fun dispose() {
            initJob.cancel()
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
 *   `darkness-demo` and any app whose tab list IS its persisted state.
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

    private var ui: UiSettings = UiSettings.defaults()
    /** Non-null only in local mode. */
    private var local: PersistedShellLayout? = null
    /** Non-null only in source mode. */
    private var external: TabListSnapshot? = null

    /**
     * Toolkit-owned layout state — per-tab active preset, pane importance
     * order, and pane geometry. Persisted under [PersistKeys.LAYOUT_STATE].
     * Single source of truth in both local and source mode; the tab-source
     * snapshot only carries pane *identity*.
     */
    private var geometryState: PersistedLayoutState = PersistedLayoutState()

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
        // without animation (skipNextOpenAnimation).
        leftSidebarController.setInitial(open = true, widthPx = 240)
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

    fun applyUi(ui: UiSettings) {
        kotlinx.browser.window.asDynamic().console.log(
            "[applyUi] entry: ui.appearance=${ui.appearance} ui.theme=${ui.theme.name} " +
                "prev.this.ui.appearance=${this.ui.appearance}"
        )
        this.ui = ui
        // Mirror appearance into the default theme-manager state so the
        // theme grid's "current mode first" sort reads the live value
        // after the topbar cycle button flips Auto/Dark/Light. Apps that
        // supply their own [ThemeManagerHost] (e.g. termtastic) bypass
        // [themeState] entirely and aren't affected.
        themeState.appearance = ui.appearance
        val docEl = document.documentElement as? HTMLElement ?: run {
            kotlinx.browser.window.asDynamic().console.warn(
                "[applyUi] BAIL: documentElement is null"
            )
            return
        }
        // Seed `:root` with the main palette + prefixed section vars so
        // any new elements that [rerender] produces have a baseline
        // `var(--t-*)` chain to read from on their first frame.
        // [rerender] itself runs Pass 3 again on the freshly built chrome
        // (every rerender wipes the topbar / sidebar / bottombar slots),
        // so the per-element section paint stays consistent across every
        // rerender path — tab switches, sidebar toggles, theme manager
        // close, layout changes, …
        applyUiSettings(docEl, ui, isDarkActive(ui.appearance))
        applyHostFontVars()
        applyCustomTitleBar()
        rerender()
    }

    /**
     * Sync path for apps that own theme resolution outside the toolkit
     * (e.g. termtastic's `TermtasticThemeManagerHost`). Updates the
     * stored snapshot + paints in place; deliberately omits [rerender]
     * for the same reason [onThemeManagerChanged] does — the user may
     * be mid-interaction in the theme editor.
     *
     * Called by [AppShellHandle.setUiSettings].
     */
    fun syncUiFromHost(ui: UiSettings) {
        kotlinx.browser.window.asDynamic().console.log(
            "[syncUiFromHost] entry: ui.appearance=${ui.appearance} ui.theme=${ui.theme.name} " +
                "prev.this.ui.appearance=${this.ui.appearance}"
        )
        this.ui = ui
        val docEl = document.documentElement as? HTMLElement ?: return
        applyUiSettings(docEl, ui, isDarkActive(ui.appearance))
        applyHostFontVars()
        applyCustomTitleBar()
    }

    /**
     * Pushes the host's currently-persisted font preferences onto the
     * document root via the toolkit's `--dt-font-*` CSS variables. Called
     * after [applyUi] (which establishes the palette) and after any
     * theme-manager change.
     *
     * Reads from the assembler's [themeHost] — the same source the
     * Settings sidebar's pill rows mutate — so app surfaces wired to the
     * `var(--dt-font-*)` chain pick up persisted values on first paint
     * without an explicit settings sync from the host.
     */
    private fun applyHostFontVars() {
        applyMonoFontFamily(themeHost.monoFontFamily)
        applyMonoFontSizePx(themeHost.monoFontSizePx)
        applyProportionalFontFamily(themeHost.proportionalFontFamily)
        applyProportionalFontSizePx(themeHost.proportionalFontSizePx)
        applySidebarFontFamily(themeHost.sidebarFontFamily)
        applySidebarFontSizePx(themeHost.sidebarFontSizePx)
        applyTabbarFontFamily(themeHost.tabbarFontFamily)
        applyTabbarFontSizePx(themeHost.tabbarFontSizePx)
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
     * Invoked by [DefaultThemeManagerHost.onChange] whenever the user
     * picks a theme / scheme / appearance in the theme manager sidebar.
     * Resolves the manager's typed state into a fresh [UiSettings],
     * paints it onto the live document via [applyUiSettings] (which
     * updates root CSS vars and per-section inline styles in place),
     * persists, and asks the theme manager to refresh its own selection
     * highlight.
     *
     * Critically, this path does NOT call [rerender]. A full rerender
     * would tear down the right-side theme-manager panel mid-interaction
     * (the user is still hovering a card), and would also discard the
     * per-section paint that [applyUiSettings] just installed on the
     * existing `.dt-sidebar` / `.dt-topbar` / etc. elements — leaving
     * freshly-rebuilt elements painted only by the document-root vars,
     * which on themes with low chrome contrast reads as the panel
     * "going all black".
     */
    fun onThemeManagerChanged() {
        val resolved = resolveActiveUiSettings(themeState, ui, paneToSection = spec.appPanes)
        this.ui = resolved
        val docEl = document.documentElement as? HTMLElement
        if (docEl != null) applyUiSettings(docEl, resolved, isDarkActive(resolved.appearance))
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
        val membershipChanged = syncControllersWithSnapshot(localSyntheticSnapshot())
        rerender()
        // Auto is the only preset that re-tiles on membership change —
        // its contract (see [LayoutPreset.Auto]) is "the toolkit picks
        // geometry for the current pane count." Other presets are
        // applied once when the user picks them from the dropdown and
        // then geometry is sticky; focus-only snapshots must not move
        // panes around.
        if (membershipChanged && activePresetIsAuto()) maybeReapplyPresetForActiveTab()
    }

    /**
     * Hydrate the toolkit's layout state from persistence. Called once
     * during [mountAppShell]'s init job, before any tab/pane snapshot
     * lands. Pre-populates [layoutControllers] for every tab the persisted
     * state knows about so the first snapshot doesn't reset their preset
     * back to [LayoutPreset.Custom].
     */
    fun applyPersistedLayoutState(s: PersistedLayoutState) {
        geometryState = s
        s.paneOrderByTab.forEach { (tabId, order) ->
            controllerFor(tabId).reset(order)
        }
        s.presetByTab.forEach { (tabId, key) ->
            LayoutPreset.fromKey(key)?.let { controllerFor(tabId).setPreset(it) }
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

    /** Source-mode entry point: subscribes to the app's push channel. */
    fun bindTabSource(source: TabSource) {
        this.local = null
        source.subscribe { snapshot ->
            val membershipChanged = syncControllersWithSnapshot(snapshot)
            this.external = snapshot
            rerender()
            // Auto is the only preset that re-tiles on membership
            // change. Apps push a fresh snapshot for every model
            // change — including pure focus changes (sidebar clicks,
            // tab activation) — and a non-Auto preset must leave the
            // user's geometry alone, otherwise the focused pane bubbles
            // to slot 0 via paneOrder and the panes visibly swap.
            if (membershipChanged && activePresetIsAuto()) maybeReapplyPresetForActiveTab()
        }
    }

    fun setSidebarOpen(open: Boolean) {
        // Route through the controller so the user-visible width
        // animation (0 ↔ widthPx) plays. The controller flips its
        // own state and calls our rerender once the transition has
        // played; we drive the rebuild from there.
        if (open != leftSidebarController.isOpen) {
            leftSidebarController.toggle(requestRebuild = ::rerender)
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
        leftSlot.innerHTML = ""
        val sidebarEl = leftSidebarController.mountSidebarOrPlaceholder(
            spec = SidebarSpec(
                content = buildLeftSidebarContent(),
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
        }

        // Bottom bar — minimal status row. Apps carry their own
        // status content via the future `bottomBarContent` slots if
        // we add them; for now the bar exists with the toolkit's
        // standard chrome so apps that mount through `mountAppShell`
        // get the same visual baseline as notegrow / termtastic.
        bottomSlot.innerHTML = ""
        bottomSlot.appendChild(buildBottomBar())

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
            applyUiSettings(chromeDocEl, ui, isDarkActive(ui.appearance))
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
                            updateGeometry(tabId, paneId) {
                                it.copy(isMaximized = !it.isMaximized)
                            }
                            // Maximize has no inline live-update path
                            // (drag/resize update CSS vars inline during the
                            // gesture; maximize toggles a class flip that
                            // only fires on rerender). Without rerender the
                            // button would appear to do nothing.
                            rerender()
                            spec.onGeometryChanged?.invoke(tabId)
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
                    // Minimize button intentionally NOT wired — apps that
                    // want a minimize affordance can re-introduce one via
                    // their own pane header customization. The toolkit's
                    // pane chrome shows the maximize / restore / close
                    // controls only.
                    onFloatingMinimized = null,
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
                    // `confirmClosePane` shows a destructive-styled dialog
                    // citing the pane title. Apps that wrap their own
                    // confirmation around the pane-close gesture can flip
                    // this off via a future spec field.
                    confirmFloatingClose = true,
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
        activePaneForActiveTab()?.let { renderer!!.focusPane(it, autoUnmaximize = false) }
        // Section repaint. Every rerender wipes & rebuilds the chrome
        // slots (`topSlot.innerHTML = ""`, ditto sidebar / bottom),
        // discarding every Pass-3 inline `--t-*` var that
        // [applyUiSettings] previously stamped on `.dt-topbar` /
        // `.dt-sidebar` / `.dt-pane`. Without re-applying here, themes
        // whose per-section schemes differ from the main scheme — e.g.
        // Emerald Garden's tabs = Hot Magenta Bar — lose their section
        // paint on every rerender path (tab switch, sidebar toggle,
        // theme manager close, layout change, …) and the chrome falls
        // back to the main palette inherited from `:root`. Apps that
        // never noticed because their themes happened to be section-
        // uniform (the toolkit demo's Neon Green) wouldn't repro this.
        // Restoring the section paint here makes `rerender` self-
        // consistent: callers don't need to remember to repaint after.
        val docEl = document.documentElement as? HTMLElement
        if (docEl != null) {
            applyUiSettings(docEl, ui, isDarkActive(ui.appearance))
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
    }

    /**
     * Resolves which pane should hold focus in the active tab.
     *
     * Source mode: prefer the snapshot's `activePaneId`. Otherwise (and
     * in local mode) fall back to the [LayoutController.activePaneId]
     * (the last pane the user clicked / activated), then to the first
     * pane in the user's importance order, then to the first visible
     * pane. Returns `null` only when the active tab has no panes at all.
     *
     * Note: this is a UI / focus-routing signal — it does not influence
     * layout. Layout slots are assigned from [LayoutController.paneOrder]
     * which the user controls explicitly via the sidebar drag-to-reorder.
     */
    private fun activePaneForActiveTab(): String? {
        val tabId = viewActiveTabId() ?: return null
        val visibleIds = viewPanesIn(tabId).map { it.id }
        if (visibleIds.isEmpty()) return null
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

        val tabs = view.map { t ->
            TabSpec(
                id = t.id,
                label = t.label,
                // No inline × close button on tabs themselves — close lives
                // exclusively in the tab's `⋮` overflow menu (where it sits
                // alongside Rename, New tab, Hide, etc.). One canonical
                // place to close a tab keeps the affordance discoverable
                // without the visual clutter of a per-tab cross.
                isClosable = false,
                isDraggable = if (srcMode) spec.tabSource!!.onReorder != null else true,
                isRenamable = if (srcMode) spec.tabSource!!.onRename != null else true,
                trailingBadge = spec.tabTrailingBadge(t.id),
                isHidden = t.isHidden,
                isHiddenFromSidebar = t.isHiddenFromSidebar,
            )
        }
        val callbacks = if (srcMode) {
            val src = spec.tabSource!!
            TabBarCallbacks(
                onSelect = { id -> src.onSelect(id) },
                onClose = src.onClose,
                onAdd = src.onAdd,
                onRename = src.onRename,
                onReorder = src.onReorder,
                // Source-mode tabs are app-owned, so the toolkit forwards
                // the visibility toggles to the host's TabSource. The host
                // mutates its own model (and persists / pushes upstream as
                // appropriate) and sends a fresh snapshot back; we never
                // touch tab visibility locally in this branch. When the
                // host opts out by leaving these callbacks null, the
                // overflow menu omits the corresponding rows.
                onSetHidden = src.onSetHidden,
                onSetHiddenFromSidebar = src.onSetHiddenFromSidebar,
                // Confirm before closing a tab from the kebab. With the
                // inline × button removed, the kebab "Close" row is the
                // only entry point so a single confirmation is enough.
                confirmTabClose = true,
            )
        } else {
            TabBarCallbacks(
                onSelect = { id -> mutateLocal { it.copy(activeTabId = id) } },
                onClose = { id -> mutateLocal { current ->
                    val nextTabs = current.tabs - id
                    val nextActive = if (current.activeTabId == id) nextTabs.firstOrNull() else current.activeTabId
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
            // Hide the inline `+` add button — it's redundant with the
            // overflow menu's "New tab" entry; that's also where rename
            // and close-tab live so users have one canonical place for
            // tab actions.
            showAddButton = false,
            // The `⋮` menu next to the tabs holds: New tab, Rename,
            // Close, Hide/Show in tab bar, Hide/Show in side bar, plus
            // a list of currently-hidden tabs to re-activate. Toolkit
            // owns the menu rendering.
            showOverflowMenu = true,
            callbacks = callbacks,
        )

        // Leading: sidebar-toggle button to the LEFT of the tab strip
        // (matching termtastic / notegrow chrome layout).
        val leading = document.createElement("div") as HTMLElement
        leading.style.display = "flex"
        leading.style.alignItems = "center"
        leading.appendChild(
            buildLeftSidebarToggleButton(
                isOpen = leftSidebarController.isOpen,
                onToggle = { setSidebarOpen(!leftSidebarController.isOpen) },
            )
        )

        // Trailing slot order:
        //   spec.extraTopbarBeforeStandard …  ‖  Layout · NewPane · ThemeToggle · ThemeMgr  ‖  spec.extraTopbarTrailing …
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
        trailing.appendChild(layoutDropdown.triggerButton)
        // If the tab source supplies secondary "new pane" actions
        // (termtastic does: Terminal link / File Browser / Git) render
        // the split-button variant — icon click still defaults to
        // onPaneAdd, hover reveals the dropdown. Apps without the
        // callback keep the historical plain-button.
        val paneAddItemsProvider = spec.tabSource?.paneAddMenuItems
        val newPaneButton = if (paneAddItemsProvider != null) {
            buildNewWindowSplitButton(
                tooltip = "New pane",
                items = {
                    val activeTabId = external?.activeTabId
                    if (activeTabId == null) emptyList()
                    else paneAddItemsProvider(activeTabId).map { item ->
                        HoverMenuItem(item.id, item.label, item.iconHtml, item.onSelect)
                    }
                },
                onDefaultClick = { addPaneToActiveTab() },
            )
        } else {
            buildNewWindowButton(tooltip = "New pane") { addPaneToActiveTab() }
        }
        trailing.appendChild(newPaneButton)
        // Three-state cycle: Auto → Dark → Light → Auto. Helper paints
        // the per-state SVG (sun / moon / half-disc) so the icon
        // reflects the current appearance every rerender.
        trailing.appendChild(
            buildAppearanceCycleButton(
                appearance = ui.appearance,
                onCycle = {
                    val cur = ui.appearance
                    val next = when (cur) {
                        Appearance.Auto -> Appearance.Dark
                        Appearance.Dark -> Appearance.Light
                        Appearance.Light -> Appearance.Auto
                    }
                    kotlinx.browser.window.asDynamic().console.log(
                        "[cycle] click: cur=$cur next=$next " +
                            "themeStateSlots=${themeState.lightThemeName}/${themeState.darkThemeName}"
                    )
                    // Re-resolve the active theme from the [themeState] slot
                    // bindings for the new appearance. Without this we'd
                    // reuse the cached [ui.theme] from the previous mode —
                    // user-visible symptom: pick Theme A in Light, cycle to
                    // Dark, Dark mode paints with A's colors instead of the
                    // dark slot's bound theme. Apps that own theme
                    // resolution outside the default host (termtastic) leave
                    // [themeState]'s slot pointers null, so the resolver
                    // would fall back to DEFAULT_LIGHT/DARK_THEME_NAME and
                    // clobber their picks; the guard below routes those apps
                    // through the previous bare-appearance path and lets
                    // them re-paint via [AppShellHandle.setUiSettings].
                    val flipped = ui.copy(appearance = next)
                    val resolved = if (themeState.lightThemeName != null ||
                        themeState.darkThemeName != null) {
                        resolveActiveUiSettings(themeState, flipped, paneToSection = spec.appPanes)
                    } else flipped
                    kotlinx.browser.window.asDynamic().console.log(
                        "[cycle] calling applyUi: resolved.appearance=${resolved.appearance} " +
                            "resolved.theme=${resolved.theme.name}"
                    )
                    applyUi(resolved)
                    kotlinx.browser.window.asDynamic().console.log(
                        "[cycle] applyUi done; calling persistUi (async)"
                    )
                    // Pass [resolved] explicitly rather than relying on
                    // [persistUi] reading `this.ui`. The synchronous
                    // [applyUi] above runs `rerender()`, which fires
                    // `spec.onAfterRefresh`; hosts that bridge the
                    // toolkit's `setUiSettings` back into their own state
                    // (e.g. termtastic's [applyAppearanceClass]) read from
                    // their backing model — which still holds the *previous*
                    // appearance at this point because nothing has yet
                    // propagated the cycle's new value into it. That
                    // bridge call lands `syncUiFromHost(staleUi)` which
                    // overwrites `this.ui` back to the previous
                    // appearance. Reading `this.ui` here would then
                    // persist the previous value, the host's own
                    // appVm-blob check would early-return as "no change,"
                    // and the cycle would silently no-op for the user.
                    persistUi(resolved)
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
                        else -> toggleSettingsSidebar(::rerender)
                    }
                },
            )
        )
        // App Settings — host-supplied content. Suppressed entirely
        // when `spec.appSettingsContent` is null so apps that haven't
        // opted in don't get a phantom icon. Sits immediately to the
        // right of the Appearance gear so the toolkit's
        // appearance-related cluster (theme / appearance / app-settings)
        // reads as a left-to-right grouping.
        if (spec.appSettingsContent != null) {
            trailing.appendChild(
                buildAppSettingsButton(
                    isOpen = isAppSettingsSidebarOpen(),
                    onToggle = {
                        when {
                            isThemeManagerSidebarOpen() -> toggleThemeManagerSidebar {
                                rerender()
                                toggleAppSettingsSidebar(::rerender)
                            }
                            isSettingsSidebarOpen() -> toggleSettingsSidebar {
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
                tabBar = tabBarSpec,
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
    private fun activePaneCountForPresets(): Int {
        val activeTab = viewActiveTabId() ?: return 0
        return viewPanesIn(activeTab).size
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
        row.className = "dt-sidebar-row" + if (isActive) " dt-active" else ""
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
            se.soderbjorn.darkness.web.util.encircledIndexGlyph(it)
        }
        val label = document.createElement("span") as HTMLElement
        label.className = "dt-sidebar-row-label" +
            if (indexGlyph != null) " dt-sidebar-row-label-rtl" else ""
        label.textContent = spec.paneLabel(tabId, paneId)
        row.appendChild(label)
        if (indexGlyph != null) {
            // Tooltip on the row carries the full label so users can still
            // see what got start-clipped — same idea as the
            // `dt-sidebar-row-label-rtl` branch in `SidebarRow`.
            row.setAttribute("title", spec.paneLabel(tabId, paneId))
            val badge = document.createElement("span") as HTMLElement
            badge.className = "dt-sidebar-row-index"
            badge.textContent = indexGlyph
            badge.setAttribute("aria-label", "Pane $indexValue")
            row.appendChild(badge)
        }
        row.addEventListener("click", {
            if (spec.tabSource != null) {
                // Activate the parent tab first when the clicked pane is
                // not in the currently active tab — `onPaneSelect` only
                // changes pane focus inside its tab, and `buildPaneLayout`
                // only renders panes for the active tab, so without this
                // a click on a row in a non-active tab section appears to
                // do nothing. With both calls the user lands on the pane
                // they clicked regardless of which tab section it was in.
                if (tabId != viewActiveTabId()) {
                    spec.tabSource.onSelect(tabId)
                }
                spec.tabSource.onPaneSelect?.invoke(tabId, paneId)
                    ?: spec.tabSource.onSelect(tabId)
            } else {
                // Local mode: clicking a row marks the pane as active
                // (drives the row's dt-active highlight via
                // [activePaneForActiveTab]) and switches tabs. The
                // click does NOT touch paneOrder — that order is owned
                // by the user via the sidebar drag-to-reorder.
                controllerFor(tabId).setActive(paneId)
                mutateLocal { it.copy(activeTabId = tabId) }
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
     */
    private fun persistLayoutState() {
        val presetByTab = layoutControllers.mapValues { (_, c) -> c.activePreset.key }
        val paneOrderByTab = layoutControllers.mapValues { (_, c) -> c.paneOrder.toList() }
        val merged = geometryState.copy(
            presetByTab = presetByTab,
            paneOrderByTab = paneOrderByTab,
        )
        geometryState = merged
        val json = encodeLayoutStateJson(merged)
        scope.launch { spec.persister.write(PersistKeys.LAYOUT_STATE, json) }
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
                initialPreset = LayoutPreset.Custom,
                grid = DEFAULT_LAYOUT_GRID,
                onChange = { persistLayoutState(); rerender() },
            )
        }

    /**
     * Returns the persisted geometry for `(tabId, paneId)`, or a
     * [defaultGeometryForNewPane] entry the next persist call will commit.
     */
    private fun geometryFor(tabId: String, paneId: String): PersistedPaneGeometry =
        geometryState.geometryByTab[tabId]?.get(paneId) ?: defaultGeometryForNewPane()

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
     */
    private fun defaultGeometryForNewPane(): PersistedPaneGeometry {
        val rawX = (0.10 + kotlin.random.Random.nextDouble() * 0.20).coerceIn(0.0, 0.55)
        val rawY = (0.10 + kotlin.random.Random.nextDouble() * 0.20).coerceIn(0.0, 0.45)
        val snapped = DEFAULT_LAYOUT_GRID.snapBox(LayoutBox(rawX, rawY, 0.45, 0.55))
        return PersistedPaneGeometry(snapped.x, snapped.y, snapped.width, snapped.height)
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
        val current = tabMap[paneId] ?: defaultGeometryForNewPane()
        val updated = transform(current)
        val nextTab = tabMap + (paneId to updated)
        geometryState = geometryState.copy(
            geometryByTab = geometryState.geometryByTab + (tabId to nextTab),
        )
        persistLayoutState()
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
     * @return `true` if the active tab's pane membership (add or remove)
     *   changed in this snapshot. Callers use this to decide whether to
     *   re-tile via [maybeReapplyPresetForActiveTab]; a focus-only
     *   snapshot returns `false` so the visible pane positions stay put.
     */
    private fun syncControllersWithSnapshot(snapshot: TabListSnapshot): Boolean {
        val previousByTab = lastSnapshot.tabs.associateBy { it.id }
        val knownTabs = snapshot.tabs.map { it.id }.toSet()
        // Drop state for tabs that are gone.
        layoutControllers.keys.retainAll(knownTabs)
        geometryState = geometryState.copy(
            presetByTab = geometryState.presetByTab.filterKeys { it in knownTabs },
            paneOrderByTab = geometryState.paneOrderByTab.filterKeys { it in knownTabs },
            geometryByTab = geometryState.geometryByTab.filterKeys { it in knownTabs },
        )
        val activeTabId = snapshot.activeTabId
        var activeMembershipChanged = false
        snapshot.tabs.forEach { tab ->
            val ctl = controllerFor(tab.id)
            val prev = previousByTab[tab.id]?.panes?.map { it.id }?.toSet() ?: emptySet()
            val curr = tab.panes.map { it.id }.toSet()
            if (tab.id == activeTabId && prev != curr) activeMembershipChanged = true
            // Removals: drop from controller + geometry.
            (prev - curr).forEach { id ->
                ctl.recordRemove(id)
                val tabMap = geometryState.geometryByTab[tab.id].orEmpty() - id
                geometryState = geometryState.copy(
                    geometryByTab = geometryState.geometryByTab + (tab.id to tabMap),
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
                        val seeded = defaultGeometryForNewPane()
                            .copy(zIndex = topZ, isMaximized = false)
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
        return activeMembershipChanged
    }

    /**
     * `true` when the active tab's preset is [LayoutPreset.Auto] —
     * the only preset that re-tiles on membership change. Other
     * presets are applied once at user request and then geometry is
     * sticky; the snapshot-driven retile path checks this gate so
     * focus-only and other non-membership snapshots don't move panes.
     *
     * @return `true` if Auto is active in the active tab; `false` for
     *   any other preset (including [LayoutPreset.Custom]) or when no
     *   tab is active.
     */
    private fun activePresetIsAuto(): Boolean {
        val tabId = viewActiveTabId() ?: return false
        return controllerFor(tabId).activePreset == LayoutPreset.Auto
    }

    /**
     * Re-applies the active tab's preset to its panes if the preset is
     * not [LayoutPreset.Custom]. Reads pane ids from the current view,
     * builds adapter [FloatingPaneSpec]s from [geometryState], runs them
     * through `controller.applyPresetToPanes`, and writes the laid-out
     * geometry back. Mode-agnostic.
     */
    private fun maybeReapplyPresetForActiveTab() {
        val tabId = viewActiveTabId() ?: return
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
                isMinimized = false,
            )
        }
        val laidOut = ctl.applyPresetToPanes(asSpecs)
        laidOut.forEach { laid ->
            updateGeometry(tabId, laid.id) { existing ->
                existing.copy(
                    xPct = laid.xPct, yPct = laid.yPct,
                    widthPct = laid.widthPct, heightPct = laid.heightPct,
                    isMaximized = false,
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

    private fun persistUi(snap: UiSettings = ui) {
        kotlinx.browser.window.asDynamic().console.log(
            "[persistUi] launching: ui.appearance=${snap.appearance} ui.theme=${snap.theme.name}"
        )
        scope.launch {
            try {
                kotlinx.browser.window.asDynamic().console.log(
                    "[persistUi] writing UI_SETTINGS (appearance=${snap.appearance})"
                )
                spec.persister.write(PersistKeys.UI_SETTINGS, snap.toJsonString())
                kotlinx.browser.window.asDynamic().console.log(
                    "[persistUi] UI_SETTINGS write returned; writing THEME_SNAPSHOT"
                )
                // Also persist the ThemeSnapshot so font preferences,
                // favorites, and custom themes/schemes round-trip across
                // launches. Apps that bypass `mountAppShell` and own their
                // own snapshot persistence (termtastic's TermtasticThemeManagerHost
                // → flat-KV server settings) won't go through here, since
                // they pass an explicit `settingsHost` whose setters route
                // around DefaultThemeManagerState.
                val snapshotJson = themeState.toSnapshot().encodeAsJsonObject().toString()
                spec.persister.write(PersistKeys.THEME_SNAPSHOT, snapshotJson)
                kotlinx.browser.window.asDynamic().console.log(
                    "[persistUi] THEME_SNAPSHOT write returned (done)"
                )
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
     * `.dt-topbar-trailing-divider` in `darkness-toolkit.css`.
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
 * `application/x-darkness-pane` (used by cross-tab pane drags from a
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

private const val SIDEBAR_PANE_ROW_MIME = "application/x-darkness-sidebar-pane-row"

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
