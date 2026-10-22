/*
 * SurfaceStyleTest.kt (jsTest)
 *
 * Pins how `applySurfaceStyle` expresses the Depth / Flat / Soft surface
 * setting on `documentElement`, that the light/dark depth tuning written by
 * `applyColorScheme` differs per side, and that the Depth rules switch off
 * under Soft exactly as under Flat.
 *
 * The attribute contract matters because `lunula.css` writes every depth rule
 * under `:root:not([data-dt-surface])`: Depth (and "never chosen") must leave
 * the attribute absent, and Flat and Soft each stamp their own value —
 * otherwise the default would silently be flat, or Soft would keep Depth's
 * shadows under its calm cards.
 *
 * @see se.soderbjorn.lunula.web.applySurfaceStyle
 * @see se.soderbjorn.lunula.web.depthTuningVars
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.web.applyColorScheme
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.injectLunulaStyles
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

    /** Soft stamps its own value, and Depth clears it again. */
    @Test
    fun softStampsAndDepthClears() {
        applySurfaceStyle(SurfaceStyle.Soft)
        assertEquals("soft", surfaceAttr())
        applySurfaceStyle(SurfaceStyle.Depth)
        assertNull(surfaceAttr())
    }

    /**
     * With the stylesheet loaded, a window, its titlebar and the frame paint
     * the same under Soft as under Flat (the Depth rules are off), and
     * differently under Depth.
     */
    @Test
    fun depthRulesAreOffUnderSoft() {
        injectLunulaStyles()
        val frame = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame"
            it.style.width = "400px"
            it.style.height = "300px"
            document.body!!.appendChild(it)
        }
        val pane = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-pane dt-pane-floating dt-pane-focused"
            frame.appendChild(it)
        }
        val header = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-pane-header"
            pane.appendChild(it)
        }
        fun snap(): List<String> = listOf(frame, pane, header).flatMap { el ->
            el.style.transition = "none"
            val st = window.getComputedStyle(el)
            listOf(st.boxShadow, st.backgroundImage)
        }
        try {
            applySurfaceStyle(SurfaceStyle.Flat)
            val flat = snap()
            applySurfaceStyle(SurfaceStyle.Soft)
            assertEquals(flat, snap(), "Soft must drop every Depth effect, as Flat does")
            applySurfaceStyle(SurfaceStyle.Depth)
            assertNotEquals(flat, snap(), "Depth paints something, so this test proves something")
        } finally {
            frame.remove()
        }
    }
}
