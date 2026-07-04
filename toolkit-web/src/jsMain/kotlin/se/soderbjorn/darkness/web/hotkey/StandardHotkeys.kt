/*
 * StandardHotkeys.kt (jsMain)
 *
 * Canonical chord constants for the toolkit's built-in navigation
 * shortcuts. Defining them in one place lets apps refer to a chord by
 * name (e.g. to override or unbind it) and lets us tweak the default
 * mapping without rippling through the codebase.
 *
 * Modifier-flag mapping on macOS:
 *   Ctrl  → Control,  Alt → Option,  Meta → Cmd
 * On Windows/Linux:
 *   Ctrl  → Ctrl,     Alt → Alt,     Meta → Win/Super
 *
 * The toolkit's defaults intentionally use Ctrl+Alt rather than
 * Meta+Alt: notegrow already uses Cmd+Opt+Left/Right for zoom-history
 * navigation (see notegrow's MainScreen), so we want a disjoint
 * modifier set for window-level chrome navigation. The chosen chords
 * also don't collide with any default macOS or web text-editing
 * shortcut (Opt+arrow word-jump is *without* Ctrl).
 */
package se.soderbjorn.darkness.web.hotkey

/**
 * Built-in chord constants used by [LayoutRenderer] and [renderTabBar]
 * out of the box. Apps can read these (to label menus / surface in a
 * cheat-sheet) or unbind them via [HotkeyRegistry.unregister].
 */
object StandardHotkeys {
    /**
     * Spatially focus the pane to the **left** of the focused one.
     *
     * Despite the historical name, this is no longer a wrap-around cycle:
     * [LayoutRenderer] resolves it to the nearest pane whose geometry sits
     * left of the current pane, and does nothing when there is none (see
     * `focusPaneInDirection`). Kept named `PreviousPane` so existing app
     * cheat-sheets that reference it keep compiling.
     */
    val PreviousPane: Hotkey = Hotkey(key = "ArrowLeft", ctrl = true, alt = true)

    /**
     * Spatially focus the pane to the **right** of the focused one.
     *
     * @see PreviousPane for the spatial (non-wrapping) semantics.
     */
    val NextPane: Hotkey = Hotkey(key = "ArrowRight", ctrl = true, alt = true)

    /** Spatially focus the pane **above** the focused one (no wrap). */
    val FocusPaneUp: Hotkey = Hotkey(key = "ArrowUp", ctrl = true, alt = true)

    /** Spatially focus the pane **below** the focused one (no wrap). */
    val FocusPaneDown: Hotkey = Hotkey(key = "ArrowDown", ctrl = true, alt = true)

    /**
     * Expand the focused pane one state step: minimized (docked) →
     * normal → maximized. Opt+Cmd+Up on macOS (Alt+Meta elsewhere) —
     * deliberately *not* Ctrl+Alt+Arrow, which the four focus chords
     * above own, and not colliding with notegrow's Cmd+Opt+Left/Right
     * zoom-history navigation (different arrow axis).
     *
     * @see CollapsePane for the opposite direction.
     */
    val ExpandPane: Hotkey = Hotkey(key = "ArrowUp", alt = true, meta = true)

    /**
     * Collapse the focused pane one state step: maximized → normal →
     * minimized (docked). Opt+Cmd+Down on macOS (Alt+Meta elsewhere).
     *
     * @see ExpandPane for the opposite direction.
     */
    val CollapsePane: Hotkey = Hotkey(key = "ArrowDown", alt = true, meta = true)

    /** Cycle to the previous (non-hidden) tab. Wraps. */
    val PreviousTab: Hotkey = Hotkey(key = "ArrowLeft", ctrl = true, alt = true, shift = true)

    /** Cycle to the next (non-hidden) tab. Wraps. */
    val NextTab: Hotkey = Hotkey(key = "ArrowRight", ctrl = true, alt = true, shift = true)

    /**
     * Positional "jump to tab N" chord for the 1-based tab [position]
     * (`1`..`9`).
     *
     * The modifier follows the OS-native convention used by Safari /
     * Chrome / iTerm: Cmd on macOS, Ctrl on Windows/Linux (resolved at
     * call time via [isMacPlatform]). The chord binds the literal digit
     * key; the "9 = last tab" rule lives in the resolver
     * (`resolveTabSwitchIndex` in `TabBar.kt`), not here.
     *
     * Bound for the active tab strip by `renderTabBar`'s
     * `installTabNavigationHotkeys`, but only in the Electron desktop
     * shell — a real browser reserves this chord for switching its own
     * tabs (see [isElectronPlatform]). Apps may read these (e.g. to label
     * a cheat-sheet) or [HotkeyRegistry.unregister] them.
     *
     * @param position 1-based key digit, `1`..`9`.
     * @return the chord for that digit on the current platform.
     */
    fun tabSwitchHotkey(position: Int): Hotkey = Hotkey(
        key = position.toString(),
        meta = isMacPlatform(),
        ctrl = !isMacPlatform(),
    )

    /**
     * Browser-safe variant of [tabSwitchHotkey] for the *web* shell: the
     * same positional "jump to tab N" behaviour but with an extra Alt/Option
     * modifier so it doesn't collide with the chord a real browser reserves
     * for switching its own tabs (plain Cmd/Ctrl+`<digit>`).
     *
     * On macOS this is Cmd+Opt+`<digit>` (`meta` + `alt`); on Windows/Linux
     * it's Ctrl+Alt+`<digit>`. Bound by `renderTabBar`'s
     * `installTabNumberHotkeys` when running outside Electron, so browser
     * users get the same tab-number switching Electron users have via
     * [tabSwitchHotkey]. As with [tabSwitchHotkey], the "9 = last tab" rule
     * lives in the resolver (`resolveTabSwitchIndex`), not here.
     *
     * @param position 1-based key digit, `1`..`9`.
     * @return the chord for that digit on the current platform.
     * @see tabSwitchHotkey for the Electron (no Alt) variant.
     */
    fun webTabSwitchHotkey(position: Int): Hotkey = Hotkey(
        key = position.toString(),
        alt = true,
        meta = isMacPlatform(),
        ctrl = !isMacPlatform(),
    )
}
