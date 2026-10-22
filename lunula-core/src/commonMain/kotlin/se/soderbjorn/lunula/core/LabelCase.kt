/* LabelCase.kt
 * How the shell's chrome labels are cased: small UPPERCASE with wide tracking,
 * or sentence case at the chrome's reading size.
 *
 * A USER setting, not a theme property — it sits beside the other style
 * settings in [AppearanceShape] and is remembered per appearance mode (see
 * [ModeStyles]). Split out of the original single Calm switch (epic LNA-16); see
 * [Looks.Calm].
 *
 * The labels it covers are the chrome's own headings: window titles, the
 * sidebar's tab headers and the app name at the top of the sidebar. Sizes stay
 * with the font settings: under [Sentence] the window title and the tab
 * headers default to 14px, and a size the user picked for the window title or
 * the sidebar still wins.
 *
 * Consumed on the web through the `data-dt-labels` attribute written by
 * `applyAppearance`; `lunula.css` keys the Sentence rules off it.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two label casings the toolkit ships.
 *
 *  - [Uppercase] — the default: 11px UPPERCASE window titles and tab headers
 *    with wide letter-spacing, the house style the shell has always had.
 *  - [Sentence] — sentence case with normal letter-spacing; window titles and
 *    tab headers at 14px unless the user picked a size.
 *
 * @property cssValue the token persisted in [AppearanceShape]; `data-dt-labels`
 *   is written (as `sentence`) only for [Sentence].
 * @see AppearanceShape
 * @see Looks.Calm
 */
@Serializable
enum class LabelCase(val cssValue: String) {
    /** Small uppercase labels with wide tracking. The default. */
    Uppercase("uppercase"),

    /** Sentence-case labels at reading size. */
    Sentence("sentence"),
    ;

    companion object {
        /**
         * The casing used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the user's
         * value and the app's [AppearanceShape] default are both null.
         */
        val Default: LabelCase = Uppercase

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised so it can never cost the user the rest of their
         * appearance settings.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching casing, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): LabelCase? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
