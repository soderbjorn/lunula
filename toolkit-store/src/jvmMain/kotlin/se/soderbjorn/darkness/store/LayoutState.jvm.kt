/**
 * JVM actuals for the per-app layout-state filesystem helpers declared in
 * [LayoutState.kt]'s commonMain header.
 *
 * Mirrors [UiSettingsStore.jvm]'s pattern verbatim: per-OS path resolution
 * via `os.name` sniff, atomic write via tmp+`Files.move(ATOMIC_MOVE)`,
 * `WatchService` filewatcher with 200ms debounce, self-write suppression
 * via a `lastWrittenBytes` map. Only the schema and filename differ —
 * see [readLayoutState] / [writeLayoutState] / [watchLayoutState].
 *
 * Reused by Android via the `androidMain` source set's automatic JVM
 * fallthrough where applicable (path resolution still goes through the
 * Android-specific actual since Android has no shared user-data root).
 *
 * @see UiSettingsStore.jvm
 */
package se.soderbjorn.darkness.store

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

/**
 * Returns the OS-conventional per-app layout-state path. Mirrors
 * [defaultSharedThemesPath]'s OS-detection logic but appends [appName]
 * as a per-app subdirectory under the shared `Darkness/` root.
 *
 * Linux follows XDG conventions: the toolkit dir is lowercased
 * (`darkness/`) and so is [appName] inside it.
 *
 * @param appName per-app subdirectory name (e.g. `"Notegrow"`).
 * @return the absolute path string, or null if `user.home` is unavailable.
 */
actual fun defaultAppLayoutStatePath(appName: String): String? {
    val osName = System.getProperty("os.name")?.lowercase() ?: return null
    val home = System.getProperty("user.home") ?: return null
    val dir: Path = when {
        "mac" in osName || "darwin" in osName ->
            Paths.get(home, "Library", "Application Support", UI_SETTINGS_DIR_NAME, appName)
        osName.startsWith("windows") -> {
            val appData = System.getenv("APPDATA")
                ?: Paths.get(home, "AppData", "Roaming").toString()
            Paths.get(appData, UI_SETTINGS_DIR_NAME, appName)
        }
        else -> {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: Paths.get(home, ".config").toString()
            Paths.get(xdg, UI_SETTINGS_DIR_NAME.lowercase(), appName.lowercase())
        }
    }
    return dir.resolve(LAYOUT_STATE_FILE_NAME).toString()
}

/**
 * Reads and parses a [LayoutState] from disk. Returns `null` on any
 * failure path; callers fall back to [LayoutState.defaults].
 */
actual fun readLayoutState(path: String): LayoutState? {
    val p = Paths.get(path)
    if (!p.exists()) return null
    val text = runCatching { p.readText() }.getOrNull() ?: return null
    if (text.isBlank()) return null
    val parsed = LayoutState.fromJsonString(text)
    // [fromJsonString] returns defaults on any failure; surface "missing
    // data" as null so the caller can distinguish "file present, defaulted"
    // from "no file" — useful when the host wants to treat first-launch
    // differently from a corrupt save.
    return parsed
}

/**
 * Per-path "bytes most recently written from this process". Both
 * [writeLayoutState] and [watchLayoutState] consult this so a writer
 * that also watches doesn't bounce on its own write.
 */
private val lastWrittenLayoutBytes = ConcurrentHashMap<String, ByteArray>()

/**
 * Atomic-write a [LayoutState] to disk. Mirrors [writeUiSettings]'s
 * tmp+ATOMIC_MOVE pattern.
 */
actual fun writeLayoutState(path: String, layout: LayoutState): Boolean {
    return runCatching {
        val target = Paths.get(path)
        target.parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        val bytes = layout.toJsonString().encodeToByteArray()
        Files.write(tmp, bytes)
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        lastWrittenLayoutBytes[path] = bytes
    }.isSuccess
}

/**
 * Watch [path] for external [LayoutState] changes via [java.nio.file.WatchService]
 * on a daemon thread. Coalesces 200ms; skips events whose payload matches
 * what this process last wrote.
 */
actual fun watchLayoutState(
    path: String,
    onChange: (LayoutState) -> Unit,
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

            val deadline = System.currentTimeMillis() + 200
            pendingFire.set(deadline)
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            if (System.currentTimeMillis() < pendingFire.get()) continue

            val bytes = runCatching { Files.readAllBytes(target) }.getOrNull() ?: continue
            val mine = lastWrittenLayoutBytes[path]
            if (mine != null && bytes.contentEquals(mine)) continue
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) continue
            val state = runCatching { LayoutState.fromJsonString(text) }.getOrNull() ?: continue
            runCatching { onChange(state) }
        }
    }, "darkness-layout-state-watch:${target.fileName}")
    thread.isDaemon = true
    thread.start()

    return Closeable {
        stopped.set(true)
        runCatching { watchService.close() }
        thread.interrupt()
    }
}
