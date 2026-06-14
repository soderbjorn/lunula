/**
 * iOS actuals for the per-app layout-state filesystem helpers.
 *
 * Stubbed for v1 — same posture as [UiSettingsStore.ios]: iOS apps in the
 * Darkness family currently consume layout via the host app's existing
 * flow (e.g. termtastic's server). When a fully-local iOS app needs
 * filesystem persistence, this implementation should resolve a per-app
 * Application Support directory via `NSFileManager` and use
 * `NSData`/`NSString` for read/write; [watchLayoutState] can use a
 * `dispatch_source_create(DISPATCH_SOURCE_TYPE_VNODE, ...)` kqueue watcher
 * or fall back to polling `NSFileManager.attributesOfItem(atPath:)`.
 *
 * @see UiSettingsStore.ios
 */
package se.soderbjorn.darkness.store

/** iOS stub — see file-level docs. */
actual fun defaultAppLayoutStatePath(appName: String): String? = null

/** iOS stub — see file-level docs. */
actual fun readLayoutState(path: String): LayoutState? = null

/** iOS stub — see file-level docs. */
actual fun writeLayoutState(path: String, layout: LayoutState): Boolean = false

/**
 * iOS stub — returns a [Closeable] whose [Closeable.close] is a no-op.
 * Replace with a `DispatchSource`/`NSFileManager`-backed watcher when
 * iOS gains local-only layout persistence.
 */
actual fun watchLayoutState(
    path: String,
    onChange: (LayoutState) -> Unit,
): Closeable = object : Closeable {
    override fun close() { /* no-op stub */ }
}
