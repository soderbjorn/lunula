/**
 * Host interface bridging the toolkit's [showThemeManager] modal to its
 * embedding application's theme state.
 *
 * The toolkit owns the editor UI; the host owns persistence, side-effects
 * (e.g. server POSTs, file writes), and the visual silhouette/swatch
 * primitives. Apps implement this interface and pass an instance to
 * [showThemeManager].
 *
 * The manager calls into the host on every read and on every action; it
 * does not cache. After a write action the host should update its own
 * state and call [refreshThemeManager] to repaint.
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.CustomScheme
import se.soderbjorn.darkness.core.Theme

/**
 * Operations the [showThemeManager] modal needs from its host application.
 *
 * @see showThemeManager
 */
interface ThemeManagerHost {

    // ── Read snapshots ─────────────────────────────────────────────

    /** Name of the currently active main colour scheme. */
    val mainSchemeName: String

    /** The currently selected [Appearance] (Auto/Dark/Light). */
    val appearance: Appearance

    /** Name of the theme currently bound to the *light* slot, or null. */
    val lightThemeName: String?

    /** Name of the theme currently bound to the *dark* slot, or null. */
    val darkThemeName: String?

    /** User-defined custom themes (named [Theme]s), keyed by name. */
    val customThemes: Map<String, Theme>

    /** User-defined custom colour schemes, keyed by name. */
    val customSchemes: Map<String, CustomScheme>

    /** Names of themes the user has marked as favorite. */
    val favoriteThemes: Collection<String>

    /** Names of colour schemes the user has marked as favorite. */
    val favoriteSchemes: Collection<String>

    /**
     * App-supplied pane → universal-section map.
     *
     * The Theme Manager uses this when resolving the active theme onto
     * the host's per-pane scheme view (e.g. via
     * [se.soderbjorn.darkness.core.resolveActiveTheme]). Each key is a
     * concrete pane name the host renders (e.g. `"sidebar"`, `"editor"`,
     * `"git"`); each value is one of the [se.soderbjorn.darkness.core.Sections]
     * constants describing that pane's role in the universal vocabulary.
     *
     * Defaults to empty so existing hosts that haven't declared a map yet
     * still compile; an empty map means "every pane inherits the main
     * scheme", which is the same behaviour as before this field existed.
     */
    val appPanes: Map<String, String> get() = emptyMap()

    // ── Actions ────────────────────────────────────────────────────

    /** Set the theme bound to the *light* slot, or clear it (`null`). */
    fun setLightThemeName(name: String?)

    /** Set the theme bound to the *dark* slot, or clear it (`null`). */
    fun setDarkThemeName(name: String?)

    /** Toggle [name] in [favoriteThemes]. */
    fun toggleFavoriteTheme(name: String)

    /** Toggle [name] in [favoriteSchemes]. */
    fun toggleFavoriteScheme(name: String)

    /** Persist [theme] as a custom theme (creates or replaces by name). */
    fun saveCustomTheme(theme: Theme)

    /** Remove a user-saved custom theme by [name]. */
    fun deleteCustomTheme(name: String)

    /** Persist [scheme] as a custom colour scheme (creates or replaces). */
    fun saveCustomScheme(scheme: CustomScheme)

    /** Remove a user-saved custom colour scheme by [name]. */
    fun deleteCustomScheme(name: String)

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

    /** Whether the host has opted in to OS desktop notifications. */
    val desktopNotifications: Boolean get() = false

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

    /** Persist the desktop-notifications toggle. */
    fun setDesktopNotifications(value: Boolean) {}

    /** Persist the custom-titlebar toggle. */
    fun setUseCustomTitleBar(value: Boolean) {}

    // ── Rendering primitives owned by the host ─────────────────────
    //
    // These let the host control the per-app look of theme silhouettes
    // and swatches without the toolkit prescribing a style.

    /**
     * Returns HTML markup for a theme silhouette preview given the
     * theme's section assignments. Set as `innerHTML` on the silhouette
     * container by the manager.
     *
     * @param theme the theme to render
     */
    fun renderConfigSilhouetteHtml(theme: Theme): String

    /**
     * Returns HTML markup for a colour-scheme swatch preview.
     * Set as `innerHTML` on the swatch container by the manager.
     *
     * @param scheme the colour scheme to render
     */
    fun renderThemeSwatchHtml(scheme: ColorScheme): String
}
