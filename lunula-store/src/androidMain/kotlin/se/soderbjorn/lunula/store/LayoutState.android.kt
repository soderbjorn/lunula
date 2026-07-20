/**
 * Android actuals for the per-app layout-state filesystem helpers.
 *
 * Mirrors [UiSettingsStore.android]'s posture: Android's per-app sandbox
 * makes a "shared across all Lunula apps" path impossible without
 * bespoke storage permissions, so [defaultAppLayoutStatePath] returns
 * `null` and apps must pass an explicit path derived from `context.filesDir`.
 *
 * Atomic writes use `File.renameTo`; [watchLayoutState] uses
 * [android.os.FileObserver] (Android's inotify wrapper) with the same
 * coalesce-and-suppress-own-writes semantics.
 *
 * @see UiSettingsStore.android
 */
package se.soderbjorn.lunula.store

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android does not expose a shared user-data dir — see file-level docs. */
actual fun defaultAppLayoutStatePath(appName: String): String? = null

/** Reads and parses a [LayoutState] from a file. Returns null on missing/IO error. */
actual fun readLayoutState(path: String): LayoutState? {
    val f = File(path)
    if (!f.exists()) return null
    val text = runCatching { f.readText() }.getOrNull() ?: return null
    if (text.isBlank()) return null
    return LayoutState.fromJsonString(text)
}

/** Per-path "bytes most recently written from this process". */
private val lastWrittenLayoutBytes = ConcurrentHashMap<String, ByteArray>()

/**
 * Atomic-write a [LayoutState] to disk via tmp+`renameTo`. Parent dirs
 * are created on demand; bytes are recorded for self-write suppression.
 */
actual fun writeLayoutState(path: String, layout: LayoutState): Boolean {
    return runCatching {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val bytes = layout.toJsonString().encodeToByteArray()
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("Atomic rename failed")
        lastWrittenLayoutBytes[path] = bytes
    }.isSuccess
}

/**
 * Watch [path] via [FileObserver]. 200ms debounce; skips callbacks
 * whose payload matches what this process last wrote.
 */
@Suppress("DEPRECATION")
actual fun watchLayoutState(
    path: String,
    onChange: (LayoutState) -> Unit,
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
            val mine = lastWrittenLayoutBytes[path]
            if (mine != null && bytes.contentEquals(mine)) return@Thread
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) return@Thread
            val state = runCatching { LayoutState.fromJsonString(text) }.getOrNull() ?: return@Thread
            runCatching { onChange(state) }
        }.apply { isDaemon = true }.start()
    }

    val mask = FileObserver.MODIFY or FileObserver.MOVED_TO or
        FileObserver.CREATE or FileObserver.CLOSE_WRITE
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
