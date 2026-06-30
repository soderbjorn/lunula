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
    /** Cycle to the previous (non-minimized) pane. Wraps. */
    val PreviousPane: Hotkey = Hotkey(key = "ArrowLeft", ctrl = true, alt = true)

    /** Cycle to the next (non-minimized) pane. Wraps. */
    val NextPane: Hotkey = Hotkey(key = "ArrowRight", ctrl = true, alt = true)

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
}
