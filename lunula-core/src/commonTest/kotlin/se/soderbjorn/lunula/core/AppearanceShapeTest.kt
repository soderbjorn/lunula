/* AppearanceShapeTest.kt
 * Round-trip and leniency for the shell's shape/density/selection/surface/
 * chrome/windows/focus/labels/navigation preferences, the legacy `shellStyle`
 * look shorthand ([AppearanceShape.lookLayer], [AppearanceShape.withLookExpanded]),
 * and the split between the shared part and the per-mode part of a shape
 * ([AppearanceShape.sharedPart], [AppearanceShape.styleOnly]).
 *
 * These settings are persisted as one hand-editable JSON blob that a
 * deployment's brand manifest can also seed, so the decoder's failure
 * behaviour is part of its contract rather than an implementation detail: a
 * value this build doesn't recognise must cost the user that ONE setting and
 * nothing else. The tests below pin that field-by-field independence, since
 * it is exactly what a naive "parse or return default" decoder gets wrong.
 */
package se.soderbjorn.lunula.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearanceShapeTest {

    @Test
    fun roundTripsEveryField() {
        val shape = AppearanceShape(
            cornerRadiusPx = 4,
            uiDensity = UiDensity.Spacious,
            selectionStyle = SelectionStyle.Fill,
            surfaceStyle = SurfaceStyle.Flat,
            shellStyle = ShellStyle.Calm,
            navigationMode = NavigationMode.Both,
            chromeStyle = ChromeStyle.Blended,
            windowLayout = WindowLayout.Sheet,
            focusStyle = FocusStyle.Tone,
            labelCase = LabelCase.Sentence,
        )
        assertEquals(shape, AppearanceShape.fromJson(shape.toJson()))
    }

    @Test
    fun theStyleSettingsRoundTripEveryValue() {
        for (c in ChromeStyle.entries) for (w in WindowLayout.entries) for (f in FocusStyle.entries) for (l in LabelCase.entries) {
            val shape = AppearanceShape(chromeStyle = c, windowLayout = w, focusStyle = f, labelCase = l)
            assertTrue(!shape.isEmpty)
            assertEquals(shape, AppearanceShape.fromJson(shape.toJson()), "$c/$w/$f/$l")
        }
        for (sel in SelectionStyle.entries) for (surf in SurfaceStyle.entries) {
            val shape = AppearanceShape(selectionStyle = sel, surfaceStyle = surf)
            assertEquals(shape, AppearanceShape.fromJson(shape.toJson()), "$sel/$surf")
        }
        assertEquals(
            "{\"chromeStyle\":\"blended\",\"windowLayout\":\"sheet\",\"focusStyle\":\"tone\",\"labelCase\":\"sentence\"}",
            AppearanceShape(
                chromeStyle = ChromeStyle.Blended,
                windowLayout = WindowLayout.Sheet,
                focusStyle = FocusStyle.Tone,
                labelCase = LabelCase.Sentence,
            ).toJson(),
        )
    }

    @Test
    fun eachStyleSettingAloneMakesTheShapeNonEmpty() {
        assertTrue(!AppearanceShape(chromeStyle = ChromeStyle.Themed).isEmpty)
        assertTrue(!AppearanceShape(windowLayout = WindowLayout.Cards).isEmpty)
        assertTrue(!AppearanceShape(focusStyle = FocusStyle.Accent).isEmpty)
        assertTrue(!AppearanceShape(labelCase = LabelCase.Uppercase).isEmpty)
    }

    @Test
    fun anUnknownStyleSettingCostsOnlyItsOwnField() {
        val decoded = AppearanceShape.fromJson(
            """{"chromeStyle":"glossy","windowLayout":"sheet","focusStyle":7,"labelCase":"sentence"}"""
        )
        assertNull(decoded.chromeStyle)
        assertNull(decoded.focusStyle)
        assertEquals(WindowLayout.Sheet, decoded.windowLayout)
        assertEquals(LabelCase.Sentence, decoded.labelCase)
    }

    @Test
    fun theStyleSettingsParseBothSpellings() {
        assertEquals(ChromeStyle.Blended, ChromeStyle.fromRaw(" Blended "))
        assertEquals(WindowLayout.Sheet, WindowLayout.fromRaw("SHEET"))
        assertEquals(FocusStyle.Tone, FocusStyle.fromRaw("tone"))
        assertEquals(LabelCase.Sentence, LabelCase.fromRaw("Sentence"))
        assertEquals(SelectionStyle.Soft, SelectionStyle.fromRaw("soft"))
        assertEquals(SurfaceStyle.Soft, SurfaceStyle.fromRaw("Soft"))
        assertNull(ChromeStyle.fromRaw(""))
        assertNull(WindowLayout.fromRaw(null))
    }

    @Test
    fun theStyleSettingDefaultsAreTheClassicShell() {
        assertEquals(ChromeStyle.Themed, ChromeStyle.Default)
        assertEquals(WindowLayout.Cards, WindowLayout.Default)
        assertEquals(FocusStyle.Accent, FocusStyle.Default)
        assertEquals(LabelCase.Uppercase, LabelCase.Default)
    }

    // ── The legacy look shorthand ──

    @Test
    fun aLegacyShellStyleStandsForTheMatchingLook() {
        assertEquals(Looks.Calm.shape, AppearanceShape(shellStyle = ShellStyle.Calm).lookLayer())
        assertEquals(Looks.Classic.shape, AppearanceShape(shellStyle = ShellStyle.Classic).lookLayer())
        assertTrue(AppearanceShape().lookLayer().isEmpty, "no shorthand, no layer")
        // The layer is the look alone: the shape's own fields are not in it.
        assertEquals(
            Looks.Calm.shape,
            AppearanceShape(shellStyle = ShellStyle.Calm, cornerRadiusPx = 2).lookLayer(),
        )
    }

    @Test
    fun aLegacyCleanBlobResolvesToTheWholeCalmLook() {
        // A blob saved by a build that only had the single switch, before the
        // preset was renamed from "Clean" to "Calm".
        val legacy = AppearanceShape.fromJson("""{"shellStyle":"clean"}""")
        assertEquals(ShellStyle.Calm, legacy.shellStyle)
        assertTrue(Looks.Calm.matches(resolveAppearance(*appearanceLadder(legacy, AppearanceShape()))))
    }

    @Test
    fun aCalmBlobResolvesToTheWholeCalmLook() {
        val shape = AppearanceShape.fromJson("""{"shellStyle":"calm"}""")
        assertEquals(ShellStyle.Calm, shape.shellStyle)
        assertTrue(Looks.Calm.matches(resolveAppearance(*appearanceLadder(shape, AppearanceShape()))))
    }

    @Test
    fun theOldCleanSpellingIsReadAsCalmButNeverWritten() {
        assertEquals("calm", ShellStyle.Calm.cssValue)
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("clean"))
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("calm"))
        // Re-saving a legacy blob writes the new spelling.
        assertEquals(
            """{"shellStyle":"calm"}""",
            AppearanceShape.fromJson("""{"shellStyle":"clean"}""").toJson(),
        )
    }

    @Test
    fun withLookExpandedFoldsTheShorthandIntoUnsetFields() {
        val shape = AppearanceShape(
            shellStyle = ShellStyle.Calm,
            cornerRadiusPx = 4,
            labelCase = LabelCase.Uppercase,
            navigationMode = NavigationMode.Sidebar,
        )
        val expanded = shape.withLookExpanded()
        assertNull(expanded.shellStyle, "the shorthand itself is cleared")
        assertEquals(4, expanded.cornerRadiusPx, "the shape's own field wins over the look")
        assertEquals(LabelCase.Uppercase, expanded.labelCase)
        assertEquals(NavigationMode.Sidebar, expanded.navigationMode, "navigation is carried as-is")
        assertEquals(Looks.Calm.shape.uiDensity, expanded.uiDensity)
        assertEquals(Looks.Calm.shape.selectionStyle, expanded.selectionStyle)
        assertEquals(Looks.Calm.shape.surfaceStyle, expanded.surfaceStyle)
        assertEquals(Looks.Calm.shape.chromeStyle, expanded.chromeStyle)
        assertEquals(Looks.Calm.shape.windowLayout, expanded.windowLayout)
        assertEquals(Looks.Calm.shape.focusStyle, expanded.focusStyle)
        // And it paints exactly what the unexpanded shape resolved to.
        assertEquals(
            resolveAppearance(*appearanceLadder(shape, AppearanceShape())),
            resolveAppearance(expanded),
        )
    }

    @Test
    fun withLookExpandedWithoutAShorthandChangesNothing() {
        val shape = AppearanceShape(cornerRadiusPx = 9, focusStyle = FocusStyle.Tone)
        assertEquals(shape, shape.withLookExpanded())
    }

    @Test
    fun sharedPartKeepsOnlyNavigation() {
        val shape = Looks.Calm.shape.copy(navigationMode = NavigationMode.TabStrip, shellStyle = ShellStyle.Calm)
        assertEquals(AppearanceShape(navigationMode = NavigationMode.TabStrip), shape.sharedPart())
    }

    @Test
    fun styleOnlyDropsNavigationAndTheShorthand() {
        val shape = Looks.Calm.shape.copy(navigationMode = NavigationMode.TabStrip, shellStyle = ShellStyle.Calm)
        assertEquals(Looks.Calm.shape, shape.styleOnly())
    }

    @Test
    fun unsetFieldsAreOmittedRatherThanWrittenAsNull() {
        // Omission, not an explicit null: the blob then means "here is what the
        // user chose", so a field added in a later version isn't shadowed by a
        // stale null an older build wrote.
        val json = AppearanceShape(cornerRadiusPx = 0).toJson()
        assertTrue("cornerRadiusPx" in json)
        assertTrue("uiDensity" !in json, "unset density must not appear: $json")
        assertTrue("selectionStyle" !in json, "unset selection must not appear: $json")
        assertTrue("surfaceStyle" !in json, "unset surface style must not appear: $json")
        assertTrue("shellStyle" !in json, "unset shell style must not appear: $json")
        assertTrue("navigationMode" !in json, "unset navigation mode must not appear: $json")
    }

    @Test
    fun zeroRadiusSurvivesAsARealChoice() {
        // Square corners are a choice, and have to stay distinguishable from
        // "never picked" — otherwise a future change to the default silently
        // un-squares every user who set 0.
        val decoded = AppearanceShape.fromJson(AppearanceShape(cornerRadiusPx = 0).toJson())
        assertEquals(0, decoded.cornerRadiusPx)
        assertTrue(!decoded.isEmpty)
    }

    @Test
    fun anUnknownValueCostsOnlyItsOwnField() {
        // The headline behaviour. A density this build doesn't have must not
        // also drop the radius and the selection style beside it.
        val decoded = AppearanceShape.fromJson(
            """{"cornerRadiusPx":12,"uiDensity":"enormous","selectionStyle":"fill"}"""
        )
        assertEquals(12, decoded.cornerRadiusPx)
        assertNull(decoded.uiDensity, "an unrecognised density degrades to unset")
        assertEquals(SelectionStyle.Fill, decoded.selectionStyle, "its neighbours survive")
    }

    @Test
    fun outOfRangeRadiusIsRejectedWithoutLosingTheRest() {
        val decoded = AppearanceShape.fromJson(
            """{"cornerRadiusPx":9999,"uiDensity":"comfortable"}"""
        )
        assertNull(decoded.cornerRadiusPx)
        assertEquals(UiDensity.Comfortable, decoded.uiDensity)
    }

    @Test
    fun malformedOrAbsentInputDecodesToEmpty() {
        for (raw in listOf(null, "", "   ", "not json", "[]", "{")) {
            assertTrue(
                AppearanceShape.fromJson(raw).isEmpty,
                "input ${raw?.let { "\"$it\"" } ?: "null"} should decode to empty",
            )
        }
    }

    @Test
    fun bothSpellingsOfEachEnumAreAccepted() {
        // The cssValue is what we write; the enum name is what a human editing
        // a brand.json by hand is likely to type.
        assertEquals(UiDensity.Comfortable, UiDensity.fromRaw("comfortable"))
        assertEquals(UiDensity.Comfortable, UiDensity.fromRaw("Comfortable"))
        assertEquals(SelectionStyle.Fill, SelectionStyle.fromRaw("fill"))
        assertEquals(SelectionStyle.Fill, SelectionStyle.fromRaw("Fill"))
        assertNull(SelectionStyle.fromRaw("solid"))
        assertNull(UiDensity.fromRaw(null))
        assertEquals(SurfaceStyle.Flat, SurfaceStyle.fromRaw("flat"))
        assertEquals(SurfaceStyle.Depth, SurfaceStyle.fromRaw("Depth"))
        assertNull(SurfaceStyle.fromRaw("glass"))
    }

    @Test
    fun surfaceStyleAloneMakesTheShapeNonEmptyAndRoundTrips() {
        // Flat is the opt-out from the Depth default, so a shape carrying only
        // it must persist — an `isEmpty` that ignored it would let a caller
        // skip the write and hand the user Depth back on the next launch.
        val shape = AppearanceShape(surfaceStyle = SurfaceStyle.Flat)
        assertTrue(!shape.isEmpty)
        assertEquals("{\"surfaceStyle\":\"flat\"}", shape.toJson())
        assertEquals(SurfaceStyle.Flat, AppearanceShape.fromJson(shape.toJson()).surfaceStyle)
    }

    @Test
    fun anUnknownSurfaceStyleCostsOnlyItsOwnField() {
        val decoded = AppearanceShape.fromJson(
            """{"surfaceStyle":"glass","uiDensity":"spacious"}"""
        )
        assertNull(decoded.surfaceStyle, "an unrecognised surface style degrades to unset")
        assertEquals(UiDensity.Spacious, decoded.uiDensity)
    }

    @Test
    fun theDefaultSurfaceStyleIsDepth() {
        // Depth is the toolkit default; Flat is the opt-out. Unset must read as Depth.
        assertEquals(SurfaceStyle.Depth, SurfaceStyle.Default)
    }

    @Test
    fun shellStyleAndNavigationModeRoundTripEveryValue() {
        for (shell in ShellStyle.entries) for (nav in NavigationMode.entries) {
            val shape = AppearanceShape(shellStyle = shell, navigationMode = nav)
            assertTrue(!shape.isEmpty, "$shell/$nav must make the shape non-empty")
            assertEquals(shape, AppearanceShape.fromJson(shape.toJson()))
        }
        assertEquals(
            "{\"shellStyle\":\"calm\",\"navigationMode\":\"tabstrip\"}",
            AppearanceShape(
                shellStyle = ShellStyle.Calm,
                navigationMode = NavigationMode.TabStrip,
            ).toJson(),
        )
    }

    @Test
    fun eachNewFieldAloneMakesTheShapeNonEmpty() {
        // A shape carrying only one of these must still be written, or the
        // user's pick would be dropped and the default handed back next launch.
        assertTrue(!AppearanceShape(shellStyle = ShellStyle.Classic).isEmpty)
        assertTrue(!AppearanceShape(navigationMode = NavigationMode.Sidebar).isEmpty)
    }

    @Test
    fun unknownShellStyleOrNavigationModeCostsOnlyItsOwnField() {
        val decoded = AppearanceShape.fromJson(
            """{"shellStyle":"brutalist","navigationMode":"ribbon",""" +
                """"surfaceStyle":"flat","cornerRadiusPx":6}"""
        )
        assertNull(decoded.shellStyle, "an unrecognised shell style degrades to unset")
        assertNull(decoded.navigationMode, "an unrecognised navigation mode degrades to unset")
        assertEquals(SurfaceStyle.Flat, decoded.surfaceStyle, "its neighbours survive")
        assertEquals(6, decoded.cornerRadiusPx)

        val mixed = AppearanceShape.fromJson("""{"shellStyle":"calm","navigationMode":42}""")
        assertEquals(ShellStyle.Calm, mixed.shellStyle)
        assertNull(mixed.navigationMode)
    }

    @Test
    fun legacyJsonWithoutTheNewFieldsStillDecodes() {
        // Blobs written before shell style / navigation mode existed must keep
        // every setting they carry, and read the new two as unset.
        val decoded = AppearanceShape.fromJson(
            """{"cornerRadiusPx":10,"uiDensity":"spacious","selectionStyle":"tint",""" +
                """"surfaceStyle":"flat"}"""
        )
        assertEquals(
            AppearanceShape(
                cornerRadiusPx = 10,
                uiDensity = UiDensity.Spacious,
                selectionStyle = SelectionStyle.Tint,
                surfaceStyle = SurfaceStyle.Flat,
            ),
            decoded,
        )
        assertNull(decoded.shellStyle)
        assertNull(decoded.navigationMode)
    }

    @Test
    fun shellStyleFromRawIsLenient() {
        assertEquals(ShellStyle.Classic, ShellStyle.fromRaw("classic"))
        assertEquals(ShellStyle.Classic, ShellStyle.fromRaw("Classic"))
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("calm"))
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("  CALM \n"))
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("Calm"))
        // The legacy spelling, in either case, is still the Calm look.
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("  CLEAN \n"))
        assertEquals(ShellStyle.Calm, ShellStyle.fromRaw("Clean"))
        assertNull(ShellStyle.fromRaw("minimal"))
        assertNull(ShellStyle.fromRaw(""))
        assertNull(ShellStyle.fromRaw("   "))
        assertNull(ShellStyle.fromRaw(null))
    }

    @Test
    fun navigationModeFromRawIsLenient() {
        assertEquals(NavigationMode.Sidebar, NavigationMode.fromRaw("sidebar"))
        assertEquals(NavigationMode.Sidebar, NavigationMode.fromRaw(" Sidebar "))
        assertEquals(NavigationMode.TabStrip, NavigationMode.fromRaw("tabstrip"))
        assertEquals(NavigationMode.TabStrip, NavigationMode.fromRaw("TabStrip"))
        assertEquals(NavigationMode.TabStrip, NavigationMode.fromRaw("tab-strip"))
        assertEquals(NavigationMode.TabStrip, NavigationMode.fromRaw("Tab_Strip"))
        assertEquals(NavigationMode.Both, NavigationMode.fromRaw("BOTH"))
        assertNull(NavigationMode.fromRaw("tabs"))
        assertNull(NavigationMode.fromRaw("tab strip"))
        assertNull(NavigationMode.fromRaw(""))
        assertNull(NavigationMode.fromRaw(null))
    }

    @Test
    fun theDefaultsAreClassicAndBoth() {
        // Classic and Both are the defaults and must stay so: unset must read
        // as the shell users already have, which draws the sidebar AND the
        // tab strip.
        assertEquals(ShellStyle.Classic, ShellStyle.Default)
        assertEquals(NavigationMode.Both, NavigationMode.Default)
    }

    @Test
    fun navigationModeReportsWhichSurfacesAreShown() {
        assertTrue(NavigationMode.Sidebar.showsSidebar)
        assertTrue(!NavigationMode.Sidebar.showsTabStrip)
        assertTrue(!NavigationMode.TabStrip.showsSidebar)
        assertTrue(NavigationMode.TabStrip.showsTabStrip)
        assertTrue(NavigationMode.Both.showsSidebar)
        assertTrue(NavigationMode.Both.showsTabStrip)
    }
}
