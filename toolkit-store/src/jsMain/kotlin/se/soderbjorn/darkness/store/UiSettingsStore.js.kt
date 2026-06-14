/**
 * JS actuals for the shared filesystem helpers.
 *
 * Browser-side code cannot reach the filesystem — atomic writes, file
 * watchers, OS-conventional path resolution all live in the JVM/native
 * actuals. Apps running in a browser context (notegrow Electron's
 * renderer process, future PWA-style apps) consume `UiSettings` through
 * an IPC bridge to a host process that owns disk access; on JS these
 * helpers therefore return `null` / `false` so a renderer that
 * accidentally calls them gets a no-op instead of a runtime crash.
 *
 * The model class itself ([se.soderbjorn.darkness.core.UiSettings] with
 * its `toJsonString` / `fromJsonString` round-trip) lives in
 * `toolkit-core/commonMain` and is fully usable on JS — only the
 * filesystem helpers are stubbed here.
 *
 * @see UiSettingsStore
 */
package se.soderbjorn.darkness.store

import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.UiSettings

/** JS stub — see file-level docs. */
actual fun defaultSharedThemesPath(): String? = null

/** JS stub — see file-level docs. */
actual fun defaultAppUiSettingsPath(appName: String): String? = null

/** JS stub — see file-level docs. */
actual fun readUiSettings(path: String, extraSchemes: List<ColorScheme>): UiSettings? = null

/** JS stub — see file-level docs. */
actual fun readUiSettingsRaw(path: String): String? = null

/** JS stub — see file-level docs. */
actual fun writeUiSettings(path: String, settings: UiSettings): Boolean = false

/** JS stub — see file-level docs. */
actual fun writeUiSettingsRaw(path: String, jsonString: String): Boolean = false

/**
 * JS stub — returns a [Closeable] whose [Closeable.close] is a no-op.
 * Browser renderers that want change notifications should subscribe to
 * the host process's IPC channel instead (e.g. Electron's
 * `darkness:uiSettingsChanged`).
 */
actual fun watchUiSettings(
    path: String,
    onChange: (UiSettings) -> Unit,
): Closeable = object : Closeable {
    override fun close() { /* no-op stub */ }
}
