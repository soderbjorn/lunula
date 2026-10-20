/*
 * NavigationLayout.kt (jsMain)
 * ----------------------------
 * Turns the user's navigation-mode setting into the two facts the shell
 * actually draws from — "is the left sidebar there?" and "is the tab strip
 * there?" — and stamps the result on `:root` as `data-dt-nav` (LNA-22).
 *
 * Three inputs meet here, in a fixed order of precedence:
 *
 *  1. The app's hard opt-outs, [AppShellSpec.showSidebar] /
 *     [AppShellSpec.showTabStrip] `= false`. These always win: an app (or a
 *     Lunicle embed) that has no room for a surface, or no meaning for it,
 *     must never have it put back by a user preference stored somewhere else.
 *  2. The user's [NavigationMode], when they have picked one.
 *  3. The app's default, [AppShellSpec.defaultAppearanceShape]'s
 *     `navigationMode`, and finally [NavigationMode.Default].
 *
 * When (1) removes the only surface the chosen mode asks for, the layout
 * falls back to whatever the app still allows, so the user is never left
 * with no way to move between tabs and windows. Only an app that hard-
 * disables both surfaces gets neither — that is the app's own statement.
 *
 * Contains [NavigationLayout], [resolveNavigationLayout] and
 * [applyNavigationAttribute]. Consumed by `ShellState` in AppShellMount.kt.
 *
 * @see NavigationMode
 */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.NavigationMode

/**
 * The navigation surfaces the shell draws, after precedence and fallback.
 *
 * @property mode           the mode the user (or the app default) asked for,
 *   before the app's opt-outs were applied. This is what a settings control
 *   should show as selected.
 * @property showsSidebar   whether the left sidebar (and its topbar toggle
 *   button) is mounted.
 * @property showsTabStrip  whether the topbar renders the tab strip.
 * @see resolveNavigationLayout
 */
internal data class NavigationLayout(
    val mode: NavigationMode,
    val showsSidebar: Boolean,
    val showsTabStrip: Boolean,
) {
    /**
     * The token stamped as `data-dt-nav` on `:root`: `sidebar`, `tabstrip`
     * or `both` for what is actually drawn (so CSS sees the fallback, not the
     * request), or `none` when the app has disabled both surfaces.
     *
     * Called by [applyNavigationAttribute].
     *
     * @return the attribute value.
     */
    val cssValue: String
        get() = when {
            showsSidebar && showsTabStrip -> NavigationMode.Both.cssValue
            showsSidebar -> NavigationMode.Sidebar.cssValue
            showsTabStrip -> NavigationMode.TabStrip.cssValue
            else -> "none"
        }
}

/**
 * Resolves which navigation surfaces to draw.
 *
 * Pure, so the precedence and fallback rules are testable without mounting
 * a shell. Called by `ShellState.applyHostFontVars` on every host
 * application (boot, theme push, settings change).
 *
 * @param userMode           the user's own pick, or `null` when they have none.
 * @param appDefaultMode     the app's default from
 *   [AppShellSpec.defaultAppearanceShape], or `null`.
 * @param appAllowsSidebar   [AppShellSpec.showSidebar]; `false` hard-disables
 *   the sidebar whatever the mode says.
 * @param appAllowsTabStrip  [AppShellSpec.showTabStrip]; `false` hard-disables
 *   the tab strip whatever the mode says.
 * @return the resolved layout.
 * @see NavigationLayout
 */
internal fun resolveNavigationLayout(
    userMode: NavigationMode?,
    appDefaultMode: NavigationMode?,
    appAllowsSidebar: Boolean,
    appAllowsTabStrip: Boolean,
): NavigationLayout {
    val mode = userMode ?: appDefaultMode ?: NavigationMode.Default
    val sidebar = mode.showsSidebar && appAllowsSidebar
    val strip = mode.showsTabStrip && appAllowsTabStrip
    // The chosen mode's only surface was taken away by the app: show what
    // the app does allow rather than leaving no navigation at all.
    if (!sidebar && !strip) {
        return NavigationLayout(mode, appAllowsSidebar, appAllowsTabStrip)
    }
    return NavigationLayout(mode, sidebar, strip)
}

/**
 * Stamps [layout] onto [root] as `data-dt-nav`, so stylesheet rules can key
 * off the navigation actually drawn.
 *
 * Called by `ShellState.applyHostFontVars` with `document.documentElement`.
 *
 * @param root   the element to stamp, normally `:root`.
 * @param layout the resolved navigation.
 * @see NavigationLayout.cssValue
 */
internal fun applyNavigationAttribute(root: HTMLElement, layout: NavigationLayout) {
    root.setAttribute("data-dt-nav", layout.cssValue)
}
