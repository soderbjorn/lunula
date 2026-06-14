/**
 * JVM implementation of the shared filesystem helpers declared in commonMain.
 *
 * Resolves the per-OS user-data directory using a small platform sniff:
 *
 * - **macOS** (`os.name` contains `"mac"`): `$HOME/Library/Application Support/Darkness/`
 * - **Windows** (`os.name` starts with `"windows"`): `%APPDATA%\Darkness\`
 * - **Linux / other**: `$XDG_CONFIG_HOME/darkness/`, falling back to `$HOME/.config/darkness/`
 *
 * This implementation is also reused by the Android target via the
 * `androidMain` source set's automatic JVM fallthrough — though apps that
 * want a Context-aware path on Android should use the Android-specific
 * helper that takes a `Context.filesDir`.
 *
 * Atomic writes are implemented as "write to `<path>.tmp`, then
 * `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`" so concurrent readers
 * (in this process or another Darkness app on the same machine) never
 * see a half-written JSON. The same byte payload is also stashed in a
 * per-path "last written" map so [watchUiSettings] can suppress the
 * inevitable self-fired event.
 *
 * @see defaultSharedThemesPath
 * @see readUiSettings
 * @see writeUiSettings
 * @see watchUiSettings
 */
package se.soderbjorn.darkness.store

import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.recommendedColorSchemes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Resolve the OS-conventional Darkness data directory.
 *
 * @return the directory path, or null if `user.home`/`os.name` is unavailable.
 */
private fun darknessDataDir(): Path? {
    val osName = System.getProperty("os.name")?.lowercase() ?: return null
    val home = System.getProperty("user.home") ?: return null
    return when {
        "mac" in osName || "darwin" in osName ->
            Paths.get(home, "Library", "Application Support", UI_SETTINGS_DIR_NAME)
        osName.startsWith("windows") -> {
            val appData = System.getenv("APPDATA") ?: Paths.get(home, "AppData", "Roaming").toString()
            Paths.get(appData, UI_SETTINGS_DIR_NAME)
        }
        else -> {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: Paths.get(home, ".config").toString()
            Paths.get(xdg, UI_SETTINGS_DIR_NAME.lowercase())
        }
    }
}

/**
 * Returns the OS-conventional shared themes file path for the current JVM.
 *
 * @return the absolute path string, or null if `user.home` is unavailable
 */
actual fun defaultSharedThemesPath(): String? =
    darknessDataDir()?.resolve(SHARED_THEMES_FILE_NAME)?.toString()

/**
 * Returns the OS-conventional per-app UI-settings file path for the
 * current JVM. The file lives in the same Darkness directory as the
 * shared themes file, so every app's per-app file is a flat sibling.
 *
 * @param appName short app identifier; used as the JSON filename stem.
 * @return the absolute path string, or null if `user.home` is unavailable.
 */
actual fun defaultAppUiSettingsPath(appName: String): String? =
    darknessDataDir()?.resolve("$appName.json")?.toString()

/**
 * Reads and parses a [UiSettings] from a JSON file on disk.
 *
 * Returns null on missing file, IO error, or unparseable JSON. Callers
 * should fall back to [UiSettings.defaults] in that case.
 *
 * @param path the absolute file path to read
 * @param extraSchemes user-defined custom schemes to consider during name lookup
 * @return the parsed settings, or null
 */
actual fun readUiSettings(path: String, extraSchemes: List<ColorScheme>): UiSettings? {
    val text = readUiSettingsRaw(path) ?: return null
    if (text.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }
        .getOrNull() ?: return null
    val pool = if (extraSchemes.isEmpty()) recommendedColorSchemes else recommendedColorSchemes + extraSchemes
    return UiSettings.resolveAgainst(obj, pool)
}

/** Reads the raw file as text. Returns null on missing/IO error. */
actual fun readUiSettingsRaw(path: String): String? {
    val p = Paths.get(path)
    if (!p.exists()) return null
    return runCatching { p.readText() }.getOrNull()
}

/**
 * Per-path "bytes most recently written from this process". Both
 * [writeUiSettings] and [watchUiSettings] consult this so a writer that
 * also watches doesn't bounce on its own write. Cleared when the file
 * is overwritten by a third party (the entry simply stops matching).
 */
private val lastWrittenBytes = ConcurrentHashMap<String, ByteArray>()

/**
 * Writes a [UiSettings] to a JSON file on disk atomically.
 *
 * Writes to `<path>.tmp` first and then renames into place via
 * [Files.move] with [StandardCopyOption.ATOMIC_MOVE]. Parent dirs are
 * created on demand. The written bytes are recorded in [lastWrittenBytes]
 * so a co-resident [watchUiSettings] suppresses the matching event.
 *
 * @param path the absolute file path to write to
 * @param settings the settings to persist
 * @return true on success, false if any step failed
 */
actual fun writeUiSettings(path: String, settings: UiSettings): Boolean =
    writeUiSettingsRaw(path, settings.toJsonString())

/**
 * Atomic-write a raw JSON string. Implementation detail shared with the
 * structured [writeUiSettings] entry point.
 */
actual fun writeUiSettingsRaw(path: String, jsonString: String): Boolean {
    return runCatching {
        val target = Paths.get(path)
        target.parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        val bytes = jsonString.encodeToByteArray()
        Files.write(tmp, bytes)
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Some filesystems (e.g. across-mount on Linux, certain SMB
            // shares) reject ATOMIC_MOVE; fall back to a non-atomic
            // replace, which is still strictly better than partial writes.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        lastWrittenBytes[path] = bytes
    }.isSuccess
}

/**
 * Watches the parent directory of [path] for change events on the named
 * file via [java.nio.file.WatchService] on a daemon thread. Coalesces
 * burst events with a 200ms debounce window. On each fire we re-read the
 * file; if the bytes match what we ourselves last wrote we skip the
 * callback (avoids self-feedback when the same process writes and
 * watches).
 *
 * Returned [Closeable] stops the daemon and closes the watch service.
 *
 * @param path the absolute file path to watch
 * @param onChange invoked on the watcher's daemon thread with the parsed [UiSettings]
 * @return a handle that, when closed, stops watching
 */
actual fun watchUiSettings(
    path: String,
    onChange: (UiSettings) -> Unit,
): Closeable {
    val target = Paths.get(path).toAbsolutePath()
    val parent = target.parent ?: throw IllegalArgumentException("Path has no parent: $path")
    runCatching { Files.createDirectories(parent) }
    val watchService = parent.fileSystem.newWatchService()
    parent.register(
        watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
    )

    val stopped = AtomicBoolean(false)
    val pendingFire = AtomicLong(0)

    val thread = Thread({
        while (!stopped.get()) {
            val key: WatchKey = try {
                watchService.take()
            } catch (_: InterruptedException) {
                break
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                break
            }
            var matched = false
            for (event in key.pollEvents()) {
                val ctx = event.context() as? Path ?: continue
                if (ctx.fileName.toString() == target.fileName.toString()) {
                    matched = true
                }
            }
            if (!key.reset()) break
            if (!matched) continue

            // Coalesce: any extra events that fire in the next 200ms
            // collapse into the same logical "saved once" callback.
            val deadline = System.currentTimeMillis() + 200
            pendingFire.set(deadline)
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            if (System.currentTimeMillis() < pendingFire.get()) continue

            val bytes = runCatching { Files.readAllBytes(target) }.getOrNull() ?: continue
            val mine = lastWrittenBytes[path]
            if (mine != null && bytes.contentEquals(mine)) continue
            val settings = runCatching {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isBlank()) null
                else (Json.parseToJsonElement(text) as? JsonObject)
                    ?.let { UiSettings.resolveAgainst(it, recommendedColorSchemes) }
            }.getOrNull() ?: continue
            runCatching { onChange(settings) }
        }
    }, "darkness-ui-settings-watch:${target.fileName}")
    thread.isDaemon = true
    thread.start()

    return Closeable {
        stopped.set(true)
        runCatching { watchService.close() }
        thread.interrupt()
    }
}
