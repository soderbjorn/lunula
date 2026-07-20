/**
 * JVM actual for the toolkit's [Closeable] interface — typealiased to
 * [java.io.Closeable] so existing call sites that already implement the
 * stdlib type compose without ceremony, and `use { … }` works directly.
 */
package se.soderbjorn.lunula.store

/** JVM/Android actual: [java.io.Closeable]. */
actual typealias Closeable = java.io.Closeable
