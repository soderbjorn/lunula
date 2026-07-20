/**
 * Android actual for the toolkit's [Closeable] interface. Identical to
 * the JVM actual — Android exposes [java.io.Closeable] from the platform
 * SDK so the typealias is the simplest correct binding.
 */
package se.soderbjorn.lunula.store

/** Android actual: [java.io.Closeable]. */
actual typealias Closeable = java.io.Closeable
