/**
 * JS actuals for the per-app layout-state filesystem helpers.
 *
 * Same posture as [UiSettingsStore.js]: browsers can't reach disk, so
 * these helpers return `null` / `false`. Apps running in Electron pass
 * the boot snapshot in via `globalThis.__darknessLayoutState` (parsed
 * with [LayoutState.fromJsonString]) and write back through an IPC
 * bridge — the renderer never touches the filesystem directly.
 *
 * The model classes ([LayoutState], [SidebarState], [TabState],
 * [PaneNodeJson]) and the JSON round-trip helpers live in commonMain
 * and are fully usable on JS — only the filesystem helpers are stubbed.
 *
 * @see UiSettingsStore.js
 */
package se.soderbjorn.lunula.store

/** JS stub — see file-level docs. */
actual fun defaultAppLayoutStatePath(appName: String): String? = null

/** JS stub — see file-level docs. */
actual fun readLayoutState(path: String): LayoutState? = null

/** JS stub — see file-level docs. */
actual fun writeLayoutState(path: String, layout: LayoutState): Boolean = false

/**
 * JS stub — returns a [Closeable] whose [Closeable.close] is a no-op.
 */
actual fun watchLayoutState(
    path: String,
    onChange: (LayoutState) -> Unit,
): Closeable = object : Closeable {
    override fun close() { /* no-op stub */ }
}
