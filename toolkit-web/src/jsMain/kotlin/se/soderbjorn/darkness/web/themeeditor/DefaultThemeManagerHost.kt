/**
 * A ready-to-use [ThemeManagerHost] implementation for the post-revamp theme
 * system, backed by a mutable [DefaultThemeManagerState].
 *
 * The Theme Manager modal ([showThemeManager]) reads/writes everything through
 * a [ThemeManagerHost]. Implementing it from scratch — slot bookkeeping,
 * custom-theme storage, and the per-app font / titlebar / notification
 * preferences — is repetitive boilerplate any Darkness app that doesn't run a
 * server (e.g. notegrow's Electron app) would otherwise ship.
 *
 * This module provides:
 *  1. [DefaultThemeManagerState] — a mutable bag of every field the host
 *     exposes. Apps own its lifetime; the manager reads through it on every
 *     render and mutates it via the host's setters.
 *  2. [DefaultThemeManagerHost] — the bridge. Each setter writes the new
 *     value into the state and fires an `onChange` callback so the app can
 *     persist (typically a [se.soderbjorn.darkness.core.ThemeSnapshotV2]).
 *
 * @see ThemeManagerHost
 * @see showThemeManager
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME
import se.soderbjorn.darkness.core.DEFAULT_LIGHT_THEME
import se.soderbjorn.darkness.core.Theme

/**
 * Mutable in-memory snapshot of every field a [ThemeManagerHost] needs to
 * expose, under the post-revamp theme system.
 *
 * Owned by the host application; pass the same instance to
 * [DefaultThemeManagerHost] for the lifetime of the app. The toolkit reads
 * through it on every manager render, so updating the state and re-rendering
 * via [refreshThemeManager] reflects external changes (e.g. a filesystem
 * watcher firing).
 *
 * @property darkThemeName  name of the theme bound to the dark slot.
 * @property lightThemeName name of the theme bound to the light slot.
 * @property appearance     the user's appearance preference (Auto/Dark/Light).
 * @property customThemes   user-saved custom themes (order preserved).
 * @property favorites      names of the user's starred / favorite themes.
 */
class DefaultThemeManagerState(
    var darkThemeName: String = DEFAULT_DARK_THEME,
    var lightThemeName: String = DEFAULT_LIGHT_THEME,
    var appearance: Appearance = Appearance.Auto,
    val customThemes: MutableList<Theme> = mutableListOf(),
    /** Names of the user's starred / favorite themes (order preserved). */
    val favorites: MutableList<String> = mutableListOf(),
    /** Monospaced main-content font family override (terminals, code). */
    var monoFontFamily: String? = null,
    /** Monospaced main-content font size override (px). */
    var monoFontSizePx: Int? = null,
    /** Proportional main-content font family override (prose editors). */
    var proportionalFontFamily: String? = null,
    /** Proportional main-content font size override (px). */
    var proportionalFontSizePx: Int? = null,
    /** Sidebar/chrome font family override. */
    var sidebarFontFamily: String? = null,
    /** Sidebar/chrome font size override (px). */
    var sidebarFontSizePx: Int? = null,
    /** Tab strip font family override (falls back to sidebar). */
    var tabbarFontFamily: String? = null,
    /** Tab strip font size override (px; falls back to sidebar). */
    var tabbarFontSizePx: Int? = null,
    /** Per-app custom-titlebar opt-in (Electron `hiddenInset` etc.). */
    var useCustomTitleBar: Boolean = false,
)

/**
 * Default [ThemeManagerHost] that operates on a [DefaultThemeManagerState] and
 * notifies a single [onChange] callback after every mutation.
 *
 * The setter implementations are `open` so a subclass can intercept (e.g. to
 * round-trip through a server before mutating state).
 *
 * @param state    backing store; mutated in place by every setter.
 * @param onChange invoked synchronously after a setter writes to [state]. Apps
 *   typically persist a snapshot here. Not called when the manager only reads.
 * @see DefaultThemeManagerState
 */
open class DefaultThemeManagerHost(
    protected val state: DefaultThemeManagerState,
    protected val onChange: () -> Unit = {},
) : ThemeManagerHost {

    override val darkThemeName: String get() = state.darkThemeName
    override val lightThemeName: String get() = state.lightThemeName
    override val appearance: Appearance get() = state.appearance
    override val customThemes: List<Theme> get() = state.customThemes
    override val favoriteThemeNames: Set<String> get() = state.favorites.toSet()

    override val monoFontFamily: String? get() = state.monoFontFamily
    override val monoFontSizePx: Int? get() = state.monoFontSizePx
    override val proportionalFontFamily: String? get() = state.proportionalFontFamily
    override val proportionalFontSizePx: Int? get() = state.proportionalFontSizePx
    override val sidebarFontFamily: String? get() = state.sidebarFontFamily
    override val sidebarFontSizePx: Int? get() = state.sidebarFontSizePx
    override val tabbarFontFamily: String? get() = state.tabbarFontFamily
    override val tabbarFontSizePx: Int? get() = state.tabbarFontSizePx
    override val useCustomTitleBar: Boolean get() = state.useCustomTitleBar

    override fun setDarkThemeName(name: String) { state.darkThemeName = name; onChange() }
    override fun setLightThemeName(name: String) { state.lightThemeName = name; onChange() }
    override fun setAppearance(appearance: Appearance) { state.appearance = appearance; onChange() }

    override fun saveCustomTheme(theme: Theme) {
        val idx = state.customThemes.indexOfFirst { it.name == theme.name }
        if (idx >= 0) state.customThemes[idx] = theme else state.customThemes.add(theme)
        onChange()
    }

    override fun deleteCustomTheme(name: String) {
        state.customThemes.removeAll { it.name == name }
        state.favorites.removeAll { it == name }
        if (state.darkThemeName == name) state.darkThemeName = DEFAULT_DARK_THEME
        if (state.lightThemeName == name) state.lightThemeName = DEFAULT_LIGHT_THEME
        onChange()
    }

    override fun toggleFavorite(name: String) {
        if (!state.favorites.remove(name)) state.favorites.add(name)
        onChange()
    }

    override fun setMonoFontFamily(value: String?) { state.monoFontFamily = value; onChange() }
    override fun setMonoFontSizePx(value: Int?) { state.monoFontSizePx = value; onChange() }
    override fun setProportionalFontFamily(value: String?) { state.proportionalFontFamily = value; onChange() }
    override fun setProportionalFontSizePx(value: Int?) { state.proportionalFontSizePx = value; onChange() }
    override fun setSidebarFontFamily(value: String?) { state.sidebarFontFamily = value; onChange() }
    override fun setSidebarFontSizePx(value: Int?) { state.sidebarFontSizePx = value; onChange() }
    override fun setTabbarFontFamily(value: String?) { state.tabbarFontFamily = value; onChange() }
    override fun setTabbarFontSizePx(value: Int?) { state.tabbarFontSizePx = value; onChange() }
    override fun setUseCustomTitleBar(value: Boolean) { state.useCustomTitleBar = value; onChange() }
}

/**
 * Builds a [se.soderbjorn.darkness.core.ThemeSnapshotV2] mirror of [state] for
 * persistence.
 *
 * @return the snapshot capturing the current slots + appearance + custom themes.
 */
fun DefaultThemeManagerState.toSnapshotV2(): se.soderbjorn.darkness.core.ThemeSnapshotV2 =
    se.soderbjorn.darkness.core.ThemeSnapshotV2(
        darkThemeName = darkThemeName,
        lightThemeName = lightThemeName,
        customThemes = customThemes.toList(),
        appearance = appearance,
        favorites = favorites.toList(),
    )

/**
 * Overwrites this [state] with the contents of [snapshot]. Used at boot to
 * hydrate the default host from persisted JSON.
 *
 * @param snapshot the persisted snapshot to apply.
 */
fun DefaultThemeManagerState.applySnapshotV2(snapshot: se.soderbjorn.darkness.core.ThemeSnapshotV2) {
    darkThemeName = snapshot.darkThemeName
    lightThemeName = snapshot.lightThemeName
    appearance = snapshot.appearance
    customThemes.clear()
    customThemes.addAll(snapshot.customThemes)
    favorites.clear()
    favorites.addAll(snapshot.favorites)
}
