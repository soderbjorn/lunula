/**
 * Web-side glue around [resolveActiveTheme] that hides the
 * `systemIsDark` / `appearance` plumbing and lands the result on a
 * fresh [UiSettings] copy ready for the toolkit's painter.
 *
 * The pure resolver lives in `toolkit-core` (commonMain) so JVM / iOS
 * apps can reuse it; this jsMain helper supplies the `Auto`-aware
 * `systemIsDark` value via [isDarkActive] and folds the resolved bundle
 * back onto a base [UiSettings].
 *
 * @see resolveActiveTheme
 * @see DefaultThemeManagerState
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.resolveActiveTheme
import se.soderbjorn.darkness.web.isDarkActive

/**
 * Build the [UiSettings] the painter should apply for the user's current
 * state.
 *
 * Pipeline:
 *  1. Snapshot [state] via [DefaultThemeManagerState.toSnapshot].
 *  2. Resolve the active theme + per-pane scheme map via the shared
 *     [resolveActiveTheme] (which handles slot selection across the
 *     light/dark/auto appearance modes and merges the snapshot's custom
 *     themes / schemes with the toolkit defaults).
 *  3. Splat the resolved bundle onto [base] — replacing the main
 *     [UiSettings.theme] and the [UiSettings.paneSchemes] map, while
 *     preserving any other [base] field the caller may have set
 *     (currently just [UiSettings.appearance], which is overwritten with
 *     [DefaultThemeManagerState.appearance] so the painter always sees
 *     the latest user pick).
 *
 * Apps call this from their post-mutation handler (the same place that
 * persists the snapshot via [ThemeSnapshotStorage]) and hand the result
 * to [se.soderbjorn.darkness.web.applyUiSettings].
 *
 * @param state          in-memory backing store for the Theme Manager.
 * @param base           the previous [UiSettings] to update; used as the
 *   template for fields the resolver doesn't compute (e.g. the user's
 *   raw appearance preference is preserved when [state.appearance] is
 *   the same as `base.appearance`).
 * @param paneToSection  app-supplied map of concrete pane name → universal
 *   [se.soderbjorn.darkness.core.Sections] constant. The resolver uses it
 *   to decide which pane gets which section's scheme.
 * @return a fresh [UiSettings] with the active theme resolved and applied.
 *
 * @see resolveActiveTheme
 * @see se.soderbjorn.darkness.web.applyUiSettings
 */
fun resolveActiveUiSettings(
    state: DefaultThemeManagerState,
    base: UiSettings,
    paneToSection: Map<String, String>,
): UiSettings {
    // Appearance is owned by [base] (the chrome's live `ui` state) so the
    // resolver always reads from the same place [applyUi] paints from.
    // [state.appearance] now mirrors [base.appearance] (see ShellState.applyUi),
    // but [base] remains the canonical read-from source — it's what every
    // other caller of [applyUiSettings] passes too.
    val snapshot = state.toSnapshot()
    val bundle = resolveActiveTheme(
        snapshot = snapshot,
        appearance = base.appearance,
        systemIsDark = isDarkActive(base.appearance),
        paneToSection = paneToSection,
    )
    return base.copy(
        theme = bundle.theme,
        paneSchemes = bundle.paneSchemes,
    )
}
