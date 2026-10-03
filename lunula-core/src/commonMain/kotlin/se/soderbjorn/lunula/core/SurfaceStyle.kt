/* SurfaceStyle.kt
 * Whether the shell's surfaces carry depth (shadows, sheen, glow, a coloured
 * wash behind the panes) or stay flat.
 *
 * A USER setting, not a theme property — it sits beside corner roundness,
 * spacing and selection in [AppearanceShape] and is persisted per-app under
 * [PersistKeys.APPEARANCE_SHAPE]. Depth is drawn entirely from colour tokens
 * every theme already resolves (accent, surfaces), so every palette works in
 * either style, and a user who prefers flat chrome keeps it through every
 * theme they try.
 *
 * Consumed on the web through the `data-dt-surface` attribute written by
 * `applySurfaceStyle`; `lunula.css` keys the depth rules off its absence.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two surface treatments the toolkit ships.
 *
 *  - [Depth] — the default. Panes float above the window on three soft
 *    shadows (contact, near, far) under a 1px top highlight; pane headers
 *    cast a small shadow onto their content; the sidebar edge is an inner
 *    shadow rather than a line. Accent fills (focused pane header, active
 *    tab, active sidebar row, active topbar button) get a vertical sheen and a
 *    coloured glow, the focused pane a faint accent halo, and the window
 *    background a soft accent wash from the top left with a neighbouring hue
 *    from the bottom right. No translucency or blur — scrolling stays cheap.
 *  - [Flat] — the toolkit's look before depth existed, pixel for pixel: flat
 *    fills, hairline borders, no shadows beyond the floating panes' own.
 *
 * @property cssValue the token persisted in [AppearanceShape]; `data-dt-surface`
 *   is written (as `flat`) only for [Flat].
 * @see AppearanceShape
 */
@Serializable
enum class SurfaceStyle(val cssValue: String) {
    /** Shadows, sheen, glow and ambient wash. The default. */
    Depth("depth"),

    /** No depth effects — the original flat look. */
    Flat("flat"),
    ;

    companion object {
        /**
         * The style used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the host's
         * value and the app's [AppearanceShape] default are both null, so
         * changing it reaches exactly the users who never expressed an opinion.
         */
        val Default: SurfaceStyle = Depth

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised (a newer build, a hand-edited file, a typo) so it can
         * never cost the user the rest of their appearance settings.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         */
        fun fromRaw(raw: String?): SurfaceStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
