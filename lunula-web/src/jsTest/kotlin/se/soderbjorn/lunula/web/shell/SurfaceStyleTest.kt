/*
 * SurfaceStyleTest.kt (jsTest)
 *
 * Pins how `applySurfaceStyle` expresses the Depth / Flat surface setting on
 * `documentElement`, and that the light/dark depth tuning written by
 * `applyColorScheme` differs per side.
 *
 * The attribute contract matters because `lunula.css` writes every depth rule
 * under `:root:not([data-dt-surface="flat"])`: Depth (and "never chosen") must
 * leave the attribute absent, and only Flat may stamp it — otherwise the
 * default would silently be flat, or Flat would not look like the old shell.
 *
 * @see se.soderbjorn.lunula.web.applySurfaceStyle
 * @see se.soderbjorn.lunula.web.depthTuningVars
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.web.applyColorScheme
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.depthTuningVars
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The stamped attribute on `documentElement`, or `null` when absent. */
private fun surfaceAttr(): String? =
    (document.documentElement as HTMLElement).getAttribute("data-dt-surface")

class SurfaceStyleTest {

    /** Leave `documentElement` as a sibling test expects to find it. */
    @AfterTest
    fun reset() {
        applySurfaceStyle(null)
    }

    /** `null` means "nobody chose" and must paint Depth — no attribute. */
    @Test
    fun unsetPaintsDepth() {
        assertEquals(SurfaceStyle.Depth, SurfaceStyle.Default)
        applySurfaceStyle(SurfaceStyle.Flat)
        applySurfaceStyle(null)
        assertNull(surfaceAttr(), "an unset surface style must leave the attribute off (Depth)")
    }

    /** Flat is the only value that stamps the attribute. */
    @Test
    fun flatStampsAndDepthClears() {
        applySurfaceStyle(SurfaceStyle.Flat)
        assertEquals("flat", surfaceAttr())
        applySurfaceStyle(SurfaceStyle.Depth)
        assertNull(surfaceAttr(), "Depth is expressed by the attribute's absence")
    }

    /** Every tuning token is written for both sides, with different values. */
    @Test
    fun depthTuningDiffersBetweenLightAndDark() {
        val dark = depthTuningVars(isDark = true)
        val light = depthTuningVars(isDark = false)
        assertEquals(dark.keys, light.keys)
        for (key in dark.keys) {
            assertNotEquals(dark[key], light[key], "$key should be tuned per side")
        }
        val el = document.createElement("div") as HTMLElement
        applyColorScheme(el, isDark = true)
        assertEquals("2.6", el.style.getPropertyValue("--dt-depth-strength"))
        applyColorScheme(el, isDark = false)
        assertEquals("1", el.style.getPropertyValue("--dt-depth-strength"))
    }
}
