/* Theme.kt
 * The core data model for the (post-revamp) Darkness theme system: a flat,
 * fully-explicit set of 23 semantic colour tokens. A [Theme] stores every
 * literal token directly — there is NO runtime colour calculator and no
 * derivation from a seed. The previous system (ColorScheme + ResolvedPalette
 * + ThemeResolver) has been removed entirely; this file plus [ResolvedTheme],
 * [BuiltinThemes], and [ThemeSnapshotV2] are its replacement.
 */
package se.soderbjorn.darkness.core

import kotlinx.serialization.Serializable

/**
 * Whether a [Theme] is designed for the dark or the light appearance slot.
 *
 * The theme editor groups built-ins under these two headings, and each user
 * binds one theme to the dark slot and one to the light slot.
 *
 * @see Theme.group
 * @see ThemeSnapshotV2
 */
@Serializable
enum class ThemeGroup { Dark, Light }

/**
 * The user's appearance preference — follow the OS, or force one slot.
 *
 * Relocated here from the deleted `ColorSchemes.kt`. Consumed by
 * [ThemeSnapshotV2.resolve] to choose between the dark and light slot.
 */
@Serializable
enum class Appearance { Auto, Dark, Light }

/**
 * A complete theme: identity metadata plus the 20 literal `#rrggbb` semantic
 * colour tokens taken directly from the source design.
 *
 * The full resolved token contract is 23, but three of them — `accentSoft`,
 * `glow`, `addBg` — are, in the design, simply the `accent`/`add` colour at a
 * fixed transparency (e.g. `rgba(accent, 0.15)`). So they are NOT stored or
 * edited separately; [resolve] derives them by setting an alpha on the
 * accent/add token (a single-channel transparency, not a colour mix). Every
 * other token is a literal value with no computation.
 *
 * The syntax palette is **8 dedicated slots** (keyword / string / number /
 * comment / function / type / operator / constant) — each its own explicit
 * colour, so highlighting stays chromatically rich rather than collapsing
 * distinct token kinds onto a shared hue.
 *
 * ### Callers
 * Built-ins come from [builtinThemes]; user clones are persisted in
 * [ThemeSnapshotV2.customThemes]. The web editor edits a [Theme]; every
 * platform resolves the selected theme via [ThemeSnapshotV2.resolve].
 *
 * @property name        unique display name; the identity key used everywhere.
 * @property group       which appearance slot this theme is designed for.
 * @property tag         short one-word label shown in the editor (e.g. "CRT").
 * @property desc        one-line description shown in the editor.
 * @property bg          app canvas — window, sidebar and title-bar background.
 * @property surface     panel/card surface, one step above the canvas.
 * @property surfaceAlt  sunken wells — code blocks, diff gutters, tracks.
 * @property border      dividers, outlines and inactive pane frames.
 * @property text        primary terminal output and body copy.
 * @property textDim     secondary labels, line numbers, timestamps, metadata.
 * @property textBright  headings, active items and key labels.
 * @property accent      active tab, selection, prompt, cursor and focus ring.
 * @property warn        in-progress, attention and caution badges.
 * @property danger      errors, destructive actions, the status dot, diff-remove.
 * @property add         new-file marker and diff-add accent.
 * @property addText     foreground on added diff lines.
 * @property synKeyword  language keywords and markup tags.
 * @property synString   string literals and quoted text.
 * @property synNumber   numeric and boolean literals.
 * @property synComment  comments and doc text.
 * @property synFunction function calls and attributes.
 * @property synType     types, classes and interfaces.
 * @property synOperator operators, punctuation and plain code.
 * @property synConstant constants, enums and symbols.
 * @see ResolvedTheme
 * @see builtinThemes
 */
@Serializable
data class Theme(
    val name: String,
    val group: ThemeGroup,
    val tag: String,
    val desc: String,
    val bg: String,
    val surface: String,
    val surfaceAlt: String,
    val border: String,
    val text: String,
    val textDim: String,
    val textBright: String,
    val accent: String,
    val warn: String,
    val danger: String,
    val add: String,
    val addText: String,
    val synKeyword: String,
    val synString: String,
    val synNumber: String,
    val synComment: String,
    val synFunction: String,
    val synType: String,
    val synOperator: String,
    val synConstant: String,
) {
    /**
     * Converts the stored hex tokens into a [ResolvedTheme] of ARGB [Long]s
     * ready for any renderer. Pure format conversion — no colour maths.
     *
     * @return the resolved palette for this theme.
     * @see ResolvedTheme
     */
    fun resolve(): ResolvedTheme = ResolvedTheme(
        bg = hexToArgb(bg),
        surface = hexToArgb(surface),
        surfaceAlt = hexToArgb(surfaceAlt),
        border = hexToArgb(border),
        text = hexToArgb(text),
        textDim = hexToArgb(textDim),
        textBright = hexToArgb(textBright),
        accent = hexToArgb(accent),
        // Derived directly from the design's formula: the accent/add colour at
        // a fixed transparency. A single alpha set — not a colour mix.
        accentSoft = withAlpha(hexToArgb(accent), 0.15),
        glow = withAlpha(hexToArgb(accent), 0.34),
        warn = hexToArgb(warn),
        danger = hexToArgb(danger),
        add = hexToArgb(add),
        addBg = withAlpha(hexToArgb(add), if (group == ThemeGroup.Light) 0.16 else 0.18),
        addText = hexToArgb(addText),
        synKeyword = hexToArgb(synKeyword),
        synString = hexToArgb(synString),
        synNumber = hexToArgb(synNumber),
        synComment = hexToArgb(synComment),
        synFunction = hexToArgb(synFunction),
        synType = hexToArgb(synType),
        synOperator = hexToArgb(synOperator),
        synConstant = hexToArgb(synConstant),
    )

    companion object {
        /**
         * The 20 editable token ids in display order, grouped by role. Used by
         * the web editor to render one colour input per token. (The three
         * derived tokens — accentSoft / glow / addBg — are not listed; they
         * follow the accent/add colour automatically.)
         */
        val TOKEN_IDS: List<String> = listOf(
            "bg", "surface", "surfaceAlt", "border",
            "text", "textDim", "textBright",
            "accent",
            "warn", "danger", "add", "addText",
            "synKeyword", "synString", "synNumber", "synComment",
            "synFunction", "synType", "synOperator", "synConstant",
        )
    }

    /**
     * Reads a token by its id (one of [TOKEN_IDS]).
     *
     * @param id the token id.
     * @return the token's hex string, or the [bg] value for an unknown id.
     */
    fun token(id: String): String = when (id) {
        "bg" -> bg
        "surface" -> surface
        "surfaceAlt" -> surfaceAlt
        "border" -> border
        "text" -> text
        "textDim" -> textDim
        "textBright" -> textBright
        "accent" -> accent
        "warn" -> warn
        "danger" -> danger
        "add" -> add
        "addText" -> addText
        "synKeyword" -> synKeyword
        "synString" -> synString
        "synNumber" -> synNumber
        "synComment" -> synComment
        "synFunction" -> synFunction
        "synType" -> synType
        "synOperator" -> synOperator
        "synConstant" -> synConstant
        else -> bg
    }

    /**
     * Returns a copy with the token [id] set to [hex]. Used by the editor when
     * the user picks a new colour for one token.
     *
     * @param id  the token id (one of [TOKEN_IDS]).
     * @param hex the new `#rrggbb` value.
     * @return the updated theme (unchanged if [id] is unknown).
     */
    fun withToken(id: String, hex: String): Theme = when (id) {
        "bg" -> copy(bg = hex)
        "surface" -> copy(surface = hex)
        "surfaceAlt" -> copy(surfaceAlt = hex)
        "border" -> copy(border = hex)
        "text" -> copy(text = hex)
        "textDim" -> copy(textDim = hex)
        "textBright" -> copy(textBright = hex)
        "accent" -> copy(accent = hex)
        "warn" -> copy(warn = hex)
        "danger" -> copy(danger = hex)
        "add" -> copy(add = hex)
        "addText" -> copy(addText = hex)
        "synKeyword" -> copy(synKeyword = hex)
        "synString" -> copy(synString = hex)
        "synNumber" -> copy(synNumber = hex)
        "synComment" -> copy(synComment = hex)
        "synFunction" -> copy(synFunction = hex)
        "synType" -> copy(synType = hex)
        "synOperator" -> copy(synOperator = hex)
        "synConstant" -> copy(synConstant = hex)
        else -> this
    }
}
