/* ThemeCategories.kt
 * The theme picker's filter model: a small set of categories a theme is sorted
 * into by *measuring its palette*, plus the free-text search that runs
 * alongside them.
 *
 * This replaces the hand-written `group` declaration that every theme used to
 * carry. A label has to be maintained, can disagree with the colours it
 * describes, and can only ever say the one thing it was designed to say —
 * "dark" or "light" — which left the two-zone themes (dark chrome over light
 * content; two bright hues on white) with no way to describe themselves at all.
 * Reading the tokens instead means a theme is categorised correctly the moment
 * it exists, including a user's own clone, and new kinds of theme can be
 * detected by adding a predicate here rather than by re-tagging the catalog.
 *
 * The four palette categories form a **partition**: every theme lands in
 * exactly one of [ThemeCategory.Dark], [ThemeCategory.Light],
 * [ThemeCategory.DarkLightSplit] and [ThemeCategory.WhiteTwoTone], decided by
 * two tone readings (content and chrome) and, when both are light, whether the
 * theme splits its chrome from its content at all.
 */
package se.soderbjorn.lunula.core

/**
 * A filter offered by the theme picker's category dropdown.
 *
 * [All] and [Starred] are selection filters — they ask about the user, not
 * about the palette. The remaining four are palette filters and partition the
 * catalog, so switching between them shows every theme exactly once across the
 * set.
 *
 * ### Callers
 * The web/Mac Theme Manager renders these as its dropdown and filters with
 * [matchesThemeCategory]; the same entries are available to the Android and iOS
 * pickers so all three agree on what "Dark" means.
 *
 * @property label the human-readable dropdown entry.
 * @see matchesThemeCategory
 * @see filterThemesForPicker
 */
enum class ThemeCategory(val label: String) {

    /** Every theme — the default, and the only entry that filters nothing. */
    All("All"),

    /**
     * The themes the user has starred. The one category that cannot be derived
     * from a palette, so it is passed the starred-name set explicitly.
     *
     * Named for the affordance rather than the storage: the card's control is a
     * ★, and its tooltips already read "Star theme" / "Unstar theme". The
     * persisted set is still called `favorites` ([ThemeSnapshotV2.favorites],
     * [ThemeManagerHost.favoriteThemeNames]) — renaming that would break every
     * app implementing the host interface, for a word no user ever sees.
     *
     * @see ThemeSnapshotV2.favorites
     */
    Starred("Starred"),

    /**
     * Dark throughout: dark content in dark chrome. Includes the two-zone dark
     * themes ("Obsidian", "Graphite") whose chrome differs from
     * their content in *value* but is still dark — a user reaching for "Dark"
     * wants those in the results.
     */
    Dark("Dark"),

    /** Light throughout, with no chrome zone splitting it — a plain light theme. */
    Light("Light"),

    /**
     * The two zones disagree in tone: the classic dark title bar / tab bar /
     * sidebar wrapped around a light workspace ("Lunamux Split", "Nord",
     * "Solarized Split", …).
     *
     * Defined as tone *disagreement* rather than literally "dark chrome, light
     * content" so the inverse — a light shell around dark content — also lands
     * here rather than falling out of the partition. No built-in does that
     * today; a user's clone may.
     */
    DarkLightSplit("Dark/Light Split"),

    /**
     * Light in both zones, but still split into two: the shell is as white as
     * the content, and the zones are told apart by hue alone ("Harbour",
     * "Orchid", "Marmalade", "Cerise").
     *
     * The distinction from [Light] is that the theme actually declares a chrome
     * zone *and* paints something differently in it — see
     * [Theme.hasDistinctChromeZone]. A light theme that leaves the chrome tokens
     * unset, or sets them to exactly the content values, is a plain [Light].
     */
    WhiteTwoTone("White two-tone"),
}

/**
 * Whether [this] theme declares a chrome zone that actually looks different
 * from its content zone.
 *
 * Declaring the tokens is not enough on its own: a theme may set `chromeBg` to
 * the same value as `bg` (all four white two-tones do exactly that) and carry
 * the split entirely in the accent and the canvas gutter. So this asks whether
 * any of the three visible zone surfaces — the chrome background, the canvas
 * behind the panes, or the chrome accent — differs from its content
 * counterpart.
 *
 * @return true when the chrome reads as its own zone.
 * @see ThemeCategory.WhiteTwoTone
 */
val Theme.hasDistinctChromeZone: Boolean
    get() = effectiveChromeBg != bg ||
        effectiveCanvas != bg ||
        effectiveChromeAccent != accent

/**
 * The single palette category [theme] belongs to.
 *
 * Never returns [ThemeCategory.All] or [ThemeCategory.Starred] — those are
 * about the user's selection, not the palette. The four it can return partition
 * the catalog, so this is a total function with no overlap.
 *
 * @param theme the theme to classify.
 * @return the theme's palette category.
 * @see matchesThemeCategory
 */
fun paletteCategoryOf(theme: Theme): ThemeCategory {
    val contentDark = theme.isDarkToned
    val chromeDark = theme.isChromeDarkToned
    return when {
        contentDark != chromeDark -> ThemeCategory.DarkLightSplit
        contentDark -> ThemeCategory.Dark
        theme.hasDistinctChromeZone -> ThemeCategory.WhiteTwoTone
        else -> ThemeCategory.Light
    }
}

/**
 * Whether [theme] should be shown under [category].
 *
 * @param theme     the theme under test.
 * @param category  the dropdown selection.
 * @param favorites the user's starred theme names — consulted only by
 *   [ThemeCategory.Starred], ignored by every other entry.
 * @return true when the theme passes the filter.
 * @see filterThemesForPicker
 */
fun matchesThemeCategory(
    theme: Theme,
    category: ThemeCategory,
    favorites: Set<String>,
): Boolean = when (category) {
    ThemeCategory.All -> true
    ThemeCategory.Starred -> theme.name in favorites
    else -> paletteCategoryOf(theme) == category
}

/**
 * Whether [theme] matches the picker's free-text box.
 *
 * Matches on name, tag and description, case-insensitively, so typing "coral"
 * finds a theme that only mentions coral in its blurb and "lunamux" finds the
 * house themes without them needing a category of their own. A blank or
 * whitespace-only query matches everything.
 *
 * @param theme the theme under test.
 * @param query the raw contents of the filter box.
 * @return true when the theme passes the search.
 */
fun matchesThemeQuery(theme: Theme, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return theme.name.contains(q, ignoreCase = true) ||
        theme.tag.contains(q, ignoreCase = true) ||
        theme.desc.contains(q, ignoreCase = true)
}

/**
 * The picker's full filter pass: order the catalog, then narrow it by the
 * category dropdown and the text box.
 *
 * Ordering runs first and the filters preserve it, so a theme keeps its place
 * in the starred-first sequence no matter which filter surfaces it.
 *
 * ### Callers
 * The web Theme Manager's `renderThemeList`, and available to the mobile
 * pickers so every platform filters identically.
 *
 * @param themes    the full pickable catalog (typically [allThemes]).
 * @param favorites the user's starred theme names.
 * @param category  the selected dropdown entry; defaults to [ThemeCategory.All].
 * @param query     the filter box contents; defaults to empty (no search).
 * @return the themes to render, in picker order.
 * @see orderThemesForPicker
 */
fun filterThemesForPicker(
    themes: List<Theme>,
    favorites: Set<String>,
    category: ThemeCategory = ThemeCategory.All,
    query: String = "",
): List<Theme> = orderThemesForPicker(themes, favorites)
    .filter { matchesThemeCategory(it, category, favorites) && matchesThemeQuery(it, query) }
