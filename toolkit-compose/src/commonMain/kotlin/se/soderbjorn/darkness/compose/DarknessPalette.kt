/**
 * Optional Compose helpers for consuming the Darkness theme model.
 *
 * Library-style: no `DarknessTheme { … }` wrapper, no Scaffold replacement.
 * Apps can either pass [ResolvedTheme] explicitly to widgets, or use
 * [LocalDarknessPalette] as an opt-in composition local.
 *
 * @see ResolvedTheme
 * @see Theme
 */
package se.soderbjorn.darkness.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.darkness.core.builtinTheme
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME

/**
 * Optional composition local that composables in the toolkit (and apps that
 * choose to use it) can read instead of receiving the palette as an explicit
 * parameter every call.
 *
 * The default value resolves the [DEFAULT_DARK_THEME] so that an app that
 * forgets to set the local still renders in a sensible theme rather than
 * crashing.
 */
val LocalDarknessPalette = compositionLocalOf<ResolvedTheme> {
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
fun darknessPaletteFor(snapshot: ThemeSnapshotV2, systemIsDark: Boolean): ResolvedTheme =
    snapshot.resolve(systemIsDark)

/**
 * Converts an ARGB [Long] (`0xAARRGGBB`) to a Compose [Color].
 *
 * @return the equivalent Compose [Color].
 */
fun Long.toComposeColor(): Color = Color(this.toInt())

/**
 * Reads the current theme from [LocalDarknessPalette].
 *
 * @return the current ambient [ResolvedTheme].
 */
@Composable
fun currentDarknessPalette(): ResolvedTheme = LocalDarknessPalette.current
