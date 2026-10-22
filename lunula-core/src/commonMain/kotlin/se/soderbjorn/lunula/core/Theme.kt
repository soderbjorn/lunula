/* Theme.kt
 * The core data model for the (post-revamp) Lunula theme system: a flat,
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
package se.soderbjorn.lunula.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * **Legacy.** Whether a [Theme] declared itself for the dark or the light
 * appearance slot.
 *
 * No theme declares this any more. A theme's tone is a property of its
 * *colours*, not of a hand-written label, so it is now read off the palette —
 * see [Theme.isDarkToned] and [ThemeCategory]. Binding a theme to a slot is
 * unaffected: any theme can go in either slot, exactly as before, because the
 * slot was never chosen from this value ([ThemeSnapshotV2] stores two names and
 * the picker fills whichever slot the active appearance points at).
 *
 * The enum survives only so a `themes.json` written before the removal — where
 * every custom theme carries a `"group"` key — still decodes, and so that any
 * out-of-tree caller still compiles.
 *
 * @see Theme.group
 * @see Theme.isDarkToned
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
 * single-channel transparency, not a colour mix). The one exception is
 * `accentSoft`, which a theme may now pin to an explicit opaque value (the
 * calm themes do). Every other token is a literal value with no computation.
 *
 * ### Derived tokens for the style settings
 * The style settings (Tone, Blended chrome, Soft selection) read three more
 * resolved values that the default settings ignore:
 * [ResolvedTheme.accentSoftSolid], [ResolvedTheme.legibleTextDim] and
 * [ResolvedTheme.accentSoftText]. They are computed in [resolve], not stored,
 * and each equals its source exactly whenever the source is already legible.
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
 * @property accentOn         optional — type ON a solid [accent] field; falls back to black/white by luminance.
 * @property accentText       optional — [accent] rendered AS type on an ordinary surface; falls back to [accent].
 * @property warnOn           optional — type ON a solid [warn] field.
 * @property warnText         optional — [warn] rendered AS type; falls back to [warn].
 * @property dangerOn         optional — type ON a solid [danger] field.
 * @property dangerText       optional — [danger] rendered AS type; falls back to [danger].
 * @property addOn            optional — type ON a solid [add] field.
 * @property chromeAccentOn   optional — type ON a solid [chromeAccent] field.
 * @property chromeAccentText optional — [chromeAccent] rendered AS type; falls back to [chromeAccent].
 * @property selection        optional — opaque background of selected text; every built-in
 *   declares it, and a custom theme saved before it existed gets the legible
 *   fallback [effectiveSelection].
 * @property windowIdle       optional — fill of an idle (unfocused, resting) window in the
 *   Calm look; falls back to a derivation from [surface] — see [effectiveWindowIdle].
 * @property accentSoft       optional — an **opaque** accent wash (the active sidebar row, a
 *   soft highlight). Unset, the wash is [accent] at [ACCENT_SOFT_ALPHA] as before; set, it
 *   replaces that translucent value outright — see [effectiveAccentSoftSolid].
 * @property tintAlpha        optional — diff-row tint opacity; falls back to the tone default.
 * @property group            **legacy, ignored** — the removed dark/light declaration. Kept
 *   so a `themes.json` written before the removal still decodes; see [ThemeGroup].
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
    // `themes.json` is shared between Lunula apps: a theme that doesn't use
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
    // ---- Optional role split: fills vs. the type that lands on them ----
    // Every token above is a *colour*, but each is consumed in two roles with
    // opposite requirements: as a large field, and as small type. A hue that
    // sings as a field is illegible at 13px, so a theme with only one token per
    // concept must pick the value that survives the worse case — and brand
    // character gets sanded off in service of legibility, everywhere, silently.
    //
    // `addText` was already the text-role sibling of `add`; these generalise
    // that precedent in both directions. `…On` is the type that sits ON the
    // fill; `…Text` is the concept rendered AS type on an ordinary surface.
    // Both are nullable with sane fallbacks, so no existing theme moves a pixel.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val accentOn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val accentText: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val warnOn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val warnText: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dangerOn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dangerText: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val addOn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeAccentOn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chromeAccentText: String? = null,
    // ---- Optional selected-text background ----
    // Its own token because no other one fits: [accentSoft] is a 15 % wash
    // meant for faint tints, which on a near-black theme is barely brighter
    // than the page. Every built-in states its value; the field stays
    // nullable and never-encoded so `themes.json` (shared between Lunula
    // apps) is unchanged for a theme that leaves it out.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selection: String? = null,
    // ---- Optional idle-window fill ----
    // The resting fill of a window that is not the focused one, for the Tone
    // shell (epic LNA-16). Optional and never-encoded like every token above,
    // so a theme that leaves it out serializes exactly as before; when unset
    // it is derived from [surface] — see [effectiveWindowIdle].
    @EncodeDefault(EncodeDefault.Mode.NEVER) val windowIdle: String? = null,
    // ---- Optional opaque accent wash ----
    // `accentSoft` has always been derived — the accent at 15 % alpha — which
    // is right for a faint tint but leaves a theme no say over the colour the
    // tint actually lands on. The calm themes (LNA-19) state an exact opaque
    // wash instead. Optional and never-encoded like every token above, so a
    // theme that leaves it out resolves and serializes exactly as before.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val accentSoft: String? = null,
    // ---- Optional non-colour properties ----
    /**
     * Opacity of the diff-add/remove row tints. `null` → the tone default
     * (0.16 light / 0.18 dark, chosen by [isDarkToned]).
     *
     * A shared constant forces every theme's syntax palette to clear the
     * *darkest* tint any theme uses; a light-first theme that lightens its own
     * diff rows can tune its syntax colours for the surface it actually has.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tintAlpha: Double? = null,
    // ---- Legacy, ignored ----
    /**
     * The removed dark/light declaration. Nothing reads it: tone comes from
     * [isDarkToned], which measures the palette instead of trusting a label.
     *
     * Retained as a trailing, defaulted, never-encoded field purely for
     * compatibility — a `themes.json` written before the removal carries
     * `"group": "Dark"` on every custom theme, and this is what lets those files
     * decode unchanged. It is last in the parameter list so that dropping it
     * from the 77 built-in definitions did not have to renumber every
     * positional argument.
     *
     * @see ThemeGroup
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val group: ThemeGroup? = null,
) {
    // ----- Tone, measured rather than declared -----

    /**
     * Whether this theme's *content* is dark — the property the removed
     * `group` field used to assert. Read off [bg]'s relative luminance, so a
     * theme is dark because it looks dark, not because someone typed it.
     *
     * Verified equivalent: across all 77 built-ins this reproduces the old
     * declared group exactly, and with no near misses — every light theme's
     * `bg` sits at ≥ 0.79 luminance and every dark one at ≤ 0.21, against a 0.5
     * boundary. So the two values it feeds ([effectiveTintAlpha] and the picker
     * ordering in [orderThemesForPicker]) are byte-identical to before.
     *
     * @see isChromeDarkToned
     * @see ThemeCategory
     */
    val isDarkToned: Boolean get() = !isColorLight(hexToArgb(bg))

    /**
     * Whether this theme's *chrome* (title bar / tab bar / sidebar) is dark.
     *
     * Reads [effectiveChromeBg], so a theme that leaves the chrome zone unset
     * simply reports its content tone — which is correct, since it paints the
     * chrome in the content colour. A theme where this disagrees with
     * [isDarkToned] is a two-zone theme, and that disagreement is exactly what
     * [ThemeCategory.DarkLightSplit] detects.
     */
    val isChromeDarkToned: Boolean get() = !isColorLight(hexToArgb(effectiveChromeBg))
    // ----- Effective chrome/canvas values (the token, or its fallback) -----
    // Every read of an optional token must go through one of these so the
    // fallback is applied exactly once, in one place.

    /** [canvas] if set, else [bg]. */
    val effectiveCanvas: String get() = canvas ?: bg
    /** [chromeBg] if set, else [bg]. */
    val effectiveChromeBg: String get() = chromeBg ?: bg
    /** [chromeText] if set, else [text]. */
    val effectiveChromeText: String get() = chromeText ?: text
    /**
     * [chromeTextDim] if set, else [textDim].
     *
     * A plain fallback, like every other optional token here — deliberately not
     * a derivation. An earlier version blended the chrome foreground toward the
     * chrome background to guarantee legibility on a saturated chrome, which
     * worked but made this the only token in the model computed rather than
     * stated, and made its behaviour depend on whether *other* tokens were set.
     *
     * What actually guarantees legibility is the lint
     * (`ThemeContrastTest.chromeDimToneIsLegibleOnItsOwnChrome`), which holds
     * whether the value is authored or computed — so the derivation was
     * preventing a bug the test already catches, at the cost of one token
     * behaving unlike the other 36. An author who has to pick the value also
     * picks a better one: the blend gave Solarized Split 3.01:1, passing the
     * floor by 0.01, where a chosen value has real margin.
     */
    val effectiveChromeTextDim: String get() = chromeTextDim ?: textDim
    /** [chromeTextBright] if set, else [textBright]. */
    val effectiveChromeTextBright: String get() = chromeTextBright ?: textBright
    /** [chromeBorder] if set, else [border]. */
    val effectiveChromeBorder: String get() = chromeBorder ?: border
    /** [chromeAccent] if set, else [accent]. */
    val effectiveChromeAccent: String get() = chromeAccent ?: accent
    /** [chromeTrack] if set, else [surfaceAlt]. */
    val effectiveChromeTrack: String get() = chromeTrack ?: surfaceAlt

    // ----- Effective role-split values -----
    //
    // The two families fall back differently, and the asymmetry is the point:
    //
    //  - a `…Text` token falls back to its own base token, because using the
    //    accent as type is what every theme did before the split and must keep
    //    doing;
    //  - an `…On` token falls back to black-or-white by luminance, because
    //    there is no previous behaviour to preserve — nothing painted type on a
    //    solid accent field until [SelectionStyle.Fill] existed — and a
    //    *legible* default is the only useful one. A theme that cares declares
    //    the exact value and wins.
    //
    // The `…On` fallback is what lets the contrast lint hold for all built-ins
    // rather than only the handful that opted in.

    /** [accentText] if set, else [accent] — the accent rendered AS type. */
    val effectiveAccentText: String get() = accentText ?: accent
    /** [warnText] if set, else [warn]. */
    val effectiveWarnText: String get() = warnText ?: warn
    /** [dangerText] if set, else [danger]. */
    val effectiveDangerText: String get() = dangerText ?: danger
    /** [chromeAccentText] if set, else [effectiveChromeAccent]. */
    val effectiveChromeAccentText: String get() = chromeAccentText ?: effectiveChromeAccent

    /** [accentOn] if set, else black/white by [accent]'s luminance. */
    val effectiveAccentOn: Long get() = onFill(accentOn, accent)
    /** [warnOn] if set, else black/white by [warn]'s luminance. */
    val effectiveWarnOn: Long get() = onFill(warnOn, warn)
    /** [dangerOn] if set, else black/white by [danger]'s luminance. */
    val effectiveDangerOn: Long get() = onFill(dangerOn, danger)
    /** [addOn] if set, else black/white by [add]'s luminance. */
    val effectiveAddOn: Long get() = onFill(addOn, add)
    /** [chromeAccentOn] if set, else black/white by the chrome accent's luminance. */
    val effectiveChromeAccentOn: Long get() = onFill(chromeAccentOn, effectiveChromeAccent)

    /**
     * [selection] if set, else a legible default for a custom theme saved
     * before the token existed: [accent] blended into [bg], at the weakest
     * strength that stands [SELECTION_TARGET_APART] apart from both [bg] and
     * [surface] while [text] and [textBright] keep [SELECTION_TEXT_AA] on it.
     *
     * Follows the `…On` precedent — there is no earlier selection colour worth
     * preserving (the old one was the faint [accentSoft] wash), so a legible
     * default is the only useful one. When no strength reaches the target with
     * readable text (a palette whose own text has little headroom, like
     * Solarized), it settles for the most visible blend that keeps the text at
     * AA, and failing that the first one [SELECTION_LOW_HEADROOM_APART] apart.
     * `ThemeContrastTest` checks this fallback against every built-in palette.
     */
    val effectiveSelection: String get() = selection ?: argbToHex(selectionFallback())

    /** The blend ladder behind [effectiveSelection]; see there. */
    private fun selectionFallback(): Long {
        val accentArgb = hexToArgb(accent)
        val bgArgb = hexToArgb(bg)
        val surfaceArgb = hexToArgb(surface)
        val textArgb = hexToArgb(text)
        val brightArgb = hexToArgb(textBright)
        val ladder = (10..100).map { mix(accentArgb, bgArgb, it / 100.0) }
        fun apart(c: Long) = minOf(contrastRatio(c, bgArgb), contrastRatio(c, surfaceArgb))
        fun textOn(c: Long) = minOf(contrastRatio(textArgb, c), contrastRatio(brightArgb, c))
        ladder.firstOrNull { apart(it) >= SELECTION_TARGET_APART && textOn(it) >= SELECTION_TEXT_AA }
            ?.let { return it }
        ladder.filter { apart(it) >= SELECTION_MIN_APART && textOn(it) >= SELECTION_TEXT_AA }
            .maxByOrNull { apart(it) }
            ?.let { return it }
        return ladder.firstOrNull { apart(it) >= SELECTION_LOW_HEADROOM_APART } ?: ladder.last()
    }

    /**
     * [windowIdle] if set, else derived from [surface] so that an idle window
     * sits one quiet step away from the focused one.
     *
     * The derivation depends on tone ([isDarkToned]), because "quieter" points
     * in opposite directions on the two:
     *
     *  - **light** — [surface] mixed [WINDOW_IDLE_LIGHT_TOWARD_BLACK] toward
     *    black, a light grey (`#FFFFFF` → `#ECECEC`);
     *  - **dark** — [surface] mixed [WINDOW_IDLE_DARK_TOWARD_BG] toward [bg],
     *    sinking it three quarters of the way into the window background
     *    (`#1A1D20` over `#0D0F10` → `#101214`), never darker than [bg].
     *
     * Like [effectiveSelection], this is a computed fallback for an optional
     * token rather than a stored value; a theme that states [windowIdle] wins.
     * Read by [resolve] and by [token] (so the editor shows the real colour).
     *
     * @return the idle-window fill as a `#rrggbb` hex string.
     * @see ResolvedTheme.windowIdle
     */
    val effectiveWindowIdle: String
        get() = windowIdle ?: argbToHex(
            if (isDarkToned) {
                mix(hexToArgb(bg), hexToArgb(surface), WINDOW_IDLE_DARK_TOWARD_BG)
            } else {
                mix(BLACK, hexToArgb(surface), WINDOW_IDLE_LIGHT_TOWARD_BLACK)
            }
        )

    /**
     * The focused window's fill under [FocusStyle.Tone]: [surface], lifted
     * toward [text] only as far as needed for the active window to stand
     * [WINDOW_TONE_MIN_CONTRAST] apart from [effectiveWindowIdle].
     *
     * Needed because the idle fill of a dark theme is derived by sinking
     * [surface] toward [bg], and in most dark palettes the two are nearly the
     * same colour: Lunamux Classic Dark's surface `#0c1611` on `#08110c` gives
     * an idle `#09120d` nobody can tell from the active window (contrast
     * 1.02). Lifting the active window instead keeps the idle ones at the
     * background level, so nothing goes darker than [bg].
     *
     * A theme that declares [windowIdle] has tuned the pair by hand (the Calm
     * themes), and keeps [surface] exactly; so does every light theme, whose
     * derived idle already sits about 1.18 apart.
     *
     * @return the active-window fill as a `#rrggbb` hex string.
     * @see ResolvedTheme.windowActive
     */
    val effectiveWindowActive: String
        get() {
            if (windowIdle != null) return surface
            val surfaceArgb = hexToArgb(surface)
            val lifted = nudgeForContrast(
                surfaceArgb, hexToArgb(text), listOf(hexToArgb(effectiveWindowIdle)),
                WINDOW_TONE_MIN_CONTRAST, WINDOW_TONE_STEP,
            )
            return if (lifted == surfaceArgb) surface else argbToHex(lifted)
        }

    /**
     * The hairline ring the Calm look draws around the focused window:
     * [border] mixed [WINDOW_RING_TOWARD_TEXT] toward [text], one step
     * stronger than an ordinary hairline so the active window's edge reads
     * on every side (`#E3E3DF` with text `#1A1C1E` → `#D5D5D1`).
     *
     * Derived only; there is no theme token to declare it, because it exists
     * to track [border] and [text]. Read by [resolve] for
     * [ResolvedTheme.windowRing], which the web layer emits as
     * `--t-window-ring`.
     *
     * @return the ring colour as a `#rrggbb` hex string.
     * @see ResolvedTheme.windowRing
     */
    val effectiveWindowRing: String
        get() = argbToHex(mix(hexToArgb(text), hexToArgb(border), WINDOW_RING_TOWARD_TEXT))

    /**
     * The accent wash as an opaque colour: [accentSoft] if declared, else
     * [accent] at [ACCENT_SOFT_ALPHA] composited over [surface] — the colour a
     * browser actually paints for the translucent wash on a surface.
     *
     * Read by [resolve] (for [ResolvedTheme.accentSoftSolid] and as the
     * backdrop of [ResolvedTheme.accentSoftText]) and by [token], so the
     * editor shows a real swatch for a theme that never declared a wash.
     *
     * @return the opaque wash as a `#rrggbb` hex string.
     * @see ResolvedTheme.accentSoftSolid
     */
    val effectiveAccentSoftSolid: String
        get() = accentSoft ?: argbToHex(
            compositeOver(withAlpha(hexToArgb(accent), ACCENT_SOFT_ALPHA), hexToArgb(surface))
        )

    /**
     * [textDim] made legible for the Calm look: unchanged when it already
     * clears [LEGIBLE_TEXT_AA] on [effectiveWindowIdle], [bg] and [surface];
     * otherwise nudged toward [text] in [LEGIBLE_NUDGE_STEP] steps
     * ([nudgeForContrast]) until it does.
     *
     * The style settings paint an inactive window's title in the dim tone on the
     * idle-window fill, a pairing no theme was drawn for — 69 built-ins fall
     * short of AA there (LNA-18). Rather than re-tint every palette, the style
     * settings read this derived value and the default rules keep reading
     * [textDim] untouched.
     *
     * Should even [text] fall short (a low-headroom palette), the ladder
     * carries on from [text] toward black or white — whichever the
     * idle-window fill contrasts with more — so the result always clears the
     * floor.
     *
     * @return the legible dim-text colour as ARGB.
     * @see ResolvedTheme.legibleTextDim
     */
    private fun legibleTextDimArgb(): Long {
        val backdrops = listOf(hexToArgb(effectiveWindowIdle), hexToArgb(bg), hexToArgb(surface), hexToArgb(effectiveWindowActive))
        val towardText = nudgeForContrast(hexToArgb(textDim), hexToArgb(text), backdrops, LEGIBLE_TEXT_AA, LEGIBLE_NUDGE_STEP)
        if (backdrops.all { contrastRatio(towardText, it) >= LEGIBLE_TEXT_AA }) return towardText
        return nudgeForContrast(towardText, onColorFor(backdrops.first()), backdrops, LEGIBLE_TEXT_AA, LEGIBLE_NUDGE_STEP)
    }

    /**
     * The accent as type, made legible on the opaque accent wash for Soft
     * shell: [effectiveAccentText] unchanged when it already clears
     * [LEGIBLE_TEXT_AA] on [effectiveAccentSoftSolid]; otherwise nudged in
     * [LEGIBLE_NUDGE_STEP] steps toward the readable extreme until it does.
     *
     * The extreme is [text] when [text] itself clears the floor on the wash and
     * contrasts with it more than the accent text does (keeping the result in
     * the palette's own ink); otherwise black or white, whichever contrasts
     * with the wash more ([onColorFor], which clears the floor for any fill).
     * Soft selection paints the active row and tab in this colour on that wash, where 51
     * built-ins' accent text falls short (LNA-18).
     *
     * @return the soft accent-text colour as ARGB.
     * @see ResolvedTheme.accentSoftText
     */
    private fun accentSoftTextArgb(): Long {
        val wash = hexToArgb(effectiveAccentSoftSolid)
        val accentTextArgb = hexToArgb(effectiveAccentText)
        val textArgb = hexToArgb(text)
        val textRatio = contrastRatio(textArgb, wash)
        val target = if (textRatio >= LEGIBLE_TEXT_AA && textRatio > contrastRatio(accentTextArgb, wash)) {
            textArgb
        } else {
            onColorFor(wash)
        }
        return nudgeForContrast(accentTextArgb, target, listOf(wash), LEGIBLE_TEXT_AA, LEGIBLE_NUDGE_STEP)
    }

    /** [tintAlpha] if set, else the tone default (0.16 light / 0.18 dark). */
    val effectiveTintAlpha: Double
        get() = tintAlpha ?: if (isDarkToned) DARK_TINT_ALPHA else LIGHT_TINT_ALPHA

    /** Shared body of the `…On` getters: the declared hex, or the legible default. */
    private fun onFill(declared: String?, fill: String): Long =
        declared?.let(::hexToArgb) ?: onColorFor(hexToArgb(fill))

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
        // A declared opaque wash (see [Theme.accentSoft]) replaces the formula.
        accentSoft = accentSoft?.let(::hexToArgb) ?: withAlpha(hexToArgb(accent), ACCENT_SOFT_ALPHA),
        glow = withAlpha(hexToArgb(accent), 0.34),
        warn = hexToArgb(warn),
        danger = hexToArgb(danger),
        add = hexToArgb(add),
        // The diff tints now read the theme's own alpha (default unchanged), so
        // a light-first theme can lighten its rows without every other theme's
        // syntax palette having to clear the darker worst case.
        addBg = withAlpha(hexToArgb(add), effectiveTintAlpha),
        removeBgTint = withAlpha(hexToArgb(danger), effectiveTintAlpha),
        addText = hexToArgb(addText),
        // Role split: the type that lands ON each fill, and each concept AS type.
        accentOn = effectiveAccentOn,
        accentText = hexToArgb(effectiveAccentText),
        warnOn = effectiveWarnOn,
        warnText = hexToArgb(effectiveWarnText),
        dangerOn = effectiveDangerOn,
        dangerText = hexToArgb(effectiveDangerText),
        addOn = effectiveAddOn,
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
        // The one derived text token — see [effectiveChromeTextDim] for why the
        // derivation is gated on the theme actually splitting its chrome.
        chromeTextDim = hexToArgb(effectiveChromeTextDim),
        chromeTextBright = hexToArgb(effectiveChromeTextBright),
        chromeBorder = hexToArgb(effectiveChromeBorder),
        chromeAccent = hexToArgb(effectiveChromeAccent),
        // Same shape as accentSoft, but keyed off the chrome accent and at the
        // design's 16% — the active-item tint inside the chrome.
        chromeAccentSoft = withAlpha(hexToArgb(effectiveChromeAccent), 0.16),
        chromeAccentOn = effectiveChromeAccentOn,
        chromeAccentText = hexToArgb(effectiveChromeAccentText),
        chromeTrack = hexToArgb(effectiveChromeTrack),
        selection = hexToArgb(effectiveSelection),
        // Declared, or derived from surface by tone — see effectiveWindowIdle.
        windowIdle = hexToArgb(effectiveWindowIdle),
        // Surface, lifted when it sits too close to the idle fill — see
        // effectiveWindowActive.
        windowActive = hexToArgb(effectiveWindowActive),
        // Derived from border and text — see effectiveWindowRing.
        windowRing = hexToArgb(effectiveWindowRing),
        // Derived tokens for the style settings; the default settings never read them. Each equals its
        // source exactly when the source already clears AA.
        accentSoftSolid = hexToArgb(effectiveAccentSoftSolid),
        legibleTextDim = legibleTextDimArgb(),
        accentSoftText = accentSoftTextArgb(),
    )

    companion object {
        /** Default [tintAlpha] for a light-toned theme (see [isDarkToned]). */
        const val LIGHT_TINT_ALPHA: Double = 0.16

        /** Default [tintAlpha] for a dark-toned theme (see [isDarkToned]). */
        const val DARK_TINT_ALPHA: Double = 0.18

        /**
         * Contrast [effectiveSelection]'s fallback aims for between the
         * selection and both [bg] and [surface]: comfortably over
         * [SELECTION_MIN_APART], so a highlight reads at a glance.
         */
        const val SELECTION_TARGET_APART: Double = 1.6

        /**
         * The least contrast a selection may have against [bg] and [surface]
         * to count as visible; see `ThemeContrastTest` for why 1.4.
         */
        const val SELECTION_MIN_APART: Double = 1.4

        /**
         * The visibility floor for a palette with little headroom — whose
         * [text] or [textBright] is under 5:1 on [bg] or [surface] (the
         * Solarized family, C64) — where no opaque colour is both
         * [SELECTION_MIN_APART] apart and AA for the text.
         */
        const val SELECTION_LOW_HEADROOM_APART: Double = 1.3

        /** WCAG AA for body text: what [text] keeps on the selection. */
        const val SELECTION_TEXT_AA: Double = 4.5

        /**
         * How far [effectiveWindowIdle]'s light-tone fallback mixes [surface]
         * toward black: `0.075` takes `#FFFFFF` to `#ECECEC`. Raised from
         * `0.05` (`#F2F2F2`) so a white focused window stands clearly apart
         * from the idle ones under Tone.
         */
        const val WINDOW_IDLE_LIGHT_TOWARD_BLACK: Double = 0.075

        /**
         * How far [effectiveWindowIdle]'s dark-tone fallback mixes [surface]
         * toward [bg]: `0.75` takes `#1A1D20` over `#0D0F10` to `#101214`.
         * Raised from `0.55` (`#131517`) for the same reason as the light
         * fallback; kept below `1.0`, so idle never goes darker than [bg].
         */
        const val WINDOW_IDLE_DARK_TOWARD_BG: Double = 0.75

        /**
         * How far [effectiveWindowRing] mixes [border] toward [text]: `0.07`
         * takes `#E3E3DF` (with text `#1A1C1E`) to `#D5D5D1`.
         */
        const val WINDOW_RING_TOWARD_TEXT: Double = 0.07

        /**
         * The least contrast [effectiveWindowActive] keeps between the active
         * and the idle window fill: about Moss Calm Dark's hand-tuned 1.11, the
         * difference that still reads as "this one is active" on a dark ground.
         * Measured across the built-ins, 33 of the 37 dark themes without a
         * declared idle fill fell short (median 1.06, worst 1.02).
         */
        const val WINDOW_TONE_MIN_CONTRAST: Double = 1.10

        /** The mix increment of [effectiveWindowActive]'s lift toward [text]. */
        const val WINDOW_TONE_STEP: Double = 0.01

        /**
         * Opacity of the derived accent wash, [ResolvedTheme.accentSoft]: the
         * design's "accent at 15 %". Ignored by a theme that declares an
         * opaque [accentSoft].
         */
        const val ACCENT_SOFT_ALPHA: Double = 0.15

        /**
         * The contrast floor the legible text tokens are nudged to reach —
         * WCAG AA for body text. See [ResolvedTheme.legibleTextDim] and
         * [ResolvedTheme.accentSoftText].
         */
        const val LEGIBLE_TEXT_AA: Double = 4.5

        /**
         * The mix increment of each nudge toward a more legible colour (see
         * [nudgeForContrast]): small, so a failing colour moves only as far as
         * it must and keeps as much of its hue as it can.
         */
        const val LEGIBLE_NUDGE_STEP: Double = 0.02

        /** Opaque black, the light-tone mix target for [effectiveWindowIdle]. */
        private const val BLACK: Long = 0xFF000000L

        /**
         * The 40 editable token ids in display order, grouped by role. Used by
         * the web editor to render one colour input per token. (The five
         * derived tokens — glow / addBg / removeBgTint / chromeAccentSoft —
         * are not listed; they follow the accent/add/danger/chromeAccent
         * colour automatically. `accentSoft` is listed: it is derived unless
         * a theme pins the opaque override, which the editor shows as the
         * composited [effectiveAccentSoftSolid].)
         *
         * The optional ids — the 8 chrome/canvas ones, the 9 role-split ones,
         * `selection`, `windowIdle` and `accentSoft` — report their *effective* value through [token], so the editor
         * always shows a real colour rather than an empty swatch, and
         * [withToken] pins an explicit one on edit.
         *
         * Each `…On` / `…Text` id follows the fill it belongs to, so the pair
         * is edited as a pair — which is the whole point of declaring them
         * together.
         */
        val TOKEN_IDS: List<String> = listOf(
            "bg", "canvas", "surface", "surfaceAlt", "windowIdle", "border",
            "text", "textDim", "textBright",
            "accent", "accentOn", "accentText", "accentSoft", "selection",
            "warn", "warnOn", "warnText",
            "danger", "dangerOn", "dangerText",
            "add", "addOn", "addText",
            "chromeBg", "chromeText", "chromeTextDim", "chromeTextBright",
            "chromeBorder",
            "chromeAccent", "chromeAccentOn", "chromeAccentText",
            "chromeTrack",
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
        "chromeAccentOn" -> argbToHex(effectiveChromeAccentOn)
        "chromeAccentText" -> effectiveChromeAccentText
        "chromeTrack" -> effectiveChromeTrack
        "accentOn" -> argbToHex(effectiveAccentOn)
        "accentText" -> effectiveAccentText
        "selection" -> effectiveSelection
        "windowIdle" -> effectiveWindowIdle
        "accentSoft" -> effectiveAccentSoftSolid
        "warnOn" -> argbToHex(effectiveWarnOn)
        "warnText" -> effectiveWarnText
        "dangerOn" -> argbToHex(effectiveDangerOn)
        "dangerText" -> effectiveDangerText
        "addOn" -> argbToHex(effectiveAddOn)
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
        "chromeAccentOn" -> copy(chromeAccentOn = hex)
        "chromeAccentText" -> copy(chromeAccentText = hex)
        "chromeTrack" -> copy(chromeTrack = hex)
        "accentOn" -> copy(accentOn = hex)
        "accentText" -> copy(accentText = hex)
        "selection" -> copy(selection = hex)
        "windowIdle" -> copy(windowIdle = hex)
        "accentSoft" -> copy(accentSoft = hex)
        "warnOn" -> copy(warnOn = hex)
        "warnText" -> copy(warnText = hex)
        "dangerOn" -> copy(dangerOn = hex)
        "dangerText" -> copy(dangerText = hex)
        "addOn" -> copy(addOn = hex)
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
