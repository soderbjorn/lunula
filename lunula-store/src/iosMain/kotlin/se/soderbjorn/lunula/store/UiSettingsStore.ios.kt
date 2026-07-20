/**
 * iOS implementation of the shared filesystem helpers.
 *
 * Stubbed for v1 — iOS apps in the Lunula family currently consume
 * settings via the host app's existing flow (e.g. termtastic's server
 * fetch). When a fully-local iOS app needs filesystem persistence, this
 * implementation should resolve a Documents/Application Support path via
 * `NSFileManager` and use `NSData`/`NSString` for read/write.
 *
 * [watchUiSettings] returns a no-op [Closeable]; a future iOS impl can
 * either use `dispatch_source_create(DISPATCH_SOURCE_TYPE_VNODE, ...)`
 * for kqueue-backed change events or fall back to a 1-second polling
 * loop on `NSFileManager.attributesOfItem(atPath:)` modification date.
 *
 * @see defaultSharedThemesPath
 */
package se.soderbjorn.lunula.store

/** iOS stub — see file-level docs. */
actual fun defaultSharedThemesPath(): String? = null

/** iOS stub — see file-level docs. */
actual fun defaultAppUiSettingsPath(appName: String): String? = null

/** iOS stub — see file-level docs. */
actual fun readUiSettingsRaw(path: String): String? = null

/** iOS stub — see file-level docs. */
actual fun writeUiSettingsRaw(path: String, jsonString: String): Boolean = false

/**
 * iOS stub — returns a [Closeable] whose [Closeable.close] is a no-op.
 * Replace with a `DispatchSource`/`NSFileManager`-backed watcher when
 * iOS gains local-only theme persistence.
 */
actual fun watchUiSettings(
    path: String,
    onChange: (String) -> Unit,
): Closeable = object : Closeable {
    override fun close() { /* no-op stub */ }
}
