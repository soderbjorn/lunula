/**
 * Tiny SAM abstraction over the storage backend a web/Electron app uses
 * to persist the JSON-encoded [se.soderbjorn.darkness.core.ThemeSnapshot].
 *
 * Apps wire one of these into their `onChange` callback (typically via
 * [DefaultThemeManagerState.toSnapshot] + [se.soderbjorn.darkness.core.ThemeSnapshot.encodeAsJsonObject])
 * and read from it on launch. The toolkit ships [localStorageThemeSnapshotStorage]
 * for browsers / Electron renderer; apps with a server-backed store
 * implement the SAM directly with their HTTP / IPC client.
 *
 * @see DefaultThemeManagerState
 * @see se.soderbjorn.darkness.core.ThemeSnapshot
 */
package se.soderbjorn.darkness.web.themeeditor

/**
 * Abstraction over the read / write side of one persisted snapshot blob.
 *
 * Two methods (read + write) so this isn't a single-abstract-method (SAM)
 * type — Kotlin requires `fun interface` to have exactly one abstract
 * function, which doesn't fit a load/save pair. Apps still typically
 * implement it with an anonymous `object` (one or two lines per method).
 *
 * Implementations need not be thread-safe; the toolkit only ever calls
 * [read] and [write] from the main JS event loop.
 */
interface ThemeSnapshotStorage {
    /**
     * Read the previously-written snapshot JSON. Returns `null` when the
     * backend has nothing stored yet (first launch) or when the backend
     * is unavailable (e.g. localStorage disabled by the user).
     */
    fun read(): String?

    /**
     * Persist [json] as the snapshot blob. Implementations should be
     * fire-and-forget; the toolkit calls this after every state mutation
     * inside an `onChange` callback, so blocking it would jank the UI.
     *
     * @param json the JSON-encoded snapshot, typically produced by
     *   [se.soderbjorn.darkness.core.ThemeSnapshot.encodeAsJsonObject]
     *   followed by `.toString()`.
     */
    fun write(json: String)
}

/**
 * In-browser [ThemeSnapshotStorage] backed by `globalThis.localStorage`.
 *
 * Used by notegrow (Electron renderer) and any other Darkness app that
 * stores the snapshot as one JSON blob in the browser's local storage.
 * Termtastic uses a server `SettingsPersister` instead and wires its own
 * implementation of the SAM.
 *
 * The implementation guards against missing `localStorage` (private
 * browsing modes, sandboxed iframes, SSR) so the helper is safe to call
 * unconditionally on launch:
 *  - [read] returns `null` when localStorage is unavailable or the key
 *    has never been written.
 *  - [write] silently no-ops when localStorage is unavailable.
 *
 * @param key the localStorage key to read / write under. Apps typically
 *   namespace this (e.g. `"notegrow.themeSnapshot"`).
 * @return a [ThemeSnapshotStorage] that round-trips through the named
 *   localStorage key.
 */
fun localStorageThemeSnapshotStorage(key: String): ThemeSnapshotStorage =
    object : ThemeSnapshotStorage {
        override fun read(): String? {
            val raw = js("(globalThis.localStorage && globalThis.localStorage.getItem(key)) || null")
            return raw as? String
        }

        override fun write(json: String) {
            js("globalThis.localStorage && globalThis.localStorage.setItem(key, json)")
            Unit
        }
    }
