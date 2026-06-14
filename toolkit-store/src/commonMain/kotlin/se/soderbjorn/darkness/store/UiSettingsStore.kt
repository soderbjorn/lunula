/**
 * Filesystem helpers for persisting [UiSettings] to a shared location.
 *
 * The toolkit deliberately avoids prescribing a `UiSettingsStore` interface
 * that apps must implement and inject. Instead, it ships a small set of
 * standalone functions: [defaultSharedThemesPath] returns a per-OS path under
 * an "Application Support" / "AppData" / "config" directory that any Darkness
 * app on the same machine can read, and [readUiSettings] / [writeUiSettings]
 * round-trip a [UiSettings] through that file.
 *
 * Apps own their persistence — call these helpers from your DI container,
 * view-model boot, or wherever fits. Termtastic doesn't use these helpers
 * at all; it persists via its server. Notegrow Electron uses them via
 * Node IPC to know the right user-data directory for the current platform.
 *
 * Note: there is no `jsMain` source set here — browser-side code can't
 * reach the filesystem. Electron apps must either receive the shared path
 * via IPC from the main process or read/write through `node:fs` from the
 * preload bridge.
 *
 * @see se.soderbjorn.darkness.core.UiSettings
 */
package se.soderbjorn.darkness.store

import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.recommendedColorSchemes

/**
 * Conventional filename for the shared Darkness theme/scheme definitions
 * file (custom themes, custom schemes, favorites). Cross-app: every
 * Darkness app on the same machine reads/writes this single file.
 *
 * @see defaultSharedThemesPath
 */
const val SHARED_THEMES_FILE_NAME = "themes.json"

/**
 * Conventional directory name (under the platform's user-data root) where
 * Darkness apps store their shared theme/UI state.
 */
const val UI_SETTINGS_DIR_NAME = "Darkness"

/**
 * Returns the conventional path where Darkness's shared theme/scheme
 * definitions live on the current platform.
 *
 * - **macOS**: `~/Library/Application Support/Darkness/themes.json`
 * - **Windows**: `%APPDATA%\Darkness\themes.json`
 * - **Linux**: `$XDG_CONFIG_HOME/darkness/themes.json` (defaults to `~/.config/darkness/`)
 * - **Android**: `<context.filesDir>/Darkness/themes.json` — see Android-specific helper
 * - **iOS**: `<Application Support>/Darkness/themes.json` via NSFileManager
 *
 * Apps may use this path or pick their own — nothing forces this location.
 *
 * @return the absolute path as a String, or null if the platform's user-data
 *   directory cannot be determined (e.g. Android with no Context yet).
 */
expect fun defaultSharedThemesPath(): String?

/**
 * Returns the conventional path where the per-app UI-settings file lives
 * for [appName] on the current platform. Per-app means: this file holds
 * **selections + UI prefs** (selected light/dark theme slots, appearance,
 * fonts, sizes, app-specific toggles) and is *not* shared across Darkness
 * apps. Each app passes its own short, lower-kebab-case identifier
 * (e.g. `"termtastic"`, `"notegrow"`, `"darkness-demo"`).
 *
 * - **macOS**: `~/Library/Application Support/Darkness/<appName>.json`
 * - **Windows**: `%APPDATA%\Darkness\<appName>.json`
 * - **Linux**: `$XDG_CONFIG_HOME/darkness/<appName>.json`
 * - **Android/iOS**: typically null — those platforms have native
 *   prefs/UserDefaults stories.
 *
 * @param appName short app identifier; appended as the filename stem.
 * @return absolute path, or null if the platform's user-data directory
 *   cannot be determined.
 */
expect fun defaultAppUiSettingsPath(appName: String): String?

/**
 * Reads a [UiSettings] from the given JSON file, or returns null if the file
 * doesn't exist or its contents can't be parsed.
 *
 * Custom user-defined colour schemes can be supplied via [extraSchemes] so
 * names that don't match the built-in [recommendedColorSchemes] still resolve.
 *
 * @param path the absolute file path to read from
 * @param extraSchemes additional colour schemes to consider when resolving names
 * @return the parsed [UiSettings], or null if the file is missing/unreadable
 */
expect fun readUiSettings(
    path: String,
    extraSchemes: List<ColorScheme> = emptyList(),
): UiSettings?

/**
 * Reads the raw file contents without parsing. Useful for apps that
 * persist additional, app-private keys alongside the toolkit's
 * [UiSettings] schema and want to keep those keys round-tripping
 * through the shared file untouched.
 *
 * @param path the absolute file path to read from
 * @return the file contents, or null if the file is missing/unreadable
 */
expect fun readUiSettingsRaw(path: String): String?

/**
 * Writes a [UiSettings] to the given JSON file, creating parent directories
 * as needed.
 *
 * Implementations must be **atomic from a reader's point of view**: a
 * concurrent or external [readUiSettings] either sees the previous file
 * contents or the new contents, never a partial write. Concretely each
 * actual writes to a sibling temp file and renames into place.
 *
 * @param path the absolute file path to write to
 * @param settings the settings to persist
 * @return `true` on success, `false` if the write failed
 */
expect fun writeUiSettings(path: String, settings: UiSettings): Boolean

/**
 * Atomic-write variant for callers that already have a serialized JSON
 * payload they want to persist verbatim (e.g. apps that round-trip
 * additional, app-private keys alongside the toolkit's [UiSettings]
 * schema and don't want those keys dropped through the
 * [UiSettings.toJsonString] sieve).
 *
 * Has the same atomicity guarantees and the same self-write suppression
 * gate as [writeUiSettings] — bytes are recorded so a co-resident
 * [watchUiSettings] won't bounce on the writer's own event.
 *
 * @param path the absolute file path to write to
 * @param jsonString a complete JSON document (typically a top-level object)
 * @return true on success, false if the write failed
 */
expect fun writeUiSettingsRaw(path: String, jsonString: String): Boolean

/**
 * Watches [path] for external changes and invokes [onChange] with the
 * freshly-parsed [UiSettings] whenever the file is modified by another
 * process (or another writer in this process).
 *
 * Implementations debounce so editors that fsync twice (or write+rename
 * on top of an existing file) only fire one callback per logical save.
 * Implementations also try to suppress events that originated from a
 * call to [writeUiSettings] from the same process, so a writer that also
 * watches doesn't loop on its own writes — the cheapest correct
 * implementation just re-reads the file on every event and skips the
 * callback when the bytes match what was last written from this process.
 *
 * The returned [Closeable] stops the watch when closed; closing is
 * idempotent in practice.
 *
 * @param path     the absolute file path to watch
 * @param onChange invoked on the watcher thread (JVM) or watcher queue
 *                 (iOS) — callers must marshal back to whatever thread
 *                 they care about themselves
 * @return a handle whose [Closeable.close] stops the watch
 */
expect fun watchUiSettings(
    path: String,
    onChange: (UiSettings) -> Unit,
): Closeable
