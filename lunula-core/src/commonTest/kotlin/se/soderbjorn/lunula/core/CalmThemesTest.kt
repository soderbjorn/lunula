/* CalmThemesTest.kt
 * Covers the ten calm built-ins drawn for the Clean shell (LNA-19) and the
 * machinery around them: the brief's stated values landing verbatim, the
 * optional opaque `accentSoft` token ([Theme.accentSoft]) and its editor and
 * serialization wiring, the curated [ThemeCategory.Clean] filter, and the
 * `calmFirst` picker ordering the web layer asks for under the Clean style.
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
        // accent / accentSoft / accentText.
        val g = builtinTheme("Graphite Calm Dark")!!
        assertEquals(
            listOf("#111214", "#1b1c1e", "#141517", "#27292b", "#ecedee", "#9da0a4", "#aeb2b7", "#2a2c2f", "#d6d9dc"),
            listOf(g.bg, g.surface, g.windowIdle, g.border, g.text, g.textDim, g.accent, g.accentSoft, g.accentText),
        )
        val m = builtinTheme("Moss Calm Light")!!
        assertEquals(
            listOf("#f7f7f5", "#ffffff", "#f3f3f0", "#e3e3df", "#1a1c1e", "#62686d", "#2f6b4f", "#e4ece6", "#1f4d38"),
            listOf(m.bg, m.surface, m.windowIdle, m.border, m.text, m.textDim, m.accent, m.accentSoft, m.accentText),
        )
    }

    @Test
    fun everyCalmThemeDeclaresTheCleanTokensExplicitly() {
        for (t in calmThemes) {
            assertNotNull(t.accentSoft, "${t.name} accentSoft")
            assertNotNull(t.accentText, "${t.name} accentText")
            assertNotNull(t.windowIdle, "${t.name} windowIdle")
            assertNotNull(t.selection, "${t.name} selection")
            assertNull(t.chromeBg, "${t.name} must not open a chrome zone — Clean ignores it")
        }
    }

    // ── Contrast: the Clean pairings, with no allowlist ──

    @Test
    fun calmThemesPassTheCleanPairingsWithoutAnyNudge() {
        val failures = mutableListOf<String>()
        for (t in calmThemes) {
            val r = t.resolve()
            for ((name, backdrop) in listOf("windowIdle" to r.windowIdle, "bg" to r.bg, "surface" to r.surface)) {
                if (contrastRatio(r.textDim, backdrop) < AA) failures += "${t.name}: textDim on $name"
            }
            if (contrastRatio(r.accentText, r.accentSoft) < AA) failures += "${t.name}: accentText on accentSoft"
            // So the Clean-derived tokens are the stated ones, untouched.
            if (r.cleanTextDim != r.textDim) failures += "${t.name}: cleanTextDim was nudged"
            if (r.cleanAccentText != r.accentText) failures += "${t.name}: cleanAccentText was nudged"
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

    // ── The Clean category ──

    @Test
    fun theCleanCategoryIsExactlyTheCalmThemes() {
        val found = builtinThemes.filter { matchesThemeCategory(it, ThemeCategory.Clean, emptySet()) }.map { it.name }
        assertEquals(CALM_THEME_NAMES.sorted(), found.sorted())
    }

    @Test
    fun calmThemesStillLandInTheirPaletteCategory() {
        // Clean is curated, not part of the partition: each calm theme is also
        // plain Dark or plain Light.
        for (t in calmThemes) {
            val expected = if (t.isDarkToned) ThemeCategory.Dark else ThemeCategory.Light
            assertEquals(expected, paletteCategoryOf(t), t.name)
        }
    }

    @Test
    fun aCloneOfACalmThemeIsStillCalm() {
        val clone = builtinTheme("Moss Calm Dark")!!.copy(name = "My Moss")
        assertTrue(matchesThemeCategory(clone, ThemeCategory.Clean, emptySet()))
        val other = builtinTheme("Dracula")!!.copy(name = "My Dracula")
        assertFalse(matchesThemeCategory(other, ThemeCategory.Clean, emptySet()))
    }

    // ── calmFirst ordering ──

    @Test
    fun calmFirstPutsTheCalmThemesAheadOfTheHouseBlock() {
        val ordered = orderThemesForPicker(builtinThemes, emptySet(), calmFirst = true).map { it.name }
        assertEquals(CALM_THEME_NAMES, ordered.take(CALM_THEME_NAMES.size))
        assertEquals(HOUSE_THEME_NAMES, ordered.drop(CALM_THEME_NAMES.size).take(HOUSE_THEME_NAMES.size))
        assertEquals(builtinThemes.size, ordered.size)
    }

    @Test
    fun withoutCalmFirstTheOrderIsTheClassicOne() {
        val classic = orderThemesForPicker(builtinThemes, emptySet())
        assertEquals(classic, orderThemesForPicker(builtinThemes, emptySet(), calmFirst = false))
        assertEquals(HOUSE_THEME_NAMES, classic.take(HOUSE_THEME_NAMES.size).map { it.name })
    }

    @Test
    fun starsStillLeadUnderCalmFirst() {
        val ordered = orderThemesForPicker(builtinThemes, setOf("Dracula"), calmFirst = true).map { it.name }
        assertEquals("Dracula", ordered.first())
        assertEquals(CALM_THEME_NAMES, ordered.drop(1).take(CALM_THEME_NAMES.size))
    }

    @Test
    fun aCalmCloneFollowsTheCalmBuiltins() {
        val clone = builtinTheme("Ember Calm Light")!!.copy(name = "Aaa Calm Clone")
        val ordered = orderThemesForPicker(allThemes(listOf(clone)), emptySet(), calmFirst = true).map { it.name }
        assertEquals(CALM_THEME_NAMES + "Aaa Calm Clone", ordered.take(CALM_THEME_NAMES.size + 1))
    }

    @Test
    fun filterThemesForPickerPassesCalmFirstThrough() {
        val filtered = filterThemesForPicker(builtinThemes, emptySet(), ThemeCategory.Light, calmFirst = true)
        assertEquals(
            CALM_THEME_NAMES.filter { it.endsWith("Light") },
            filtered.take(5).map { it.name },
        )
    }
}
