/* FocusStyle.kt
 * How the shell marks the active (focused) window: with the accent on its
 * title bar, or by tone — the active window bright, the others a quiet step
 * darker, the active one lifted by a hairline ring and a soft shadow.
 *
 * A USER setting, not a theme property — it sits beside the other style
 * settings in [AppearanceShape] and is remembered per appearance mode (see
 * [ModeStyles]). Split out of the original single Calm switch (epic LNA-16); see
 * [Looks.Calm].
 *
 * Consumed on the web through the `data-dt-focus` attribute written by
 * `applyAppearance`; `lunula.css` keys the Tone rules off it. Under Tone the
 * toolkit also publishes the window's current fill as `--dt-window-bg` on each
 * `.dt-pane`, so an app's pane content can paint
 * `background: var(--dt-window-bg, <its own colour>)` and dim with its window
 * without knowing which setting is in effect.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two focus treatments the toolkit ships.
 *
 *  - [Accent] — the default: the focused window's title bar wears the
 *    selection accent (a tint, a solid fill or a soft wash, per
 *    [SelectionStyle]), with Tint's accent outline and glow.
 *  - [Tone] — the focused window is painted `--t-window-active` and every
 *    other window `--t-window-idle`; the focused one also gets a 1px inner
 *    `--t-window-ring` and a soft neutral lift. No accent band and no glow.
 *    Idle window titles are dimmed. A lone or maximized window gets neither
 *    ring nor lift, having nothing to stand out from.
 *
 * Under [Tone] the selection setting only marks the active tab and sidebar
 * row; window headers no longer wear it.
 *
 * @property cssValue the token persisted in [AppearanceShape]; `data-dt-focus`
 *   is written (as `tone`) only for [Tone].
 * @see AppearanceShape
 * @see Theme.effectiveWindowIdle
 * @see Looks.Calm
 */
@Serializable
enum class FocusStyle(val cssValue: String) {
    /** The selection accent on the focused window's title bar. The default. */
    Accent("accent"),

    /** Active window by tone, ring and lift; idle windows a quiet step back. */
    Tone("tone"),
    ;

    companion object {
        /**
         * The style used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the user's
         * value and the app's [AppearanceShape] default are both null.
         */
        val Default: FocusStyle = Accent

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised so it can never cost the user the rest of their
         * appearance settings.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): FocusStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
