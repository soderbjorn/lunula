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
 * The five palette categories form a **partition**: every theme lands in
 * exactly one of [ThemeCategory.Dark], [ThemeCategory.Light],
 * [ThemeCategory.WhiteOneTone], [ThemeCategory.WhiteTwoTone] and
 * [ThemeCategory.DarkLightSplit], decided by two tone readings (content and
 * chrome), and, when both are light, whether the theme splits its chrome from
 * its content at all and whether its windows are white.
 */
package se.soderbjorn.lunula.core

/**
 * A filter offered by the theme picker's category dropdown.
 *
 * [All] and [Starred] are selection filters — they ask about the user, not
 * about the palette. [Calm] is a curated filter: the calm themes, which also
 * appear under their palette category. The remaining
 * five are palette filters and partition the catalog, so switching between
 * them shows every theme exactly once across the set.
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
     * The calm themes (LNA-19): the ten "<Hue> Calm Light/Dark" built-ins and
     * any theme tagged [CALM_THEME_TAG], decided by [isCalmTheme]. Quiet,
     * near-neutral palettes that pair well with the Calm look.
     *
     * Not part of the palette partition — a calm theme is also Dark or Light,
     * and [paletteCategoryOf] never returns this. It is a curated shortcut,
     * offered whatever the style settings are.
     *
     * @see CALM_THEME_NAMES
     */
    Calm("Calm"),

    /**
     * Dark throughout: dark content in dark chrome. Includes the two-zone dark
     * themes ("Obsidian", "Graphite") whose chrome differs from
     * their content in *value* but is still dark — a user reaching for "Dark"
     * wants those in the results.
     */
    Dark("Dark"),

    /**
     * Light throughout, with no chrome zone splitting it, on a tinted palette:
     * cream, pink, green, lavender and the like ("Paper", "Sakura", "Matcha").
     * A light, unsplit theme whose windows are plain white is a
     * [WhiteOneTone] instead.
     */
    Light("Light"),

    /**
     * Light throughout, with no chrome zone splitting it, and windows of
     * neutral near-white ([Theme.hasWhiteWindows]): one white tone with the
     * accent as the only colour ("GitHub Light", "High Contrast", "Lunamux
     * Light", the light Calm themes). The unsplit counterpart of
     * [WhiteTwoTone].
     */
    WhiteOneTone("White one-tone"),


    /**
     * Light in both zones, but still split into two: the shell is as white as
     * the content, and the zones are told apart by hue alone ("Harbour",
     * "Orchid", "Marmalade", "Cerise", "Fern", "Lagoon", "Peony", "Sunbeam",
     * "Bluebell", "Kiwi", "Spearmint", "Dusk", "Sunset", "Cactus", "Pebble",
     * "Nimbus").
     *
     * The distinction from [Light] is that the theme actually declares a chrome
     * zone *and* paints something differently in it — see
     * [Theme.hasDistinctChromeZone]. A light theme that leaves the chrome tokens
     * unset, or sets them to exactly the content values, is a plain [Light].
     */
    WhiteTwoTone("White two-tone"),

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
}

/**
 * Whether [this] theme declares a chrome zone that actually looks different
 * from its content zone.
 *
 * Declaring the tokens is not enough on its own: a theme may set `chromeBg` to
 * the same value as `bg` (every white two-tone does exactly that) and carry
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
 * Whether [this] theme's windows are neutral near-white: every channel of
 * [Theme.surface] at least [WHITE_WINDOW_MIN_CHANNEL], and no two more than
 * [WHITE_WINDOW_MAX_SPREAD] apart (so a faint cream or blush does not count).
 *
 * @return true for white windows.
 * @see ThemeCategory.WhiteOneTone
 */
val Theme.hasWhiteWindows: Boolean
    get() {
        val argb = hexToArgb(surface)
        val channels = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        return channels.min() >= WHITE_WINDOW_MIN_CHANNEL &&
            channels.max() - channels.min() <= WHITE_WINDOW_MAX_SPREAD
    }

/** The least value of each surface channel for [Theme.hasWhiteWindows] (`#F8`). */
const val WHITE_WINDOW_MIN_CHANNEL: Long = 0xF8

/** The widest spread between surface channels for [Theme.hasWhiteWindows]. */
const val WHITE_WINDOW_MAX_SPREAD: Long = 3

/**
 * The single palette category [theme] belongs to.
 *
 * Never returns [ThemeCategory.All], [ThemeCategory.Starred] or
 * [ThemeCategory.Calm] — those are about the user's selection or a curated
 * set, not the palette. The five it can return partition
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
        theme.hasWhiteWindows -> ThemeCategory.WhiteOneTone
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
    ThemeCategory.Calm -> isCalmTheme(theme)
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
