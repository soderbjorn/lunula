/* UiDensity.kt
 * The chrome spacing scale — how much air the toolkit's shell puts around its
 * own furniture (pane gutters, header padding, tab padding, sidebar rows).
 *
 * A user setting, not a theme property. A theme answers "what does this app
 * look like"; density answers "how do I like my windows", which is the same
 * kind of question as font size — the answer should survive a theme change, and
 * a user who wants room to breathe should not have to adopt a palette to get
 * it. It is persisted per-app alongside the font preferences (see
 * `ThemeManagerHost.uiDensity`) and applied via `applyUiDensity`.
 *
 * Deliberately three steps, not a continuous slider: these values retune eight
 * interacting layout tokens at once, and the combinations that read as
 * *designed* rather than merely *spaced* are few. A slider would offer a
 * thousand settings of which three are good.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * How much space the toolkit chrome puts around its own furniture.
 *
 * @property cssValue the token written to the `data-dt-density` attribute on
 *   the themed root; `lunula.css` re-declares the `--dt-*` spacing tokens under
 *   each value. [Compact] has no attribute — it *is* the stylesheet's baseline,
 *   so writing one would mean stating the defaults twice and letting them drift.
 * @see se.soderbjorn.lunula.web.applyUiDensity
 */
@Serializable
enum class UiDensity(val cssValue: String) {
    /**
     * The toolkit's historical spacing, and the default. Dense enough that a
     * four-pane split on a laptop is four usable panes rather than four
     * letterboxes.
     */
    Compact("compact"),

    /**
     * Roomier: a 52px window title strip, wider pane gutters, taller sidebar
     * rows. The spacing of the Framna concept — chrome that reads as a set of
     * cards on a plane rather than a packed toolset.
     */
    Comfortable("comfortable"),

    /**
     * One further step out, for large displays and presentation use, where the
     * constraint is legibility at distance rather than panes per screen.
     */
    Spacious("spacious"),
    ;

    companion object {
        /**
         * Parses a persisted [cssValue] or enum name back to a [UiDensity],
         * tolerating anything unrecognised.
         *
         * Apps persist this as a plain string in their own settings blobs, so
         * the parse has to survive a value written by a newer build, a hand-
         * edited config, or a key that was never set.
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching density, or `null` when [raw] names none.
         */
        fun fromRaw(raw: String?): UiDensity? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
