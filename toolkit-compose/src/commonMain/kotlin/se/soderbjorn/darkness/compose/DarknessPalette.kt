/**
 * Optional Compose helpers for consuming the Darkness theme model.
 *
 * Library-style: no `DarknessTheme { … }` wrapper, no Scaffold replacement.
 * Apps can either pass [ResolvedPalette] explicitly to widgets, or use
 * [LocalDarknessPalette] as an opt-in composition local.
 *
 * @see ResolvedPalette
 * @see ColorScheme
 */
package se.soderbjorn.darkness.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.resolve

/**
 * Optional composition local that composables in the toolkit (and apps that
 * choose to use it) can read instead of receiving the palette as an explicit
 * parameter every call.
 *
 * Apps that don't want a global palette can ignore this local entirely;
 * every toolkit composable also accepts a `palette: ResolvedPalette`
 * parameter.
 *
 * The default value uses the [DEFAULT_THEME_NAME] scheme in dark mode so
 * that an app that forgets to set the local still renders in a sensible
 * theme rather than crashing.
 */
val LocalDarknessPalette = compositionLocalOf<ResolvedPalette> {
    recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }.resolve(isDark = true)
}

/**
 * Resolves the [ResolvedPalette] for a [UiSettings], given the host
 * platform's current dark-mode flag.
 *
 * @param settings the user's [UiSettings]
 * @param systemIsDark whether the OS is currently in dark mode
 * @return the fully resolved palette for the active theme
 */
fun darknessPaletteFor(settings: UiSettings, systemIsDark: Boolean): ResolvedPalette {
    val isDark = when (settings.appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    return settings.theme.resolve(isDark)
}

/**
 * Resolves the [ResolvedPalette] for a [ColorScheme] in a specific mode.
 * Convenience wrapper for callers that don't have a [UiSettings].
 *
 * @param scheme the colour scheme to resolve
 * @param isDark whether to resolve in dark mode
 * @return the fully resolved palette
 */
fun darknessPaletteFor(scheme: ColorScheme, isDark: Boolean): ResolvedPalette =
    scheme.resolve(isDark)

/**
 * Converts an ARGB [Long] (`0xAARRGGBB`) to a Compose [Color].
 *
 * Used by widgets that read [ResolvedPalette] tokens and need to project
 * them onto Compose graphics primitives.
 *
 * @return the equivalent Compose [Color]
 */
fun Long.toComposeColor(): Color = Color(this.toInt())

/**
 * Reads the current palette from [LocalDarknessPalette].
 *
 * Apps that haven't set the local will receive the default palette (Tron,
 * dark variant). Composable widgets in the toolkit use this as the default
 * value of their `palette` parameter so callers can either rely on the
 * local or pass an explicit palette.
 *
 * @return the current ambient palette
 */
@Composable
fun currentDarknessPalette(): ResolvedPalette = LocalDarknessPalette.current
