/* AppearanceShapeTest.kt
 * Round-trip and leniency for the shell's shape/density/selection preferences.
 *
 * These three settings are persisted as one hand-editable JSON blob that a
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
        )
        assertEquals(shape, AppearanceShape.fromJson(shape.toJson()))
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
    }
}
