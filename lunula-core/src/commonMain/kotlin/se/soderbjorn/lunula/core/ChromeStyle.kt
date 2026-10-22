/* ChromeStyle.kt
 * How the shell's chrome (top bar, sidebars, bottom bar, tab strip and the
 * controls on them) is painted: in the theme's own chrome colours, or blended
 * into the window background with quiet, borderless controls.
 *
 * A USER setting, not a theme property — it sits beside corner roundness,
 * spacing, selection and surfaces in [AppearanceShape], and is remembered per
 * appearance mode (see [ModeStyles]). It was split out of the original single
 * Calm switch (epic LNA-16), which used to bundle it with the window layout, the
 * focus treatment and the label casing; [Looks.Calm] is that bundle now.
 *
 * Consumed on the web through the `data-dt-chrome` attribute written by
 * `applyAppearance`; `lunula.css` keys the Blended rules off it.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two chrome treatments the toolkit ships.
 *
 *  - [Themed] — the default: the chrome as it has always been drawn, in the
 *    theme's chrome colours (`--t-chrome-*`), with hairlines between the bars
 *    and bordered tabs and buttons.
 *  - [Blended] — one ground. The top bar, sidebars and bottom bar sit on the
 *    window background (`--t-bg`) with no hairlines between them, the theme's
 *    separate chrome colours are ignored, idle tabs are plain text, and the
 *    top-bar and window buttons are borderless ghosts that show a quiet chip
 *    on hover. The sidebar tree becomes a quiet outline.
 *
 * @property cssValue the token persisted in [AppearanceShape]; `data-dt-chrome`
 *   is written (as `blended`) only for [Blended].
 * @see AppearanceShape
 * @see Looks.Calm
 */
@Serializable
enum class ChromeStyle(val cssValue: String) {
    /** The theme's own chrome colours and bordered controls. The default. */
    Themed("themed"),

    /** Chrome on the window background, with quiet borderless controls. */
    Blended("blended"),
    ;

    companion object {
        /**
         * The style used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the user's
         * value and the app's [AppearanceShape] default are both null.
         */
        val Default: ChromeStyle = Themed

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised (a newer build, a hand-edited file, a typo) so it can
         * never cost the user the rest of their appearance settings.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): ChromeStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
