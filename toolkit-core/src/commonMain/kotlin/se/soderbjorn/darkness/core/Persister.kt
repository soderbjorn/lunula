/* Persister.kt
 * Tiny KV abstraction the toolkit uses to read/write per-app state
 * (theme blob, ui settings, layout state) without taking a hard
 * dependency on any backend (Electron IPC, HTTP, localStorage, JVM
 * file I/O, …). Apps implement this interface against whatever
 * storage they own; the toolkit drives reads on mount and writes on
 * change. */
package se.soderbjorn.darkness.core

/**
 * Backend-agnostic key→string store the toolkit uses for persistence.
 *
 * Implementations are app-supplied and intentionally minimal: the
 * toolkit does not need transactions, batching, or typed values — it
 * always writes the full serialized JSON for a given key. Two methods
 * is the entire surface.
 *
 * ### Callers
 * Held by [se.soderbjorn.darkness.web.shell.AppShellSpec] and read /
 * written by `mountAppShell` for the standard
 * [PersistKeys] (`theme`, `uiSettings`, `layout`). Apps may also use
 * the same instance to persist their own state under app-specific
 * keys — there is no namespace partitioning at this layer.
 *
 * ### Threading
 * `read` and `write` are `suspend` so implementations can do their
 * I/O off the caller's thread. The toolkit calls them from a
 * coroutine scope it owns; per-key write coalescing is the toolkit's
 * job, not the implementer's.
 *
 * @see PersistKeys
 */
interface Persister {
    /**
     * Reads the value previously written under [key], or `null` if
     * no value has been written. Implementations must not throw on
     * "missing key" — return `null`.
     *
     * @param key opaque storage key (use [PersistKeys] for the
     *   toolkit-canonical names; apps may use any other key for
     *   their own state).
     * @return the value previously written, or `null` if absent.
     */
    suspend fun read(key: String): String?

    /**
     * Writes [value] under [key], overwriting any previous value.
     * Implementations should make the write durable before
     * returning so a subsequent process restart sees the new value.
     *
     * @param key opaque storage key.
     * @param value full serialized value (typically a JSON string).
     */
    suspend fun write(key: String, value: String)
}

/**
 * Canonical keys the toolkit uses with [Persister]. Apps should not
 * reuse these for their own state; pick a different prefix.
 */
object PersistKeys {
    /** Serialized [UiSettings] (theme picks + per-section overrides + appearance). */
    const val UI_SETTINGS: String = "darkness.uiSettings"

    /**
     * Serialized tab + pane *identity* state — tab list, per-tab pane id
     * list, active tab id. Geometry, preset, and importance order live
     * separately under [LAYOUT_STATE]. Used only in local mode (no
     * `TabSource`); apps with their own tab/pane source own identity
     * persistence themselves.
     */
    const val LAYOUT: String = "darkness.layout"

    /**
     * Serialized toolkit-owned layout state shared across local + source
     * mode: per-tab active [se.soderbjorn.darkness.web.layout.LayoutPreset]
     * key, per-tab pane importance order (head = primary slot), and per-
     * pane geometry (x/y/width/height/zIndex/maximized).
     *
     * The toolkit's `mountAppShell` reads/writes this through the app's
     * supplied [Persister]; apps don't need to know about the schema —
     * pane geometry is no longer part of the app's [TabListSnapshot].
     */
    const val LAYOUT_STATE: String = "darkness.layoutState"

    /**
     * Serialized [se.soderbjorn.darkness.store.WorldsState] — the "worlds"
     * container one level above [se.soderbjorn.darkness.store.LayoutState].
     * Each world owns its own tab/pane [LayoutState] and an optional
     * per-world theme selection. Used only in local mode (demo / the
     * toolkit's own showcase); source-mode apps (e.g. Lunamux) receive
     * the world model from their server and don't persist it here.
     *
     * A missing key means "no worlds persisted yet" — local-mode callers
     * migrate the single [LAYOUT_STATE] blob into one default world.
     */
    const val WORLDS: String = "darkness.worlds"

    /**
     * Legacy serialized theme-snapshot key. No longer written by the toolkit;
     * retained only so the server can *synthesize* an approximate legacy blob
     * under this key for pre-revamp mobile apps. New code uses [THEME_V2_CUSTOM]
     * and [THEME_V2_SELECTION].
     */
    const val THEME_SNAPSHOT: String = "darkness.themeSnapshot"

    /**
     * Serialized array of the user's custom [Theme] definitions. **Shared**
     * across Darkness apps via the `themes.json` file (see [SHARED_THEMES_KEYS]).
     */
    const val THEME_V2_CUSTOM: String = "darkness.theme.v2.custom"

    /**
     * Serialized per-app theme selection: `{darkThemeName, lightThemeName,
     * appearance}`. Each app remembers its own slot picks.
     *
     * @see ThemeSnapshotV2
     */
    const val THEME_V2_SELECTION: String = "darkness.theme.v2.selection"

    /**
     * Serialized JSON array of theme *names* the user has starred / favorited
     * (e.g. `["Solarized Dark","Ayu Light"]`). Per-app (not shared via
     * `themes.json`): each app remembers its own starred set. A missing key
     * means "nothing starred". Names may reference either built-in or custom
     * themes; unknown names are simply ignored at render time.
     *
     * The theme picker (web/Mac + mobile) reads this to hoist starred themes to
     * the top of the single theme list and to draw the filled/empty star.
     *
     * @see ThemeSnapshotV2.favorites
     */
    const val THEME_V2_FAVORITES: String = "darkness.theme.v2.favorites"

    /**
     * Serialized sidebar UI state — currently the set of collapsed
     * section ids in the default tabs→panes tree. JSON array of string
     * ids; missing key means every section is open.
     */
    const val SIDEBAR_STATE: String = "darkness.sidebarState"

    /**
     * Serialized custom hotkey bindings: a JSON object mapping action id
     * → array of chord specs (e.g. `{"toolkit.pane.focusLeft":
     * ["ctrl+alt+ArrowLeft"]}`). A present action id fully *replaces* the
     * action's default chords; a missing id means "use the defaults".
     *
     * Written by the toolkit's web shell when the user edits bindings in
     * the hotkey-config dialog, and read once at mount to apply the
     * user's overrides. Apps that sync UI settings from a server should
     * also re-apply this key on live settings pushes (see
     * `HotkeyBindings.applyCustomBindingsJson` in toolkit-web).
     */
    const val HOTKEY_BINDINGS: String = "darkness.hotkeyBindings"
}

/**
 * In-memory [Persister] for tests and skeleton apps. Not durable —
 * data lives only as long as the process. Apps in production should
 * implement their own durable persister.
 */
class InMemoryPersister : Persister {
    private val store = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) {
        store[key] = value
    }
}
