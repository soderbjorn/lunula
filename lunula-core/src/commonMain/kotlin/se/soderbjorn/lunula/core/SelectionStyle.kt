/* SelectionStyle.kt
 * How the shell paints "this is the selected thing".
 *
 * A USER setting, not a theme property — it sits beside corner roundness and
 * spacing in [AppearanceShape], and is persisted per-app under
 * [PersistKeys.APPEARANCE_SHAPE].
 *
 * It started life on [Theme] and was moved, which is worth recording because
 * the first placement is the intuitive one: the Framna concept needs Fill, so
 * Fill looks like part of Framna. But that reasoning gets the ownership
 * backwards. A theme answers "which colours"; this answers "how is selection
 * expressed", and the two are independent — every one of the 74 palettes is
 * legible in either language, because Fill reads its foreground from the `…On`
 * tokens that every theme now resolves. Binding it to the theme would have
 * meant a user who prefers filled selection loses it the moment they try a
 * different palette, which is the same mistake as making font size a property
 * of the typeface.
 *
 * A deployment can still name its own default without owning a theme — see
 * `defaultSelectionStyle` in a brand manifest, which seeds the setting beneath
 * the user's own choice and above the toolkit's [SelectionStyle.Default].
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The two selection languages the toolkit ships.
 *
 * These are not two intensities of one idea — they put the accent on opposite
 * sides of the figure/ground relationship, which is why this is an enum and not
 * a strength dial:
 *
 *  - [Tint] — the historical treatment. The accent is a 15% wash under the item
 *    plus a 1px ring around it, and the label keeps its ordinary text colour.
 *    The accent sits *behind* the content.
 *  - [Fill] — the accent becomes a solid field and the label flips to that
 *    field's declared foreground ([Theme.accentOn] / [Theme.chromeAccentOn]).
 *    The accent *is* the surface, and the content sits on it. This is
 *    [Default] — see the companion for why the toolkit changed its mind.
 *
 * The choice lands in three places at once — the focused pane header, the
 * active tab, and the active sidebar row — because they are one idea wearing
 * three costumes; letting them disagree is how one app starts reading as two.
 * [Fill] additionally suppresses the resting pane outline and the focus glow:
 * with a solid header band doing that work, a ring and a halo repeating it are
 * a third and fourth voice, and the panes separate on the canvas gap alone.
 *
 * Consumed on the web through the `data-dt-selection` attribute written by
 * `applySelectionStyle`; `lunula.css` keys the selection and outline/glow rules
 * off it.
 *
 * @property cssValue the token written to `data-dt-selection`.
 * @see AppearanceShape
 * @see Theme.accentOn
 */
@Serializable
enum class SelectionStyle(val cssValue: String) {
    /** Accent as a 15% wash + ring, ordinary label colour. */
    Tint("tint"),

    /** Accent as a solid field, label in the fill's `…On` foreground. The default. */
    Fill("fill"),
    ;

    companion object {
        /**
         * The style used when neither the user nor the app has chosen one.
         *
         * [Tint] held this position first, because it was the only treatment
         * that existed and every palette had already been drawn against it.
         * [Fill] takes it now on the strength of what the setting is actually
         * for: selection has to answer "where am I" at a glance, and a 15%
         * wash under a row is a signal you have to look for, while a solid
         * accent field is one you cannot miss. The wash reads as hover on any
         * surface that also hovers — which is all three of them.
         *
         * Read as a FALLBACK, never written: it is consulted only where the
         * host's value and the app's [AppearanceShape] default are both null,
         * so a user who has picked either style keeps it, and an app that
         * prefers the old language says so through
         * `AppShellSpec.defaultAppearanceShape` without its users' own picks
         * being touched. Changing this constant is what reaches everyone who
         * never expressed an opinion — which is the whole reason the setting
         * is stored as a nullable rather than as a defaulted value.
         */
        val Default: SelectionStyle = Fill

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised.
         *
         * Lenient for the same reason [UiDensity.fromRaw] is: the value is a
         * plain string in a settings blob or a deployment's brand manifest, so
         * it can arrive from a newer build, a hand-edited file, or a typo — and
         * none of those should cost the user the rest of their appearance
         * settings.
         *
         * @param raw the stored value, in either spelling; may be null/blank.
         * @return the matching style, or `null` when [raw] names none.
         */
        fun fromRaw(raw: String?): SelectionStyle? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
