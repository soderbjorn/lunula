/* ThemeSelectionTest.kt
 * The lint over [Theme.selection], the opaque background of selected text.
 *
 * A selection has two jobs that pull against each other: it must stand
 * clearly apart from the page, and the text on it must stay readable. These
 * tests pin both for every built-in, pin that every built-in states its own
 * value, and check that the fallback a custom theme without one gets
 * ([Theme.effectiveSelection]) does both jobs for every built-in palette.
 *
 * ### The floors
 * - **Text: 4.5:1 (WCAG AA for body text)** for [Theme.text] and
 *   [Theme.textBright] on the selection — the same floor as the `…On` lint in
 *   [ThemeContrastTest].
 * - **Apart: 1.4:1** between the selection and both [Theme.bg] and
 *   [Theme.surface]. Contrast ratio is a luminance measure, so this asks for a
 *   real step in lightness, not only a change of hue. The old selection, the
 *   15 % accent wash, sat around 1.1–1.2:1 on dark themes and was the reported
 *   bug; 1.4:1 is the smallest step that reads at a glance as a highlight
 *   against near-black and near-white alike, while leaving room for AA text
 *   in all but the lowest-contrast palettes.
 * - **Low headroom.** A palette whose own text is under 5:1 on its `bg` or
 *   `surface` (the Solarized family, C64) has no opaque colour that is 1.4:1
 *   apart *and* keeps AA text — measured, not assumed. There the floors are
 *   1.3:1 apart ([Theme.SELECTION_LOW_HEADROOM_APART]) and 3:1 for text, WCAG's
 *   floor for large text and UI parts. Which palettes count is computed from
 *   the colours ([hasLowHeadroom]), never from a list of names.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text-on-surface contrast under which a palette has [hasLowHeadroom]. */
private const val HEADROOM: Double = 5.0

/** The text floor for a palette with [hasLowHeadroom]: WCAG AA for large text. */
private const val LOW_HEADROOM_TEXT: Double = 3.0

/** Formats a ratio to two decimals without depending on a platform formatter. */
private fun ratio(value: Double): String {
    val hundredths = (value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * `true` when [t]'s body or bright text is under [HEADROOM] on its own `bg` or
 * `surface`, which leaves no opaque selection both clearly apart and AA.
 */
private fun hasLowHeadroom(t: Theme): Boolean {
    val grounds = listOf(hexToArgb(t.bg), hexToArgb(t.surface))
    val texts = listOf(hexToArgb(t.text), hexToArgb(t.textBright))
    return grounds.any { g -> texts.any { contrastRatio(it, g) < HEADROOM } }
}

/**
 * Checks [selection] against [t]'s floors (see the file header).
 *
 * @return a failure line, or `null` when the selection passes.
 */
private fun problemWith(t: Theme, selection: String): String? {
    val sel = hexToArgb(selection)
    val apart = minOf(contrastRatio(sel, hexToArgb(t.bg)), contrastRatio(sel, hexToArgb(t.surface)))
    val text = minOf(contrastRatio(hexToArgb(t.text), sel), contrastRatio(hexToArgb(t.textBright), sel))
    val low = hasLowHeadroom(t)
    val apartFloor = if (low) Theme.SELECTION_LOW_HEADROOM_APART else Theme.SELECTION_MIN_APART
    val textFloor = if (low) LOW_HEADROOM_TEXT else Theme.SELECTION_TEXT_AA
    if (apart >= apartFloor && text >= textFloor) return null
    return "${t.name}: selection $selection is ${ratio(apart)}:1 from bg/surface (needs ${ratio(apartFloor)}), " +
        "text ${ratio(text)}:1 on it (needs ${ratio(textFloor)})"
}

class ThemeSelectionTest {

    /** No built-in leans on the fallback: each states a chosen colour. */
    @Test
    fun everyBuiltinDeclaresASelection() {
        val missing = builtinThemes.filter { it.selection == null }.map { it.name }
        assertTrue(missing.isEmpty(), "built-ins without a selection: $missing")
    }

    /** Every built-in's selection is visible and keeps its text readable. */
    @Test
    fun builtinSelectionsStandApartAndKeepTextReadable() {
        val failures = builtinThemes.mapNotNull { problemWith(it, it.effectiveSelection) }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The fallback is sane for custom themes: with the declared value removed,
     * every built-in palette still gets a selection that passes the same lint.
     */
    @Test
    fun theFallbackPassesForEveryBuiltinPalette() {
        val failures = builtinThemes.mapNotNull { t ->
            problemWith(t, t.copy(selection = null).effectiveSelection)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Only the low-headroom palettes get the relaxed floors — a short list. */
    @Test
    fun lowHeadroomIsTheExceptionNotTheRule() {
        val low = builtinThemes.filter(::hasLowHeadroom).map { it.name }.toSet()
        assertEquals(setOf("Solarized Dark", "Solarized Light", "Solarized Split", "C64"), low)
    }

    /** A theme without `selection` encodes no `selection` key, and decodes back. */
    @Test
    fun aThemeWithoutASelectionEncodesWithoutTheKey() {
        val custom = builtinThemes.first().copy(name = "Mine", selection = null)
        val json = Json.encodeToString(Theme.serializer(), custom)
        assertFalse(json.contains("selection"), "unset selection must not be encoded: $json")
        assertEquals(custom, Json.decodeFromString(Theme.serializer(), json))
    }

    /** A declared value round-trips and wins over the fallback. */
    @Test
    fun aDeclaredSelectionRoundTripsAndWins() {
        val custom = builtinThemes.first().copy(name = "Mine", selection = "#123456")
        val json = Json.encodeToString(Theme.serializer(), custom)
        assertEquals(custom, Json.decodeFromString(Theme.serializer(), json))
        assertEquals("#123456", custom.effectiveSelection)
        assertEquals(hexToArgb("#123456"), custom.resolve().selection)
        assertEquals(custom.resolve().selection, custom.resolve().selectionBg)
    }

    /** The editor lists the token, shows the effective value and pins an edit. */
    @Test
    fun theEditorShowsAndEditsTheSelection() {
        assertTrue("selection" in Theme.TOKEN_IDS)
        val custom = builtinThemes.first().copy(name = "Mine", selection = null)
        assertEquals(custom.effectiveSelection, custom.token("selection"))
        assertEquals("#abcdef", custom.withToken("selection", "#abcdef").selection)
    }
}
