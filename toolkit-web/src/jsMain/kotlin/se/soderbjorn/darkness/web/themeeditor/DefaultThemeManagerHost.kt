/**
 * A ready-to-use [ThemeManagerHost] implementation plus the neutral
 * silhouette/swatch HTML the manager needs to paint its previews.
 *
 * The Theme Manager modal ([showThemeManager]) reads/writes everything
 * through a [ThemeManagerHost] the host application provides. Implementing
 * that interface from scratch — wiring up custom-theme/scheme bookkeeping,
 * favorite toggling, and silhouette/swatch markup — is repetitive: any
 * Darkness app that doesn't run a server (e.g. notegrow's Electron app)
 * ends up shipping the same boilerplate.
 *
 * This module shortens that work to two pieces:
 *
 *  1. [DefaultThemeManagerState] — a mutable bag of all the fields the
 *     host needs to expose. Apps own the lifetime; the manager reads
 *     through it on every render and mutates it via the host's setters.
 *  2. [DefaultThemeManagerHost] — the bridge from [ThemeManagerHost] to
 *     that state. Each setter writes the new value into the state and
 *     fires an `onChange` callback so the app can persist (e.g. via
 *     [se.soderbjorn.darkness.store.writeUiSettings]). The class is `open`
 *     so apps that want a chrome-aware silhouette (termtastic) can
 *     subclass and override [renderConfigSilhouetteHtml] /
 *     [renderThemeSwatchHtml] without rewriting the rest.
 *
 * The neutral [defaultRenderConfigSilhouetteHtml] / [defaultRenderThemeSwatchHtml]
 * helpers paint a generic "tabs + sidebar + main content" silhouette and a
 * mini terminal-card swatch, themed off the toolkit's `--t-…` tokens.
 * Apps that don't have an opinion on what their app silhouette looks like
 * can use them as-is.
 *
 * @see ThemeManagerHost
 * @see showThemeManager
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.CustomScheme
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.Sections
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.darkness.core.hexToArgb
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.darkness.web.isDarkActive

/**
 * Mutable in-memory snapshot of every field a [ThemeManagerHost] needs to
 * expose, plus the user-facing theme/scheme libraries the manager mutates.
 *
 * Owned by the host application; pass the same instance to
 * [DefaultThemeManagerHost] for the lifetime of the app. The toolkit reads
 * through it on every manager render, so updating the state and re-rendering
 * via [refreshThemeManager] is enough to reflect external changes (e.g. a
 * filesystem watcher firing).
 *
 * @property mainSchemeName  the active main colour-scheme name; mirrors
 *   [se.soderbjorn.darkness.core.UiSettings.theme] when persisting via that
 *   schema.
 * @property appearance      the user's appearance preference (Auto/Dark/Light).
 * @property lightThemeName  theme bound to the *light* slot, or `null`.
 * @property darkThemeName   theme bound to the *dark* slot, or `null`.
 * @property customThemes    user-saved custom themes, keyed by name.
 * @property customSchemes   user-saved custom colour schemes, keyed by name.
 * @property favoriteThemes  theme names the user has starred.
 * @property favoriteSchemes scheme names the user has starred.
 */
class DefaultThemeManagerState(
    var mainSchemeName: String = DEFAULT_THEME_NAME,
    var appearance: Appearance = Appearance.Auto,
    var lightThemeName: String? = null,
    var darkThemeName: String? = null,
    val customThemes: MutableMap<String, Theme> = mutableMapOf(),
    val customSchemes: MutableMap<String, CustomScheme> = mutableMapOf(),
    val favoriteThemes: MutableSet<String> = mutableSetOf(),
    val favoriteSchemes: MutableSet<String> = mutableSetOf(),
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
    /** Desktop-notifications opt-in. */
    var desktopNotifications: Boolean = false,
    /** Per-app custom-titlebar opt-in (Electron `hiddenInset` etc.). */
    var useCustomTitleBar: Boolean = false,
)

/**
 * Default [ThemeManagerHost] that operates on a [DefaultThemeManagerState]
 * and notifies a single [onChange] callback after every mutation.
 *
 * Apps wire it up like this:
 *
 * ```kotlin
 * val state = DefaultThemeManagerState(
 *     mainSchemeName = persisted.theme.name,
 *     appearance     = persisted.appearance,
 * )
 * val host = DefaultThemeManagerHost(state) { saveSnapshot(state) }
 * showThemeManager(host, mountInto = appBody)
 * ```
 *
 * Override [renderConfigSilhouetteHtml] / [renderThemeSwatchHtml] in a
 * subclass to inject app-specific preview chrome — termtastic does this
 * to render a terminal-aware silhouette instead of the toolkit's neutral
 * one. The setter implementations are also `open` so a subclass can
 * intercept (e.g. to round-trip through a server before mutating state).
 *
 * @param state    backing store; mutated in place by every setter.
 * @param onChange invoked synchronously after a setter writes to [state].
 *   Apps typically persist a snapshot here. The callback is *not* called
 *   when the manager only reads.
 *
 * @see DefaultThemeManagerState
 * @see defaultRenderConfigSilhouetteHtml
 * @see defaultRenderThemeSwatchHtml
 */
open class DefaultThemeManagerHost(
    protected val state: DefaultThemeManagerState,
    /**
     * App-supplied `pane → `[Sections]` constant` map. Exposed via
     * [ThemeManagerHost.appPanes] so the manager and toolkit resolvers
     * can compute per-pane scheme assignments without the host having to
     * subclass. Defaults to empty (no panes declared) so existing
     * call-sites that don't pass a map keep compiling.
     */
    private val _appPanes: Map<String, String> = emptyMap(),
    protected val onChange: () -> Unit = {},
) : ThemeManagerHost {

    override val mainSchemeName: String get() = state.mainSchemeName
    override val appearance: Appearance get() = state.appearance
    override val lightThemeName: String? get() = state.lightThemeName
    override val darkThemeName: String? get() = state.darkThemeName
    override val customThemes: Map<String, Theme> get() = state.customThemes
    override val customSchemes: Map<String, CustomScheme> get() = state.customSchemes
    override val favoriteThemes: Collection<String> get() = state.favoriteThemes
    override val favoriteSchemes: Collection<String> get() = state.favoriteSchemes
    override val appPanes: Map<String, String> get() = _appPanes
    override val monoFontFamily: String? get() = state.monoFontFamily
    override val monoFontSizePx: Int? get() = state.monoFontSizePx
    override val proportionalFontFamily: String? get() = state.proportionalFontFamily
    override val proportionalFontSizePx: Int? get() = state.proportionalFontSizePx
    override val sidebarFontFamily: String? get() = state.sidebarFontFamily
    override val sidebarFontSizePx: Int? get() = state.sidebarFontSizePx
    override val tabbarFontFamily: String? get() = state.tabbarFontFamily
    override val tabbarFontSizePx: Int? get() = state.tabbarFontSizePx
    override val desktopNotifications: Boolean get() = state.desktopNotifications
    override val useCustomTitleBar: Boolean get() = state.useCustomTitleBar

    override fun setMonoFontFamily(value: String?) { state.monoFontFamily = value; onChange() }
    override fun setMonoFontSizePx(value: Int?) { state.monoFontSizePx = value; onChange() }
    override fun setProportionalFontFamily(value: String?) { state.proportionalFontFamily = value; onChange() }
    override fun setProportionalFontSizePx(value: Int?) { state.proportionalFontSizePx = value; onChange() }
    override fun setSidebarFontFamily(value: String?) { state.sidebarFontFamily = value; onChange() }
    override fun setSidebarFontSizePx(value: Int?) { state.sidebarFontSizePx = value; onChange() }
    override fun setTabbarFontFamily(value: String?) { state.tabbarFontFamily = value; onChange() }
    override fun setTabbarFontSizePx(value: Int?) { state.tabbarFontSizePx = value; onChange() }
    override fun setDesktopNotifications(value: Boolean) { state.desktopNotifications = value; onChange() }
    override fun setUseCustomTitleBar(value: Boolean) { state.useCustomTitleBar = value; onChange() }

    override fun setLightThemeName(name: String?) {
        state.lightThemeName = name
        // Mirror to mainSchemeName when the slot matches the active appearance
        // so single-theme consumers (those that don't track separate light/dark
        // slots) can keep reading mainSchemeName as the source of truth. The
        // toolkit's theme grid writes the picked theme into the slot matching
        // the *active* appearance, so this mirroring is the natural fix for
        // "click a theme → nothing happens" in single-slot apps.
        if (!isDarkActive(state.appearance) && name != null) {
            state.mainSchemeName = name
        }
        onChange()
    }

    override fun setDarkThemeName(name: String?) {
        state.darkThemeName = name
        if (isDarkActive(state.appearance) && name != null) {
            state.mainSchemeName = name
        }
        onChange()
    }

    override fun toggleFavoriteTheme(name: String) {
        if (!state.favoriteThemes.add(name)) state.favoriteThemes.remove(name)
        onChange()
    }

    override fun toggleFavoriteScheme(name: String) {
        if (!state.favoriteSchemes.add(name)) state.favoriteSchemes.remove(name)
        onChange()
    }

    override fun saveCustomTheme(theme: Theme) {
        state.customThemes[theme.name] = theme
        onChange()
    }

    override fun deleteCustomTheme(name: String) {
        state.customThemes.remove(name)
        state.favoriteThemes.remove(name)
        if (state.lightThemeName == name) state.lightThemeName = null
        if (state.darkThemeName == name) state.darkThemeName = null
        onChange()
    }

    override fun saveCustomScheme(scheme: CustomScheme) {
        state.customSchemes[scheme.name] = scheme
        onChange()
    }

    override fun deleteCustomScheme(name: String) {
        state.customSchemes.remove(name)
        state.favoriteSchemes.remove(name)
        if (state.mainSchemeName == name) state.mainSchemeName = DEFAULT_THEME_NAME
        onChange()
    }

    override fun renderConfigSilhouetteHtml(theme: Theme): String =
        defaultRenderConfigSilhouetteHtml(
            theme = theme,
            isDark = isDarkActive(state.appearance),
            customSchemes = state.customSchemes,
        )

    override fun renderThemeSwatchHtml(scheme: ColorScheme): String =
        defaultRenderThemeSwatchHtml(scheme, isDark = isDarkActive(state.appearance))
}

/**
 * Detailed mini-app silhouette markup that mirrors the structure of a real
 * Darkness app window: a tab strip with sidebar-toggle, three tabs (one
 * outlined as active) and a right-side row of icon dots; a sidebar with two
 * section headers and item rows where the active item shows the same
 * Active-accent outline as the focused tab; and a main area holding *two*
 * side-by-side panes (with the chrome-around-panes background showing
 * through the gap between them). Each pane has its own titlebar (folder
 * icon + path title + trailing control icon) and a body with text lines;
 * one pane carries an additional outer Active-accent ring marking it as
 * the focused pane. A bottom accent bar caps the silhouette.
 *
 * A small preview that mirrors the actual layout makes it obvious how each
 * section colour is applied (tabs vs sidebar vs windows-chrome vs pane vs
 * main) — far more useful than a stylised swatch when picking between
 * dozens of themes.
 *
 * Colour sourcing (matches how the runtime CSS cascade paints the real
 * UI — see [se.soderbjorn.darkness.web.applyUiSettings]):
 *  - **Tabs** use the `Tabs` section: strip on `surface.base`, active-tab
 *    fill on `surface.raised` with an inset outline ring from
 *    `text.primary`, inactive labels from `text.tertiary`, control-icon
 *    accent from `accent.primary`.
 *  - **Sidebar** uses the dedicated `sidebar.*` palette (bg, text, textDim,
 *    activeBg, activeText) so the highlighted row matches what the real
 *    app renders.
 *  - **Pane chrome** (titlebar background, title text, border) reads from
 *    the **`Chrome` section** — not Windows. The runtime publishes
 *    `--t-chrome-*` from the chrome scheme; `.dt-pane-header` reads those
 *    vars. Themes routinely assign chrome to a different scheme than
 *    windows (e.g. Emerald Garden: `windows = Mint Chip`, `chrome = Forest
 *    Panel`), so reading chrome.* off `windowsP` produced grayish previews
 *    that didn't match the real app.
 *  - **Windows-chrome padding** around the pane reads `surface.raised`
 *    from the `Windows` section (this is the cream/dark frame the pane
 *    floats on inside the main area).
 *  - **Pane body** uses `Main` section's `surface.base` + `text.primary`.
 *  - **Prompt + bottom accent** use `Active` section's `accent.primary`.
 *
 * Section overrides on [theme] are resolved against [recommendedColorSchemes]
 * and [customSchemes]; unknown names fall through to the main scheme so a
 * partially-broken theme still paints something instead of bailing out.
 *
 * The output uses the toolkit's `.dt-cs-…` class hierarchy so apps can
 * style it from `darkness-toolkit.css` without owning the CSS themselves.
 *
 * @param theme        the theme whose section assignments drive the colours.
 * @param isDark       whether to resolve schemes against their dark variant.
 * @param customSchemes user-defined schemes the theme may reference.
 * @return a self-contained HTML snippet suitable for `innerHTML`.
 */
fun defaultRenderConfigSilhouetteHtml(
    theme: Theme,
    isDark: Boolean,
    customSchemes: Map<String, CustomScheme> = emptyMap(),
): String {
    fun resolveScheme(name: String?): ColorScheme? {
        if (name.isNullOrEmpty()) return null
        return customSchemes[name]?.toColorScheme()
            ?: recommendedColorSchemes.firstOrNull { it.name == name }
    }

    val mainScheme = resolveScheme(theme.colorScheme) ?: return ""

    fun paletteFor(sectionName: String?): ResolvedPalette {
        val scheme = resolveScheme(sectionName) ?: mainScheme
        return scheme.resolve(isDark)
    }

    val tabsP    = paletteFor(theme.sections[Sections.Tabs])
    val sidebarP = paletteFor(theme.sections[Sections.Sidebar])
    val mainP    = paletteFor(theme.sections[Sections.Main])
    val windowsP = paletteFor(theme.sections[Sections.Windows])
    val chromeP  = paletteFor(theme.sections[Sections.Chrome])
    val activeP  = paletteFor(theme.sections[Sections.Active])

    // Active-chrome scheme (focused-pane titlebar). When the theme has no
    // explicit ActiveChrome assignment, the active scheme equals the Chrome
    // scheme and the focused pane's titlebar renders identical to inactive
    // panes — exactly the inheritance behaviour Neon themes rely on to stay
    // visually unchanged. When ActiveChrome IS set we use the section
    // scheme's raw bg/fg (no mix-softening) so the focused titlebar reads
    // as a saturated colored band, matching the runtime CSS rule
    // `.dt-pane.dt-pane-focused .dt-pane-header` in darkness-toolkit.css.
    val activeChromeSchemeName = theme.sections[Sections.ActiveChrome]
    val activeChromeScheme = resolveScheme(activeChromeSchemeName)
    val paneTitleBgActive: String
    val paneTitleTextActive: String
    if (activeChromeScheme != null) {
        val rawBg = hexToArgb(if (isDark) activeChromeScheme.darkBg else activeChromeScheme.lightBg)
        val rawFg = hexToArgb(if (isDark) activeChromeScheme.darkFg else activeChromeScheme.lightFg)
        paneTitleBgActive = argbToCss(rawBg)
        paneTitleTextActive = argbToCss(rawFg)
    } else {
        paneTitleBgActive = argbToCss(chromeP.chrome.titlebar)
        paneTitleTextActive = argbToCss(chromeP.chrome.titleText)
    }

    // Tabs strip + active-tab outline
    val tabsBg            = argbToCss(tabsP.surface.base)
    val tabsActiveBg      = argbToCss(tabsP.surface.raised)
    val tabsActiveRing    = argbToCss(tabsP.text.primary)
    val tabsActiveText    = argbToCss(tabsP.text.primary)
    val tabsDim           = argbToCss(tabsP.text.tertiary)
    val tabsAccent        = argbToCss(tabsP.accent.primary)

    // Sidebar (uses dedicated sidebar palette so active row matches real app)
    val sidebarBg         = argbToCss(sidebarP.sidebar.bg)
    val sidebarText       = argbToCss(sidebarP.sidebar.text)
    val sidebarDim        = argbToCss(sidebarP.sidebar.textDim)
    val sidebarActiveBg   = argbToCss(sidebarP.sidebar.activeBg)
    val sidebarActiveText = argbToCss(sidebarP.sidebar.activeText)

    // Main content area + floating pane
    // - windowsBg: chrome/frame around the pane (Windows section)
    // - paneTitle*: the pane's own titlebar (Chrome section — NOT Windows)
    // - mainBg/mainFg: the pane body content (Main section)
    val mainBg            = argbToCss(mainP.surface.base)
    val mainFg            = argbToCss(mainP.text.primary)
    val windowsBg         = argbToCss(windowsP.surface.raised)
    val paneTitleBg       = argbToCss(chromeP.chrome.titlebar)
    val paneTitleText     = argbToCss(chromeP.chrome.titleText)
    val paneBorder        = argbToCss(chromeP.chrome.border)

    // Active accent (prompt cursor + bottom accent bar)
    val activeBg          = argbToCss(activeP.accent.primary)

    return """<span class="dt-config-silhouette">
        <span class="dt-cs-tabs" style="background:$tabsBg">
            <span class="dt-cs-tab-toggle" style="background:$tabsDim"></span>
            <span class="dt-cs-tab dt-cs-tab-active" style="background:$tabsActiveBg;box-shadow:inset 0 0 0 1px $tabsActiveRing">
                <span class="dt-cs-tab-label" style="background:$tabsActiveText"></span>
            </span>
            <span class="dt-cs-tab">
                <span class="dt-cs-tab-label" style="background:$tabsDim"></span>
            </span>
            <span class="dt-cs-tab">
                <span class="dt-cs-tab-label" style="background:$tabsDim"></span>
            </span>
            <span class="dt-cs-tabs-spacer"></span>
            <span class="dt-cs-tab-icons">
                <span class="dt-cs-tab-icon" style="background:$tabsAccent"></span>
                <span class="dt-cs-tab-icon" style="background:$tabsDim"></span>
                <span class="dt-cs-tab-icon" style="background:$tabsDim"></span>
            </span>
        </span>
        <span class="dt-cs-body">
            <span class="dt-cs-sidebar" style="background:$sidebarBg">
                <span class="dt-cs-sb-header" style="background:$sidebarDim"></span>
                <span class="dt-cs-sb-item dt-cs-sb-item-active" style="box-shadow:inset 0 0 0 1px $activeBg">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
                <span class="dt-cs-sb-item">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
                <span class="dt-cs-sb-header" style="background:$sidebarDim"></span>
                <span class="dt-cs-sb-item">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
            </span>
            <span class="dt-cs-main" style="background:$windowsBg">
                <span class="dt-cs-pane dt-cs-pane-focused" style="box-shadow:inset 0 0 0 1px $paneBorder, 0 0 0 1px $activeBg">
                    <span class="dt-cs-pane-titlebar" style="background:$paneTitleBgActive">
                        <span class="dt-cs-pane-icon" style="background:$paneTitleTextActive"></span>
                        <span class="dt-cs-pane-title" style="background:$paneTitleTextActive"></span>
                        <span class="dt-cs-pane-titlebar-spacer"></span>
                        <span class="dt-cs-pane-icon" style="background:$paneTitleTextActive"></span>
                    </span>
                    <span class="dt-cs-pane-body" style="background:$mainBg">
                        <span class="dt-cs-line">
                            <span class="dt-cs-prompt" style="background:$activeBg"></span>
                            <span class="dt-cs-text dt-cs-text-long" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-mid" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-short" style="background:$mainFg"></span>
                        </span>
                    </span>
                </span>
                <span class="dt-cs-pane" style="box-shadow:inset 0 0 0 1px $paneBorder">
                    <span class="dt-cs-pane-titlebar" style="background:$paneTitleBg">
                        <span class="dt-cs-pane-icon" style="background:$paneTitleText"></span>
                        <span class="dt-cs-pane-title" style="background:$paneTitleText"></span>
                        <span class="dt-cs-pane-titlebar-spacer"></span>
                        <span class="dt-cs-pane-icon" style="background:$paneTitleText"></span>
                    </span>
                    <span class="dt-cs-pane-body" style="background:$mainBg">
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-long" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-short" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-mid" style="background:$mainFg"></span>
                        </span>
                    </span>
                </span>
            </span>
        </span>
        <span class="dt-cs-accent" style="background:$activeBg"></span>
    </span>"""
}

/**
 * Neutral mini-card swatch markup: a sample line in the scheme's main bg/fg
 * colours plus a row of syntax-colour dots. Used in the Theme Manager's
 * scheme grid and per-section pickers.
 *
 * @param scheme the colour scheme to preview.
 * @param isDark whether to resolve against the scheme's dark variant.
 * @return a self-contained HTML snippet suitable for `innerHTML`.
 */
fun defaultRenderThemeSwatchHtml(scheme: ColorScheme, isDark: Boolean): String {
    val p = scheme.resolve(isDark)
    val bg = argbToCss(p.terminal.bg)
    val fg = argbToCss(p.terminal.fg)
    val accent = argbToCss(p.accent.primary)
    val syntaxDots = listOf(
        p.syntax.keyword, p.syntax.string, p.syntax.number, p.syntax.comment,
        p.syntax.function, p.syntax.type, p.syntax.operator, p.syntax.constant,
    ).joinToString("") { color ->
        """<span class="dt-syntax-dot" style="background:${argbToCss(color)}"></span>"""
    }
    return """<span class="dt-theme-swatch">
        <span class="dt-swatch-line" style="background:$bg;color:$fg">
            <span class="dt-swatch-prompt" style="color:$accent">&#10095;</span> abc
        </span>
        <span class="dt-swatch-syntax-row">$syntaxDots</span>
    </span>"""
}
