/**
 * Android implementation of the shared filesystem helpers.
 *
 * Android's per-app sandbox makes a "shared across all Darkness apps"
 * filesystem location not actually possible without bespoke storage
 * permissions, so [defaultSharedThemesPath] returns null on Android by
 * default — apps must pass an explicit path (e.g. `context.filesDir`)
 * to [readUiSettingsRaw]/[writeUiSettingsRaw]. The host app can also use
 * the JVM read/write helpers directly with any `java.io.File` path
 * it controls.
 *
 * Atomic writes are implemented via `File.renameTo` after writing to a
 * sibling `.tmp` file. [watchUiSettings] uses [android.os.FileObserver]
 * (Android's native inotify wrapper) for the same coalesce-and-suppress-
 * own-writes semantics as the JVM implementation.
 *
 * @see defaultSharedThemesPath
 */
package se.soderbjorn.darkness.store

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android does not expose a single "Application Support"-equivalent directory
 * shared across apps; this returns null and the host app should pass an
 * explicit path derived from `context.filesDir` or similar.
 *
 * @return null on Android — see file-level docs
 */
actual fun defaultSharedThemesPath(): String? = null

/**
 * Android stub for the per-app UI-settings path. Same rationale as
 * [defaultSharedThemesPath] — the host app should manage its own paths.
 */
actual fun defaultAppUiSettingsPath(appName: String): String? = null

/** Reads the raw file as text. Returns null on missing/IO error. */
actual fun readUiSettingsRaw(path: String): String? {
    val f = File(path)
    if (!f.exists()) return null
    return runCatching { f.readText() }.getOrNull()
}

/** Per-path "bytes most recently written from this process" — see JVM docs. */
private val lastWrittenBytes = ConcurrentHashMap<String, ByteArray>()

/**
 * Atomic-write a raw JSON string to disk. Writes to `<path>.tmp` then
 * renames into place via [File.renameTo]. Parent dirs are created on
 * demand. The written bytes are recorded so [watchUiSettings] can
 * suppress the matching event.
 */
actual fun writeUiSettingsRaw(path: String, jsonString: String): Boolean {
    return runCatching {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val bytes = jsonString.encodeToByteArray()
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("Atomic rename failed")
        lastWrittenBytes[path] = bytes
    }.isSuccess
}

/**
 * Watches [path] via [FileObserver] (Android's inotify wrapper). Debounces
 * 200ms and skips callbacks whose payload matches what this process last
 * wrote.
 *
 * Uses the API-29+ [FileObserver] constructor that takes a [File] when
 * available; falls back to the deprecated [String] constructor on older
 * SDKs. Both deliver the same events.
 */
@Suppress("DEPRECATION")
actual fun watchUiSettings(
    path: String,
    onChange: (String) -> Unit,
): Closeable {
    val target = File(path).absoluteFile
    val parent = target.parentFile ?: throw IllegalArgumentException("Path has no parent: $path")
    parent.mkdirs()

    val stopped = AtomicBoolean(false)
    val pendingFire = AtomicLong(0)

    fun fireDebounced() {
        val deadline = System.currentTimeMillis() + 200
        pendingFire.set(deadline)
        Thread {
            try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
            if (stopped.get()) return@Thread
            if (System.currentTimeMillis() < pendingFire.get()) return@Thread
            val bytes = runCatching { target.readBytes() }.getOrNull() ?: return@Thread
            val mine = lastWrittenBytes[path]
            if (mine != null && bytes.contentEquals(mine)) return@Thread
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) return@Thread
            runCatching { onChange(text) }
        }.apply { isDaemon = true }.start()
    }

    val mask = FileObserver.MODIFY or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.CLOSE_WRITE
    val observer: FileObserver = if (android.os.Build.VERSION.SDK_INT >= 29) {
        object : FileObserver(parent, mask) {
            override fun onEvent(event: Int, eventPath: String?) {
                if (eventPath == target.name) fireDebounced()
            }
        }
    } else {
        object : FileObserver(parent.absolutePath, mask) {
            override fun onEvent(event: Int, eventPath: String?) {
                if (eventPath == target.name) fireDebounced()
            }
        }
    }
    observer.startWatching()

    return Closeable {
        stopped.set(true)
        runCatching { observer.stopWatching() }
    }
}
