/**
 * Tiny multiplatform [Closeable] interface used by toolkit-store helpers
 * that hand back a teardown handle.
 *
 * Kotlin's stdlib does not (yet) ship a common [Closeable] type; on JVM
 * and Android we typealias to [java.io.Closeable] so existing `use { … }`
 * call sites keep working, and on iOS we ship a tiny SAM interface.
 *
 * @see watchUiSettings
 */
package se.soderbjorn.darkness.store

/**
 * Resource handle that can be released exactly once. Idempotent close
 * is encouraged but not enforced — callers should treat double-close
 * as undefined behaviour.
 */
expect interface Closeable {
    /**
     * Release any resources held by this handle. Implementations should
     * be safe to call from any thread.
     */
    fun close()
}
