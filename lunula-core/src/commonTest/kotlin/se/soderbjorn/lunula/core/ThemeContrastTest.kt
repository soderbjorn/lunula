/* ThemeContrastTest.kt
 * The contrast lint over the role-split tokens.
 *
 * Five review rounds on the Framna theme were all instances of one bug: a
 * colour chosen as a FIELD being read as TYPE, or vice versa. The role split
 * (`…On` / `…Text` in [Theme]) removes the ambiguity; this file removes the
 * regression, by asserting the pairing holds for every built-in rather than for
 * the one theme someone happened to look at.
 *
 * The two tests are deliberately scoped differently, and the difference is not
 * an oversight:
 *
 *  - [onFillForegroundsClearAA] runs over ALL built-ins, because every theme
 *    has an effective `…On` value whether or not it declared one — the fallback
 *    picks black or white by luminance, so the assertion is meaningful
 *    everywhere and holds by construction.
 *  - [declaredTextTokensClearAAOnEverySurface] runs only over themes that
 *    DECLARE a `…Text` token. An undeclared one falls back to its fill, which
 *    is the pre-split behaviour: asserting AA there would not be a lint, it
 *    would be a demand that 73 existing themes change appearance to satisfy a
 *    rule written after them. The lint applies to what a theme claims, and a
 *    theme claims a text role by declaring it.
 *
 * Two later checks (LNA-18) cover pairings the Clean shell relies on —
 * [textDimClearsAAOnWindowIdle] and [accentTextClearsAAOnAccentSoft]. They run
 * over all built-ins, but a handful of existing palettes already fall short,
 * and fixing a palette is a design decision rather than a test change. So
 * each check carries an explicit allowlist of the themes known to fail: the
 * suite stays green, any NEW failure turns it red with the failing pairs
 * listed, and an allowlisted theme that starts passing turns it red too, so
 * the list can only shrink.
 *
 * @see contrastRatio
 * @see Theme.accentOn
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.x AA floor for body text. */
private const val AA: Double = 4.5

/**
 * Built-ins whose [Theme.textDim] is below AA on their [ResolvedTheme.windowIdle]
 * fill, recorded when the check was introduced (LNA-18). Not to be grown: a
 * new theme must pass, and a listed theme that is fixed must be removed.
 */
private val KNOWN_TEXT_DIM_ON_WINDOW_IDLE_FAILURES: Set<String> = setOf(
    "Lunamux Dark",
    "Lunamux Light",
    "Lunamux Split",
    "Lunarbor Dark",
    "Lunarbor Light",
    "Lunamux Classic Dark",
    "Lunamux Classic Light",
    "Lunamux Classic Split",
    "Citron Light",
    "Obsidian",
    "Graphite",
    "Phosphor",
    "Tokyo Midnight",
    "Amber CRT",
    "Synthwave",
    "Nord Slate",
    "Solarized Dark",
    "Dracula",
    "Gruvbox Dark",
    "Catppuccin Mocha",
    "Carbon",
    "Monokai Pro",
    "Deep Sea",
    "Ember",
    "Espresso",
    "Ultraviolet",
    "Rosé Pine",
    "Crimson",
    "Oxide",
    "Ice",
    "Royal",
    "Aurora",
    "Bubblegum",
    "Cobalt Night",
    "Umbra",
    "Crimson Split",
    "Ember Split",
    "Nord",
    "Solarized Split",
    "Sandstone",
    "Fern",
    "Paper",
    "Solarized Light",
    "Blossom",
    "Cobalt Sky",
    "Gruvbox Light",
    "Catppuccin Latte",
    "Frost",
    "Sepia",
    "Sakura",
    "Citrus",
    "Lavender Haze",
    "Seafoam",
    "Peach",
    "Marigold",
    "Periwinkle",
    "Matcha",
    "Terracotta",
    "Sky",
    "Lemonade",
    "Sage",
    "Flamingo",
    "Ink & Gold",
    "Cotton Candy",
    "Meadow",
    "Slate",
    "Heather",
    "Workbench",
    "C64",
)

/**
 * Built-ins whose effective [Theme.accentText] is below AA on
 * [ResolvedTheme.accentSoft] composited over [Theme.surface], recorded when
 * the check was introduced (LNA-18). Same rules as the list above.
 */
private val KNOWN_ACCENT_TEXT_ON_ACCENT_SOFT_FAILURES: Set<String> = setOf(
    "Lunamux Light",
    "Lunamux Split",
    "Lunarbor Dark",
    "Lunamux Classic Light",
    "Lunamux Classic Split",
    "Citron Light",
    "Obsidian",
    "Graphite",
    "Nord Slate",
    "Solarized Dark",
    "Dracula",
    "Gruvbox Dark",
    "GitHub Dark",
    "Espresso",
    "Ultraviolet",
    "Crimson",
    "Oxide",
    "Crimson Split",
    "Ember Split",
    "Nord",
    "Solarized Split",
    "Sandstone",
    "Paper",
    "Solarized Light",
    "Blossom",
    "Cobalt Sky",
    "Gruvbox Light",
    "GitHub Light",
    "Catppuccin Latte",
    "Frost",
    "Sepia",
    "Sakura",
    "Citrus",
    "Lavender Haze",
    "Seafoam",
    "Peach",
    "Marigold",
    "Periwinkle",
    "Matcha",
    "Terracotta",
    "Sky",
    "Lemonade",
    "Sage",
    "Flamingo",
    "Ink & Gold",
    "Cotton Candy",
    "Meadow",
    "Slate",
    "Heather",
    "Workbench",
    "C64",
)

/**
 * Composites a possibly translucent ARGB [over] onto an opaque [under], the
 * way the browser paints it: the alpha channel becomes the [mix] amount.
 *
 * @param over the translucent colour (e.g. [ResolvedTheme.accentSoft]).
 * @param under the opaque colour it sits on.
 * @return the opaque colour actually seen.
 */
private fun composite(over: Long, under: Long): Long {
    val alpha = ((over ushr 24) and 0xFF) / 255.0
    return mix(over or 0xFF000000L, under, alpha)
}

/**
 * Shared body of the allowlisted checks: fails on any failure outside
 * [allowlist], and on any allowlisted theme that no longer fails.
 *
 * @param what human-readable name of the pairing, for the message.
 * @param failures theme name → failure description, for every failing built-in.
 * @param allowlist the theme names known to fail.
 */
private fun assertOnlyKnownFailures(
    what: String,
    failures: Map<String, String>,
    allowlist: Set<String>,
) {
    val unexpected = failures.filterKeys { it !in allowlist }.values
    assertTrue(
        unexpected.isEmpty(),
        "$what below ${AA}:1 (not in the known-failure allowlist):\n" +
            unexpected.joinToString("\n"),
    )
    val nowPassing = allowlist.filter { it !in failures }
    assertTrue(
        nowPassing.isEmpty(),
        "$what: allowlisted themes now pass — remove them from the allowlist: $nowPassing",
    )
    val unknown = allowlist.filter { name -> builtinThemes.none { it.name == name } }
    assertEquals(emptyList(), unknown, "$what: allowlist names themes that do not exist")
}

/** Formats a ratio to two decimals without depending on a platform formatter. */
private fun ratio(value: Double): String {
    val hundredths = (value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

class ThemeContrastTest {

    /**
     * Every foreground declared for a solid fill clears AA against that fill.
     *
     * This is the assertion the token split makes cheap: because a fill and the
     * type that lands on it are now declared together, the check needs no
     * knowledge of which surfaces a renderer happens to combine — the pair
     * itself carries the answer.
     */
    @Test
    fun onFillForegroundsClearAA() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val pairs = listOf(
                Triple("accentOn", t.effectiveAccentOn, t.accent),
                Triple("warnOn", t.effectiveWarnOn, t.warn),
                Triple("dangerOn", t.effectiveDangerOn, t.danger),
                Triple("addOn", t.effectiveAddOn, t.add),
                Triple("chromeAccentOn", t.effectiveChromeAccentOn, t.effectiveChromeAccent),
            )
            for ((name, fg, fillHex) in pairs) {
                val r = contrastRatio(fg, hexToArgb(fillHex))
                if (r < AA) failures += "${t.name}: $name on $fillHex is ${ratio(r)}:1"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "on-fill foregrounds below ${AA}:1:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Every explicitly declared `…Text` token clears AA against each ordinary
     * surface it can land on — [Theme.bg], [Theme.surface] and
     * [Theme.surfaceAlt] for the content zone, plus the chrome background for
     * the chrome one.
     *
     * `surfaceAlt` is the surface worth naming: it is where code blocks, wells
     * and gutters sit, it is usually the lowest-contrast of the three, and it
     * is the one nobody checks by eye.
     */
    @Test
    fun declaredTextTokensClearAAOnEverySurface() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            val contentSurfaces = listOf("bg" to t.bg, "surface" to t.surface, "surfaceAlt" to t.surfaceAlt)
            val declared = listOf(
                Triple("accentText", t.accentText, contentSurfaces),
                Triple("warnText", t.warnText, contentSurfaces),
                Triple("dangerText", t.dangerText, contentSurfaces),
                Triple("chromeAccentText", t.chromeAccentText,
                    listOf("chromeBg" to t.effectiveChromeBg)),
            )
            for ((name, hex, surfaces) in declared) {
                val fg = hex ?: continue
                for ((surfaceName, surfaceHex) in surfaces) {
                    val r = contrastRatio(hexToArgb(fg), hexToArgb(surfaceHex))
                    if (r < AA) {
                        failures += "${t.name}: $name ($fg) on $surfaceName ($surfaceHex) is ${ratio(r)}:1"
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "declared text-role tokens below ${AA}:1:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * A theme that paints a *saturated* chrome must not be left reading its
     * dim chrome tone off the base zone.
     *
     * [Theme.effectiveChromeTextDim] derives the value in exactly that case, so
     * this pins the property the derivation exists to guarantee rather than the
     * derivation itself: whatever a split-chrome theme's dim tone ends up
     * being, it has to be legible on the chrome it actually sits on. 3:1 is the
     * large-text/secondary floor — these are labels, counts and timestamps, not
     * body copy.
     */
    @Test
    fun chromeDimToneIsLegibleOnItsOwnChrome() {
        val failures = mutableListOf<String>()
        for (t in builtinThemes) {
            if (t.chromeBg == null) continue
            val r = contrastRatio(hexToArgb(t.effectiveChromeTextDim), hexToArgb(t.effectiveChromeBg))
            if (r < 3.0) failures += "${t.name}: chromeTextDim is ${ratio(r)}:1 on ${t.effectiveChromeBg}"
        }
        assertTrue(
            failures.isEmpty(),
            "chrome dim tones below 3.0:1 on their own chrome:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * [Theme.textDim] stays legible on the idle-window fill.
     *
     * The Clean shell paints resting windows in [ResolvedTheme.windowIdle], and
     * their metadata (timestamps, counts, line numbers) is still drawn in the
     * dim tone, so the pair has to clear body-text AA.
     */
    @Test
    fun textDimClearsAAOnWindowIdle() {
        val failures = mutableMapOf<String, String>()
        for (t in builtinThemes) {
            val r = t.resolve()
            val c = contrastRatio(r.textDim, r.windowIdle)
            if (c < AA) {
                failures[t.name] = "${t.name}: textDim (${t.textDim}) on windowIdle " +
                    "(${argbToHex(r.windowIdle)}) is ${ratio(c)}:1"
            }
        }
        assertOnlyKnownFailures("textDim on windowIdle", failures, KNOWN_TEXT_DIM_ON_WINDOW_IDLE_FAILURES)
    }

    /**
     * The accent rendered as type stays legible on the accent wash.
     *
     * [ResolvedTheme.accentSoft] is translucent (accent at 15% alpha), so the
     * colour actually seen depends on what is under it; it is composited over
     * [Theme.surface] before measuring.
     */
    @Test
    fun accentTextClearsAAOnAccentSoft() {
        val failures = mutableMapOf<String, String>()
        for (t in builtinThemes) {
            val r = t.resolve()
            val wash = composite(r.accentSoft, r.surface)
            val c = contrastRatio(r.accentText, wash)
            if (c < AA) {
                failures[t.name] = "${t.name}: accentText (${argbToHex(r.accentText)}) on " +
                    "accentSoft over surface (${argbToHex(wash)}) is ${ratio(c)}:1"
            }
        }
        assertOnlyKnownFailures(
            "accentText on accentSoft",
            failures,
            KNOWN_ACCENT_TEXT_ON_ACCENT_SOFT_FAILURES,
        )
    }

    /**
     * The calm themes (LNA-19) were drawn for the Clean shell, so they are
     * held to every check above with no exemption: none may appear in either
     * allowlist. Since [textDimClearsAAOnWindowIdle] and
     * [accentTextClearsAAOnAccentSoft] fail on any unlisted failure, this plus
     * those two is the full guarantee.
     */
    @Test
    fun calmThemesHaveNoAllowlistEntries() {
        val listed = CALM_THEME_NAMES.filter {
            it in KNOWN_TEXT_DIM_ON_WINDOW_IDLE_FAILURES || it in KNOWN_ACCENT_TEXT_ON_ACCENT_SOFT_FAILURES
        }
        assertEquals(emptyList(), listed)
        for (name in CALM_THEME_NAMES) {
            val t = builtinTheme(name)!!
            val r = t.resolve()
            assertTrue(contrastRatio(r.textDim, r.windowIdle) >= AA, "$name: textDim on windowIdle")
            assertTrue(
                contrastRatio(r.accentText, composite(r.accentSoft, r.surface)) >= AA,
                "$name: accentText on accentSoft",
            )
        }
    }

    @Test
    fun compositeOfATranslucentColourIsTheAlphaMix() {
        // Guards the helper the accentSoft check depends on: 15% of white over
        // black is the 15% mix, and an opaque colour composites to itself.
        val soft = withAlpha(0xFFFFFFFFL, 0.15)
        val alpha = ((soft ushr 24) and 0xFF) / 255.0
        assertEquals(mix(0xFFFFFFFFL, 0xFF000000L, alpha), composite(soft, 0xFF000000L))
        assertEquals(0xFF336699L, composite(0xFF336699L, 0xFF000000L))
    }
}
