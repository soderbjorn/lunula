/*
 * Hotkey.kt (jsMain)
 *
 * Pure data model for a keyboard chord. Used by [HotkeyRegistry] as both
 * the dedup key (each unique chord binds to at most one action) and the
 * matcher run against incoming `KeyboardEvent`s.
 *
 * Chord names use the platform-neutral DOM modifier vocabulary
 * (`ctrl/alt/shift/meta`). On macOS, `meta` is Cmd and `alt` is Option;
 * on Windows/Linux, `meta` is Windows/Super and `alt` is Alt. Apps that
 * want a "Cmd-on-Mac, Ctrl-elsewhere" semantic should resolve the
 * platform at hotkey-creation time and pick the right modifier flag.
 *
 * commonMain rules don't apply here (this is jsMain only) — the matcher
 * is allowed to use `KeyboardEvent`.
 */
package se.soderbjorn.darkness.web.hotkey

import org.w3c.dom.events.KeyboardEvent

/**
 * One keyboard chord — `key` plus a fully-specified modifier mask.
 *
 * The modifier flags are exact-match: a chord with `ctrl = true, alt = true`
 * fires only when both are held *and no other modifier is held*. That keeps
 * `Ctrl+Alt+Right` (pane next) from also firing on `Ctrl+Shift+Alt+Right`
 * (tab next). Use [matches] to test an event.
 *
 * @property key the value of `KeyboardEvent.key` to match (case-sensitive).
 *   For arrow keys this is `"ArrowLeft"`, `"ArrowRight"`, `"ArrowUp"`,
 *   `"ArrowDown"`. For letter keys, prefer the lowercase form
 *   (`"a"`, `"b"`); the matcher lowercases the incoming event's key so
 *   Shift-modified letters still match.
 * @property ctrl  Control must be held.
 * @property alt   Alt/Option must be held.
 * @property shift Shift must be held.
 * @property meta  Meta/Cmd/Windows must be held.
 */
data class Hotkey(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    /**
     * Returns `true` iff [event] exactly matches this chord — same key
     * (case-insensitive for letter keys) and same modifier mask.
     *
     * **Alt/Option composition fallback:** on macOS (and some non-US
     * layouts elsewhere), holding Option composes the letter into a
     * different character before it reaches `KeyboardEvent.key` — ⌥O
     * reports `"ø"` (or `"œ"` on Swedish layouts), ⌥R reports `"®"`, and
     * so on, even with Control also held. A chord like `⌃⌥O` registered
     * as `key = "o"` would therefore never match on those setups. So when
     * Alt is held and the plain `key` comparison fails, single
     * letter/digit chords additionally match on the *physical* key via
     * `KeyboardEvent.code` (`"KeyO"`, `"Digit1"`, …), which is unaffected
     * by composition. The fallback is gated on `event.altKey` so plain
     * and Ctrl/Cmd-only chords keep pure layout-relative (`key`-based)
     * semantics — composition only ever comes from Alt.
     */
    fun matches(event: KeyboardEvent): Boolean {
        val evKey = event.key.let { if (it.length == 1) it.lowercase() else it }
        val target = key.let { if (it.length == 1) it.lowercase() else it }
        val keyMatches = evKey == target ||
            (event.altKey && physicalCodeFor(target) != null && physicalCodeFor(target) == event.code)
        if (!keyMatches) return false
        return event.ctrlKey == ctrl &&
            event.altKey == alt &&
            event.shiftKey == shift &&
            event.metaKey == meta
    }
}

/**
 * Maps a single lowercase ASCII letter or digit to its physical
 * `KeyboardEvent.code` value (`"a"` → `"KeyA"`, `"1"` → `"Digit1"`).
 * Used by [Hotkey.matches] as the Alt-composition fallback — see its kdoc.
 *
 * @param k the normalized (lowercased) chord key.
 * @return the corresponding `code` string, or `null` when [k] is not a
 *   single ASCII letter/digit (no fallback for those chords).
 */
private fun physicalCodeFor(k: String): String? = when {
    k.length == 1 && k[0] in 'a'..'z' -> "Key" + k.uppercase()
    k.length == 1 && k[0] in '0'..'9' -> "Digit$k"
    else -> null
}

/**
 * Detect the user-agent platform once. Used by [toChordLabel] to pick
 * the right modifier glyphs / words. Mac vs. Windows/Linux is the only
 * split that matters for keyboard symbols today.
 */
private val IS_MAC: Boolean = run {
    val ua = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''") as String
    ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
}

/**
 * Detect once whether we're running inside an Electron renderer (the
 * desktop app) rather than a plain browser tab. Checks the user-agent
 * `Electron` token first, then falls back to the Node `process.versions`
 * bridge that Electron exposes to the renderer.
 */
private val IS_ELECTRON: Boolean = js(
    """
    (function() {
        try {
            var nav = (typeof navigator !== 'undefined') ? navigator : null;
            var ua = (nav && nav.userAgent) || '';
            if (ua.indexOf('Electron') !== -1) return true;
            if (typeof process !== 'undefined' && process.versions && process.versions.electron) return true;
        } catch (e) {}
        return false;
    })()
    """
) as Boolean

/**
 * `true` when running on a macOS-class platform (Mac, iPhone, iPad),
 * detected from the user-agent.
 *
 * Drives the Cmd-vs-Ctrl split for platform-aware chords — e.g. the
 * positional tab-switch keys ([StandardHotkeys.tabSwitchHotkey]) bind to
 * Cmd on macOS and Ctrl elsewhere — and mirrors the glyph choice already
 * made by [toChordLabel].
 *
 * @return `true` on macOS / iOS user-agents, `false` otherwise.
 */
fun isMacPlatform(): Boolean = IS_MAC

/**
 * `true` when running inside an Electron desktop renderer rather than a
 * plain browser tab.
 *
 * Used to gate chords that a real browser reserves for itself: the OS /
 * browser owns Cmd/Ctrl+`<digit>` for switching *browser* tabs and a
 * page `keydown` can't reliably override it, so the toolkit only arms the
 * positional tab-switch hotkeys in the desktop shell (see
 * `installTabNavigationHotkeys` in `TabBar.kt`).
 *
 * @return `true` when an Electron runtime is detected, `false` in a
 *   normal browser.
 */
fun isElectronPlatform(): Boolean = IS_ELECTRON

/**
 * Returns a platform-formatted chord representation as a `List<String>`
 * of cap labels, suitable for direct use as a [HotkeyEntry.chord].
 *
 * macOS uses Unicode glyphs (⌃ ⌥ ⇧ ⌘) so the labels read as native
 * keyboard symbols; Windows/Linux spell out the modifier words
 * (`Ctrl` / `Alt` / `Shift` / `Win`) since those platforms don't share
 * macOS's glyph conventions. Arrow keys and Enter are also normalised
 * to glyphs (← → ↑ ↓ ⏎) since they're cross-platform conventional.
 *
 * Modifier order on macOS is the system convention: Ctrl, Opt, Shift,
 * Cmd. Windows/Linux follows the same order with words.
 *
 * Pure helper — toolkit code does not consume this. Apps call it when
 * assembling [HotkeysModalSpec] entries so they don't have to hand-write
 * the same modifier labels for every chord.
 *
 * @return ordered list of cap labels, modifiers first, key last.
 */
fun Hotkey.toChordLabel(): List<String> {
    val out = mutableListOf<String>()
    if (IS_MAC) {
        if (ctrl) out += "⌃"
        if (alt) out += "⌥"
        if (shift) out += "⇧"
        if (meta) out += "⌘"
    } else {
        if (ctrl) out += "Ctrl"
        if (alt) out += "Alt"
        if (shift) out += "Shift"
        if (meta) out += "Win"
    }
    out += when (key) {
        "ArrowLeft" -> "←"
        "ArrowRight" -> "→"
        "ArrowUp" -> "↑"
        "ArrowDown" -> "↓"
        "Enter" -> "⏎"
        " " -> "Space"
        else -> if (key.length == 1) key.uppercase() else key
    }
    return out
}
