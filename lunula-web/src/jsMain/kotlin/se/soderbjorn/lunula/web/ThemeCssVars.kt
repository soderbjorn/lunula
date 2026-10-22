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
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.ResolvedAppearance
import se.soderbjorn.lunula.core.ResolvedTheme
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
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
 * The style-setting vars — `--t-window-idle`, `--t-window-active`,
 * `--t-window-ring`, `--t-accent-soft-solid`, `--t-text-dim-legible` and
 * `--t-accent-soft-text` — are likewise always emitted, opaque, for the
 * Tone, Sheet, Blended and Soft rules to read; the default rules ignore them.
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
    put("--t-selection", argbToCss(selection))
    put("--t-window-idle", argbToCss(windowIdle))
    // The focused window's fill under Focus = Tone: surface, lifted when it
    // would sit too close to the idle fill. See ResolvedTheme.windowActive.
    put("--t-window-active", argbToCss(windowActive))
    // The Tone focused-window ring: border nudged toward text. The default
    // settings never read it. See ResolvedTheme.windowRing.
    put("--t-window-ring", argbToCss(windowRing))
    // Legible derivations for the soft selection and the dim chrome text
    // (LNA-19). Always emitted; the default rules never read them. Each equals its source exactly when the source is already
    // legible — see ResolvedTheme.legibleTextDim / accentSoftText.
    put("--t-accent-soft-solid", argbToCss(accentSoftSolid))
    put("--t-text-dim-legible", argbToCss(legibleTextDim))
    put("--t-accent-soft-text", argbToCss(accentSoftText))
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
 * [applyCssVars]. Also writes the per-side tuning vars of the Depth surface
 * ([depthTuningVars]) and of the soft lift ([softLiftTuningVars]).
 *
 * @param element the target element (e.g. `document.documentElement`)
 * @param isDark whether the current theme is the dark variant
 */
fun applyColorScheme(element: HTMLElement, isDark: Boolean) {
    element.style.setProperty("color-scheme", if (isDark) "dark" else "light")
    applyCssVars(element, depthTuningVars(isDark))
    applyCssVars(element, softLiftTuningVars(isDark))
}

/**
 * The light/dark tuning of the soft lift, as the `--dt-soft-lift` custom
 * property `lunula.css` reads for the focused window under
 * [FocusStyle.Tone] and for dialogs and menus under [SurfaceStyle.Soft].
 *
 * Written by [applyColorScheme] next to [depthTuningVars], for the same
 * reason: the values are inert under the default settings, where no rule reads them, so
 * switching a setting never has to wait for a theme repaint.
 *
 * `--dt-soft-lift` is the soft two-layer drop shadow under the focused
 * window (a tight contact shadow plus a wide ambient one). It is black on
 * both sides, but at about 3.5× the alpha on dark grounds, where a light
 * theme's 5 % / 10 % would not show at all.
 *
 * @param isDark whether the dark variant is active.
 * @return CSS custom property name → value.
 * @see depthTuningVars
 */
fun softLiftTuningVars(isDark: Boolean): Map<String, String> = if (isDark) mapOf(
    "--dt-soft-lift" to "0 2px 4px rgba(0, 0, 0, 0.2), 0 12px 32px rgba(0, 0, 0, 0.35)",
) else mapOf(
    "--dt-soft-lift" to "0 2px 4px rgba(0, 0, 0, 0.05), 0 12px 32px rgba(0, 0, 0, 0.1)",
)

/**
 * The light/dark tuning of the Depth surface style ([SurfaceStyle.Depth]), as
 * the `--dt-depth-*` custom properties `lunula.css` composes its shadows, sheen,
 * glow and ambient wash from.
 *
 * Written by [applyColorScheme] on every theme application, whatever the
 * surface style — the values are inert under [SurfaceStyle.Flat], where no rule
 * reads them — so switching Depth on never has to wait for a theme repaint.
 * Apps may read them in their own CSS to match the toolkit's elevation (e.g.
 * `box-shadow: 0 8px 18px -6px color-mix(in srgb, var(--dt-depth-shadow)
 * calc(14% * var(--dt-depth-strength)), transparent)`).
 *
 * Why the two sides differ:
 *  - **Shadow colour** — black on dark; on light a dark tint of the theme's own
 *    accent hue, so shadows read as warm/olive/blue rather than dirty grey.
 *  - **Strength** — one multiplier on every shadow alpha, ~2.6× on dark, where
 *    shadows hardly show against a near-black canvas.
 *  - **Highlight** — the 1px top edge is what does the lifting on dark (barely
 *    there, .06) and a crisp white rim on light (.9).
 *  - **Glow / wash** — accent glows and the corner washes are cut back on light
 *    grounds, where at dark-theme strength they look like smudges.
 *
 * @param isDark whether the dark variant is active.
 * @return CSS custom property name → value.
 */
fun depthTuningVars(isDark: Boolean): Map<String, String> = if (isDark) mapOf(
    "--dt-depth-shadow" to "#000",
    "--dt-depth-strength" to "2.6",
    "--dt-depth-highlight" to "rgb(255 255 255 / 0.06)",
    "--dt-depth-glow" to "70%",
    "--dt-depth-wash" to "26%",
    "--dt-depth-wash-2" to "16%",
) else mapOf(
    "--dt-depth-shadow" to "oklch(from var(--t-accent, #4f8cff) 0.3 0.06 h)",
    "--dt-depth-strength" to "1",
    "--dt-depth-highlight" to "rgb(255 255 255 / 0.9)",
    "--dt-depth-glow" to "40%",
    "--dt-depth-wash" to "15%",
    "--dt-depth-wash-2" to "11%",
)

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
 * Fill and Soft are written; [SelectionStyle.Tint] *removes* the attribute,
 * because the stylesheet paints tint in its base rules and keys only the Fill
 * and Soft overrides off the selector. The asymmetry is deliberate — it keeps the default
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
// uiDensity / surfaceStyle), and all are applied on boot the same way the
// fonts are.

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

/**
 * Apply [style] as the shell's surface treatment.
 *
 * Stamps `data-dt-surface` on `documentElement` — `flat` for
 * [SurfaceStyle.Flat], `soft` for [SurfaceStyle.Soft] — and removes the
 * attribute for Depth; `null` resolves to [SurfaceStyle.Default] (Depth).
 * `lunula.css` writes the Depth rules under `:root:not([data-dt-surface])`, so
 * Flat and Soft both start from the base stylesheet with nothing layered on,
 * Soft adds its calm cards, and the default needs no attribute.
 *
 * Like [applyUiDensity] this is a user setting, not a theme property: it rides
 * the host-apply pass in `AppShellMount` (restored at boot, re-applied on every
 * rebuild, untouched by theme changes) through [applyAppearance]. The
 * light/dark tuning it depends on comes from [depthTuningVars], written by
 * [applyColorScheme].
 *
 * @param style the surface style, or `null` for the default.
 * @see SurfaceStyle
 * @see applyAppearance
 */
fun applySurfaceStyle(style: SurfaceStyle?) {
    val root = kotlinx.browser.document.documentElement as? HTMLElement ?: return
    when (style ?: SurfaceStyle.Default) {
        SurfaceStyle.Depth -> root.removeAttribute("data-dt-surface")
        else -> root.setAttribute("data-dt-surface", (style ?: SurfaceStyle.Default).cssValue)
    }
}

/**
 * Stamps [attr] on [element] with [value], or removes it when [value] is
 * `null`. The shared mechanics of the four style appliers below, each of
 * which writes its attribute only for the non-default option so the base
 * stylesheet stays the default look.
 */
private fun stampOrClear(element: HTMLElement, attr: String, value: String?) {
    if (value == null) element.removeAttribute(attr) else element.setAttribute(attr, value)
}

/**
 * Apply [style] as the chrome treatment: `data-dt-chrome="blended"` for
 * [ChromeStyle.Blended], no attribute for Themed (the base stylesheet).
 *
 * @param element the themed root (e.g. `document.documentElement`).
 * @param style   the chrome style, or `null` for [ChromeStyle.Default].
 * @see ChromeStyle
 * @see applyAppearance
 */
fun applyChromeStyle(element: HTMLElement, style: ChromeStyle?) =
    stampOrClear(element, "data-dt-chrome", (style ?: ChromeStyle.Default).takeIf { it != ChromeStyle.Themed }?.cssValue)

/**
 * Apply [layout] as the window layout: `data-dt-windows="sheet"` for
 * [WindowLayout.Sheet], no attribute for Cards (the base stylesheet).
 *
 * @param element the themed root (e.g. `document.documentElement`).
 * @param layout  the window layout, or `null` for [WindowLayout.Default].
 * @see WindowLayout
 * @see applyAppearance
 */
fun applyWindowLayout(element: HTMLElement, layout: WindowLayout?) =
    stampOrClear(element, "data-dt-windows", (layout ?: WindowLayout.Default).takeIf { it != WindowLayout.Cards }?.cssValue)

/**
 * Apply [style] as the focus treatment: `data-dt-focus="tone"` for
 * [FocusStyle.Tone], no attribute for Accent (the base stylesheet).
 *
 * @param element the themed root (e.g. `document.documentElement`).
 * @param style   the focus style, or `null` for [FocusStyle.Default].
 * @see FocusStyle
 * @see applyAppearance
 */
fun applyFocusStyle(element: HTMLElement, style: FocusStyle?) =
    stampOrClear(element, "data-dt-focus", (style ?: FocusStyle.Default).takeIf { it != FocusStyle.Accent }?.cssValue)

/**
 * Apply [case] as the chrome label casing: `data-dt-labels="sentence"` for
 * [LabelCase.Sentence], no attribute for Uppercase (the base stylesheet).
 *
 * @param element the themed root (e.g. `document.documentElement`).
 * @param case    the casing, or `null` for [LabelCase.Default].
 * @see LabelCase
 * @see applyAppearance
 */
fun applyLabelCase(element: HTMLElement, case: LabelCase?) =
    stampOrClear(element, "data-dt-labels", (case ?: LabelCase.Default).takeIf { it != LabelCase.Uppercase }?.cssValue)

/**
 * Applies every style setting of [resolved] at once: corner radius, spacing,
 * selection, surfaces, chrome, windows, active window and labels.
 *
 * Called by `AppShellMount`'s host-apply pass (on boot, on every rebuild, and
 * when the appearance switches between dark and light, since each mode has
 * its own values) and by the Appearance settings after a pick.
 *
 * @param element  the themed root, normally `document.documentElement`.
 * @param resolved the values to paint.
 * @see se.soderbjorn.lunula.core.resolveAppearance
 * @see appliedAppearance
 */
fun applyAppearance(element: HTMLElement, resolved: ResolvedAppearance) {
    applyCornerRadiusPx(resolved.cornerRadiusPx)
    applyUiDensity(resolved.uiDensity)
    applySelectionStyle(element, resolved.selectionStyle)
    applySurfaceStyle(resolved.surfaceStyle)
    applyChromeStyle(element, resolved.chromeStyle)
    applyWindowLayout(element, resolved.windowLayout)
    applyFocusStyle(element, resolved.focusStyle)
    applyLabelCase(element, resolved.labelCase)
}

/**
 * Reads back the style settings [applyAppearance] last stamped on [element].
 *
 * The stamps on `:root` are the RESOLVED values (user pick for the active
 * mode, then app/brand default, then toolkit default), so this is how code
 * without access to the shell follows them: the Theme Manager's thumbnails,
 * and an app deciding whether its terminal canvas should go transparent under
 * [FocusStyle.Tone]. Before anything is stamped it reads the defaults, the
 * same as what is painted.
 *
 * @param element the themed root, normally `document.documentElement`.
 * @return the values in effect on [element].
 * @see applyAppearance
 */
fun appliedAppearance(element: HTMLElement): ResolvedAppearance {
    val radius = element.style.getPropertyValue("--dt-corner-radius").removeSuffix("px").trim().toIntOrNull()
    return ResolvedAppearance(
        cornerRadiusPx = radius,
        uiDensity = UiDensity.fromRaw(element.getAttribute("data-dt-density")) ?: UiDensity.Compact,
        selectionStyle = SelectionStyle.fromRaw(element.getAttribute("data-dt-selection")) ?: SelectionStyle.Tint,
        surfaceStyle = SurfaceStyle.fromRaw(element.getAttribute("data-dt-surface")) ?: SurfaceStyle.Depth,
        chromeStyle = ChromeStyle.fromRaw(element.getAttribute("data-dt-chrome")) ?: ChromeStyle.Themed,
        windowLayout = WindowLayout.fromRaw(element.getAttribute("data-dt-windows")) ?: WindowLayout.Cards,
        focusStyle = FocusStyle.fromRaw(element.getAttribute("data-dt-focus")) ?: FocusStyle.Accent,
        labelCase = LabelCase.fromRaw(element.getAttribute("data-dt-labels")) ?: LabelCase.Uppercase,
    )
}
