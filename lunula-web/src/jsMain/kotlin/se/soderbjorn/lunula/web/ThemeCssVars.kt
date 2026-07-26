/**
 * Helpers for projecting a [ResolvedTheme] onto a DOM element via CSS
 * custom properties.
 *
 * This module is deliberately library-style: it ships pure functions and
 * a small `applyCssVars` helper. Apps decide *where* to apply the variables
 * (document root, a subtree, or a Shadow DOM) — there is no global "install
 * the theme on document.body" function.
 *
 * Property names follow the flat `--t-<token>` convention (e.g. `--t-bg`,
 * `--t-text`, `--t-accent`) — exactly the 32 semantic tokens of the
 * post-revamp theme system, including the 9 chrome/canvas ones
 * (`--t-chrome-bg`, `--t-canvas`, …). The toolkit stylesheet reads these names
 * directly, so themes round-trip without any stylesheet rewrites.
 *
 * @see ResolvedTheme
 */
package se.soderbjorn.lunula.web

import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.ResolvedTheme
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.UiDensity
import se.soderbjorn.lunula.core.argbToCss
import se.soderbjorn.lunula.web.themeeditor.allFontPresets
import se.soderbjorn.lunula.web.themeeditor.resolveFontFamilyCss
import se.soderbjorn.lunula.web.themeeditor.resolveProportionalFontFamilyCss

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
 * Converts a [ResolvedTheme] to a map of CSS custom property names to CSS
 * colour values — exactly the 32 flat `--t-<token>` names the toolkit
 * stylesheet reads.
 *
 * Every value is produced via [argbToCss].
 *
 * The 9 chrome/canvas vars are always emitted: [ResolvedTheme] has already
 * applied each optional token's fallback, so a theme that doesn't split its
 * chrome from its content emits chrome vars equal to its base tokens. The
 * stylesheet can therefore read `--t-chrome-bg` unconditionally instead of
 * restating the fallback in a `var()` chain.
 *
 * @return map of CSS property name to CSS colour string
 * @see argbToCss
 * @see applyCssVars
 */
fun ResolvedTheme.toCssVarMap(): Map<String, String> = buildMap {
    put("--t-bg", argbToCss(bg))
    put("--t-canvas", argbToCss(canvas))
    put("--t-chrome-bg", argbToCss(chromeBg))
    put("--t-chrome-text", argbToCss(chromeText))
    put("--t-chrome-text-dim", argbToCss(chromeTextDim))
    put("--t-chrome-text-bright", argbToCss(chromeTextBright))
    put("--t-chrome-border", argbToCss(chromeBorder))
    put("--t-chrome-accent", argbToCss(chromeAccent))
    put("--t-chrome-accent-soft", argbToCss(chromeAccentSoft))
    put("--t-chrome-track", argbToCss(chromeTrack))
    put("--t-surface", argbToCss(surface))
    put("--t-surface-alt", argbToCss(surfaceAlt))
    put("--t-border", argbToCss(border))
    put("--t-text", argbToCss(text))
    put("--t-text-dim", argbToCss(textDim))
    put("--t-text-bright", argbToCss(textBright))
    put("--t-accent", argbToCss(accent))
    put("--t-accent-soft", argbToCss(accentSoft))
    put("--t-accent-on", argbToCss(accentOn))
    put("--t-accent-text", argbToCss(accentText))
    put("--t-glow", argbToCss(glow))
    put("--t-warn", argbToCss(warn))
    put("--t-warn-on", argbToCss(warnOn))
    put("--t-warn-text", argbToCss(warnText))
    put("--t-danger", argbToCss(danger))
    put("--t-danger-on", argbToCss(dangerOn))
    put("--t-danger-text", argbToCss(dangerText))
    put("--t-remove-bg", argbToCss(removeBgTint))
    put("--t-add", argbToCss(add))
    put("--t-add-bg", argbToCss(addBg))
    put("--t-add-on", argbToCss(addOn))
    put("--t-add-text", argbToCss(addText))
    put("--t-chrome-accent-on", argbToCss(chromeAccentOn))
    put("--t-chrome-accent-text", argbToCss(chromeAccentText))
    put("--t-syn-keyword", argbToCss(synKeyword))
    put("--t-syn-string", argbToCss(synString))
    put("--t-syn-number", argbToCss(synNumber))
    put("--t-syn-comment", argbToCss(synComment))
    put("--t-syn-function", argbToCss(synFunction))
    put("--t-syn-type", argbToCss(synType))
    put("--t-syn-operator", argbToCss(synOperator))
    put("--t-syn-constant", argbToCss(synConstant))
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
 * Paints a [ResolvedTheme] onto [element] (typically `document.documentElement`):
 * writes every variable from [toCssVarMap] then sets the matching
 * `color-scheme` via [applyColorScheme].
 *
 * This is the single entry point apps call to apply the active theme. The
 * old per-section / per-pane override machinery is gone — the new theme
 * system paints one flat palette and every `var(--t-*)` reference resolves
 * to it.
 *
 * @param element the root element to paint (e.g. `document.documentElement`).
 * @param theme   the resolved palette to write.
 * @param isDark  whether the dark variant is active (drives `color-scheme`).
 * @see toCssVarMap
 * @see applyColorScheme
 */
fun applyTheme(element: HTMLElement, theme: ResolvedTheme, isDark: Boolean) {
    applyCssVars(element, theme.toCssVarMap())
    applyColorScheme(element, isDark)
}

/**
 * Stamps the active [SelectionStyle] onto [element] as the `data-dt-selection`
 * attribute. `null` means "nobody has chosen", and resolves to
 * [SelectionStyle.Default].
 *
 * Only Fill is written; [SelectionStyle.Tint] *removes* the attribute, because
 * the stylesheet paints tint in its base rules and keys only the fill overrides
 * off the selector. The asymmetry is deliberate — it keeps the default
 * expressible in one Kotlin constant instead of forcing the whole selection
 * section of `lunula.css` to be rewritten as `:not([data-dt-selection="tint"])`
 * every time the default moves.
 *
 * An attribute rather than a CSS variable, and that is the whole design. The
 * two selection languages are not one rule with a different colour in it: Fill
 * swaps a wash for a solid field, flips the label to the field's declared
 * foreground, and drops the pane outline and the focus glow — four rules moving
 * together. A variable can carry a value into a rule; only a selector can
 * decide which rules exist. Keeping the switch in the stylesheet also keeps
 * both languages readable side by side, instead of scattering the difference
 * across a dozen `var()` fallback chains.
 *
 * Deliberately NOT called by [applyTheme]. This is a user setting, not a theme
 * property: binding it to the palette would mean a user who prefers filled
 * selection loses it the moment they try a different theme. It rides the same
 * path as corner radius and density instead — see `applyHostFontVars` in
 * `AppShellMount`.
 *
 * @param element the themed root (e.g. `document.documentElement`).
 * @param style   the selection language, or `null` for [SelectionStyle.Default].
 * @see SelectionStyle
 * @see applyCornerRadiusPx
 */
fun applySelectionStyle(element: HTMLElement, style: SelectionStyle?) {
    val effective = style ?: SelectionStyle.Default
    if (effective == SelectionStyle.Tint) element.removeAttribute("data-dt-selection")
    else element.setAttribute("data-dt-selection", effective.cssValue)
}

// ── Per-category font CSS variables ─────────────────────────────────
//
// Each font category (mono / proportional / sidebar / tabbar /
// pane-header) has a pair of `--dt-font-*` variables on `documentElement`
// that app stylesheets reference directly:
//
//     .dt-pane-terminal     { font-family: var(--dt-font-mono);
//                              font-size:   var(--dt-font-mono-size); }
//     .ng-editor-body       { font-family: var(--dt-font-prop);
//                              font-size:   var(--dt-font-prop-size); }
//     .dt-sidebar, .dt-topbar { font-family: var(--dt-font-sidebar, inherit);
//                              font-size:   var(--dt-font-sidebar-size, inherit); }
//     .dt-tabbar            { font-family: var(--dt-font-tabbar, var(--dt-font-sidebar, inherit));
//                              font-size:   var(--dt-font-tabbar-size, var(--dt-font-sidebar-size, inherit)); }
//     .dt-pane-title        { font-family: var(--dt-font-pane-header, inherit);
//                              font-size:   var(--dt-font-pane-header-size, var(--dt-pane-title-size, 11px)); }
//
// The `display` category (`--dt-font-display`) has no toolkit-native element:
// it is written for a consuming app whose own stylesheet binds a heading /
// display face to `var(--dt-font-display, …)` (e.g. Lunicle's issue titles and
// board column headers), parallel to how `--dt-font-prop` drives app prose.
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
 * Apply [key] (a [se.soderbjorn.lunula.web.themeeditor.FontPreset.key]
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
    val css = allFontPresets().firstOrNull { it.key == key }?.cssStack
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
    val css = allFontPresets().firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-tabbar", css)
}

/** Apply [px] as the tab-strip font size. */
fun applyTabbarFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-tabbar-size", px?.let { "${it}px" })
}

/**
 * Apply [key] as the pane-title (pane header) font. Falls through the same
 * resolution as [applySidebarFontFamily] so any preset kind is accepted.
 */
fun applyPaneHeaderFontFamily(key: String?) {
    if (key.isNullOrEmpty()) {
        setOrClearVar("--dt-font-pane-header", null)
        return
    }
    val css = allFontPresets().firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-pane-header", css)
}

/** Apply [px] as the pane-title (pane header) font size. */
fun applyPaneHeaderFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-pane-header-size", px?.let { "${it}px" })
}

/**
 * Apply [key] as the display (heading) font — the consuming app's largest
 * headings, e.g. issue titles and column names. Resolved like prose (any
 * preset key, built-in or app-injected, walks [allFontPresets]); the app's
 * stylesheet consumes `--dt-font-display` on whichever elements it treats as
 * display. Falls back to prose in the app CSS when unset, so an app that
 * never sets it is unchanged.
 */
fun applyDisplayFontFamily(key: String?) {
    if (key.isNullOrEmpty()) {
        setOrClearVar("--dt-font-display", null)
        return
    }
    val css = allFontPresets().firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-display", css)
}

/** Apply [px] as the display (heading) font size. */
fun applyDisplayFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-display-size", px?.let { "${it}px" })
}

// ── Shape and density appearance settings ───────────────────────────
//
// These are USER settings, not theme properties, and the distinction is worth
// stating because the first draft had them the other way round. A theme
// answers "what does this app look like"; corner roundness and spacing answer
// "how do I like my windows", which is the same kind of question as font size
// — the answer should survive switching theme, and a user who likes square
// corners should not have to give up a palette to keep them.
//
// So they live beside the font settings: the toolkit owns the values and the
// CSS wiring, each app owns persistence (see ThemeManagerHost.cornerRadiusPx /
// uiDensity), and both are applied on boot the same way the fonts are.

/**
 * Apply [px] as the corner radius of panes, tabs and sidebar rows.
 *
 * Writes `--dt-corner-radius`, which `.dt-app-frame` reads when computing
 * `--dt-frame-radius` (panes) and `--dt-tab-radius` (tabs, sidebar pills, dock
 * items). Passing `null` clears the override and restores the toolkit default.
 *
 * The tab radius is not a second setting: the stylesheet derives it as
 * `min(--dt-corner-radius, 11px)`, so a square setting squares everything and a
 * round one rounds the panes further than the pills. Two independent dials
 * would let a user build a shell whose windows and tabs disagree about what
 * kind of object they are, which is a worse outcome than the one choice they
 * actually want to express.
 *
 * @param px the radius in pixels, or `null` for the toolkit default.
 * @see se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost.cornerRadiusPx
 */
fun applyCornerRadiusPx(px: Int?) {
    setOrClearVar("--dt-corner-radius", px?.let { "${it}px" })
}

/**
 * Apply [density] as the chrome spacing scale.
 *
 * Stamps `data-dt-density` on `documentElement`; `lunula.css` re-declares the
 * `--dt-*` padding/gap tokens under that attribute. Passing `null` clears it,
 * which is the same as [UiDensity.Compact] — the toolkit's historical spacing.
 *
 * @param density the spacing scale, or `null` for the default.
 * @see UiDensity
 */
fun applyUiDensity(density: UiDensity?) {
    val root = kotlinx.browser.document.documentElement as? HTMLElement ?: return
    if (density == null || density == UiDensity.Compact) root.removeAttribute("data-dt-density")
    else root.setAttribute("data-dt-density", density.cssValue)
}
