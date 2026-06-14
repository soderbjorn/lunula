/**
 * Universal section vocabulary shared by every Darkness app.
 *
 * A [Theme] assigns a colour scheme to each universal section. Apps render
 * their concrete panes from the section assigned to that pane via the app's
 * `paneToSection` map (declared in app code, not here).
 *
 * Why a fixed set rather than a free-form string-keyed map: the value of a
 * shared vocabulary is that themes designed in one app look right in
 * another. Free-form section names re-fragment the space — a theme that
 * defines `myCustomTier = "Foo"` is dead weight in any other app. The fixed
 * set is the cross-app contract; pinning specific app-local panes to a
 * particular scheme (if ever needed) is an app-side concern that lives
 * outside the toolkit's persistence model.
 *
 * Adding a new section is a toolkit version bump, deliberately.
 */
package se.soderbjorn.darkness.core

/**
 * Section name constants. Use these whenever building or interpreting
 * [Theme.sections] or app `paneToSection` maps so the toolkit and apps
 * agree on the spelling.
 *
 * Roles:
 *  - [Main] — dominant content surface (terminal in termtastic, editor in notegrow).
 *  - [Sidebar] — primary navigation column (session list, note tree).
 *  - [Tabs] — top tab strip.
 *  - [Chrome] — window chrome / titlebar (inactive panes).
 *  - [ActiveChrome] — window chrome / titlebar of the **focused** pane (bg + text
 *    flip only; border/shadow/icons stay sourced from [Chrome]). Optional —
 *    when unassigned, the focused pane renders identically to inactive panes
 *    and only the existing outline ring distinguishes it.
 *  - [Active] — focus rings, active-pane indicators.
 *  - [Windows] — pane frames, split borders.
 *  - [Auxiliary] — secondary content panels (git, diff, file browser, outline, starred…).
 *  - [BottomBar] — status / footer.
 *  - [Accent] — global accent / highlight colour (command palette border, primary
 *    button background, focus ring). Drives `--t-accent-*` across the app when
 *    set; falls back to the main scheme's derived accent when not.
 */
object Sections {
    const val Main = "main"
    const val Sidebar = "sidebar"
    const val Tabs = "tabs"
    const val Chrome = "chrome"
    const val ActiveChrome = "activeChrome"
    const val Active = "active"
    const val Windows = "windows"
    const val Auxiliary = "auxiliary"
    const val BottomBar = "bottomBar"
    const val Accent = "accent"

    /**
     * Every universal section name in canonical UI display order
     * (most-prominent first). Used by the Theme Editor to render the
     * sections picker rows.
     */
    val all: List<String> = listOf(
        Main, Sidebar, Tabs, Chrome, ActiveChrome, Active, Windows, Auxiliary, BottomBar, Accent,
    )
}
