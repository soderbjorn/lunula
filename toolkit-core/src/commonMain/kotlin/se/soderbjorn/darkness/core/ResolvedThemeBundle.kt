/**
 * Active-theme resolver shared by every Darkness app.
 *
 * [resolveActiveTheme] picks the snapshot's slot for the current
 * appearance, looks the slot's name up as a [Theme] (custom themes ∪
 * [defaultThemes]), then resolves every concrete pane the app declares
 * via the precedence ladder defined on [Theme]. The returned
 * [ResolvedThemeBundle] carries the main scheme + a per-pane scheme map
 * the app can splat onto its paint pipeline (e.g. notegrow's
 * [UiSettings.paneSchemes]).
 *
 * Mirrors the `refreshActiveTheme` semantics of termtastic's pre-toolkit
 * `SettingsViewModel` so both apps now share one implementation.
 *
 * @see Theme
 * @see ThemeSnapshot
 * @see UiSettings.applyTheme
 */
package se.soderbjorn.darkness.core

/**
 * Resolved colour-scheme assignments for one paint pass.
 *
 * @property theme       the active main colour scheme.
 * @property paneSchemes per-pane resolved schemes, keyed by concrete pane
 *   name. Includes only panes the app declared in its `paneToSection` map
 *   AND for which the resolved scheme exists.
 */
data class ResolvedThemeBundle(
    val theme: ColorScheme,
    val paneSchemes: Map<String, ColorScheme>,
)

/**
 * Resolve the per-pane [ColorScheme] map for [theme] given the app's
 * [paneToSection] map and the user's [customSchemes] pool.
 *
 * Resolution per pane:
 * ```
 * theme.sections[paneToSection[pane]]    // universal section assignment
 *     ?: theme.colorScheme               // theme baseline
 * ```
 *
 * Names that don't resolve in `recommendedColorSchemes` ∪ [customSchemes]
 * are silently skipped. The caller can fall back to the main scheme for
 * any pane not present in the result.
 */
fun resolvePaneSchemes(
    theme: Theme,
    paneToSection: Map<String, String>,
    customSchemes: Map<String, ColorScheme> = emptyMap(),
): Map<String, ColorScheme> {
    val out = mutableMapOf<String, ColorScheme>()
    for ((pane, section) in paneToSection) {
        val name = theme.sections[section] ?: theme.colorScheme
        val scheme = lookupScheme(name, customSchemes) ?: continue
        out[pane] = scheme
    }
    // Also surface section-keyed entries (e.g. "accent" → that section's
    // scheme) so painters that operate at the universal-section level —
    // like the global accent overlay in `applyUiSettings` — can look up
    // by [Sections] constant directly without the host having to
    // declare a pseudo "accent" pane in its [paneToSection] map.
    //
    // Concrete-pane keys take precedence: if an app already has a pane
    // literally named "accent", we don't overwrite that lookup.
    for (section in Sections.all) {
        if (section in out) continue
        val name = theme.sections[section] ?: continue
        val scheme = lookupScheme(name, customSchemes) ?: continue
        out[section] = scheme
    }
    return out
}

/**
 * Resolve the active main scheme + per-pane schemes for the user's
 * current state.
 *
 * Slot selection:
 *  - if [Appearance.Dark], or [Appearance.Auto] with [systemIsDark] true,
 *    the active slot is [ThemeSnapshot.darkThemeName] (falling back to
 *    [DEFAULT_DARK_THEME_NAME]).
 *  - otherwise the active slot is [ThemeSnapshot.lightThemeName] (falling
 *    back to [DEFAULT_LIGHT_THEME_NAME]).
 *
 * The slot name is looked up in `snapshot.customThemes` ∪ [defaultThemes].
 * If neither matches, a synthetic `Theme(name=slot, colorScheme=slot)` is
 * used so a name that resolves only as a scheme still applies.
 *
 * @param snapshot     the persisted theme snapshot.
 * @param appearance   the user's appearance preference.
 * @param systemIsDark the host platform's "prefers dark" setting; only
 *   consulted when [appearance] is [Appearance.Auto].
 * @param paneToSection the app's pane → universal section map.
 * @return the resolved bundle. If the slot's main scheme is unknown,
 *   falls back to [DEFAULT_THEME_NAME] from `recommendedColorSchemes`.
 */
fun resolveActiveTheme(
    snapshot: ThemeSnapshot,
    appearance: Appearance,
    systemIsDark: Boolean,
    paneToSection: Map<String, String>,
): ResolvedThemeBundle {
    val effectiveDark = when (appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    val slotName = if (effectiveDark) {
        snapshot.darkThemeName ?: DEFAULT_DARK_THEME_NAME
    } else {
        snapshot.lightThemeName ?: DEFAULT_LIGHT_THEME_NAME
    }
    val customSchemesAsColor: Map<String, ColorScheme> =
        snapshot.customSchemes.mapValues { it.value.toColorScheme() }
    val theme: Theme = snapshot.customThemes[slotName]
        ?: defaultThemes.firstOrNull { it.name == slotName }
        ?: Theme(name = slotName, colorScheme = slotName)
    val main = lookupScheme(theme.colorScheme, customSchemesAsColor)
        ?: lookupScheme(DEFAULT_THEME_NAME, customSchemesAsColor)
        ?: recommendedColorSchemes.first()
    val schemes = resolvePaneSchemes(theme, paneToSection, customSchemesAsColor)
    return ResolvedThemeBundle(theme = main, paneSchemes = schemes)
}

private fun lookupScheme(
    name: String,
    customSchemes: Map<String, ColorScheme>,
): ColorScheme? {
    if (name.isEmpty()) return null
    return recommendedColorSchemes.firstOrNull { it.name == name }
        ?: customSchemes[name]
}
