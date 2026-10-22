/* ModeStylesTest.kt
 * Covers [ModeStyles], the style settings remembered separately for dark and
 * light mode, and its codec: the `{"dark": {...}, "light": {...}}` form, the
 * shell's shape blob that carries it beside the shared top-level fields
 * ([ModeStyles.shapeBlob]), and the fall-back to the legacy single-mode blob
 * ([ModeStyles.fromJson] returning null, [ModeStyles.bothFrom]).
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-mode style pair and its codec; see the file comment for what is pinned. */
class ModeStylesTest {

    private val pair = ModeStyles(
        dark = Looks.Calm.shape,
        light = AppearanceShape(cornerRadiusPx = 0, focusStyle = FocusStyle.Tone),
    )

    @Test
    fun roundTripsThroughToJson() {
        assertEquals(pair, ModeStyles.fromJson(pair.toJson()))
        assertEquals(ModeStyles(), ModeStyles.fromJson(ModeStyles().toJson()))
    }

    @Test
    fun forModeAndWithModeTouchOnlyTheirOwnMode() {
        assertEquals(pair.dark, pair.forMode(isDark = true))
        assertEquals(pair.light, pair.forMode(isDark = false))
        val edited = pair.withMode(isDark = true, AppearanceShape(labelCase = LabelCase.Uppercase))
        assertEquals(AppearanceShape(labelCase = LabelCase.Uppercase), edited.dark)
        assertEquals(pair.light, edited.light, "editing dark must leave light alone")
    }

    @Test
    fun withModeStripsSharedFieldsAndTheShorthand() {
        val edited = ModeStyles().withMode(
            isDark = false,
            AppearanceShape(navigationMode = NavigationMode.Sidebar, shellStyle = ShellStyle.Calm, uiDensity = UiDensity.Spacious),
        )
        assertEquals(AppearanceShape(uiDensity = UiDensity.Spacious), edited.light)
    }

    @Test
    fun fromJsonIsNullForALegacyBlob() {
        // A blob written before the settings were per mode carries only
        // top-level fields: the caller must fall back to them.
        assertNull(ModeStyles.fromJson("""{"shellStyle":"clean","cornerRadiusPx":6}"""))
        assertNull(ModeStyles.fromJson(null))
        assertNull(ModeStyles.fromJson(""))
        assertNull(ModeStyles.fromJson("not json"))
        assertNull(ModeStyles.fromJson("[]"))
    }

    @Test
    fun fromJsonAcceptsOneModeAlone() {
        val decoded = assertNotNull(ModeStyles.fromJson("""{"light":{"labelCase":"sentence"}}"""))
        assertEquals(AppearanceShape(), decoded.dark)
        assertEquals(AppearanceShape(labelCase = LabelCase.Sentence), decoded.light)
    }

    @Test
    fun fromJsonIsLenientPerField() {
        val decoded = assertNotNull(
            ModeStyles.fromJson("""{"dark":{"chromeStyle":"neon","windowLayout":"sheet"},"light":42}"""),
        )
        assertEquals(AppearanceShape(windowLayout = WindowLayout.Sheet), decoded.dark)
        assertEquals(AppearanceShape(), decoded.light)
    }

    @Test
    fun bothFromSeedsBothModesWithTheStylePart() {
        val legacy = AppearanceShape(cornerRadiusPx = 4, shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.TabStrip)
        val seeded = ModeStyles.bothFrom(legacy)
        assertEquals(AppearanceShape(cornerRadiusPx = 4), seeded.dark)
        assertEquals(seeded.dark, seeded.light)
    }

    @Test
    fun shapeBlobKeepsTheLegacyTopLevelFieldsAndAddsBothModes() {
        val legacy = AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar, cornerRadiusPx = 6)
        val blob = ModeStyles.shapeBlob(legacy, pair)
        val obj = Json.parseToJsonElement(blob) as JsonObject
        assertTrue("dark" in obj && "light" in obj, blob)
        // An older build reads only the top level and gets exactly the legacy shape.
        assertEquals(legacy, AppearanceShape.fromJson(blob))
        // A current build reads the pair back.
        assertEquals(pair, ModeStyles.fromJson(blob))
    }

    @Test
    fun shapeBlobWithoutModesIsTheLegacyShapeAlone() {
        val legacy = AppearanceShape(navigationMode = NavigationMode.Both)
        val blob = ModeStyles.shapeBlob(legacy, null)
        assertEquals(legacy.toJson(), blob)
        assertNull(ModeStyles.fromJson(blob))
    }

    @Test
    fun encodeNeverWritesSharedFieldsIntoAMode() {
        val leaky = ModeStyles(dark = AppearanceShape(navigationMode = NavigationMode.Sidebar, focusStyle = FocusStyle.Tone))
        val dark = (leaky.encode()["dark"] as JsonObject)
        assertTrue("navigationMode" !in dark)
        assertTrue("focusStyle" in dark)
    }

    @Test
    fun eachModeResolvesThroughTheLadderOnItsOwn() {
        val app = AppearanceShape(labelCase = LabelCase.Sentence)
        val dark = resolveAppearance(*appearanceLadder(pair.forMode(true), app))
        val light = resolveAppearance(*appearanceLadder(pair.forMode(false), app))
        assertTrue(Looks.Calm.matches(dark))
        assertEquals(0, light.cornerRadiusPx)
        assertEquals(FocusStyle.Tone, light.focusStyle)
        assertEquals(LabelCase.Sentence, light.labelCase, "an unpicked field falls to the app default")
    }
}
