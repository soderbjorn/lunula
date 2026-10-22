/* CalmSidebarStyleTest.kt
 * Browser tests for the sidebar tree under the style settings that used to be
 * the Calm look (epic LNA-16, LNA-21): Blended chrome's "Sidebar tree"
 * rules, Sentence labels' header casing, and Soft selection's active row, at
 * the end of `lunula.css`.
 *
 * A real left sidebar is rendered with the toolkit's own factories
 * ([renderLeftSidebar], [renderSidebarSection], [SidebarRow]) inside a
 * `.dt-app-frame`, with a palette whose every colour is distinct written onto
 * :root, so a computed colour names exactly which token painted it. The tree
 * holds an active tab (an active and an idle window row) and an inactive tab
 * (one row), plus a footer.
 *
 * Checked:
 *
 *  - The defaults are untouched: under every selection language and surface
 *    style, the sidebar's computed styles are what the pre-split appliers
 *    painted, before and after a round trip through the whole Calm look.
 *  - Blended alone paints the tree as a quiet outline: 30px rows indented
 *    16px, rows under the active tab in text and under other tabs in the
 *    legible dim, headers (active tab in text at 650, others dim at 500), and
 *    a hairline footer.
 *  - Sentence labels alone set the headers' casing and size.
 *  - Soft selection alone makes the active row the one filled thing (solid
 *    accent-soft, contrast-safe accent text at 600, no ring or sheen), under
 *    either chrome and every surface style.
 *  - The whole Calm look combines them.
 *
 * @see se.soderbjorn.lunula.web.applyAppearance
 * @see CalmShellStyleTest
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.resolveAppearance
import se.soderbjorn.lunula.web.applyAppearance
import se.soderbjorn.lunula.web.applyChromeStyle
import se.soderbjorn.lunula.web.applyCornerRadiusPx
import se.soderbjorn.lunula.web.applyCssVars
import se.soderbjorn.lunula.web.applyLabelCase
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.applyUiDensity
import se.soderbjorn.lunula.web.clearCssVars
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A palette whose every colour is distinct, written as `rgb()` because that
 * is how `getComputedStyle` reports colours back. The chrome tokens are set
 * too, so a default snapshot differs from Blended wherever Blended remaps them.
 */
private val SIDEBAR_PALETTE = mapOf(
    "--t-bg" to "rgb(10, 11, 12)",
    "--t-chrome-bg" to "rgb(16, 17, 18)",
    "--t-chrome-text" to "rgb(40, 41, 42)",
    "--t-chrome-text-dim" to "rgb(50, 51, 52)",
    "--t-chrome-accent" to "rgb(60, 61, 62)",
    "--t-chrome-accent-soft" to "rgb(64, 65, 66)",
    "--t-chrome-border" to "rgb(68, 69, 70)",
    "--t-surface" to "rgb(200, 201, 202)",
    "--t-border" to "rgb(100, 101, 102)",
    "--t-text" to "rgb(20, 21, 22)",
    "--t-text-dim" to "rgb(90, 91, 92)",
    "--t-accent" to "rgb(30, 60, 190)",
    "--t-accent-soft" to "rgb(220, 225, 240)",
    "--t-accent-text" to "rgb(25, 50, 150)",
    "--t-text-dim-legible" to "rgb(70, 71, 72)",
    "--t-accent-soft-text" to "rgb(24, 48, 140)",
    "--t-accent-soft-solid" to "rgb(221, 226, 241)",
)

/** A 14px-box stroke icon, the shape hosts pass as a row's `iconHtml`. */
private const val ICON_SVG =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16"/></svg>"""

class CalmSidebarStyleTest {

    private val root: HTMLElement get() = document.documentElement as HTMLElement
    private lateinit var frame: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        applyCssVars(root, SIDEBAR_PALETTE)
        clearStyleStamps()
        frame = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame"
            it.style.width = "900px"
            it.style.height = "600px"
            document.body!!.appendChild(it)
        }
        renderTree()
    }

    @AfterTest
    fun unmount() {
        frame.parentElement?.removeChild(frame)
        clearStyleStamps()
        applySelectionStyle(root, SelectionStyle.Default)
        applySurfaceStyle(null)
        clearCssVars(root, SIDEBAR_PALETTE)
    }

    /**
     * Renders a left sidebar into [frame]: an active tab with an active row
     * ("Editor") and an idle row ("Shell"), an inactive tab with one row
     * ("Notes"), and a plain-text footer. The active tab is marked the way
     * `AppShellMount.buildLeftSidebarContent` marks it, with `active-tab`.
     */
    private fun renderTree() {
        val content = document.createElement("div") as HTMLElement
        val activeTab = renderSidebarSection(
            SidebarSectionSpec(
                title = "Work tab",
                isOpen = true,
                items = listOf(
                    SidebarRow(label = "Editor", iconHtml = ICON_SVG, isActive = true),
                    SidebarRow(label = "Shell", iconHtml = ICON_SVG),
                ),
            )
        )
        activeTab.classList.add("active-tab")
        activeTab.id = "tab-active"
        val otherTab = renderSidebarSection(
            SidebarSectionSpec(
                title = "Other tab",
                isOpen = true,
                items = listOf(SidebarRow(label = "Notes", iconHtml = ICON_SVG)),
            )
        )
        otherTab.id = "tab-other"
        content.appendChild(activeTab)
        content.appendChild(otherTab)
        val footer = (document.createElement("div") as HTMLElement).also { it.textContent = "1 working" }
        frame.appendChild(renderLeftSidebar(SidebarSpec(widthPx = 260, content = content, footer = footer)))
        // Transitions would report mid-flight values after a style flip.
        for (i in 0 until frame.querySelectorAll("*").length) {
            (frame.querySelectorAll("*").item(i) as? HTMLElement)?.style?.transition = "none"
        }
    }

    private fun el(selector: String): HTMLElement = assertNotNull(
        frame.querySelector(selector) as? HTMLElement,
        "nothing matches $selector",
    )

    private fun style(selector: String) = window.getComputedStyle(el(selector))

    /** Sidebar elements whose computed styles the style settings change. */
    private val parts = mapOf(
        "sidebar" to ".dt-sidebar",
        "activeHeader" to "#tab-active .dt-sidebar-section-header",
        "otherHeader" to "#tab-other .dt-sidebar-section-header",
        "chevron" to "#tab-active .dt-sidebar-section-chevron",
        "activeRow" to "#tab-active .dt-sidebar-row.dt-active",
        "idleRow" to "#tab-active .dt-sidebar-row:not(.dt-active)",
        "otherRow" to "#tab-other .dt-sidebar-row",
        "activeIcon" to "#tab-active .dt-sidebar-row.dt-active .dt-sidebar-row-icon",
        "idleIcon" to "#tab-other .dt-sidebar-row-icon",
        "section" to "#tab-active",
        "footer" to ".dt-sidebar-footer",
    )

    private fun snapshot(): Map<String, String> {
        val props = listOf(
            "background-color", "background-image", "box-shadow", "color", "font-size",
            "font-weight", "text-transform", "letter-spacing", "height", "margin-left",
            "padding-left", "border-top-width", "border-bottom-color", "border-radius",
        )
        val out = linkedMapOf<String, String>()
        for ((name, sel) in parts) {
            val s = style(sel)
            for (p in props) out["$name $p"] = s.getPropertyValue(p)
        }
        return out
    }

    /**
     * Runs [check] once for every selection language and surface style,
     * applied on top of whatever else the test stamped.
     */
    private fun forEveryCombination(check: (String) -> Unit) {
        for (surface in SurfaceStyle.entries) {
            applySurfaceStyle(surface)
            for (selection in SelectionStyle.entries) {
                applySelectionStyle(root, selection)
                check("$selection/$surface")
            }
        }
    }

    /** Applies the whole Calm look, the way the shell does. */
    private fun applyCalmLook() = applyAppearance(root, resolveAppearance(Looks.Calm.shape))

    @Test
    fun defaultsSidebarIsUnchanged() {
        for (surface in SurfaceStyle.entries) for (selection in SelectionStyle.entries) {
            val combo = "$selection/$surface"
            // What the pre-split appliers painted.
            clearStyleStamps()
            applySelectionStyle(root, selection)
            applySurfaceStyle(surface)
            applyCornerRadiusPx(null)
            applyUiDensity(null)
            val before = snapshot()
            val defaults = resolveAppearance(AppearanceShape(selectionStyle = selection, surfaceStyle = surface))
            applyAppearance(root, defaults)
            assertEquals(before, snapshot(), "the defaults changed the sidebar under $combo")
            applyCalmLook()
            assertTrue(before != snapshot(), "Calm changed nothing under $combo, so this test proves nothing")
            applyAppearance(root, defaults)
            assertEquals(before, snapshot(), "a round trip through Calm left a change under $combo")
        }
    }

    /** Soft selection alone, under either chrome: the active row is the one filled thing. */
    @Test
    fun softActiveRowIsTheOneFilledThing() {
        for (chrome in ChromeStyle.entries) {
            applyChromeStyle(root, chrome)
            for (surface in SurfaceStyle.entries) {
                applySurfaceStyle(surface)
                applySelectionStyle(root, SelectionStyle.Soft)
                val combo = "$chrome/$surface"
                val active = style("#tab-active .dt-sidebar-row.dt-active")
                assertEquals(SIDEBAR_PALETTE["--t-accent-soft-solid"], active.backgroundColor, "active row fill, $combo")
                assertEquals("none", active.backgroundImage, "no Depth sheen, $combo")
                assertEquals("none", active.boxShadow, "no ring, $combo")
                assertEquals(SIDEBAR_PALETTE["--t-accent-soft-text"], active.color, "active row label, $combo")
                assertEquals("600", active.fontWeight, "active row weight, $combo")
                for (sel in listOf("#tab-active .dt-sidebar-row:not(.dt-active)", "#tab-other .dt-sidebar-row", "#tab-active .dt-sidebar-section-header")) {
                    assertEquals("rgba(0, 0, 0, 0)", style(sel).backgroundColor, "$sel must not be filled, $combo")
                }
            }
        }
    }

    /** The whole Calm look: the soft active row with Blended's 8px radius and its icon in the label colour. */
    @Test
    fun calmLookActiveRowIsTheOneFilledThing() {
        applyCalmLook()
        val active = style("#tab-active .dt-sidebar-row.dt-active")
        assertEquals(SIDEBAR_PALETTE["--t-accent-soft-solid"], active.backgroundColor)
        assertEquals(SIDEBAR_PALETTE["--t-accent-soft-text"], active.color)
        assertEquals("8px", active.borderTopLeftRadius)
        assertEquals(
            SIDEBAR_PALETTE["--t-accent-soft-text"],
            style("#tab-active .dt-sidebar-row.dt-active .dt-sidebar-row-icon").color,
            "active row icon",
        )
    }

    /** Blended alone, under every selection and surface: rows follow their tab, 30px, indented 16px. */
    @Test
    fun blendedRowsFollowTheirTab() {
        applyChromeStyle(root, ChromeStyle.Blended)
        forEveryCombination { combo ->
            assertEquals(SIDEBAR_PALETTE["--t-text"], style("#tab-active .dt-sidebar-row:not(.dt-active)").color, "row under the active tab, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-text-dim-legible"], style("#tab-other .dt-sidebar-row").color, "row under another tab, $combo")
            assertEquals("8px", style("#tab-active .dt-sidebar-row.dt-active").borderTopLeftRadius, "active row radius, $combo")
        }
        for (sel in listOf(".dt-sidebar-row.dt-active", "#tab-active .dt-sidebar-row:not(.dt-active)", "#tab-other .dt-sidebar-row")) {
            assertEquals(30.0, el(sel).getBoundingClientRect().height, "$sel height")
        }
        // The icon is an SVGElement, not an HTMLElement, so it is looked up as a
        // plain Element.
        val icon = assertNotNull(frame.querySelector("#tab-other .dt-sidebar-row-icon svg"), "no row icon svg")
        assertEquals(14.0, icon.getBoundingClientRect().width, "row icon size")
        // Indented 16px under the header, the active row included.
        val headerLeft = el("#tab-active .dt-sidebar-section-header").getBoundingClientRect().left
        for (sel in listOf(".dt-sidebar-row.dt-active", "#tab-active .dt-sidebar-row:not(.dt-active)")) {
            assertEquals(16.0, el(sel).getBoundingClientRect().left - headerLeft, "$sel indent")
        }
    }

    /** Blended alone: header colours and weights follow the tab; the casing is left to Labels. */
    @Test
    fun blendedHeadersFollowTheirTab() {
        applyChromeStyle(root, ChromeStyle.Blended)
        forEveryCombination { combo ->
            val active = style("#tab-active .dt-sidebar-section-header")
            val other = style("#tab-other .dt-sidebar-section-header")
            assertEquals(SIDEBAR_PALETTE["--t-text"], active.color, "active tab header, $combo")
            assertEquals("650", active.fontWeight, "active tab header weight, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-text-dim-legible"], other.color, "inactive tab header, $combo")
            assertEquals("500", other.fontWeight, "inactive tab header weight, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-text-dim-legible"], style("#tab-active .dt-sidebar-section-chevron").color, "chevron, $combo")
            assertEquals("uppercase", active.textTransform, "casing is the Labels setting's, $combo")
        }
    }

    /** Sentence labels alone: tab headers in sentence case at 14px with no tracking. */
    @Test
    fun sentenceHeadersAreSentenceCase() {
        applyLabelCase(root, LabelCase.Sentence)
        forEveryCombination { combo ->
            for (sel in listOf("#tab-active .dt-sidebar-section-header", "#tab-other .dt-sidebar-section-header")) {
                val h = style(sel)
                assertEquals("none", h.textTransform, "header must not be uppercase, $combo")
                assertEquals("14px", h.fontSize, "header size, $combo")
                assertTrue(h.letterSpacing == "normal" || h.letterSpacing == "0px", "no tracking, got ${h.letterSpacing}, $combo")
            }
        }
    }

    /** The whole Calm look: sentence-case headers in Blended's colours. */
    @Test
    fun calmLookHeadersAreSentenceCase() {
        applyCalmLook()
        val active = style("#tab-active .dt-sidebar-section-header")
        val other = style("#tab-other .dt-sidebar-section-header")
        for (h in listOf(active, other)) {
            assertEquals("none", h.textTransform)
            assertEquals("14px", h.fontSize)
        }
        assertEquals(SIDEBAR_PALETTE["--t-text"], active.color)
        assertEquals("650", active.fontWeight)
        assertEquals(SIDEBAR_PALETTE["--t-text-dim-legible"], other.color)
    }

    /** Blended alone: the footer is muted text above a hairline. */
    @Test
    fun blendedFooterIsMutedTextAboveAHairline() {
        applyChromeStyle(root, ChromeStyle.Blended)
        val footer = style(".dt-sidebar-footer")
        assertEquals("1px", footer.borderTopWidth)
        assertEquals(SIDEBAR_PALETTE["--t-border"], footer.borderTopColor)
        assertEquals("rgba(0, 0, 0, 0)", footer.backgroundColor)
        assertEquals(SIDEBAR_PALETTE["--t-text-dim-legible"], footer.color)
    }
}
