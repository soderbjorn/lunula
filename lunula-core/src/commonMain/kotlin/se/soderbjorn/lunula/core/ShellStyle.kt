/* ShellStyle.kt
 * The look shorthand: a single value naming one of the built-in presets
 * ([Looks]) — Classic or Calm — where a whole set of style settings is meant.
 *
 * It began as the one switch between the established "Classic" chrome and a
 * calmer composition (epic LNA-16, first called "Clean"). That composition has
 * since been split into separate style settings (chrome, windows, active
 * window, labels, plus Soft selection and surfaces), and the presets that
 * bundle them are [Looks]. What is left here is the shorthand, kept because it
 * is what a deployment's brand manifest (`defaultStyle`), a page's `?style=`
 * and settings saved before the split say: [AppearanceShape.lookLayer] expands
 * it into the preset's values beneath any setting named beside it.
 *
 * New code writes the individual settings; nothing paints from this value
 * directly.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The presets the shorthand can name.
 *
 *  - [Classic] — [Looks.Classic]: the shell exactly as it has always been drawn.
 *  - [Calm] — [Looks.Calm]: one ground, one rounded sheet of hairline-split
 *    windows, the active window shown by tone, soft selection and surfaces,
 *    sentence-case labels.
 *
 * @property cssValue the token persisted in [AppearanceShape].
 * @see Looks.forShellStyle
 * @see AppearanceShape.lookLayer
 */
@Serializable
enum class ShellStyle(val cssValue: String) {
    /** The established shell. The default. */
    Classic("classic"),

    /** The calmer shell (epic LNA-16). Read from the old spelling `clean` too. */
    Calm("calm"),
    ;

    companion object {
        /**
         * The style used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the host's
         * value and the app's [AppearanceShape] default are both null, so
         * changing it reaches exactly the users who never expressed an opinion.
         */
        val Default: ShellStyle = Classic

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised (a newer build, a hand-edited file, a typo) so it can
         * never cost the user the rest of their appearance settings.
         *
         * `clean`, the preset's name before it was renamed Calm, is still read
         * as [Calm]: it is what settings saved before the rename, existing brand
         * manifests and old `?style=clean` links say.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): ShellStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (v == "clean") return Calm
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
