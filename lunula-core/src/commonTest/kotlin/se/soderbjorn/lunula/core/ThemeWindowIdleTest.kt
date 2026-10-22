/* ThemeWindowIdleTest.kt
 * Covers the optional `windowIdle` token added to [Theme] for the Calm look
 * (LNA-18): its tone-aware derivation from `surface` when unset, the declared
 * value winning when set, its editor wiring through [Theme.token] /
 * [Theme.withToken], and its absence from the serialized form of a theme that
 * leaves it out (themes.json is shared between Lunula apps). Also covers the
 * derived focused-window ring that goes with it ([Theme.effectiveWindowRing]),
 * and the focused window's fill under the Tone active-window setting
 * ([Theme.effectiveWindowActive] / [ResolvedTheme.windowActive]): lifted from
 * `surface` toward `text` until it stands [Theme.WINDOW_TONE_MIN_CONTRAST]
 * apart from the idle fill, but only for a theme that leaves `windowIdle`
 * undeclared; a theme that declares it keeps `surface` exactly.
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
        assertEquals("#ececec", argbToHex(idle))
        assertNear("#ececec", idle)
    }

    @Test
    fun aDarkThemeDerivesWindowIdleByMixingSurfaceTowardBg() {
        val dark = base.copy(bg = "#0d0f10", surface = "#1a1d20")
        assertTrue(dark.isDarkToned, "fixture must be dark-toned")
        val idle = dark.resolve().windowIdle
        assertEquals("#101214", argbToHex(idle))
        assertNear("#101213", idle)
        // Never sinks below the ground: still at least as light as bg.
        assertTrue(luminance(idle) >= luminance(hexToArgb(dark.bg)), "idle darker than bg")
    }

    @Test
    fun theWindowRingIsBorderNudgedTowardText() {
        val light = builtinTheme("Lunicle Calm Light")!!
        assertEquals("#d5d5d1", light.effectiveWindowRing)
        assertEquals(hexToArgb("#d5d5d1"), light.resolve().windowRing)
        // Always a step from the border toward the text, on either tone.
        for (t in listOf(light, builtinTheme("Lunicle Calm Dark")!!)) {
            val ring = t.resolve().windowRing
            val border = hexToArgb(t.border)
            val text = hexToArgb(t.text)
            assertTrue(contrastRatio(ring, text) < contrastRatio(border, text), "${t.name}: ring must lean toward text")
        }
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

    // ── windowActive ──

    @Test
    fun everyUndeclaredDarkBuiltinsActiveWindowStandsApartFromIdle() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (t in builtinThemes.filter { it.windowIdle == null && it.isDarkToned }) {
            val r = t.resolve()
            val c = contrastRatio(r.windowActive, r.windowIdle)
            if (c < Theme.WINDOW_TONE_MIN_CONTRAST) {
                failures += "${t.name}: windowActive ${argbToHex(r.windowActive)} vs idle ${argbToHex(r.windowIdle)} is $c"
            }
            checked++
        }
        assertTrue(checked > 0, "fixture: some dark built-ins must leave windowIdle undeclared")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun aDeclaredWindowIdleKeepsTheActiveWindowAtSurface() {
        val declared = builtinThemes.filter { it.windowIdle != null }
        assertTrue(declared.isNotEmpty(), "fixture: the Calm themes declare windowIdle")
        for (t in declared) {
            assertEquals(t.surface, t.effectiveWindowActive, t.name)
            assertEquals(hexToArgb(t.surface), t.resolve().windowActive, t.name)
        }
        // Even when the declared pair is identical: hand-tuned means untouched.
        val same = base.copy(windowIdle = base.surface)
        assertEquals(base.surface, same.effectiveWindowActive)
    }

    @Test
    fun lightBuiltinsKeepTheActiveWindowAtSurface() {
        val light = builtinThemes.filter { !it.isDarkToned }
        assertTrue(light.isNotEmpty())
        for (t in light) {
            assertEquals(hexToArgb(t.surface), t.resolve().windowActive, t.name)
        }
    }

    @Test
    fun theLiftGoesTowardTextAndOnlyAsFarAsNeeded() {
        // Lunamux Classic Dark: surface and derived idle nearly identical.
        val t = builtinTheme("Lunamux Classic Dark") ?: base.copy(bg = "#08110c", surface = "#0c1611")
        val r = t.resolve()
        val surface = hexToArgb(t.surface)
        assertTrue(r.windowActive != surface, "a too-close pair must be lifted")
        assertTrue(
            contrastRatio(r.windowActive, hexToArgb(t.text)) < contrastRatio(surface, hexToArgb(t.text)),
            "the lift must lean toward text",
        )
        // The step before the result still fell short.
        val step = Theme.WINDOW_TONE_STEP
        val k = (1..kotlin.math.round(1 / step).toInt())
            .first { mix(hexToArgb(t.text), surface, it * step) == r.windowActive }
        val previous = mix(hexToArgb(t.text), surface, (k - 1) * step)
        assertTrue(contrastRatio(previous, r.windowIdle) < Theme.WINDOW_TONE_MIN_CONTRAST, "the step before must still fail")
    }

    @Test
    fun aDarkPairAlreadyApartKeepsSurface() {
        val dark = base.copy(bg = "#000000", surface = "#3a3a3a")
        assertTrue(dark.isDarkToned)
        val r = dark.resolve()
        if (contrastRatio(hexToArgb(dark.surface), r.windowIdle) >= Theme.WINDOW_TONE_MIN_CONTRAST) {
            assertEquals(dark.surface, dark.effectiveWindowActive)
        }
    }
}
