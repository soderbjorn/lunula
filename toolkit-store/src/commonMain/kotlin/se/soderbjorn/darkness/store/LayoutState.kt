/**
 * Per-app layout-state model and filesystem helpers.
 *
 * [LayoutState] captures the parts of the toolkit shell that change as the
 * user works — sidebar widths, sidebar visibility, the active tab, and
 * each tab's pane tree (with its optional fullscreen-within-tab leaf
 * overlay). It is intentionally **separate** from the shared theme/UI state:
 * theme/appearance change rarely (and apply across the whole Darkness app
 * family), while layout state changes constantly (and is per-app —
 * notegrow's tree ≠ termtastic's). Different blast radius, different write
 * cadence, different file.
 *
 * The schema is versioned via [LayoutState.schemaVersion]; readers should
 * fall back to defaults rather than crash on an unknown major version.
 *
 * Path conventions live under each app's own subdirectory of `Darkness/`:
 *
 * - macOS:   `~/Library/Application Support/Darkness/<AppName>/layout-state.json`
 * - Windows: `%APPDATA%\Darkness\<AppName>\layout-state.json`
 * - Linux:   `$XDG_CONFIG_HOME/darkness/<app-name>/layout-state.json` (defaults to `~/.config/darkness/<app-name>/`)
 *
 * Persistence helpers ([readLayoutState] / [writeLayoutState] /
 * [watchLayoutState]) mirror the shared UI-settings flavour: atomic write,
 * self-write suppression, debounced filewatching. Apps that don't reach
 * the filesystem directly (browser-side renderers) should round-trip
 * the JSON through an IPC bridge — see notegrow's Electron preload.
 *
 * @see readUiSettingsRaw
 */
package se.soderbjorn.darkness.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Conventional filename for the per-app layout-state JSON blob. */
const val LAYOUT_STATE_FILE_NAME = "layout-state.json"

/**
 * Current schema version. Bump when a breaking change to the JSON shape
 * lands. Readers that see a higher version they don't understand should
 * fall back to [LayoutState.defaults] rather than corrupt the file.
 */
const val LAYOUT_STATE_SCHEMA_VERSION = 1

/** Default sidebar width when no persisted value is available. */
private const val DEFAULT_SIDEBAR_WIDTH_PX = 260

/**
 * Returns the conventional per-app layout-state path for the current
 * platform, or `null` if the platform's user-data directory cannot be
 * resolved.
 *
 * Apps decide their own [appName] (typically the user-facing product
 * name with capitalisation, e.g. `"Notegrow"`); the Linux variant
 * lowercases it to follow XDG conventions.
 *
 * @param appName the per-app subdirectory name under `Darkness/` (e.g.
 *   `"Notegrow"`). Must be non-blank; blank values fall back to a
 *   shared `Darkness/layout-state.json`, which is almost certainly not
 *   what you want.
 * @return the absolute path string, or null if the platform's user-data
 *   root cannot be determined.
 */
expect fun defaultAppLayoutStatePath(appName: String): String?

/**
 * Reads and parses a [LayoutState] from a JSON file on disk. Returns
 * `null` when the file is missing, unreadable, blank, malformed, or
 * carries a schema version this build does not understand.
 *
 * Callers should fall back to [LayoutState.defaults] in that case.
 *
 * @param path the absolute file path to read
 * @return the parsed layout state, or null
 */
expect fun readLayoutState(path: String): LayoutState?

/**
 * Writes a [LayoutState] to disk atomically. Bytes are recorded so a
 * co-resident [watchLayoutState] won't bounce on its own write.
 *
 * @param path the absolute file path to write
 * @param layout the layout to persist
 * @return true on success, false if the write failed
 */
expect fun writeLayoutState(path: String, layout: LayoutState): Boolean

/**
 * Watches [path] for external changes and invokes [onChange] with the
 * freshly-parsed [LayoutState] whenever the file is modified by another
 * process (or another writer in this process). Coalesces burst events
 * with the same 200ms debounce as [watchUiSettings] and skips events
 * whose payload matches what this process last wrote.
 *
 * @param path the absolute file path to watch
 * @param onChange invoked on the watcher thread with the parsed layout
 * @return a [Closeable] whose [Closeable.close] stops the watch
 */
expect fun watchLayoutState(
    path: String,
    onChange: (LayoutState) -> Unit,
): Closeable

/**
 * Persisted state for a single sidebar (left or right). Apps that
 * don't render a particular side just leave its slot at default
 * (`visible = false` for the right sidebar).
 *
 * @property widthPx   most-recently-applied width in pixels.
 * @property visible   whether the sidebar is rendered at all. `false`
 *   means "this app does not show this sidebar"; the toolkit doesn't
 *   render the slot.
 * @property collapsed when [visible] is true, whether the sidebar is
 *   collapsed (rendered but at zero/min width with a re-expand button).
 *   Round-trips a different concept than [visible]: a collapsed
 *   sidebar still occupies a render slot.
 */
data class SidebarState(
    val widthPx: Int = DEFAULT_SIDEBAR_WIDTH_PX,
    val visible: Boolean = true,
    val collapsed: Boolean = false,
)

/**
 * Pure JSON mirror of `toolkit-web`'s `PaneNode` sealed class, kept here
 * (in commonMain) so filesystem actuals on jvmMain/iosMain/androidMain
 * can serialize without depending on jsMain. Apps that hold a real
 * `PaneNode` convert via thin `toJson()` / `toPaneNode()` extensions in
 * jsMain.
 *
 * @see TabState.tree
 */
sealed class PaneNodeJson {
    /**
     * Terminal node — corresponds to `PaneNode.Leaf(id, title)`.
     *
     * @property id    stable pane identifier; survives across launches.
     * @property title human-readable title, or `null`.
     */
    data class Leaf(val id: String, val title: String? = null) : PaneNodeJson()

    /**
     * Binary split — corresponds to `PaneNode.Split`.
     *
     * @property orientation `"horizontal"` (side-by-side) or `"vertical"` (stacked).
     * @property ratio       firstFraction in (0.0, 1.0), quantised to 4 decimal
     *   places on serialise so dragged splits stay diffable across saves.
     * @property first       first child (left or top).
     * @property second      second child (right or bottom).
     */
    data class Split(
        val orientation: SplitOrientation,
        val ratio: Double,
        val first: PaneNodeJson,
        val second: PaneNodeJson,
    ) : PaneNodeJson()

    /** Side a [Split] divides on. Mirrors `toolkit-web`'s `SplitOrientation`. */
    enum class SplitOrientation { Horizontal, Vertical }
}

/**
 * Persisted state for a single tab.
 *
 * @property id              stable tab identifier; survives across launches.
 * @property title           human-readable tab title.
 * @property expandedLeafId  optional pane id currently rendered fullscreen
 *   within the tab. `null` when no leaf is expanded; matches the
 *   `PaneLayout.expandedLeafId` overlay flag.
 * @property tree            the tab's pane tree.
 * @property isHidden        when `true`, the tab is omitted from the visible
 *   tab strip but still known to the layout. Surfaces in the tab-bar
 *   overflow menu under "Unlisted tabs" so the user can re-activate it.
 *   Toggled from the toolkit's `TabBarCallbacks.onSetHidden`. Defaults to
 *   `false`.
 * @property isHiddenFromSidebar when `true`, the tab is omitted from the
 *   left sidebar's tree but still rendered in the tab strip and still
 *   owns its panes. Lets the user declutter the sidebar independently of
 *   the tab bar. Orthogonal to [isHidden]: a tab can be visible in the
 *   strip but hidden from the sidebar, and vice versa. Toggled from the
 *   toolkit's `TabBarCallbacks.onSetHiddenFromSidebar`. Defaults to `false`.
 */
data class TabState(
    val id: String,
    val title: String,
    val expandedLeafId: String? = null,
    /**
     * Optional persisted split tree. `null` means the tab uses the
     * floats-only model (every pane is in [floatingPanes]). Apps that
     * never used the split tree (notegrow ≥ "floats-only") write `null`;
     * apps with legacy data will still have a tree here on load and
     * should flatten it into [floatingPanes].
     */
    val tree: PaneNodeJson? = null,
    val isHidden: Boolean = false,
    val isHiddenFromSidebar: Boolean = false,
    /**
     * Floating overlay panes pinned on top of [tree]. Each entry is
     * absolutely-positioned by the renderer at `(xPct, yPct)` with size
     * `(widthPct, heightPct)` (all container-relative fractions on the
     * 5% snap grid) and stacked by `zIndex`. Empty list means the tab
     * has no overlay panes; persisted only when non-empty so existing
     * saved files stay diff-clean. See `FloatingPaneSpec` in toolkit-web.
     */
    val floatingPanes: List<FloatingPaneJson> = emptyList(),
    /**
     * Last layout preset applied to this tab — one of the keys from
     * `LayoutPreset.key` (e.g. `"auto"`, `"grid"`, `"hero-left"`, …).
     * `null` means no preset is driving (manual placement / fresh
     * tab). Persisted so apps can re-engage Auto re-tile after a
     * reload without forcing the user to re-pick from the dropdown.
     */
    val layoutPreset: String? = null,
)

/**
 * Pure JSON mirror of `toolkit-web`'s `FloatingPaneSpec`, kept here
 * (commonMain) so platform actuals can serialize without depending on
 * jsMain. Apps holding a real `FloatingPaneSpec` convert via thin
 * `toJson()` / `toFloatingPaneSpec()` extensions in jsMain.
 *
 * @property id          stable pane id; survives across launches.
 * @property title       optional pane title for the chrome header.
 * @property xPct        left edge as a fraction (0.0..1.0) of container width.
 * @property yPct        top edge as a fraction (0.0..1.0) of container height.
 * @property widthPct    width as a fraction of container width.
 * @property heightPct   height as a fraction of container height.
 * @property zIndex      stacking order — higher floats above lower.
 * @property isMaximized when `true`, the renderer paints the pane
 *   full-bleed regardless of geometry; restoring brings it back to the
 *   stored size.
 * @property isMinimized when `true`, the renderer omits the pane
 *   entirely — hosts surface it in their sidebar so the user can restore.
 */
data class FloatingPaneJson(
    val id: String,
    val title: String? = null,
    val xPct: Double,
    val yPct: Double,
    val widthPct: Double,
    val heightPct: Double,
    val zIndex: Int = 1,
    val isMaximized: Boolean = false,
    val isMinimized: Boolean = false,
)

/**
 * Top-level persisted layout snapshot.
 *
 * @property schemaVersion forward-migration gate; readers seeing a
 *   higher version they don't understand fall back to [defaults].
 * @property leftSidebar   state of the left sidebar slot.
 * @property rightSidebar  state of the right sidebar slot. Defaults
 *   `visible = false` so apps that don't render a right sidebar don't
 *   show an empty rail on first launch.
 * @property activeTabId   id of the currently active tab, or `null`
 *   when [tabs] is empty.
 * @property tabs          ordered tab list. Order is preserved on
 *   round-trip; reorders by the user produce a new list.
 */
data class LayoutState(
    val schemaVersion: Int = LAYOUT_STATE_SCHEMA_VERSION,
    val leftSidebar: SidebarState = SidebarState(),
    val rightSidebar: SidebarState = SidebarState(visible = false),
    val activeTabId: String? = null,
    val tabs: List<TabState> = emptyList(),
) {

    /**
     * Serialise to the canonical JSON string used on disk and across IPC.
     *
     * @return a JSON object string suitable for persisting.
     */
    fun toJsonString(): String {
        val obj = buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("leftSidebar", leftSidebar.toJson())
            put("rightSidebar", rightSidebar.toJson())
            activeTabId?.let { put("activeTabId", it) }
            put("tabs", buildJsonArray { tabs.forEach { add(it.toJson()) } })
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        /**
         * Default layout used when no persisted state exists yet. Ships a
         * single tab with a single pane so apps never have to handle a
         * "no tabs, no panes" empty state on first launch.
         *
         * The pane is a full-bleed maximized float (the floats-only
         * model): id `"pane-<tabId>"`, matching the seed-pane convention
         * host apps use for fresh tabs, so a host that later re-seeds a
         * pane for the same tab converges on the same identity instead
         * of minting a duplicate.
         *
         * @return a fresh [LayoutState] with safe defaults.
         */
        fun defaults(): LayoutState = LayoutState(
            activeTabId = "tab-default",
            tabs = listOf(
                TabState(
                    id = "tab-default",
                    title = "Default",
                    floatingPanes = listOf(
                        FloatingPaneJson(
                            id = "pane-tab-default",
                            title = null,
                            xPct = 0.0,
                            yPct = 0.0,
                            widthPct = 1.0,
                            heightPct = 1.0,
                            zIndex = 1,
                            isMaximized = true,
                        ),
                    ),
                ),
            ),
        )

        /**
         * Parse a JSON string into a [LayoutState]. Returns [defaults] for
         * blank input, unparseable JSON, or a schema version this build
         * does not understand. Never throws — a typo in stored data must
         * not crash a client.
         *
         * @param json a JSON object string in the canonical layout shape.
         * @return the parsed state, or [defaults] on any failure path.
         */
        fun fromJsonString(json: String): LayoutState {
            if (json.isBlank()) return defaults()
            val obj = runCatching {
                Json.parseToJsonElement(json) as? JsonObject
            }.getOrNull() ?: return defaults()
            val version = (obj["schemaVersion"] as? JsonPrimitive)
                ?.content?.toIntOrNull() ?: return defaults()
            if (version > LAYOUT_STATE_SCHEMA_VERSION) return defaults()
            return runCatching { fromJson(obj) }.getOrNull() ?: defaults()
        }

        /** Internal: structured JSON → [LayoutState]; throws on bad shape. */
        private fun fromJson(obj: JsonObject): LayoutState {
            fun string(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val left = (obj["leftSidebar"] as? JsonObject)?.let { parseSidebarState(it) }
                ?: SidebarState()
            val right = (obj["rightSidebar"] as? JsonObject)?.let { parseSidebarState(it) }
                ?: SidebarState(visible = false)
            val tabsJson = obj["tabs"] as? JsonArray ?: JsonArray(emptyList())
            val tabs = tabsJson.mapNotNull { it as? JsonObject }.map { parseTabState(it) }
            val schemaVersion = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: LAYOUT_STATE_SCHEMA_VERSION
            return LayoutState(
                schemaVersion = schemaVersion,
                leftSidebar = left,
                rightSidebar = right,
                activeTabId = string("activeTabId"),
                tabs = tabs,
            )
        }
    }
}

/** Quantise a fraction to 4 decimal places so saved files stay diffable. */
private fun quantizeRatio(r: Double): Double {
    if (r.isNaN() || r.isInfinite()) return 0.5
    val clamped = r.coerceIn(0.0, 1.0)
    val scaled = (clamped * 10_000.0)
    val rounded = if (scaled >= 0) (scaled + 0.5).toLong() else (scaled - 0.5).toLong()
    return rounded / 10_000.0
}

private fun SidebarState.toJson(): JsonObject = buildJsonObject {
    put("widthPx", widthPx)
    put("visible", visible)
    put("collapsed", collapsed)
}

private fun parseSidebarState(obj: JsonObject): SidebarState {
    val width = (obj["widthPx"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: DEFAULT_SIDEBAR_WIDTH_PX
    val visible = (obj["visible"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
    val collapsed = (obj["collapsed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    return SidebarState(widthPx = width, visible = visible, collapsed = collapsed)
}

private fun TabState.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    expandedLeafId?.let { put("expandedLeafId", it) }
    tree?.let { put("tree", it.toJson()) }
    if (isHidden) put("isHidden", true)
    if (isHiddenFromSidebar) put("isHiddenFromSidebar", true)
    if (floatingPanes.isNotEmpty()) {
        put("floatingPanes", buildJsonArray {
            floatingPanes.forEach { add(it.toJson()) }
        })
    }
    layoutPreset?.let { put("layoutPreset", it) }
}

private fun parseTabState(obj: JsonObject): TabState {
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("TabState missing id")
    val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    val expanded = (obj["expandedLeafId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val treeObj = obj["tree"] as? JsonObject
    val hidden = (obj["isHidden"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    val hiddenFromSidebar = (obj["isHiddenFromSidebar"] as? JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() ?: false
    val floats = (obj["floatingPanes"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.map { parseFloatingPaneJson(it) }
        ?: emptyList()
    val layoutPreset = (obj["layoutPreset"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    return TabState(
        id = id,
        title = title,
        expandedLeafId = expanded,
        tree = treeObj?.let { parsePaneNodeJson(it) },
        isHidden = hidden,
        isHiddenFromSidebar = hiddenFromSidebar,
        floatingPanes = floats,
        layoutPreset = layoutPreset,
    )
}

private fun FloatingPaneJson.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    title?.let { put("title", it) }
    put("x", quantizeRatio(xPct))
    put("y", quantizeRatio(yPct))
    put("w", quantizeRatio(widthPct))
    put("h", quantizeRatio(heightPct))
    put("z", zIndex)
    if (isMaximized) put("max", true)
    if (isMinimized) put("min", true)
}

private fun parseFloatingPaneJson(obj: JsonObject): FloatingPaneJson {
    fun double(key: String): Double? =
        (obj[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("FloatingPaneJson missing id")
    val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return FloatingPaneJson(
        id = id,
        title = title,
        xPct = double("x") ?: 0.1,
        yPct = double("y") ?: 0.1,
        widthPct = double("w") ?: 0.45,
        heightPct = double("h") ?: 0.55,
        zIndex = (obj["z"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
        isMaximized = (obj["max"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
        isMinimized = (obj["min"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
    )
}

private fun PaneNodeJson.toJson(): JsonObject = when (this) {
    is PaneNodeJson.Leaf -> buildJsonObject {
        put("kind", "leaf")
        put("id", id)
        title?.let { put("title", it) }
    }
    is PaneNodeJson.Split -> buildJsonObject {
        put("kind", "split")
        put(
            "orientation",
            when (orientation) {
                PaneNodeJson.SplitOrientation.Horizontal -> "horizontal"
                PaneNodeJson.SplitOrientation.Vertical -> "vertical"
            },
        )
        put("ratio", quantizeRatio(ratio))
        put("first", first.toJson())
        put("second", second.toJson())
    }
}

private fun parsePaneNodeJson(obj: JsonObject): PaneNodeJson {
    val kind = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("PaneNodeJson missing kind")
    return when (kind) {
        "leaf" -> {
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Leaf missing id")
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            PaneNodeJson.Leaf(id = id, title = title)
        }
        "split" -> {
            val orient = when (
                (obj["orientation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ) {
                "horizontal" -> PaneNodeJson.SplitOrientation.Horizontal
                "vertical" -> PaneNodeJson.SplitOrientation.Vertical
                else -> throw IllegalArgumentException("Split has unknown orientation")
            }
            val ratio = (obj["ratio"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.5
            val first = (obj["first"] as? JsonObject)
                ?.let { parsePaneNodeJson(it) }
                ?: throw IllegalArgumentException("Split missing first")
            val second = (obj["second"] as? JsonObject)
                ?.let { parsePaneNodeJson(it) }
                ?: throw IllegalArgumentException("Split missing second")
            PaneNodeJson.Split(
                orientation = orient,
                ratio = quantizeRatio(ratio),
                first = first,
                second = second,
            )
        }
        else -> throw IllegalArgumentException("Unknown pane kind: $kind")
    }
}
