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
import se.soderbjorn.lunula.core.argbToCss
import se.soderbjorn.lunula.web.themeeditor.fontPresets
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
    put("--t-glow", argbToCss(glow))
    put("--t-warn", argbToCss(warn))
    put("--t-danger", argbToCss(danger))
    put("--t-add", argbToCss(add))
    put("--t-add-bg", argbToCss(addBg))
    put("--t-add-text", argbToCss(addText))
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

/**
 * Apply [key] as the pane-title (pane header) font. Falls through the same
 * resolution as [applySidebarFontFamily] so any preset kind is accepted.
 */
fun applyPaneHeaderFontFamily(key: String?) {
    if (key.isNullOrEmpty()) {
        setOrClearVar("--dt-font-pane-header", null)
        return
    }
    val css = fontPresets.firstOrNull { it.key == key }?.cssStack
        ?: resolveProportionalFontFamilyCss(key)
    setOrClearVar("--dt-font-pane-header", css)
}

/** Apply [px] as the pane-title (pane header) font size. */
fun applyPaneHeaderFontSizePx(px: Int?) {
    setOrClearVar("--dt-font-pane-header-size", px?.let { "${it}px" })
}
