/* ThemeCategoriesTest.kt
 * Covers the picker's derived filter model — the replacement for the removed
 * per-theme `group` declaration.
 *
 * Two things are worth pinning here. First, that categorising by *measuring*
 * the palette actually reproduces what the hand-written labels used to say:
 * the dark/light tone counts, and the tint-alpha default that used to read the
 * label, must not have moved. Second, that the four palette categories really
 * do partition the catalog — the property the dropdown's usefulness rests on,
 * since a theme that fell into two of them (or none) would appear twice as the
 * user cycles the filter, or vanish entirely.
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The four categories a palette can be sorted into (i.e. not All/Starred). */
private val paletteCategories = listOf(
    ThemeCategory.Dark,
    ThemeCategory.Light,
    ThemeCategory.DarkLightSplit,
    ThemeCategory.WhiteTwoTone,
)

class ThemeCategoriesTest {

    // ── Tone is measured, and measures the same as the old declaration ──

    @Test
    fun toneSplitsTheCatalogAsTheRemovedGroupDeclarationDid() {
        // The counts the file header has always claimed, now derived rather
        // than declared. A theme whose palette contradicts its former label
        // would move one of these.
        assertEquals(35, builtinThemes.count { it.isDarkToned }, "dark-toned built-ins")
        assertEquals(42, builtinThemes.count { !it.isDarkToned }, "light-toned built-ins")
    }

    @Test
    fun everyBuiltinSitsFarFromTheToneBoundary() {
        // The measurement is only a safe stand-in for the old label while no
        // theme is ambiguous. Assert the margin explicitly so a future palette
        // that lands near 50% luminance fails here — loudly, and next to this
        // explanation — instead of silently flipping category.
        for (t in builtinThemes) {
            val l = luminance(hexToArgb(t.bg))
            assertTrue(
                l < 0.3 || l > 0.7,
                "${t.name}: bg luminance $l is too close to the 0.5 tone boundary",
            )
        }
    }

    @Test
    fun tintAlphaDefaultFollowsToneNotADeclaration() {
        assertEquals(Theme.DARK_TINT_ALPHA, builtinTheme("Lunamux Dark")!!.effectiveTintAlpha)
        assertEquals(Theme.LIGHT_TINT_ALPHA, builtinTheme("Lunamux Light")!!.effectiveTintAlpha)
        // A theme that states its own alpha still wins over the tone default.
        assertEquals(0.1, builtinTheme("Harbour Split")!!.effectiveTintAlpha)
    }

    // ── The four palette categories partition the catalog ──

    @Test
    fun everyThemeLandsInExactlyOnePaletteCategory() {
        for (t in builtinThemes) {
            val hits = paletteCategories.filter { matchesThemeCategory(t, it, emptySet()) }
            assertEquals(1, hits.size, "${t.name} matched $hits")
        }
    }

    @Test
    fun thePaletteCategoriesCoverTheWholeCatalog() {
        val counted = paletteCategories.sumOf { c ->
            builtinThemes.count { matchesThemeCategory(it, c, emptySet()) }
        }
        assertEquals(builtinThemes.size, counted)
    }

    // ── Each category picks out the themes it is meant to ──

    @Test
    fun whiteTwoToneFindsTheLightChromeSplitsAndOnlyThose() {
        val found = builtinThemes
            .filter { paletteCategoryOf(it) == ThemeCategory.WhiteTwoTone }
            .map { it.name }
        assertEquals(
            listOf("Harbour Split", "Orchid Split", "Marmalade Split", "Cerise Split"),
            found,
        )
    }

    @Test
    fun darkLightSplitFindsTheDarkChromeOverLightContentThemes() {
        val found = builtinThemes
            .filter { paletteCategoryOf(it) == ThemeCategory.DarkLightSplit }
            .map { it.name }
        assertEquals(
            listOf(
                "Lunamux Split", "Lunamux Classic Split", "Crimson Split", "Ember Split",
                "Nord Split", "Solarized Split", "Sandstone Split",
            ),
            found,
        )
    }

    @Test
    fun aDarkThemeWithADarkChromeZoneStaysUnderDark() {
        // "Obsidian" and "Graphite" split their chrome from their
        // content, but both zones are dark — someone filtering for Dark wants
        // them, and "Dark/Light Split" would be a lie.
        assertEquals(ThemeCategory.Dark, paletteCategoryOf(builtinTheme("Obsidian")!!))
        assertEquals(ThemeCategory.Dark, paletteCategoryOf(builtinTheme("Graphite")!!))
    }

    @Test
    fun aPlainLightThemeIsLightNotTwoTone() {
        val t = builtinTheme("Lunamux Light")!!
        assertNull(t.chromeBg, "fixture must not declare a chrome zone")
        assertFalse(t.hasDistinctChromeZone)
        assertEquals(ThemeCategory.Light, paletteCategoryOf(t))
    }

    // ── Starred, search, and the combined filter ──

    @Test
    fun starredMatchesTheStarredNamesAndNothingElse() {
        val stars = setOf("Dracula", "Paper")
        val found = builtinThemes
            .filter { matchesThemeCategory(it, ThemeCategory.Starred, stars) }
            .map { it.name }
        assertEquals(listOf("Dracula", "Paper"), found.sorted())
    }

    @Test
    fun allMatchesEverything() {
        assertTrue(builtinThemes.all { matchesThemeCategory(it, ThemeCategory.All, emptySet()) })
    }

    @Test
    fun searchMatchesNameTagAndDescriptionCaseInsensitively() {
        val t = builtinTheme("Harbour Split")!!
        assertTrue(matchesThemeQuery(t, "harbour"), "name")
        assertTrue(matchesThemeQuery(t, "BRIGHT"), "tag")
        assertTrue(matchesThemeQuery(t, "coral"), "description")
        assertFalse(matchesThemeQuery(t, "gruvbox"))
    }

    @Test
    fun aBlankSearchMatchesEverything() {
        assertTrue(builtinThemes.all { matchesThemeQuery(it, "") })
        assertTrue(builtinThemes.all { matchesThemeQuery(it, "   ") })
    }

    // ── Ordering: stars, then the house block, then the alphabet ──

    @Test
    fun theHouseThemesLeadAndListCurrentBeforeClassic() {
        // The house block is the one exception to alphabetical order, and the
        // reason for it: sorted by name, all three Classic themes would come
        // first, inverting "current palette, then the one it replaced".
        assertEquals(
            listOf(
                "Lunamux Dark", "Lunamux Light", "Lunamux Split",
                "Lunamux Classic Dark", "Lunamux Classic Light", "Lunamux Classic Split",
            ),
            orderThemesForPicker(builtinThemes, emptySet()).take(6).map { it.name },
        )
        assertEquals(HOUSE_THEME_NAMES, orderThemesForPicker(builtinThemes, emptySet()).take(6).map { it.name })
    }

    @Test
    fun everythingBelowTheHouseBlockIsAlphabetical() {
        val rest = orderThemesForPicker(builtinThemes, emptySet()).drop(6).map { it.name }
        assertEquals(rest.sortedBy { it.lowercase() }, rest)
        // And it really is the whole remainder, not a sorted prefix.
        assertEquals(builtinThemes.size - HOUSE_THEME_NAMES.size, rest.size)
    }

    @Test
    fun starsOutrankBothTheHouseBlockAndTheAlphabet() {
        // A starred theme leads even though it is neither a house theme nor
        // first alphabetically — stars are the only user-owned ordering signal.
        val ordered = orderThemesForPicker(builtinThemes, setOf("Paper", "Dracula"))
        assertEquals(listOf("Dracula", "Paper"), ordered.take(2).map { it.name })
        assertEquals("Lunamux Dark", ordered[2].name, "the house block follows the stars")
    }

    @Test
    fun toneDoesNotReEnterTheSort() {
        // The old sort put every dark theme ahead of every light one. Nothing
        // should reintroduce that: below the house block, a light theme sorts
        // above a dark one whenever its name does.
        val rest = orderThemesForPicker(builtinThemes, emptySet()).drop(6)
        val paper = rest.indexOfFirst { it.name == "Paper" }        // light
        val phosphor = rest.indexOfFirst { it.name == "Phosphor" }  // dark
        assertTrue(paper < phosphor, "Paper (light) must precede Phosphor (dark) by name")
    }

    @Test
    fun filteringPreservesPickerOrder() {
        val stars = setOf("Paper")
        val ordered = orderThemesForPicker(builtinThemes, stars)
        val filtered = filterThemesForPicker(builtinThemes, stars, ThemeCategory.Light)
        assertEquals(ordered.filter { it in filtered }, filtered)
        // And the star still floats to the top of what survives the filter.
        assertEquals("Paper", filtered.first().name)
    }

    @Test
    fun theCategoryAndTheSearchBothApply() {
        // "Split" appears in the name of all thirteen chrome-zone themes, but
        // only the light-chrome four are White two-tone.
        val found = filterThemesForPicker(
            builtinThemes, emptySet(), ThemeCategory.WhiteTwoTone, "split",
        )
        assertEquals(4, found.size)
        assertTrue(found.all { paletteCategoryOf(it) == ThemeCategory.WhiteTwoTone })
    }

    // ── Renamed built-ins keep resolving under their old names ──

    @Test
    fun aRenamedBuiltinResolvesUnderItsOldName() {
        assertEquals("Lunamux Classic Dark", builtinTheme("Termtastic Dark")?.name)
        assertEquals("Lunamux Classic Light", builtinTheme("Termtastic Light")?.name)
        assertEquals("Lunamux Classic Split", builtinTheme("Termtastic Split")?.name)
        // "Split" promised dark chrome over light content; these are dark in
        // both zones, so the word was dropped rather than left misdescribing them.
        assertEquals("Obsidian", builtinTheme("Obsidian Split")?.name)
        assertEquals("Graphite", builtinTheme("Graphite Split")?.name)
        assertEquals("Lunamux Classic Dark", canonicalThemeName("Termtastic Dark"))
        // A name that was never renamed passes straight through.
        assertEquals("Lunamux Dark", canonicalThemeName("Lunamux Dark"))
        assertNull(builtinTheme("Nothing Named This"))
    }

    @Test
    fun aSlotPinnedToARenamedBuiltinStillResolvesToIt() {
        val snap = ThemeSnapshotV2(darkThemeName = "Termtastic Dark", appearance = Appearance.Dark)
        val expected = builtinTheme("Lunamux Classic Dark")!!.resolve()
        assertEquals(expected, snap.resolve(systemIsDark = true))
    }

    @Test
    fun aCustomThemeReusingARetiredNameStillWins() {
        val mine = builtinTheme("Dracula")!!.copy(name = "Termtastic Dark")
        val snap = ThemeSnapshotV2(
            darkThemeName = "Termtastic Dark",
            customThemes = listOf(mine),
            appearance = Appearance.Dark,
        )
        assertEquals(mine.resolve(), snap.resolve(systemIsDark = true))
    }

    // ── The legacy `group` field is gone from the model but still decodes ──

    @Test
    fun noBuiltinDeclaresAGroup() {
        assertTrue(builtinThemes.all { it.group == null })
    }

    @Test
    fun aThemeSerializedBeforeTheRemovalStillDecodes() {
        // What a pre-removal `themes.json` entry looks like: every token plus
        // the `"group"` key that no longer means anything.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val before = builtinTheme("Lunamux Dark")!!.copy(
            name = "Old Clone",
            group = ThemeGroup.Dark,
        )
        val encoded = json.encodeToString(Theme.serializer(), before)
        assertTrue(encoded.contains("\"group\""), "fixture must carry the legacy key")
        val decoded = json.decodeFromString(Theme.serializer(), encoded)
        assertEquals(before, decoded)
        assertNotNull(decoded.group)
        // And it changes nothing: tone still comes from the palette.
        assertTrue(decoded.isDarkToned)
    }

    @Test
    fun aThemeWrittenNowOmitsTheLegacyKey() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(Theme.serializer(), builtinTheme("Lunamux Dark")!!)
        assertFalse(encoded.contains("\"group\""))
    }
}
