/* ThemeChromeZoneTest.kt
 * Covers the chrome/canvas zone added to [Theme]: the 8 optional tokens, their
 * fallbacks to the base zone, the derived [ResolvedTheme.chromeAccentSoft], and
 * the guarantee that a theme which sets none of them is unaffected.
 *
 * These are the invariants the split-chrome design handoff promises ("each
 * falls back to an existing base token, so every pre-existing theme is
 * unchanged — this is purely additive"), so they are worth pinning: nothing
 * else in the build would catch a regression in the fallback wiring.
 */
package se.soderbjorn.darkness.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A theme with no chrome zone — every optional token left unset. */
private val plain: Theme = builtinTheme("Lunamux Dark")!!

/** A theme that sets all 8 chrome/canvas tokens. */
private val split: Theme = builtinTheme("Obsidian Split")!!

class ThemeChromeZoneTest {

    @Test
    fun unsetChromeTokensFallBackToTheirBaseToken() {
        assertNull(plain.chromeBg, "fixture must not set the chrome zone")
        val r = plain.resolve()
        assertEquals(r.bg, r.canvas)
        assertEquals(r.bg, r.chromeBg)
        assertEquals(r.text, r.chromeText)
        assertEquals(r.textDim, r.chromeTextDim)
        assertEquals(r.textBright, r.chromeTextBright)
        assertEquals(r.border, r.chromeBorder)
        assertEquals(r.accent, r.chromeAccent)
        assertEquals(r.surfaceAlt, r.chromeTrack)
    }

    @Test
    fun setChromeTokensResolveToTheirOwnValueNotTheFallback() {
        val r = split.resolve()
        assertEquals(hexToArgb("#000000"), r.chromeBg)
        assertEquals(hexToArgb("#000000"), r.canvas)
        assertEquals(hexToArgb("#b98cff"), r.chromeAccent)
        assertEquals(hexToArgb("#14171d"), r.chromeTrack)
        // The whole point of the split: chrome is NOT the content background.
        assertTrue(r.chromeBg != r.bg)
        assertTrue(r.canvas != r.bg)
    }

    @Test
    fun chromeAccentSoftIsTheChromeAccentAt16Percent() {
        assertEquals(withAlpha(split.resolve().chromeAccent, 0.16), split.resolve().chromeAccentSoft)
        // A theme without a chrome accent derives the soft tint from `accent`.
        assertEquals(withAlpha(plain.resolve().accent, 0.16), plain.resolve().chromeAccentSoft)
    }

    @Test
    fun tokenReportsTheEffectiveValueAndWithTokenPinsAnExplicitOne() {
        // `token` resolves the fallback, so the editor always shows a colour.
        assertEquals(plain.bg, plain.token("chromeBg"))
        assertEquals(plain.surfaceAlt, plain.token("chromeTrack"))
        assertEquals("#000000", split.token("chromeBg"))

        val pinned = plain.withToken("chromeBg", "#123456")
        assertEquals("#123456", pinned.chromeBg)
        assertEquals("#123456", pinned.token("chromeBg"))
        assertEquals(plain.bg, pinned.bg, "pinning a chrome token must not touch the base zone")
    }

    @Test
    fun everyTokenIdRoundTripsThroughWithToken() {
        // Guards the three lists that must stay in lockstep: a token id present
        // in TOKEN_IDS but missing a `when` branch in `token`/`withToken` fails
        // open (returns `bg` / discards the edit) rather than erroring.
        for (id in Theme.TOKEN_IDS) {
            assertEquals("#010203", plain.withToken(id, "#010203").token(id), "token id: $id")
        }
    }

    @Test
    fun aThemeWithoutAChromeZoneSerializesWithoutTheChromeKeys() {
        // @EncodeDefault(NEVER) keeps `themes.json` byte-identical for themes
        // that don't split their chrome — which matters because the file is
        // shared with other (possibly older) Darkness apps.
        val json = Json.encodeToString(Theme.serializer(), plain)
        assertFalse(json.contains("chromeBg"), "unset chrome tokens must not be encoded: $json")
        assertFalse(json.contains("canvas"))
        assertEquals(plain, Json.decodeFromString(Theme.serializer(), json))
    }

    @Test
    fun aSplitThemeRoundTripsThroughSerialization() {
        val json = Json.encodeToString(Theme.serializer(), split)
        assertTrue(json.contains("chromeBg"))
        assertEquals(split, Json.decodeFromString(Theme.serializer(), json))
    }

    @Test
    fun onlyTheSplitThemesDeclareAChromeZone() {
        val withChrome = builtinThemes.filter { it.chromeBg != null }.map { it.name }
        assertEquals(
            listOf(
                "Obsidian Split", "Graphite Split", "Lunamux Split", "Termtastic Split",
                "Crimson Split", "Ember Split", "Nord Split", "Solarized Split", "Sandstone Split",
            ),
            withChrome,
        )
        // Every split theme sets the zone completely — a half-set zone would
        // silently mix chrome and content colours.
        for (t in builtinThemes.filter { it.chromeBg != null }) {
            for (id in listOf(
                "canvas", "chromeBg", "chromeText", "chromeTextDim", "chromeTextBright",
                "chromeBorder", "chromeAccent", "chromeTrack",
            )) {
                assertTrue(t.token(id).startsWith("#"), "${t.name} must set $id")
            }
            assertTrue(t.chromeText != null && t.chromeTrack != null, "${t.name} sets the full zone")
        }
    }

    @Test
    fun builtinThemeNamesAreUnique() {
        val names = builtinThemes.map { it.name }
        assertEquals(names.size, names.toSet().size, "theme names are the identity key")
        assertEquals(73, names.size)
    }
}
