/* CalmThemesTest.kt
 * Covers the ten calm built-ins drawn for the Calm look (LNA-19) and the
 * machinery around them: the brief's stated values landing verbatim, the
 * optional opaque `accentSoft` token ([Theme.accentSoft]) and its editor and
 * serialization wiring, the curated [ThemeCategory.Calm] filter, and the
 * picker order, which no longer depends on the style settings (the old
 * `calmFirst` hoist under the Calm style is gone).
 *
 * Contrast for these themes is pinned in [ThemeContrastTest] (no allowlist
 * entries, every check) and [ThemeSelectionTest] (which runs over every
 * built-in); this file checks the calm-specific pairings explicitly as well, so
 * a regression names the calm theme at fault.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WCAG AA for body text. */
private const val AA: Double = 4.5

/** The calm built-ins, in [CALM_THEME_NAMES] order. */
private val calmThemes: List<Theme> = CALM_THEME_NAMES.map { assertNotNull(builtinTheme(it), it) }

class CalmThemesTest {

    // ── The catalog ──

    @Test
    fun tenCalmThemesExistUnderNonClashingNames() {
        assertEquals(10, CALM_THEME_NAMES.size)
        assertEquals(10, calmThemes.size)
        // The bare names are existing, unrelated built-ins and must stay so.
        for (existing in listOf("Graphite", "Ember", "Orchid")) {
            val t = assertNotNull(builtinTheme(existing))
            assertFalse(isCalmTheme(t), "$existing is not a calm theme")
        }
        // And no calm name is a legacy alias for something else.
        for (name in CALM_THEME_NAMES) assertEquals(name, canonicalThemeName(name))
        assertTrue(CALM_THEME_NAMES.none { it in HOUSE_THEME_NAMES })
    }

    @Test
    fun eachHueHasALightAndADarkVariantWhoseToneMatchesTheName() {
        for (t in calmThemes) {
            assertEquals(CALM_THEME_TAG, t.tag, t.name)
            assertEquals(t.name.endsWith("Dark"), t.isDarkToned, "${t.name} tone")
        }
    }

    @Test
    fun theBriefsStatedValuesLandVerbatim() {
        // Spot-check one theme fully against the ticket (LNA-19), in the
        // brief's order: bg / surface / windowIdle / border / text / textDim /
        // accent / accentSoft / accentText. windowIdle is the one exception:
        // it was later moved a step further from surface (the brief's #141517
        // and #f3f3f0 became #131416 and #ecece8) so the active window stands
        // out more.
        val g = builtinTheme("Graphite Calm Dark")!!
        assertEquals(
            listOf("#111214", "#1b1c1e", "#131416", "#27292b", "#ecedee", "#9da0a4", "#aeb2b7", "#2a2c2f", "#d6d9dc"),
            listOf(g.bg, g.surface, g.windowIdle, g.border, g.text, g.textDim, g.accent, g.accentSoft, g.accentText),
        )
        val m = builtinTheme("Moss Calm Light")!!
        assertEquals(
            listOf("#f7f7f5", "#ffffff", "#ecece8", "#e3e3df", "#1a1c1e", "#62686d", "#2f6b4f", "#e4ece6", "#1f4d38"),
            listOf(m.bg, m.surface, m.windowIdle, m.border, m.text, m.textDim, m.accent, m.accentSoft, m.accentText),
        )
    }

    @Test
    fun everyCalmThemeDeclaresTheCalmTokensExplicitly() {
        for (t in calmThemes) {
            assertNotNull(t.accentSoft, "${t.name} accentSoft")
            assertNotNull(t.accentText, "${t.name} accentText")
            assertNotNull(t.windowIdle, "${t.name} windowIdle")
            assertNotNull(t.selection, "${t.name} selection")
            assertNull(t.chromeBg, "${t.name} must not open a chrome zone — Calm ignores it")
        }
    }

    @Test
    fun everyCalmIdleWindowSitsBetweenTheGroundAndTheFocusedSurface() {
        // Light: idle is darker than the white focused window. Dark: idle is
        // darker than the focused surface but never darker than the ground.
        for (t in calmThemes) {
            val r = t.resolve()
            assertTrue(luminance(r.windowIdle) < luminance(r.surface), "${t.name}: idle must differ from surface")
            if (t.isDarkToned) {
                assertTrue(luminance(r.windowIdle) >= luminance(r.bg), "${t.name}: idle darker than bg")
            }
        }
    }

    // ── Contrast: the Calm pairings, with no allowlist ──

    @Test
    fun calmThemesPassTheCalmPairingsWithoutAnyNudge() {
        val failures = mutableListOf<String>()
        for (t in calmThemes) {
            val r = t.resolve()
            for ((name, backdrop) in listOf("windowIdle" to r.windowIdle, "bg" to r.bg, "surface" to r.surface)) {
                if (contrastRatio(r.textDim, backdrop) < AA) failures += "${t.name}: textDim on $name"
            }
            if (contrastRatio(r.accentText, r.accentSoft) < AA) failures += "${t.name}: accentText on accentSoft"
            // So the Calm-derived tokens are the stated ones, untouched.
            if (r.legibleTextDim != r.textDim) failures += "${t.name}: legibleTextDim was nudged"
            if (r.accentSoftText != r.accentText) failures += "${t.name}: accentSoftText was nudged"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun calmThemesTypeRolesClearAAOnEverySurface() {
        // Every token a calm theme uses as type — including the derived warn /
        // danger / add and the syntax slots — reads on all four surfaces.
        val failures = mutableListOf<String>()
        for (t in calmThemes) {
            val surfaces = listOf(t.bg, t.surface, t.surfaceAlt, t.effectiveWindowIdle)
            val type = listOf(
                "text" to t.text, "textDim" to t.textDim, "textBright" to t.textBright,
                "accentText" to t.effectiveAccentText, "warn" to t.warn, "danger" to t.danger, "add" to t.add,
                "synKeyword" to t.synKeyword, "synString" to t.synString, "synNumber" to t.synNumber,
                "synComment" to t.synComment, "synFunction" to t.synFunction, "synType" to t.synType,
                "synOperator" to t.synOperator, "synConstant" to t.synConstant,
            )
            for ((name, hex) in type) for (s in surfaces) {
                if (contrastRatio(hexToArgb(hex), hexToArgb(s)) < AA) failures += "${t.name}: $name on $s"
            }
            val addTint = t.resolve().addBg.let { compositeOver(it, hexToArgb(t.surface)) }
            if (contrastRatio(hexToArgb(t.addText), addTint) < AA) failures += "${t.name}: addText on add tint"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ── The opaque accentSoft override ──

    @Test
    fun aDeclaredAccentSoftIsResolvedOpaqueAndVerbatim() {
        val t = builtinTheme("Lunicle Calm Light")!!
        val r = t.resolve()
        assertEquals(hexToArgb("#e3eaf7"), r.accentSoft)
        assertEquals(r.accentSoft, r.accentSoftSolid)
        assertEquals("#e3eaf7", t.token("accentSoft"))
    }

    @Test
    fun anUndeclaredAccentSoftKeepsTheFifteenPercentFormula() {
        val t = builtinTheme("Lunamux Dark")!!
        assertNull(t.accentSoft)
        val r = t.resolve()
        assertEquals(withAlpha(hexToArgb(t.accent), Theme.ACCENT_SOFT_ALPHA), r.accentSoft)
        assertEquals(compositeOver(r.accentSoft, r.surface), r.accentSoftSolid)
        assertEquals(argbToHex(r.accentSoftSolid), t.token("accentSoft"))
    }

    @Test
    fun theEditorListsAccentSoftAndPinsAnEdit() {
        assertTrue("accentSoft" in Theme.TOKEN_IDS)
        val base = builtinTheme("Dracula")!!
        val edited = base.withToken("accentSoft", "#223344")
        assertEquals("#223344", edited.accentSoft)
        assertEquals(hexToArgb("#223344"), edited.resolve().accentSoft)
        assertEquals(base.accent, edited.accent, "pinning the wash must not touch the accent")
    }

    @Test
    fun accentSoftIsOmittedFromSerializationUnlessDeclared() {
        val plain = builtinTheme("Lunamux Dark")!!
        val json = Json.encodeToString(Theme.serializer(), plain)
        assertFalse(json.contains("accentSoft"), "unset accentSoft must not be encoded: $json")
        val custom = plain.copy(name = "Mine", accentSoft = "#abcdef")
        val customJson = Json.encodeToString(Theme.serializer(), custom)
        assertTrue(customJson.contains("\"accentSoft\":\"#abcdef\""), customJson)
        assertEquals(custom, Json.decodeFromString(Theme.serializer(), customJson))
    }

    // ── The Calm category ──

    @Test
    fun theCalmCategoryIsExactlyTheCalmThemes() {
        val found = builtinThemes.filter { matchesThemeCategory(it, ThemeCategory.Calm, emptySet()) }.map { it.name }
        assertEquals(CALM_THEME_NAMES.sorted(), found.sorted())
    }

    @Test
    fun theCalmCategoryIsLabelledCalm() {
        assertEquals("Calm", ThemeCategory.Calm.label)
    }

    @Test
    fun calmThemesStillLandInTheirPaletteCategory() {
        // Calm is curated, not part of the partition: each calm theme is also
        // plain Dark, plain Light or (with white windows) White one-tone.
        for (t in calmThemes) {
            val expected = when {
                t.isDarkToned -> ThemeCategory.Dark
                t.hasWhiteWindows -> ThemeCategory.WhiteOneTone
                else -> ThemeCategory.Light
            }
            assertEquals(expected, paletteCategoryOf(t), t.name)
        }
    }

    @Test
    fun aCloneOfACalmThemeIsStillCalm() {
        val clone = builtinTheme("Moss Calm Dark")!!.copy(name = "My Moss")
        assertTrue(matchesThemeCategory(clone, ThemeCategory.Calm, emptySet()))
        val other = builtinTheme("Dracula")!!.copy(name = "My Dracula")
        assertFalse(matchesThemeCategory(other, ThemeCategory.Calm, emptySet()))
    }

    // ── Picker order (independent of the style settings) ──

    @Test
    fun theOrderIsStarsThenHouseThenAlphabetical() {
        val ordered = orderThemesForPicker(builtinThemes, emptySet()).map { it.name }
        assertEquals(HOUSE_THEME_NAMES, ordered.take(HOUSE_THEME_NAMES.size))
        val rest = ordered.drop(HOUSE_THEME_NAMES.size)
        assertEquals(rest.sortedBy { it.lowercase() }, rest)
        assertEquals(builtinThemes.size, ordered.size)
    }

    @Test
    fun calmThemesAreNotHoistedAheadOfTheHouseBlock() {
        // The calm themes used to lead the list under the old single Clean style. The
        // order now has no style input at all, so they sort alphabetically
        // among the rest, after the house block.
        val ordered = orderThemesForPicker(builtinThemes, emptySet()).map { it.name }
        val firstCalm = ordered.indexOfFirst { it in CALM_THEME_NAMES }
        assertTrue(firstCalm >= HOUSE_THEME_NAMES.size, "a calm theme sits at $firstCalm, inside the house block")
        assertTrue(ordered.take(CALM_THEME_NAMES.size) != CALM_THEME_NAMES, "the calm block must not lead")
    }

    @Test
    fun theOrderIsStableAcrossCalls() {
        // No hidden state (such as the style the shell is in) may feed the order.
        val a = orderThemesForPicker(builtinThemes, setOf("Dracula"))
        val b = orderThemesForPicker(builtinThemes.reversed(), setOf("Dracula"))
        assertEquals(a, b)
        assertEquals("Dracula", a.first().name)
    }

    @Test
    fun filterThemesForPickerKeepsThePickerOrder() {
        val filtered = filterThemesForPicker(builtinThemes, emptySet(), ThemeCategory.Calm)
        val expected = orderThemesForPicker(builtinThemes, emptySet()).filter { isCalmTheme(it) }
        assertEquals(expected, filtered)
        assertEquals(CALM_THEME_NAMES.sorted(), filtered.map { it.name }.sorted())
    }
}
