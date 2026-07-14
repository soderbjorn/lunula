/* Theme.kt
 * The core data model for the (post-revamp) Darkness theme system: a flat,
 * fully-explicit set of semantic colour tokens. A [Theme] stores every
 * literal token directly — there is NO runtime colour calculator and no
 * derivation from a seed. The previous system (ColorScheme + ResolvedPalette
 * + ThemeResolver) has been removed entirely; this file plus [ResolvedTheme],
 * [BuiltinThemes], and [ThemeSnapshotV2] are its replacement.
 *
 * The palette covers three zones: the **chrome** (title bar / tab bar /
 * sidebar), the **canvas** (the area behind and between the panes), and the
 * **panes** themselves (content, on `bg`/`surface`/…). The eight chrome/canvas
 * tokens are optional — each falls back to its base-zone counterpart — so a
 * theme that ignores them is styled exactly as it was before they existed.
 */
package se.soderbjorn.darkness.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * A complete theme: identity metadata plus the 20 required literal `#rrggbb`
 * semantic colour tokens taken directly from the source design, plus 8
 * optional chrome/canvas tokens.
 *
 * The full resolved token contract is 32, but four of them — `accentSoft`,
 * `glow`, `addBg`, `chromeAccentSoft` — are, in the design, simply the
 * `accent`/`add`/`chromeAccent` colour at a fixed transparency (e.g.
 * `rgba(accent, 0.15)`). So they are NOT stored or edited separately;
 * [resolve] derives them by setting an alpha on the source token (a
 * single-channel transparency, not a colour mix). Every other token is a
 * literal value with no computation.
 *
 * ### The three zones
 * The 8 nullable tokens let a theme paint the app **chrome** (title bar, tab
 * bar, sidebar) and the pane **canvas** independently of the **pane** content:
 *
 * | token              | falls back to |
 * |--------------------|---------------|
 * | [chromeBg]         | [bg]          |
 * | [chromeText]       | [text]        |
 * | [chromeTextDim]    | [textDim]     |
 * | [chromeTextBright] | [textBright]  |
 * | [chromeBorder]     | [border]      |
 * | [chromeAccent]     | [accent]      |
 * | [chromeTrack]      | [surfaceAlt]  |
 * | [canvas]           | [bg]          |
 *
 * Because every one falls back, a theme that leaves them all `null` renders
 * byte-identically to how it did before these tokens existed — the feature is
 * purely additive. Read them through [resolve] (or the `effective*`
 * accessors), never directly, or the fallback is skipped.
 *
 * Content panes and the syntax palette are deliberately untouched by the
 * chrome zone. The syntax palette is **8 dedicated slots** (keyword / string /
 * number / comment / function / type / operator / constant) — each its own
 * explicit colour, so highlighting stays chromatically rich rather than
 * collapsing distinct token kinds onto a shared hue.
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
 * @property bg          app canvas — window and outermost background, the lowest layer.
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
 * @property canvas           optional — area behind and between the panes; falls back to [bg].
 * @property chromeBg         optional — title bar + tab bar + sidebar background; falls back to [bg].
 * @property chromeText       optional — sidebar rows and chrome body text; falls back to [text].
 * @property chromeTextDim    optional — chrome icons, counts and metadata; falls back to [textDim].
 * @property chromeTextBright optional — app name, active tab, active item; falls back to [textBright].
 * @property chromeBorder     optional — chrome dividers and frame; falls back to [border].
 * @property chromeAccent     optional — chrome section headers and active accent; falls back to [accent].
 * @property chromeTrack      optional — sidebar usage-meter track; falls back to [surfaceAlt].
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
@OptIn(ExperimentalSerializationApi::class)
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
    // ---- Optional chrome/canvas zone (null = fall back to the base token) ----
    // @EncodeDefault(NEVER) keeps an unset token out of the serialized form
    // entirely, rather than writing an explicit `null`. That matters because
    // `themes.json` is shared between Darkness apps: a theme that doesn't use
    // the chrome zone serializes byte-identically to how it did before these
    // fields existed, so no round-trip through an older app is perturbed.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val canvas: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeBg: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeText: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeTextDim: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeTextBright: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeBorder: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeAccent: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeTrack: String? = null,
) {
    // ----- Effective chrome/canvas values (the token, or its fallback) -----
    // Every read of an optional token must go through one of these so the
    // fallback is applied exactly once, in one place.

    /** [canvas] if set, else [bg]. */
    val effectiveCanvas: String get() = canvas ?: bg
    /** [chromeBg] if set, else [bg]. */
    val effectiveChromeBg: String get() = chromeBg ?: bg
    /** [chromeText] if set, else [text]. */
    val effectiveChromeText: String get() = chromeText ?: text
    /** [chromeTextDim] if set, else [textDim]. */
    val effectiveChromeTextDim: String get() = chromeTextDim ?: textDim
    /** [chromeTextBright] if set, else [textBright]. */
    val effectiveChromeTextBright: String get() = chromeTextBright ?: textBright
    /** [chromeBorder] if set, else [border]. */
    val effectiveChromeBorder: String get() = chromeBorder ?: border
    /** [chromeAccent] if set, else [accent]. */
    val effectiveChromeAccent: String get() = chromeAccent ?: accent
    /** [chromeTrack] if set, else [surfaceAlt]. */
    val effectiveChromeTrack: String get() = chromeTrack ?: surfaceAlt

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
        // Chrome/canvas zone: each optional token resolved through its
        // fallback, so an unset token simply mirrors its base-zone
        // counterpart and the theme renders as it always did.
        canvas = hexToArgb(effectiveCanvas),
        chromeBg = hexToArgb(effectiveChromeBg),
        chromeText = hexToArgb(effectiveChromeText),
        chromeTextDim = hexToArgb(effectiveChromeTextDim),
        chromeTextBright = hexToArgb(effectiveChromeTextBright),
        chromeBorder = hexToArgb(effectiveChromeBorder),
        chromeAccent = hexToArgb(effectiveChromeAccent),
        // Same shape as accentSoft, but keyed off the chrome accent and at the
        // design's 16% — the active-item tint inside the chrome.
        chromeAccentSoft = withAlpha(hexToArgb(effectiveChromeAccent), 0.16),
        chromeTrack = hexToArgb(effectiveChromeTrack),
    )

    companion object {
        /**
         * The 28 editable token ids in display order, grouped by role. Used by
         * the web editor to render one colour input per token. (The four
         * derived tokens — accentSoft / glow / addBg / chromeAccentSoft — are
         * not listed; they follow the accent/add/chromeAccent colour
         * automatically.)
         *
         * The 8 chrome/canvas ids are optional on [Theme]; [token] reports
         * their *effective* value so the editor always shows a real colour,
         * and [withToken] pins an explicit one.
         */
        val TOKEN_IDS: List<String> = listOf(
            "bg", "canvas", "surface", "surfaceAlt", "border",
            "text", "textDim", "textBright",
            "accent",
            "warn", "danger", "add", "addText",
            "chromeBg", "chromeText", "chromeTextDim", "chromeTextBright",
            "chromeBorder", "chromeAccent", "chromeTrack",
            "synKeyword", "synString", "synNumber", "synComment",
            "synFunction", "synType", "synOperator", "synConstant",
        )
    }

    /**
     * Reads a token by its id (one of [TOKEN_IDS]).
     *
     * For the 8 optional chrome/canvas ids this returns the **effective**
     * value — the token if set, otherwise its fallback — so a caller (the
     * editor's swatch, a renderer) never has to know a token was unset.
     *
     * @param id the token id.
     * @return the token's hex string, or the [bg] value for an unknown id.
     */
    fun token(id: String): String = when (id) {
        "bg" -> bg
        "canvas" -> effectiveCanvas
        "chromeBg" -> effectiveChromeBg
        "chromeText" -> effectiveChromeText
        "chromeTextDim" -> effectiveChromeTextDim
        "chromeTextBright" -> effectiveChromeTextBright
        "chromeBorder" -> effectiveChromeBorder
        "chromeAccent" -> effectiveChromeAccent
        "chromeTrack" -> effectiveChromeTrack
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
     * Setting one of the 8 optional chrome/canvas ids pins an explicit value,
     * ending that token's fallback to its base-zone counterpart.
     *
     * @param id  the token id (one of [TOKEN_IDS]).
     * @param hex the new `#rrggbb` value.
     * @return the updated theme (unchanged if [id] is unknown).
     */
    fun withToken(id: String, hex: String): Theme = when (id) {
        "bg" -> copy(bg = hex)
        "canvas" -> copy(canvas = hex)
        "chromeBg" -> copy(chromeBg = hex)
        "chromeText" -> copy(chromeText = hex)
        "chromeTextDim" -> copy(chromeTextDim = hex)
        "chromeTextBright" -> copy(chromeTextBright = hex)
        "chromeBorder" -> copy(chromeBorder = hex)
        "chromeAccent" -> copy(chromeAccent = hex)
        "chromeTrack" -> copy(chromeTrack = hex)
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
