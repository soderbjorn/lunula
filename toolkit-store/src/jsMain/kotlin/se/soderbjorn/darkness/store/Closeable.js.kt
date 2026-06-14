/**
 * JS actual for the toolkit's [Closeable] interface. Browser-side code
 * cannot reach the filesystem; the watch helpers ship only stubs on JS,
 * but a [Closeable] type still has to exist for the `expect` declaration
 * to compile. Same shape as the iOS variant — a standalone SAM interface.
 */
package se.soderbjorn.darkness.store

/** JS actual: standalone interface. */
actual interface Closeable {
    actual fun close()
}
