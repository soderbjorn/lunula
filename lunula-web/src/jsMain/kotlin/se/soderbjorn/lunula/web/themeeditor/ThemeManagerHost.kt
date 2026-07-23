/**
 * Host interface bridging the toolkit's [showThemeManager] modal to its
 * embedding application's theme state.
 *
 * The toolkit owns the editor UI; the host owns persistence and side-effects
 * (e.g. server POSTs, file writes). Apps implement this interface and pass an
 * instance to [showThemeManager].
 *
 * Under the post-revamp theme system the host's surface is small: the two
 * slot selections, the appearance preference, and the user's custom themes —
 * plus the per-app font / titlebar preferences that the Settings sidebar
 * mutates. Colour-schemes, favorites, and per-pane sections are gone.
 *
 * The manager calls into the host on every read and on every action; it does
 * not cache. After a write action the host should update its own state and
 * call [refreshThemeManager] to repaint.
 */
package se.soderbjorn.lunula.web.themeeditor

import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.Theme

/**
 * Operations the [showThemeManager] modal needs from its host application.
 *
 * @see showThemeManager
 */
interface ThemeManagerHost {

    // ── Read snapshots ─────────────────────────────────────────────

    /** Name of the theme currently bound to the *dark* slot. */
    val darkThemeName: String

    /** Name of the theme currently bound to the *light* slot. */
    val lightThemeName: String

    /** The currently selected [Appearance] (Auto/Dark/Light). */
    val appearance: Appearance

    /** User-defined custom themes (named [Theme]s). */
    val customThemes: List<Theme>

    /**
     * Names of the themes the user has starred / favorited. The manager hoists
     * these to the top of its single theme list and draws a filled star on
     * their cards. Defaults to empty for hosts that don't support favorites.
     *
     * @see toggleFavorite
     */
    val favoriteThemeNames: Set<String> get() = emptySet()

    // ── Actions ────────────────────────────────────────────────────

    /** Set the theme bound to the *dark* slot. */
    fun setDarkThemeName(name: String)

    /** Set the theme bound to the *light* slot. */
    fun setLightThemeName(name: String)

    /** Set the appearance preference (Auto/Dark/Light). */
    fun setAppearance(appearance: Appearance)

    /** Persist [theme] as a custom theme (creates or replaces by name). */
    fun saveCustomTheme(theme: Theme)

    /** Remove a user-saved custom theme by [name]. */
    fun deleteCustomTheme(name: String)

    /**
     * Toggle the starred / favorite state of the theme [name] and persist it.
     * After the write the host should call [refreshThemeManager] so the list
     * re-sorts and the star icon repaints. No-op for hosts that don't support
     * favorites (the default).
     *
     * @param name the theme whose favorite state to flip.
     * @see favoriteThemeNames
     */
    fun toggleFavorite(name: String) {}

    // ── Per-app settings (font / size / titlebar / notifications) ─
    //
    // These map directly onto the controls in the toolkit's Settings
    // sidebar (see [openSettingsSidebar]). Persistence is per-app —
    // notegrow writes to its layout-state file, termtastic posts to its
    // server. Returning sensible defaults is fine when the host hasn't
    // loaded settings yet; the sidebar displays the defaults until the
    // user mutates a control.

    /** Monospaced font family for main-content surfaces (terminals, code
     *  panes), or `null` to fall back to the system mono stack. */
    val monoFontFamily: String? get() = null

    /** Monospaced main-content font size in px, or `null` for the toolkit
     *  default (13). */
    val monoFontSizePx: Int? get() = null

    /** Proportional font family for prose main-content surfaces (e.g.
     *  notegrow's editor), or `null` to fall back to the system stack. */
    val proportionalFontFamily: String? get() = null

    /** Proportional main-content font size in px, or `null` for the
     *  toolkit default (15). */
    val proportionalFontSizePx: Int? get() = null

    /** Chrome font family for the sidebar (also drives the topbar by
     *  default), or `null` to inherit the toolkit's stack. */
    val sidebarFontFamily: String? get() = null

    /** Chrome font size in px for the sidebar / topbar, or `null` for the
     *  toolkit default. */
    val sidebarFontSizePx: Int? get() = null

    /** Tab-strip font family, or `null` to fall back to the sidebar font. */
    val tabbarFontFamily: String? get() = null

    /** Tab-strip font size in px, or `null` to fall back to the sidebar
     *  size. */
    val tabbarFontSizePx: Int? get() = null

    /** Pane-title (pane header) font family, or `null` to fall back to the
     *  sidebar font. */
    val paneHeaderFontFamily: String? get() = null

    /** Pane-title (pane header) font size in px, or `null` to fall back to
     *  the sidebar size. */
    val paneHeaderFontSizePx: Int? get() = null

    /** Display (heading) font family, or `null` to fall back to the app's
     *  display default (and thence prose). */
    val displayFontFamily: String? get() = null

    /** Display (heading) font size in px, or `null` for the app default. */
    val displayFontSizePx: Int? get() = null

    /** When `true`, the host renders an in-window titlebar drag region
     *  (Electron's `titleBarStyle: hiddenInset` pattern) instead of the
     *  OS-native chrome. Default `false`. */
    val useCustomTitleBar: Boolean get() = false

    /** Persist a new monospaced-font-family preference. `null` clears it. */
    fun setMonoFontFamily(value: String?) {}

    /** Persist a new monospaced-font-size preference. `null` clears it. */
    fun setMonoFontSizePx(value: Int?) {}

    /** Persist a new proportional-font-family preference. `null` clears it. */
    fun setProportionalFontFamily(value: String?) {}

    /** Persist a new proportional-font-size preference. `null` clears it. */
    fun setProportionalFontSizePx(value: Int?) {}

    /** Persist a new sidebar-font-family preference. `null` clears it. */
    fun setSidebarFontFamily(value: String?) {}

    /** Persist a new sidebar-font-size preference. `null` clears it. */
    fun setSidebarFontSizePx(value: Int?) {}

    /** Persist a new tabbar-font-family preference. `null` clears it. */
    fun setTabbarFontFamily(value: String?) {}

    /** Persist a new tabbar-font-size preference. `null` clears it. */
    fun setTabbarFontSizePx(value: Int?) {}

    /** Persist a new pane-title-font-family preference. `null` clears it. */
    fun setPaneHeaderFontFamily(value: String?) {}

    /** Persist a new pane-title-font-size preference. `null` clears it. */
    fun setPaneHeaderFontSizePx(value: Int?) {}

    /** Persist a new display-font-family preference. `null` clears it. */
    fun setDisplayFontFamily(value: String?) {}

    /** Persist a new display-font-size preference. `null` clears it. */
    fun setDisplayFontSizePx(value: Int?) {}

    /** Persist the custom-titlebar toggle. */
    fun setUseCustomTitleBar(value: Boolean) {}
}
