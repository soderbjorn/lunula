/* NavigationMode.kt
 * How the user moves between the shell's tabs: through the sidebar, through a
 * horizontal tab strip, or through both at once.
 *
 * A USER setting, not a theme property — it sits beside the other shell
 * preferences in [AppearanceShape] and is persisted per-app under
 * [PersistKeys.APPEARANCE_SHAPE]. It belongs to the "Clean" shell work (epic
 * LNA-16): at this stage the value is only modelled, persisted and resolved in
 * the web shell's `applyHostFontVars` (user → app default →
 * [NavigationMode.Default]); nothing renders differently yet.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The navigation surfaces the shell can offer.
 *
 *  - [Sidebar] — the default: tabs are listed in the sidebar only, as the
 *    shell has always done.
 *  - [TabStrip] — tabs are shown as a horizontal strip and the sidebar is
 *    hidden.
 *  - [Both] — the sidebar and the tab strip are shown together.
 *
 * @property cssValue the token persisted in [AppearanceShape].
 * @see AppearanceShape
 * @see ShellStyle
 */
@Serializable
enum class NavigationMode(val cssValue: String) {
    /** Navigation through the sidebar only. The default. */
    Sidebar("sidebar"),

    /** Navigation through a horizontal tab strip only. */
    TabStrip("tabstrip"),

    /** Sidebar and tab strip together. */
    Both("both"),
    ;

    /**
     * Whether this mode shows the sidebar.
     *
     * For the shell's layout code, so it can ask the question it actually has
     * ("do I draw the sidebar?") instead of enumerating modes at each call site.
     *
     * @return `true` for [Sidebar] and [Both].
     * @see showsTabStrip
     */
    val showsSidebar: Boolean
        get() = this == Sidebar || this == Both

    /**
     * Whether this mode shows the horizontal tab strip.
     *
     * The counterpart of [showsSidebar], for the same layout callers.
     *
     * @return `true` for [TabStrip] and [Both].
     * @see showsSidebar
     */
    val showsTabStrip: Boolean
        get() = this == TabStrip || this == Both

    companion object {
        /**
         * The mode used when neither the user nor the app has chosen one.
         *
         * Read as a FALLBACK, never written: consulted only where the host's
         * value and the app's [AppearanceShape] default are both null, so
         * changing it reaches exactly the users who never expressed an opinion.
         */
        val Default: NavigationMode = Sidebar

        /**
         * Parses a persisted [cssValue] or enum name, tolerating anything
         * unrecognised (a newer build, a hand-edited file, a typo) so it can
         * never cost the user the rest of their appearance settings.
         *
         * Besides the [cssValue] (`tabstrip`) and the enum name, the hyphenated
         * and underscored spellings `tab-strip` / `tab_strip` are accepted,
         * since those are what a person hand-editing a brand manifest is
         * likely to type.
         *
         * Called by [AppearanceShape.fromJson].
         *
         * @param raw the stored value, in any accepted spelling; may be null/blank.
         * @return the matching mode, or `null` when [raw] names none.
         * @see SurfaceStyle.fromRaw
         */
        fun fromRaw(raw: String?): NavigationMode? {
            val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (v == "tab-strip" || v == "tab_strip") return TabStrip
            return entries.firstOrNull { it.cssValue == v || it.name.lowercase() == v }
        }
    }
}
