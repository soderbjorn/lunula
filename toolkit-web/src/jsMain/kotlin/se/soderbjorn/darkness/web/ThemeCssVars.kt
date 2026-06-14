/**
 * Helpers for projecting a [ResolvedPalette] onto a DOM element via CSS
 * custom properties.
 *
 * This module is deliberately library-style: it ships pure functions and
 * a small `applyCssVars` helper. Apps decide *where* to apply the variables
 * (document root, a subtree, or a Shadow DOM) — there is no global "install
 * the theme on document.body" function.
 *
 * Property names follow the `--t-group-token` convention (e.g.
 * `--t-surface-base`, `--t-text-primary`) which matches the convention
 * used by termtastic's existing stylesheet so themes round-trip without
 * stylesheet rewrites.
 *
 * @see ResolvedPalette
 */
package se.soderbjorn.darkness.web

import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.darkness.web.themeeditor.fontPresets
import se.soderbjorn.darkness.web.themeeditor.resolveFontFamilyCss
import se.soderbjorn.darkness.web.themeeditor.resolveProportionalFontFamilyCss

/**
 * Returns whether the host platform currently prefers a dark colour scheme,
 * based on the browser's `prefers-color-scheme` media query.
 *
 * @return true if the system prefers dark mode
 */
fun systemPrefersDark(): Boolean =
    kotlinx.browser.window.matchMedia("(prefers-color-scheme: dark)").matches

/**
 * Resolves the [Appearance] preference into a concrete `isDark` boolean,
 * deferring to [systemPrefersDark] when the user's preference is `Auto`.
 *
 * @param appearance the user's selected appearance preference
 * @return true if the dark variant should be used
 */
fun isDarkActive(appearance: Appearance): Boolean = when (appearance) {
    Appearance.Dark -> true
    Appearance.Light -> false
    Appearance.Auto -> systemPrefersDark()
}

/**
 * Converts a [ResolvedPalette] to a map of CSS custom property names to
 * CSS colour values.
 *
 * Property names follow the `--t-group-token` convention (e.g.
 * `--t-surface-base`, `--t-text-primary`). Colours with alpha use
 * `rgba()` format; fully opaque colours use `#rrggbb`.
 *
 * @return map of CSS property name to CSS colour string
 * @see argbToCss
 * @see applyCssVars
 */
fun ResolvedPalette.toCssVarMap(): Map<String, String> = buildMap {
    // Surface
    put("--t-surface-base", argbToCss(surface.base))
    put("--t-surface-raised", argbToCss(surface.raised))
    put("--t-surface-sunken", argbToCss(surface.sunken))
    put("--t-surface-overlay", argbToCss(surface.overlay))
    // Text
    put("--t-text-primary", argbToCss(text.primary))
    put("--t-text-secondary", argbToCss(text.secondary))
    put("--t-text-tertiary", argbToCss(text.tertiary))
    put("--t-text-disabled", argbToCss(text.disabled))
    put("--t-text-inverse", argbToCss(text.inverse))
    // Border
    put("--t-border-subtle", argbToCss(border.subtle))
    put("--t-border-default", argbToCss(border.default))
    put("--t-border-strong", argbToCss(border.strong))
    put("--t-border-focus", argbToCss(border.focus))
    put("--t-border-focusGlow", argbToCss(border.focusGlow))
    // Accent
    put("--t-accent-primary", argbToCss(accent.primary))
    put("--t-accent-primarySoft", argbToCss(accent.primarySoft))
    put("--t-accent-primaryGlow", argbToCss(accent.primaryGlow))
    put("--t-accent-onPrimary", argbToCss(accent.onPrimary))
    // Semantic
    put("--t-semantic-danger", argbToCss(semantic.danger))
    put("--t-semantic-warn", argbToCss(semantic.warn))
    put("--t-semantic-success", argbToCss(semantic.success))
    put("--t-semantic-info", argbToCss(semantic.info))
    // Terminal (also useful for any text-editor pane: bg, fg, cursor, selection)
    put("--t-terminal-bg", argbToCss(terminal.bg))
    put("--t-terminal-fg", argbToCss(terminal.fg))
    put("--t-terminal-cursor", argbToCss(terminal.cursor))
    put("--t-terminal-selection", argbToCss(terminal.selection))
    put("--t-terminal-selectionText", argbToCss(terminal.selectionText))
    // Chrome
    put("--t-chrome-titlebar", argbToCss(chrome.titlebar))
    put("--t-chrome-titleText", argbToCss(chrome.titleText))
    put("--t-chrome-border", argbToCss(chrome.border))
    put("--t-chrome-shadow", argbToCss(chrome.shadow))
    put("--t-chrome-closeDot", argbToCss(chrome.closeDot))
    put("--t-chrome-minDot", argbToCss(chrome.minDot))
    put("--t-chrome-maxDot", argbToCss(chrome.maxDot))
    // Sidebar
    put("--t-sidebar-bg", argbToCss(sidebar.bg))
    put("--t-sidebar-text", argbToCss(sidebar.text))
    put("--t-sidebar-textDim", argbToCss(sidebar.textDim))
    put("--t-sidebar-activeBg", argbToCss(sidebar.activeBg))
    put("--t-sidebar-activeText", argbToCss(sidebar.activeText))
    // Bottom bar
    put("--t-bottomBar-bg", argbToCss(bottomBar.bg))
    put("--t-bottomBar-text", argbToCss(bottomBar.text))
    put("--t-bottomBar-textDim", argbToCss(bottomBar.textDim))
    put("--t-bottomBar-border", argbToCss(bottomBar.border))
    // Diff
    put("--t-diff-addBg", argbToCss(diff.addBg))
    put("--t-diff-addFg", argbToCss(diff.addFg))
    put("--t-diff-addGutter", argbToCss(diff.addGutter))
    put("--t-diff-removeBg", argbToCss(diff.removeBg))
    put("--t-diff-removeFg", argbToCss(diff.removeFg))
    put("--t-diff-removeGutter", argbToCss(diff.removeGutter))
    put("--t-diff-contextFg", argbToCss(diff.contextFg))
    // Syntax
    put("--t-syntax-keyword", argbToCss(syntax.keyword))
    put("--t-syntax-string", argbToCss(syntax.string))
    put("--t-syntax-number", argbToCss(syntax.number))
    put("--t-syntax-comment", argbToCss(syntax.comment))
    put("--t-syntax-function", argbToCss(syntax.function))
    put("--t-syntax-type", argbToCss(syntax.type))
    put("--t-syntax-operator", argbToCss(syntax.operator))
    put("--t-syntax-constant", argbToCss(syntax.constant))
}

/**
 * Returns a small map of generic CSS aliases (`--surface`, `--text-primary`,
 * etc.) suitable for stylesheets that want a shorter naming scheme than the
 * full `--t-group-token` form.
 *
 * Apps that don't need the aliases can ignore this function.
 *
 * @return map of alias name to CSS colour string
 * @see toCssVarMap
 */
fun ResolvedPalette.toCssAliasMap(): Map<String, String> = buildMap {
    put("--background", argbToCss(sidebar.bg))
    put("--surface", argbToCss(surface.raised))
    put("--bg-elevated", argbToCss(surface.overlay))
    put("--text-primary", argbToCss(text.primary))
    put("--text-secondary", argbToCss(text.secondary))
    put("--separator", argbToCss(border.subtle))
    put("--toolbar-shadow", "0 2px 8px ${argbToCss(chrome.shadow)}")
}

/**
 * Applies a map of CSS custom properties to an [HTMLElement]'s inline style.
 *
 * Apps decide where to apply: `document.documentElement` for whole-app
 * theming, a subtree root for scoped theming, or any element when
 * different sections need different themes.
 *
 * @param element the target element (e.g. `document.documentElement`)
 * @param vars map of CSS property name to value, typically from [toCssVarMap]
 */
fun applyCssVars(element: HTMLElement, vars: Map<String, String>) {
    for ((prop, value) in vars) {
        element.style.setProperty(prop, value)
    }
}

/**
 * Removes the given CSS custom properties from an element's inline style,
 * letting the inherited cascade values take over again.
 *
 * @param element the element to clear properties from
 * @param vars the property names to remove (only the keys are read; values are ignored)
 */
fun clearCssVars(element: HTMLElement, vars: Map<String, String>) {
    for (prop in vars.keys) {
        element.style.removeProperty(prop)
    }
}

/**
 * Sets `color-scheme: dark` or `color-scheme: light` on the element, which
 * lets the browser style native form controls and scrollbars to match the
 * theme. Apps typically call this on `document.documentElement` alongside
 * [applyCssVars].
 *
 * @param element the target element (e.g. `document.documentElement`)
 * @param isDark whether the current theme is the dark variant
 */
fun applyColorScheme(element: HTMLElement, isDark: Boolean) {
    element.style.setProperty("color-scheme", if (isDark) "dark" else "light")
}

/**
 * Section-prefix groups used by [applyUiSettings] when overlaying a
 * per-section [se.soderbjorn.darkness.core.ColorScheme] override on top
 * of the main theme at the document-root level. Only CSS variables whose
 * name starts with one of the listed prefixes are taken from the section
 * override at root scope; the full per-container paint in pass 3 is what
 * actually makes each section look distinct.
 *
 * Centralised here so any future palette field added to [toCssVarMap]
 * picks up section override behaviour automatically.
 */
private val SECTION_VAR_PREFIXES: Map<String, String> = mapOf(
    "sidebar" to "--t-sidebar-",
    "terminal" to "--t-terminal-",
    "diff" to "--t-diff-",
    "fileBrowser" to "--t-fileBrowser-",
    "tabs" to "--t-tabs-",
    "chrome" to "--t-chrome-",
    "windows" to "--t-windows-",
    "active" to "--t-active-",
    "bottomBar" to "--t-bottomBar-",
)

/**
 * Stable toolkit class selectors that identify the DOM subtree owned by
 * each themable section. Section schemes are painted as a FULL palette
 * onto every matching element so that every `--t-*` reference inside the
 * subtree resolves to the section's scheme — not just the few prefix
 * vars whose names happen to collide with the section name.
 *
 * Sections without a stable toolkit selector (`terminal`, `diff`,
 * `fileBrowser`, `active`) are app-specific: hosts that surface those
 * regions can layer their own per-element paint on top using
 * [applyCssVars] with the relevant section's palette. The toolkit only
 * owns the chrome it actually renders.
 *
 * @see applyUiSettings
 */
private val SECTION_SELECTORS: Map<String, String> = mapOf(
    // The topbar is the silhouette's top band — it's painted as "tabs",
    // not "chrome". The "chrome" section is a token producer: its
    // `--t-chrome-*` vars are published on `:root` by Pass 2 for elements
    // like `.dt-pane-header` that read `var(--t-chrome-titlebar)`. It
    // does not own a dedicated visible surface of its own. Routing it
    // onto `.dt-topbar` (the previous mapping) hid the "tabs" scheme on
    // themes whose tabs differ from chrome — e.g. Emerald Garden, where
    // tabs = Hot Magenta Bar never appeared because chrome = Forest
    // Panel painted the same element first.
    //
    // `.dt-tabbar` (the inner flex child inside `.dt-topbar-tabstrip`)
    // has no background-color rule, so painting onto it produced no
    // visible result either. The whole topbar is the right host for the
    // tabs scheme: its existing background rule reads `--t-surface-base`
    // so Pass 3 painting the tabs palette there flips the bar to the
    // tabs scheme's surface automatically. Matches the silhouette
    // renderer's `.dt-cs-tabs` band intent
    // (DefaultThemeManagerHost.kt:294-297).
    "sidebar" to ".dt-sidebar",
    "bottomBar" to ".dt-bottombar",
    "tabs" to ".dt-topbar",
    "windows" to ".dt-pane",
)

/**
 * Paints the full [se.soderbjorn.darkness.core.UiSettings] composition
 * onto [element] (typically `document.documentElement`) and, for each
 * non-null per-pane scheme, onto every matching section container
 * underneath [element].
 *
 *  1. Pass 1: resolve [se.soderbjorn.darkness.core.UiSettings.theme] for
 *     [isDark] and write every var from [toCssVarMap] + [toCssAliasMap]
 *     on [element]. This establishes the baseline palette every CSS rule
 *     can fall back to.
 *  2. Pass 2: for each entry in [SECTION_VAR_PREFIXES] whose pane has a
 *     scheme distinct from the main theme, write the pane's prefixed vars
 *     (e.g. `--t-chrome-*` for the chrome pane) on [element]. Lets rules
 *     outside any section container (e.g. floating overlays mounted at the
 *     document root) still pick up the override.
 *  3. Pass 3: for each entry in the active [paneSelectors] (defaults to
 *     [SECTION_SELECTORS] when null), find every matching element under
 *     [element] and paint the FULL palette + alias map of the pane's
 *     scheme onto it. Stale section vars are cleared first so picking a
 *     Theme that DOESN'T override a pane returns that container to
 *     inheriting the main palette. Inside each container, every `--t-*`
 *     var resolves to the pane's scheme — this is how termtastic gets a
 *     Neon Green sidebar on a Night Owl chrome.
 *  4. Sets `color-scheme: dark | light` on [element].
 *
 * Replaces the per-app paint code in notegrow / termtastic that only
 * applied the main theme. Without pass 3 the topbar, sidebar, bottom bar,
 * and panes all painted from the same root-level vars and visually
 * collapsed onto a single colour scheme — even when the active Theme
 * called for distinct schemes per section.
 *
 * Idempotent: every per-element write is paired with a removeProperty in
 * the next call, so switching from a multi-pane Theme to a uniform one
 * doesn't leave stale colours stuck on a container.
 *
 * Pane-name keys in [SECTION_VAR_PREFIXES] / [SECTION_SELECTORS] are
 * looked up in [se.soderbjorn.darkness.core.UiSettings.paneSchemes] via
 * [se.soderbjorn.darkness.core.UiSettings.schemeForPane], which falls
 * back to the main theme for any pane the app doesn't render. Apps that
 * don't have a given pane (e.g. notegrow has no terminal) simply emit no
 * override for it.
 *
 * @param element       the root element to paint (e.g. `document.documentElement`).
 *   Section containers are queried via `querySelectorAll` from this root.
 * @param settings      the persisted UI settings to paint.
 * @param isDark        whether the dark variant of each scheme should resolve.
 * @param paneSelectors when non-null, REPLACES [SECTION_SELECTORS] for
 *   pass 3 so apps with custom DOM structure can declare their own
 *   `pane → CSS selector` mapping. Pass `null` (the default) to use the
 *   built-in toolkit selectors. The keys must be the concrete pane names
 *   the app declares in its `paneToSection` map; values are CSS selectors
 *   resolved relative to [element].
 *
 * @see SECTION_SELECTORS
 * @see applyCssVars
 */
fun applyUiSettings(
    element: HTMLElement,
    settings: se.soderbjorn.darkness.core.UiSettings,
    isDark: Boolean,
    paneSelectors: Map<String, String>? = null,
) {
    // Pass 1: paint the main palette so every variable has a baseline.
    val mainPalette = settings.theme.resolve(isDark)
    val mainVars = mainPalette.toCssVarMap()
    val mainAliases = mainPalette.toCssAliasMap()
    applyCssVars(element, mainVars)
    applyCssVars(element, mainAliases)

    // Pass 2: overlay pane-specific *prefixed* vars at the root so
    // global rules outside any section container can still pick up the
    // override (e.g. a floating overlay pinned to documentElement).
    for ((paneName, prefix) in SECTION_VAR_PREFIXES) {
        val override = settings.schemeForPane(paneName)
        if (override === settings.theme) continue
        val sectionPalette = override.resolve(isDark)
        val sectionVars = sectionPalette.toCssVarMap()
            .filterKeys { it.startsWith(prefix) }
        applyCssVars(element, sectionVars)
    }

    // Pass 2b: the universal "accent" section is special — it does NOT
    // own a prefixed namespace of its own, it owns the global `--t-accent-*`
    // tokens (everywhere `accent.*` is referenced across the app). When
    // the user picks a scheme for the Accent section in the Theme Manager,
    // we overlay just that scheme's accent group onto the document root
    // so global overlays — command palettes, primary buttons, focus rings
    // — pick up an accent that's INDEPENDENT of the main scheme.
    val accentOverride = settings.schemeForPane(se.soderbjorn.darkness.core.Sections.Accent)
    if (accentOverride !== settings.theme) {
        val accentPalette = accentOverride.resolve(isDark)
        val accentVars = accentPalette.toCssVarMap()
            .filterKeys { it.startsWith("--t-accent-") }
        applyCssVars(element, accentVars)
    }

    // Pass 2c: the universal "activeChrome" section is special — it owns
    // the FOCUSED pane's titlebar bg + text, but is NOT a full chrome
    // surface. We expose only `--t-chrome-titlebar-active` and
    // `--t-chrome-titleText-active` on the document root, sourced raw
    // (no mix-softening) from the section scheme's bg/fg so the focused
    // pane reads as a saturated colored band. When the section is
    // unassigned the vars are removed and the CSS rule on
    // `.dt-pane.dt-pane-focused .dt-pane-titlebar` falls back to the
    // inactive `--t-chrome-titlebar` / `--t-chrome-titleText`. Themes
    // (e.g. Neon) that don't opt in produce no visual change.
    val activeChromeOverride = settings.schemeForPane(se.soderbjorn.darkness.core.Sections.ActiveChrome)
    if (activeChromeOverride !== settings.theme) {
        val acScheme = activeChromeOverride
        val acBg = se.soderbjorn.darkness.core.hexToArgb(
            if (isDark) acScheme.darkBg else acScheme.lightBg
        )
        val acFg = se.soderbjorn.darkness.core.hexToArgb(
            if (isDark) acScheme.darkFg else acScheme.lightFg
        )
        element.style.setProperty("--t-chrome-titlebar-active", argbToCss(acBg))
        element.style.setProperty("--t-chrome-titleText-active", argbToCss(acFg))
    } else {
        element.style.removeProperty("--t-chrome-titlebar-active")
        element.style.removeProperty("--t-chrome-titleText-active")
    }

    // Pass 3: per-pane container paint. The full palette is set on
    // every matching element so all `--t-*` references inside the
    // subtree resolve to the pane's scheme. Stale section vars are
    // removed first so picking a Theme without an override for this
    // pane returns the container to inheriting the main palette.
    val activeSelectors = paneSelectors ?: SECTION_SELECTORS
    val allKeys = mainVars.keys + mainAliases.keys
    for ((paneName, selector) in activeSelectors) {
        val nodes = element.querySelectorAll(selector)
        if (nodes.length == 0) continue
        val override = settings.schemeForPane(paneName)
        val needsApply = override !== settings.theme
        val cssVars: Map<String, String> = if (needsApply) {
            val sectionPalette = override.resolve(isDark)
            sectionPalette.toCssVarMap() + sectionPalette.toCssAliasMap()
        } else {
            emptyMap()
        }
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            // Clear unconditionally so a previous render's overlay is
            // wiped even when the new Theme doesn't override this pane.
            for (k in allKeys) el.style.removeProperty(k)
            if (needsApply) applyCssVars(el, cssVars)
        }
    }

    applyColorScheme(element, isDark)
}

// ── Per-category font CSS variables ─────────────────────────────────
//
// Each font category (mono / proportional / sidebar / tabbar) has a
// pair of `--dt-font-*` variables on `documentElement` that app
// stylesheets reference directly:
//
//     .dt-pane-terminal     { font-family: var(--dt-font-mono);
//                              font-size:   var(--dt-font-mono-size); }
//     .ng-editor-body       { font-family: var(--dt-font-prop);
//                              font-size:   var(--dt-font-prop-size); }
//     .dt-sidebar, .dt-topbar { font-family: var(--dt-font-sidebar, inherit);
//                              font-size:   var(--dt-font-sidebar-size, inherit); }
//     .dt-tabbar            { font-family: var(--dt-font-tabbar, var(--dt-font-sidebar, inherit));
//                              font-size:   var(--dt-font-tabbar-size, var(--dt-font-sidebar-size, inherit)); }
//
// Helpers below resolve preset keys to CSS stacks via [resolveFontFamilyCss] /
// [resolveProportionalFontFamilyCss], then write the resulting value (or
// remove the property if `null`) on `documentElement`. The Settings
// sidebar's pill-row click handlers call them for immediate-paint
// feedback; persistence sync (e.g. termtastic's settings round-trip)
// also calls them after applying a snapshot.

private fun setOrClearVar(name: String, value: String?) {
    val root = kotlinx.browser.document.documentElement as? HTMLElement ?: return
    if (value.isNullOrEmpty()) root.style.removeProperty(name)
    else root.style.setProperty(name, value)
}

/**
 * Apply [key] (a [se.soderbjorn.darkness.web.themeeditor.FontPreset.key]
 * for a `Mono` preset) as the document-level monospaced font.
 *
 * Sets `--dt-font-mono` to the resolved CSS stack, or clears it when
 * [key] is null/empty.
 */
fun applyMonoFontFamily(key: String?) {
    setOrClearVar("--dt-font-mono",
        if (key.isNullOrEmpty()) null else resolveFontFamilyCss(key))
}

/**
 * Apply [px] as the document-level monospaced font size. Clears the
 * variable when [px] is null.
 */
fun applyMonoFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-mono-size", px?.let { "${it}px" })
}

/**
 * Apply [key] (a `Proportional` preset key) as the document-level
 * proportional font for prose surfaces.
 */
fun applyProportionalFontFamily(key: String?) {
    setOrClearVar("--dt-font-prop",
        if (key.isNullOrEmpty()) null else resolveProportionalFontFamilyCss(key))
}

/** Apply [px] as the document-level proportional font size. */
fun applyProportionalFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-prop-size", px?.let { "${it}px" })
}

/**
 * Apply [key] as the chrome (sidebar + topbar) font. Sidebar font is
 * proportional by convention but the helper accepts either kind so a
 * power-user who wants a monospaced sidebar can pick one.
 */
fun applySidebarFontFamily(key: String?) {
    if (key.isNullOrEmpty()) {
        setOrClearVar("--dt-font-sidebar", null)
        return
    }
    val css = fontPresets.firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-sidebar", css)
}

/** Apply [px] as the chrome (sidebar + topbar) font size. */
fun applySidebarFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-sidebar-size", px?.let { "${it}px" })
}

/**
 * Apply [key] as the tab-strip font. Falls through the same resolution
 * as [applySidebarFontFamily] so any preset kind is accepted.
 */
fun applyTabbarFontFamily(key: String?) {
    if (key.isNullOrEmpty()) {
        setOrClearVar("--dt-font-tabbar", null)
        return
    }
    val css = fontPresets.firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-tabbar", css)
}

/** Apply [px] as the tab-strip font size. */
fun applyTabbarFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-tabbar-size", px?.let { "${it}px" })
}
