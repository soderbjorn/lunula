/* ShellStyle.kt
 * Which overall shell composition the toolkit draws: the established
 * "Classic" chrome, or the newer "Clean" one (epic LNA-16).
 *
 * A USER setting, not a theme property — it sits beside corner roundness,
 * spacing, selection and surface depth in [AppearanceShape] and is persisted
 * per-app under [PersistKeys.APPEARANCE_SHAPE]. Like the other shape settings
 * it answers "how do I like my windows" rather than "which colours", so a user
 * who picks one shell style keeps it through every palette they try.
 *
 * At this stage the value is only modelled, persisted and resolved; nothing
 * renders differently yet. The web shell resolves it in `applyHostFontVars`
 * (user → app default → [ShellStyle.Default]) so the later Clean rendering
 * work can key off it. [ShellStyle.Classic] is the default and its rendering
 * must never change because this setting exists.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two shell compositions the toolkit knows about.
 *
 *  - [Classic] — the default: the shell exactly as it has always been drawn.
 *  - [Clean] — a quieter composition being built under epic LNA-16. Until its
 *    rendering lands, choosing it is persisted and resolved but has no visual
 *    effect.
 *
 * @property cssValue the token persisted in [AppearanceShape] (and, once the
 *   Clean rendering exists, the value the web shell stamps on the document).
 * @see AppearanceShape
 * @see NavigationMode
 */
@Serializable
enum class ShellStyle(val cssValue: String) {
    /** The established shell chrome. The default. */
    Classic("classic"),

    /** The newer, quieter shell composition (epic LNA-16). */
    Clean("clean"),
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
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): ShellStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
