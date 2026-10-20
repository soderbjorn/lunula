/* NavigationMode.kt
 * How the user moves between the shell's tabs: through the sidebar, through a
 * horizontal tab strip, or through both at once.
 *
 * A USER setting, not a theme property — it sits beside the other shell
 * preferences in [AppearanceShape] and is persisted per-app under
 * [PersistKeys.APPEARANCE_SHAPE]. It belongs to the "Clean" shell work (epic
 * LNA-16). The web shell resolves it in `applyHostFontVars` (user → app
 * default → [NavigationMode.Default]), lets an app's hard opt-outs
 * (`AppShellSpec.showSidebar` / `showTabStrip = false`) override it, and
 * shows or hides the sidebar and tab strip at runtime to match (LNA-22).
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.Serializable

/**
 * The navigation surfaces the shell can offer.
 *
 *  - [Sidebar] — tabs are listed in the sidebar only; no tab strip.
 *  - [TabStrip] — tabs are shown as a horizontal strip and the sidebar is
 *    hidden.
 *  - [Both] — the default: the sidebar and the tab strip are shown
 *    together, which is what the shell has always drawn.
 *
 * @property cssValue the token persisted in [AppearanceShape].
 * @see AppearanceShape
 * @see ShellStyle
 */
@Serializable
enum class NavigationMode(val cssValue: String) {
    /** Navigation through the sidebar only. */
    Sidebar("sidebar"),

    /** Navigation through a horizontal tab strip only. */
    TabStrip("tabstrip"),

    /** Sidebar and tab strip together. The default. */
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
         *
         * [Both], because that is what the shell drew before this setting
         * existed (`AppShellSpec.showSidebar` and `showTabStrip` both default
         * to `true`). Any other value would change every app's chrome on
         * upgrade for users who never touched the setting, which the Clean
         * epic (LNA-16) rules out: nothing changes for anyone who doesn't
         * opt in. An app that wants a different default for its users says
         * so through its `AppearanceShape` default instead.
         */
        val Default: NavigationMode = Both

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
