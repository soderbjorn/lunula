/**
 * Glue between [DefaultThemeManagerState] and the persistence-friendly
 * [ThemeSnapshot] codec.
 *
 * Apps that use [DefaultThemeManagerState] as their in-memory backing
 * store typically persist via [ThemeSnapshot]'s [ThemeSnapshot.encodeAsJsonObject]
 * or [ThemeSnapshot.encodeAsStringMap]. This module shortens the
 * boilerplate of "copy every state field into a snapshot" / "copy every
 * snapshot field back into the state" to two extension functions, so the
 * onChange callback wired into [DefaultThemeManagerHost] can be a single
 * line:
 *
 * ```kotlin
 * val host = DefaultThemeManagerHost(state) {
 *     storage.write(state.toSnapshot().encodeAsJsonObject().toString())
 * }
 * // on launch:
 * state.applySnapshot(ThemeSnapshot.fromJsonString(storage.read().orEmpty()))
 * ```
 *
 * [applySnapshot] mutates the state in place (clear+addAll on the maps and
 * sets) so the same [DefaultThemeManagerState] reference can stay live
 * across reloads — anything that still holds a reference to one of its
 * collections (e.g. an open Theme Manager render) sees the new contents
 * without having to be rebound.
 *
 * @see DefaultThemeManagerState
 * @see ThemeSnapshot
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.ThemeSnapshot

/**
 * Read every persisted field from this [DefaultThemeManagerState] into a
 * fresh [ThemeSnapshot].
 *
 * Called by the host's `onChange` callback (and on shutdown) so the app
 * can hand the snapshot to its storage backend (localStorage, server,
 * filesystem, …).
 *
 * @receiver the in-memory state to snapshot.
 * @return a [ThemeSnapshot] capturing the current slot bindings, custom
 *   themes/schemes, favorites, and per-app font / titlebar preferences.
 *   The state's [DefaultThemeManagerState.mainSchemeName] is intentionally
 *   *not* in the snapshot — it's a derived view of the active slot, not a
 *   persisted field, and gets re-derived on every load.
 *
 * @see applySnapshot
 * @see ThemeSnapshot.encodeAsJsonObject
 */
fun DefaultThemeManagerState.toSnapshot(): ThemeSnapshot = ThemeSnapshot(
    lightThemeName = lightThemeName,
    darkThemeName = darkThemeName,
    customThemes = customThemes.toMap(),
    customSchemes = customSchemes.toMap(),
    favoriteThemes = favoriteThemes.toList(),
    favoriteSchemes = favoriteSchemes.toList(),
    monoFontFamily = monoFontFamily,
    monoFontSizePx = monoFontSizePx,
    proportionalFontFamily = proportionalFontFamily,
    proportionalFontSizePx = proportionalFontSizePx,
    sidebarFontFamily = sidebarFontFamily,
    sidebarFontSizePx = sidebarFontSizePx,
    tabbarFontFamily = tabbarFontFamily,
    tabbarFontSizePx = tabbarFontSizePx,
    desktopNotifications = desktopNotifications,
    useCustomTitleBar = useCustomTitleBar,
)

/**
 * Overwrite the state's persisted fields with [snapshot].
 *
 * Mutating in place (clear+addAll on the maps and sets) keeps every
 * existing reference to the state's collections valid, which matters when
 * the Theme Manager is open at the moment storage round-trips: the
 * already-rendered editor reads through the same map references and picks
 * up the new contents on its next render.
 *
 * Unknown / missing fields in [snapshot] reset the corresponding state
 * field to its default (null / empty / `false`). No backwards-compatible
 * legacy reader is consulted — see the toolkit's "no theme format compat"
 * policy.
 *
 * @receiver the state to update.
 * @param snapshot the parsed snapshot to apply.
 *
 * @see toSnapshot
 * @see ThemeSnapshot.fromJsonString
 */
fun DefaultThemeManagerState.applySnapshot(snapshot: ThemeSnapshot) {
    lightThemeName = snapshot.lightThemeName
    darkThemeName = snapshot.darkThemeName
    customThemes.clear()
    customThemes.putAll(snapshot.customThemes)
    customSchemes.clear()
    customSchemes.putAll(snapshot.customSchemes)
    favoriteThemes.clear()
    favoriteThemes.addAll(snapshot.favoriteThemes)
    favoriteSchemes.clear()
    favoriteSchemes.addAll(snapshot.favoriteSchemes)
    monoFontFamily = snapshot.monoFontFamily
    monoFontSizePx = snapshot.monoFontSizePx
    proportionalFontFamily = snapshot.proportionalFontFamily
    proportionalFontSizePx = snapshot.proportionalFontSizePx
    sidebarFontFamily = snapshot.sidebarFontFamily
    sidebarFontSizePx = snapshot.sidebarFontSizePx
    tabbarFontFamily = snapshot.tabbarFontFamily
    tabbarFontSizePx = snapshot.tabbarFontSizePx
    desktopNotifications = snapshot.desktopNotifications
    useCustomTitleBar = snapshot.useCustomTitleBar
}
