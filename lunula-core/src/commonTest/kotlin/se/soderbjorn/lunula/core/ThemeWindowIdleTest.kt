/* ThemeWindowIdleTest.kt
 * Covers the optional `windowIdle` token added to [Theme] for the Clean shell
 * (LNA-18): its tone-aware derivation from `surface` when unset, the declared
 * value winning when set, its editor wiring through [Theme.token] /
 * [Theme.withToken], and its absence from the serialized form of a theme that
 * leaves it out (themes.json is shared between Lunula apps).
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A built-in with no declared windowIdle, used as the base for fixtures. */
private val base: Theme = builtinTheme("Lunamux Dark")!!

/**
 * Asserts each RGB channel of [actual] is within 1 of [expected] — the
 * examples in the ticket are approximate, and `mix` rounds per channel.
 */
private fun assertNear(expected: String, actual: Long) {
    val e = hexToArgb(expected)
    for (shift in listOf(16, 8, 0)) {
        val d = (((e shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
        assertTrue(d in -1..1, "expected about $expected, got ${argbToHex(actual)}")
    }
}

class ThemeWindowIdleTest {

    @Test
    fun aLightThemeDerivesWindowIdleBySinkingSurfaceTowardBlack() {
        val light = base.copy(bg = "#ffffff", surface = "#ffffff")
        assertFalse(light.isDarkToned, "fixture must be light-toned")
        val idle = light.resolve().windowIdle
        assertEquals("#f2f2f2", argbToHex(idle))
        assertNear("#f3f3f3", idle)
    }

    @Test
    fun aDarkThemeDerivesWindowIdleByMixingSurfaceTowardBg() {
        val dark = base.copy(bg = "#0d0f10", surface = "#1a1d20")
        assertTrue(dark.isDarkToned, "fixture must be dark-toned")
        val idle = dark.resolve().windowIdle
        assertEquals("#131517", argbToHex(idle))
        assertNear("#121416", idle)
    }

    @Test
    fun aDeclaredWindowIdleWinsOverTheDerivation() {
        val pinned = base.copy(windowIdle = "#123456")
        assertEquals(hexToArgb("#123456"), pinned.resolve().windowIdle)
        assertEquals("#123456", pinned.token("windowIdle"))
    }

    @Test
    fun theEditorSeesTheEffectiveValueAndCanPinIt() {
        assertNull(base.windowIdle, "fixture must not declare windowIdle")
        assertTrue("windowIdle" in Theme.TOKEN_IDS)
        assertEquals(base.effectiveWindowIdle, base.token("windowIdle"))
        val edited = base.withToken("windowIdle", "#010203")
        assertEquals("#010203", edited.windowIdle)
        assertEquals(base.surface, edited.surface, "pinning windowIdle must not touch surface")
    }

    @Test
    fun windowIdleIsOmittedFromSerializationUnlessDeclared() {
        val plainJson = Json.encodeToString(Theme.serializer(), base)
        assertFalse(plainJson.contains("windowIdle"), "unset windowIdle must not be encoded")
        assertEquals(base, Json.decodeFromString(Theme.serializer(), plainJson))

        val custom = base.copy(name = "My Theme", windowIdle = "#abcdef")
        val customJson = Json.encodeToString(Theme.serializer(), custom)
        assertTrue(customJson.contains("\"windowIdle\":\"#abcdef\""), customJson)
        assertEquals(custom, Json.decodeFromString(Theme.serializer(), customJson))
    }

    @Test
    fun customThemesRoundTripThroughTheSnapshot() {
        val custom = base.copy(name = "My Theme", windowIdle = "#abcdef")
        val snap = ThemeSnapshotV2(customThemes = listOf(custom))
        val back = ThemeSnapshotV2.fromStrings(null, snap.customThemesJson())
        assertEquals(listOf(custom), back.customThemes)
    }
}
