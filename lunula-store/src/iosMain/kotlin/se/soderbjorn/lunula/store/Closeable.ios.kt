/**
 * iOS actual for the toolkit's [Closeable] interface — a small standalone
 * SAM interface, since the iOS toolchain doesn't ship a stdlib counterpart.
 */
package se.soderbjorn.lunula.store

/** iOS actual: standalone interface. */
actual interface Closeable {
    actual fun close()
}
