/* LooksTest.kt
 * Covers the named presets of the style settings ([Look], [Looks]) and the
 * one resolution ladder every reader of those settings walks
 * ([resolveAppearance], [appearanceLadder]).
 *
 * A look is just a convenient combination of independent settings, matched
 * exactly against what is painted ([Look.matches]); nothing stores which look
 * was picked. These tests pin that a look matches itself, that changing any
 * one field makes it stop matching (the Presets block then reads "custom"), that an
 * unset corner radius is read as the stylesheet's default, and the precedence
 * of the ladder: user > user's legacy look > app default > app's legacy look >
 * toolkit default.
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every single-field change of [resolved] to another value of that field, so a
 * test can check that no single change keeps a look matching.
 *
 * @param resolved the starting values (normally a look's resolution).
 * @return one variant per (field, other value), labelled for failure messages.
 */
private fun singleFieldVariants(resolved: ResolvedAppearance): List<Pair<String, ResolvedAppearance>> = buildList {
    add("corner" to resolved.copy(cornerRadiusPx = (resolved.cornerRadiusPx ?: DEFAULT_CORNER_RADIUS_PX) + 1))
    UiDensity.entries.filter { it != resolved.uiDensity }.forEach { add("density=$it" to resolved.copy(uiDensity = it)) }
    SelectionStyle.entries.filter { it != resolved.selectionStyle }.forEach { add("selection=$it" to resolved.copy(selectionStyle = it)) }
    SurfaceStyle.entries.filter { it != resolved.surfaceStyle }.forEach { add("surface=$it" to resolved.copy(surfaceStyle = it)) }
    ChromeStyle.entries.filter { it != resolved.chromeStyle }.forEach { add("chrome=$it" to resolved.copy(chromeStyle = it)) }
    WindowLayout.entries.filter { it != resolved.windowLayout }.forEach { add("windows=$it" to resolved.copy(windowLayout = it)) }
    FocusStyle.entries.filter { it != resolved.focusStyle }.forEach { add("focus=$it" to resolved.copy(focusStyle = it)) }
    LabelCase.entries.filter { it != resolved.labelCase }.forEach { add("labels=$it" to resolved.copy(labelCase = it)) }
}

/** Looks matching and the resolution ladder; see the file comment for what is pinned. */
class LooksTest {

    // ── Looks ──

    @Test
    fun eachLookMatchesItself() {
        for (look in Looks.all) {
            assertTrue(look.matches(resolveAppearance(look.shape)), look.id)
        }
    }

    @Test
    fun theLooksDoNotMatchEachOther() {
        assertFalse(Looks.Classic.matches(resolveAppearance(Looks.Calm.shape)))
        assertFalse(Looks.Calm.matches(resolveAppearance(Looks.Classic.shape)))
    }

    @Test
    fun changingAnyOneFieldMakesNeitherLookMatch() {
        for (look in Looks.all) {
            for ((label, variant) in singleFieldVariants(resolveAppearance(look.shape))) {
                assertFalse(look.matches(variant), "${look.id} still matches after $label")
                for (other in Looks.all) {
                    assertFalse(other.matches(variant), "${other.id} matches ${look.id} with $label")
                }
            }
        }
    }

    @Test
    fun theToolkitDefaultsAreTheClassicLook() {
        // Nothing set anywhere: the radius stays null (clear the override) and
        // still reads as Classic's 18px.
        val nothing = resolveAppearance(AppearanceShape())
        assertNull(nothing.cornerRadiusPx)
        assertTrue(Looks.Classic.matches(nothing))
        assertFalse(Looks.Calm.matches(nothing))
    }

    @Test
    fun aNullCornerReadsAsTheDefaultWhenMatching() {
        val calm = resolveAppearance(Looks.Calm.shape)
        // Calm states 14px, so an unset corner (the default 18px) is not Calm.
        assertFalse(Looks.Calm.matches(calm.copy(cornerRadiusPx = null)))
        // Classic states the default, so a null corner matches it.
        val classic = resolveAppearance(Looks.Classic.shape)
        assertTrue(Looks.Classic.matches(classic.copy(cornerRadiusPx = null)))
        assertTrue(Looks.Classic.matches(classic.copy(cornerRadiusPx = DEFAULT_CORNER_RADIUS_PX)))
    }

    @Test
    fun everyLookSetsEveryPerModeFieldAndNoSharedOnes() {
        for (look in Looks.all) {
            val s = look.shape
            val missing = listOfNotNull(
                "corner".takeIf { s.cornerRadiusPx == null },
                "density".takeIf { s.uiDensity == null },
                "selection".takeIf { s.selectionStyle == null },
                "surface".takeIf { s.surfaceStyle == null },
                "chrome".takeIf { s.chromeStyle == null },
                "windows".takeIf { s.windowLayout == null },
                "focus".takeIf { s.focusStyle == null },
                "labels".takeIf { s.labelCase == null },
            )
            assertTrue(missing.isEmpty(), "${look.id} leaves unset: $missing")
            assertNull(s.navigationMode, "${look.id} must not set navigation")
            assertNull(s.shellStyle, "${look.id} must not set the legacy shorthand")
            assertEquals(s, look.shape.styleOnly())
        }
    }

    @Test
    fun theCalmLookIsTheSplitCalmBundle() {
        val c = Looks.Calm.shape
        assertEquals(14, c.cornerRadiusPx)
        assertEquals(UiDensity.Comfortable, c.uiDensity)
        assertEquals(SelectionStyle.Soft, c.selectionStyle)
        assertEquals(SurfaceStyle.Soft, c.surfaceStyle)
        assertEquals(ChromeStyle.Blended, c.chromeStyle)
        assertEquals(WindowLayout.Sheet, c.windowLayout)
        assertEquals(FocusStyle.Tone, c.focusStyle)
        assertEquals(LabelCase.Sentence, c.labelCase)
    }

    @Test
    fun theLooksAreOfferedClassicFirstWithDistinctIds() {
        assertEquals(listOf("classic", "calm"), Looks.all.map { it.id })
        assertEquals(listOf("Classic", "Calm"), Looks.all.map { it.name })
        assertEquals(Looks.Calm, Looks.forShellStyle(ShellStyle.Calm))
        assertEquals(Looks.Classic, Looks.forShellStyle(ShellStyle.Classic))
    }

    @Test
    fun toShapeRoundTripsThroughResolve() {
        for (look in Looks.all) {
            val resolved = resolveAppearance(look.shape)
            assertEquals(resolved, resolveAppearance(resolved.toShape()))
            assertEquals(look.shape, resolved.toShape())
        }
        val defaults = resolveAppearance(AppearanceShape())
        assertNull(defaults.toShape().cornerRadiusPx)
        assertEquals(defaults, resolveAppearance(defaults.toShape()))
    }

    // ── The ladder ──

    @Test
    fun theUserWinsOverEveryOtherLayer() {
        val user = AppearanceShape(focusStyle = FocusStyle.Accent, shellStyle = ShellStyle.Calm)
        val app = AppearanceShape(focusStyle = FocusStyle.Tone, shellStyle = ShellStyle.Calm)
        val r = resolveAppearance(*appearanceLadder(user, app))
        assertEquals(FocusStyle.Accent, r.focusStyle)
    }

    @Test
    fun theUsersLegacyLookWinsOverTheAppDefault() {
        val user = AppearanceShape(shellStyle = ShellStyle.Calm)
        val app = AppearanceShape(windowLayout = WindowLayout.Cards, labelCase = LabelCase.Uppercase)
        val r = resolveAppearance(*appearanceLadder(user, app))
        assertEquals(WindowLayout.Sheet, r.windowLayout)
        assertEquals(LabelCase.Sentence, r.labelCase)
        assertTrue(Looks.Calm.matches(r))
    }

    @Test
    fun theAppDefaultWinsOverTheAppsLegacyLook() {
        val app = AppearanceShape(shellStyle = ShellStyle.Calm, chromeStyle = ChromeStyle.Themed)
        val r = resolveAppearance(*appearanceLadder(AppearanceShape(), app))
        assertEquals(ChromeStyle.Themed, r.chromeStyle, "the brand's own field beats its shorthand")
        assertEquals(WindowLayout.Sheet, r.windowLayout, "the rest of the brand's look still applies")
        assertFalse(Looks.Calm.matches(r))
    }

    @Test
    fun theAppsLegacyLookWinsOverTheToolkitDefault() {
        val r = resolveAppearance(*appearanceLadder(AppearanceShape(), AppearanceShape(shellStyle = ShellStyle.Calm)))
        assertTrue(Looks.Calm.matches(r))
        assertEquals(14, r.cornerRadiusPx)
    }

    @Test
    fun theToolkitDefaultFillsWhatNoLayerNames() {
        val r = resolveAppearance(*appearanceLadder(AppearanceShape(cornerRadiusPx = 3), AppearanceShape()))
        assertEquals(3, r.cornerRadiusPx)
        assertEquals(UiDensity.Compact, r.uiDensity)
        assertEquals(SelectionStyle.Default, r.selectionStyle)
        assertEquals(SurfaceStyle.Default, r.surfaceStyle)
        assertEquals(ChromeStyle.Default, r.chromeStyle)
        assertEquals(WindowLayout.Default, r.windowLayout)
        assertEquals(FocusStyle.Default, r.focusStyle)
        assertEquals(LabelCase.Default, r.labelCase)
    }

    @Test
    fun aUserClassicShorthandUndoesABrandCalmDefault() {
        // A user who picked Classic before the split, under a brand whose
        // default is Calm: Classic's every field sits above the brand.
        val r = resolveAppearance(
            *appearanceLadder(AppearanceShape(shellStyle = ShellStyle.Classic), AppearanceShape(shellStyle = ShellStyle.Calm)),
        )
        assertTrue(Looks.Classic.matches(r))
    }

    @Test
    fun theLadderIsUserThenUserLookThenAppThenAppLook() {
        val user = AppearanceShape(cornerRadiusPx = 1, shellStyle = ShellStyle.Calm)
        val app = AppearanceShape(cornerRadiusPx = 2, shellStyle = ShellStyle.Classic)
        val ladder = appearanceLadder(user, app)
        assertEquals(listOf(user, Looks.Calm.shape, app, Looks.Classic.shape), ladder.toList())
    }

    @Test
    fun eachFieldResolvesIndependently() {
        val r = resolveAppearance(
            AppearanceShape(labelCase = LabelCase.Sentence),
            AppearanceShape(labelCase = LabelCase.Uppercase, focusStyle = FocusStyle.Tone),
            AppearanceShape(focusStyle = FocusStyle.Accent, surfaceStyle = SurfaceStyle.Soft),
        )
        assertEquals(LabelCase.Sentence, r.labelCase)
        assertEquals(FocusStyle.Tone, r.focusStyle)
        assertEquals(SurfaceStyle.Soft, r.surfaceStyle)
    }
}
