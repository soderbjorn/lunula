/* WindowLayout.kt
 * How the windows of a tab are laid out on the canvas: as separate cards with
 * a gap between them, or together on one rounded sheet, split by hairlines.
 *
 * A USER setting, not a theme property — it sits beside the other style
 * settings in [AppearanceShape] and is remembered per appearance mode (see
 * [ModeStyles]). Split out of the original single Calm switch (epic LNA-16); see
 * [Looks.Calm] for the bundle it came from.
 *
 * Consumed on the web through the `data-dt-windows` attribute written by
 * `applyAppearance`; `lunula.css` keys the Sheet rules off it.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two window layouts the toolkit ships.
 *
 *  - [Cards] — the default: every window is a rounded card resting on the
 *    canvas, with `--dt-pane-gutter` of space around it.
 *  - [Sheet] — every window of the tab sits inside one rounded sheet (taking
 *    the corner-roundness setting) with a 1px edge. Windows inside it are
 *    square, meet with no gap and are split by 1px hairlines; a maximized
 *    window fills the sheet, and the dock of minimized windows lives at its
 *    foot.
 *
 * Under [Sheet] the window gap is always zero, so the Spacing setting only
 * changes the chrome around the sheet.
 *
 * @property cssValue the token persisted in [AppearanceShape]; `data-dt-windows`
 *   is written (as `sheet`) only for [Sheet].
 * @see AppearanceShape
 * @see Looks.Calm
 */
@Serializable
enum class WindowLayout(val cssValue: String) {
    /** Separate rounded cards with a gap between them. The default. */
    Cards("cards"),

    /** One rounded sheet of hairline-split windows. */
    Sheet("sheet"),
    ;

    companion object {
        /**
         * The layout used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the user's
         * value and the app's [AppearanceShape] default are both null.
         */
        val Default: WindowLayout = Cards

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised so it can never cost the user the rest of their
         * appearance settings.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching layout, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): WindowLayout? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
