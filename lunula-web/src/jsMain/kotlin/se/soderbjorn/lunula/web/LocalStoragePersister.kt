/* LocalStoragePersister.kt
 * Browser-backed [Persister] using `globalThis.localStorage`. Suitable
 * for the skeleton example and for any web-only host that doesn't
 * need cross-window or server-side persistence. Production apps with
 * Electron / server backends should implement their own [Persister]
 * (see notegrow's Electron-IPC bridge or termtastic's HTTP-backed
 * SettingsPersister). */
package se.soderbjorn.lunula.web

import se.soderbjorn.lunula.core.Persister

/**
 * Reads and writes to the browser's `localStorage` under an app-supplied
 * prefix. The prefix prevents collision when multiple lunula
 * apps share an origin (e.g. running in the same Electron renderer).
 *
 * @property namespace prefix prepended to every key passed to
 *   [Persister.read] / [Persister.write], joined with `:`. So a
 *   namespace of `"notegrow"` and a key of `"darkness.theme"` becomes
 *   the underlying localStorage key `"notegrow:darkness.theme"`.
 *
 * @see se.soderbjorn.lunula.core.Persister
 */
class LocalStoragePersister(private val namespace: String) : Persister {
    override suspend fun read(key: String): String? {
        val full = compose(key)
        val raw = js("(globalThis.localStorage && globalThis.localStorage.getItem(full)) || null")
        return raw as? String
    }

    override suspend fun write(key: String, value: String) {
        val full = compose(key)
        js("globalThis.localStorage && globalThis.localStorage.setItem(full, value)")
    }

    private fun compose(key: String): String = "$namespace:$key"
}
