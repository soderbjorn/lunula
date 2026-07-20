/**
 * Optional Compose helpers for consuming the Lunula theme model.
 *
 * Library-style: no `LunulaTheme { … }` wrapper, no Scaffold replacement.
 * Apps can either pass [ResolvedTheme] explicitly to widgets, or use
 * [LocalLunulaPalette] as an opt-in composition local.
 *
 * @see ResolvedTheme
 * @see Theme
 */
package se.soderbjorn.lunula.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.lunula.core.ResolvedTheme
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.builtinTheme
import se.soderbjorn.lunula.core.DEFAULT_DARK_THEME

/**
 * Optional composition local that composables in the toolkit (and apps that
 * choose to use it) can read instead of receiving the palette as an explicit
 * parameter every call.
 *
 * The default value resolves the [DEFAULT_DARK_THEME] so that an app that
 * forgets to set the local still renders in a sensible theme rather than
 * crashing.
 */
val LocalLunulaPalette = compositionLocalOf<ResolvedTheme> {
    builtinTheme(DEFAULT_DARK_THEME)!!.resolve()
}

/**
 * Resolves the [ResolvedTheme] for a [ThemeSnapshotV2], given the host
 * platform's current dark-mode flag.
 *
 * @param snapshot the user's persisted theme selection.
 * @param systemIsDark whether the OS is currently in dark mode.
 * @return the fully resolved theme for the active slot.
 */
fun lunulaPaletteFor(snapshot: ThemeSnapshotV2, systemIsDark: Boolean): ResolvedTheme =
    snapshot.resolve(systemIsDark)

/**
 * Converts an ARGB [Long] (`0xAARRGGBB`) to a Compose [Color].
 *
 * @return the equivalent Compose [Color].
 */
fun Long.toComposeColor(): Color = Color(this.toInt())

/**
 * Reads the current theme from [LocalLunulaPalette].
 *
 * @return the current ambient [ResolvedTheme].
 */
@Composable
fun currentLunulaPalette(): ResolvedTheme = LocalLunulaPalette.current
