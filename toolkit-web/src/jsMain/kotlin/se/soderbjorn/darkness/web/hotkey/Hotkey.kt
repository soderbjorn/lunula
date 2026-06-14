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
     */
    fun matches(event: KeyboardEvent): Boolean {
        val evKey = event.key.let { if (it.length == 1) it.lowercase() else it }
        val target = key.let { if (it.length == 1) it.lowercase() else it }
        if (evKey != target) return false
        return event.ctrlKey == ctrl &&
            event.altKey == alt &&
            event.shiftKey == shift &&
            event.metaKey == meta
    }
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
