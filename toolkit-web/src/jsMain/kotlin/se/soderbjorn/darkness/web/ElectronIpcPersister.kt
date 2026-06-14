/* ElectronIpcPersister.kt
 * Browser-renderer-side [Persister] backed by `globalThis.darknessApi`
 * — the IPC bridge an Electron host's preload script exposes (notegrow,
 * darkness-demo, and any other Darkness Electron app share the same
 * shape: `readUiSettings` / `writeUiSettings` / `readLayoutState` /
 * `writeLayoutState`).
 *
 * Use this when you're running inside Electron and want theme/layout
 * state to live in the OS-conventional shared / per-app files instead
 * of localStorage. Falls back gracefully (returns `null` on read,
 * silently swallows write) when the bridge isn't installed — apps can
 * use [tryElectronIpcPersister] to detect that and pick a different
 * backend (e.g. [LocalStoragePersister]) for the browser-only case. */
package se.soderbjorn.darkness.web

import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.Persister

/**
 * IPC-backed [Persister] that maps:
 * - [PersistKeys.UI_SETTINGS] → `darknessApi.readUiSettings()` / `writeUiSettings()`
 * - [PersistKeys.LAYOUT] → `darknessApi.readLayoutState()` / `writeLayoutState()`
 *   (the channel name is historical — it stores the *app's* tab list,
 *   not the toolkit's geometry state)
 * - [PersistKeys.LAYOUT_STATE] → `darknessApi.readLayoutToolkitState()` /
 *   `writeLayoutToolkitState()` (toolkit-owned per-tab pane geometry,
 *   layout preset, and paneOrder — see
 *   `se.soderbjorn.darkness.web.shell.PersistedLayoutState`)
 * - any other key → null on read, no-op on write (the bridge only owns
 *   the toolkit-canonical keys).
 *
 * Reads also fall back to the boot-time snapshots packed into
 * `globalThis.__darknessSettings` / `__darknessLayoutState` /
 * `__darknessLayoutToolkitState` when the IPC channel hasn't returned
 * yet (rare; the bridge's invoke is asynchronous but cheap).
 */
class ElectronIpcPersister : Persister {
    override suspend fun read(key: String): String? = when (key) {
        PersistKeys.UI_SETTINGS -> readUiSettings()
        PersistKeys.LAYOUT -> readLayoutState()
        PersistKeys.LAYOUT_STATE -> readLayoutToolkitState()
        else -> null
    }

    override suspend fun write(key: String, value: String) {
        when (key) {
            PersistKeys.UI_SETTINGS -> writeUiSettings(value)
            PersistKeys.LAYOUT -> writeLayoutState(value)
            PersistKeys.LAYOUT_STATE -> writeLayoutToolkitState(value)
            else -> { /* no-op */ }
        }
    }

    private suspend fun readUiSettings(): String? {
        val api = js("globalThis.darknessApi") ?: return readBootSnapshot("__darknessSettings")
        val fn = js("api && api.readUiSettings")
        if (js("typeof fn !== 'function'") as Boolean) return readBootSnapshot("__darknessSettings")
        return invokeAsync(fn, api) ?: readBootSnapshot("__darknessSettings")
    }

    private suspend fun writeUiSettings(value: String) {
        val api = js("globalThis.darknessApi") ?: return
        val fn = js("api && api.writeUiSettings")
        if (js("typeof fn !== 'function'") as Boolean) return
        invokeAsync(fn, api, value)
    }

    private suspend fun readLayoutState(): String? {
        val api = js("globalThis.darknessApi") ?: return readBootSnapshot("__darknessLayoutState")
        val fn = js("api && api.readLayoutState")
        if (js("typeof fn !== 'function'") as Boolean) return readBootSnapshot("__darknessLayoutState")
        return invokeAsync(fn, api) ?: readBootSnapshot("__darknessLayoutState")
    }

    private suspend fun writeLayoutState(value: String) {
        val api = js("globalThis.darknessApi") ?: return
        val fn = js("api && api.writeLayoutState")
        if (js("typeof fn !== 'function'") as Boolean) return
        invokeAsync(fn, api, value)
    }

    private suspend fun readLayoutToolkitState(): String? {
        val api = js("globalThis.darknessApi") ?: return readBootSnapshot("__darknessLayoutToolkitState")
        val fn = js("api && api.readLayoutToolkitState")
        if (js("typeof fn !== 'function'") as Boolean) return readBootSnapshot("__darknessLayoutToolkitState")
        return invokeAsync(fn, api) ?: readBootSnapshot("__darknessLayoutToolkitState")
    }

    private suspend fun writeLayoutToolkitState(value: String) {
        val api = js("globalThis.darknessApi") ?: return
        val fn = js("api && api.writeLayoutToolkitState")
        if (js("typeof fn !== 'function'") as Boolean) return
        invokeAsync(fn, api, value)
    }

    private fun readBootSnapshot(name: String): String? =
        js("globalThis[name] || null") as? String

    /**
     * Calls the Electron IPC function (which returns a Promise) and
     * suspends until it resolves. The promise's value is the IPC
     * result string (or null/undefined for absent state).
     */
    private suspend fun invokeAsync(fn: dynamic, thisArg: dynamic, arg: String? = null): String? =
        suspendCancellableCoroutine { cont ->
            val promise = if (arg == null) fn.call(thisArg) else fn.call(thisArg, arg)
            promise.then(
                { value: dynamic -> cont.resume(value as? String) },
                { _: dynamic -> cont.resume(null) },
            )
        }
}

/**
 * Returns an [ElectronIpcPersister] when `globalThis.darknessApi` is
 * present (i.e. running inside an Electron renderer with the standard
 * darkness preload script), or `null` when running in a plain browser.
 *
 * Apps that want to support both contexts can do:
 * ```
 * val persister = tryElectronIpcPersister() ?: LocalStoragePersister("myapp")
 * ```
 */
fun tryElectronIpcPersister(): Persister? {
    val present = (js("typeof globalThis.darknessApi !== 'undefined'") as Boolean)
    return if (present) ElectronIpcPersister() else null
}
