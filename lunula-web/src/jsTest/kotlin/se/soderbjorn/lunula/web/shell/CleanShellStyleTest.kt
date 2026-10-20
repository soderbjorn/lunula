/* CleanShellStyleTest.kt
 * Browser tests for the Clean shell style (epic LNA-16, LNA-20): the
 * `data-dt-style` stamp written by `applyShellStyle`, and the
 * `:root[data-dt-style="clean"]` block at the end of `lunula.css` that keys
 * off it.
 *
 * Two promises are checked on a real, rendered sample shell (an app frame
 * holding two windows from [LayoutRenderer]), with a known palette written
 * onto :root:
 *
 *  - Classic is untouched. With the style Classic (or never chosen) no
 *    attribute is stamped, and the computed styles of the frame, sheet,
 *    windows and titlebars are exactly what they were before Clean was ever
 *    applied, including after a round trip through Clean. This is the
 *    guarantee that lets Clean ship with Classic as the default.
 *  - Clean actually paints. With the style Clean the attribute is stamped and
 *    the rules win over Fill and Depth: the focused window sits on
 *    `--t-surface`, the idle one on `--t-window-idle` (with the toolkit's
 *    content slot transparent and un-inset, so the body shows that colour),
 *    idle titles use `--t-clean-text-dim`, the titlebar is 44px
 *    and sentence case, and the action strip's hover reveal still hides it at
 *    rest. The focused window is also lifted: an inner `--t-window-ring`
 *    hairline and a soft drop shadow, which idle, lone and maximized windows
 *    do not get, and which leave the toolkit's stacking (z-index) untouched.
 *
 * @see se.soderbjorn.lunula.web.applyShellStyle
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.web.POINTER_ACTIVE_BODY_CLASS
import se.soderbjorn.lunula.web.applyCssVars
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applyShellStyle
import se.soderbjorn.lunula.web.clearCssVars
import se.soderbjorn.lunula.web.injectLunulaStyles
import se.soderbjorn.lunula.web.layout.FloatingPaneSpec
import se.soderbjorn.lunula.web.layout.LayoutClassNames
import se.soderbjorn.lunula.web.layout.LayoutRenderer
import se.soderbjorn.lunula.web.layout.PaneActions
import se.soderbjorn.lunula.web.layout.PaneCallbacks
import se.soderbjorn.lunula.web.layout.PaneHeaderClassNames
import se.soderbjorn.lunula.web.layout.PaneHeaderSpec
import se.soderbjorn.lunula.web.layout.PaneLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A palette whose every colour is distinct, so a computed background names
 * exactly which token painted it. Written as `rgb()` because that is how
 * `getComputedStyle` reports colours back.
 */
private val PALETTE = mapOf(
    "--t-bg" to "rgb(10, 11, 12)",
    "--t-canvas" to "rgb(13, 14, 15)",
    "--t-chrome-bg" to "rgb(16, 17, 18)",
    "--t-surface" to "rgb(200, 201, 202)",
    "--t-surface-alt" to "rgb(190, 191, 192)",
    "--t-window-idle" to "rgb(180, 181, 182)",
    "--t-window-ring" to "rgb(120, 121, 122)",
    "--t-border" to "rgb(100, 101, 102)",
    "--t-text" to "rgb(20, 21, 22)",
    "--t-text-dim" to "rgb(90, 91, 92)",
    "--t-accent" to "rgb(30, 60, 190)",
    "--t-accent-soft" to "rgb(220, 225, 240)",
    "--t-accent-on" to "rgb(255, 255, 254)",
    "--t-accent-text" to "rgb(25, 50, 150)",
    // The Clean-only tokens. A real theme derives them from the ones above
    // (`ResolvedTheme.cleanTextDim` nudges textDim for contrast), so they are
    // stated here too: left out, whatever theme an earlier test applied to
    // :root would decide them, and the assertions would test that theme.
    "--t-clean-text-dim" to "rgb(70, 71, 72)",
    "--t-clean-accent-text" to "rgb(24, 48, 140)",
    "--t-accent-soft-solid" to "rgb(221, 226, 241)",
)

/** Whether the test browser hovers, which the action strip's hide is gated on. */
private fun hasHoverPointer(): Boolean =
    window.matchMedia("(hover: hover) and (pointer: fine)").matches

class CleanShellStyleTest {

    private val root: HTMLElement get() = document.documentElement as HTMLElement
    private lateinit var frame: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        document.body!!.classList.remove(POINTER_ACTIVE_BODY_CLASS)
        applyCssVars(root, PALETTE)
        applyShellStyle(root, null)
        frame = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame"
            it.style.width = "900px"
            it.style.height = "600px"
            document.body!!.appendChild(it)
        }
    }

    @AfterTest
    fun unmount() {
        frame.parentElement?.removeChild(frame)
        applyShellStyle(root, null)
        applySelectionStyle(root, SelectionStyle.Default)
        clearCssVars(root, PALETTE)
    }

    /**
     * Renders an app frame holding two side-by-side windows, `p1` (focused,
     * as the first visible pane) and `p2` (idle), each with one action.
     *
     * @param panes the windows to render; defaults to the two halves.
     */
    private fun renderShell(
        panes: List<FloatingPaneSpec> = listOf(
            FloatingPaneSpec(id = "p1", title = "First window", xPct = 0.0, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
            FloatingPaneSpec(id = "p2", title = "Second window", xPct = 0.5, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
        ),
    ) {
        val main = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame-main"
            it.style.height = "100%"
            frame.appendChild(it)
        }
        val container = (document.createElement("div") as HTMLElement).also { main.appendChild(it) }
        LayoutRenderer(
            container = container,
            callbacks = PaneCallbacks(
                contentRenderer = { _, slot -> slot.textContent = "content" },
                paneHeader = { id, title ->
                    PaneHeaderSpec(title = title ?: id, actions = listOf(PaneActions.close { }))
                },
            ),
        ).render(
            layout = PaneLayout(floatingPanes = panes),
            suppressSeparators = true,
        )
    }

    private fun pane(id: String): HTMLElement = assertNotNull(
        frame.querySelector(".${LayoutClassNames.PANE}[data-pane-id=\"$id\"]") as? HTMLElement,
        "no pane $id rendered",
    )

    private fun HTMLElement.child(cls: String): HTMLElement = assertNotNull(
        querySelector(".$cls") as? HTMLElement,
        "no .$cls under ${className}",
    )

    /**
     * The computed values Clean would change if it leaked into Classic, for
     * the frame, the sheet, both windows and their titlebars and titles.
     */
    private fun snapshot(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val props = listOf(
            "background-color", "background-image", "border-top-width", "border-radius",
            "box-shadow", "outline-color", "outline-width", "height", "padding-left",
            "color", "font-size", "font-weight", "text-transform", "letter-spacing",
            "left", "width", "margin-left", "margin-bottom",
        )
        val elements = mapOf(
            "frame" to frame,
            "main" to frame.child("dt-app-frame-main"),
            "sheet" to frame.child(LayoutClassNames.ROOT),
            "p1" to pane("p1"),
            "p2" to pane("p2"),
            "p1.header" to pane("p1").child(LayoutClassNames.PANE_HEADER),
            "p2.header" to pane("p2").child(LayoutClassNames.PANE_HEADER),
            "p1.title" to pane("p1").child(LayoutClassNames.PANE_TITLE),
            "p2.title" to pane("p2").child(LayoutClassNames.PANE_TITLE),
            "p1.action" to pane("p1").child(PaneHeaderClassNames.ACTION),
            "p1.content" to pane("p1").child(LayoutClassNames.PANE_CONTENT),
            "p2.content" to pane("p2").child(LayoutClassNames.PANE_CONTENT),
        )
        for ((name, el) in elements) {
            // Transitions would report mid-flight values after a style flip.
            el.style.transition = "none"
            val style = window.getComputedStyle(el)
            for (p in props) out["$name $p"] = style.getPropertyValue(p)
        }
        // The stacking, and the ring pseudo-element Clean adds, for both windows.
        for (id in listOf("p1", "p2")) {
            out["$id z-index"] = window.getComputedStyle(pane(id)).zIndex
            val after = window.getComputedStyle(pane(id), "::after")
            out["$id::after content"] = after.getPropertyValue("content")
            out["$id::after border-top-color"] = after.borderTopColor
        }
        return out
    }

    @Test
    fun classicStampsNoAttribute() {
        applyShellStyle(root, ShellStyle.Classic)
        assertFalse(root.hasAttribute("data-dt-style"), "Classic must not stamp data-dt-style")
        applyShellStyle(root, ShellStyle.Clean)
        applyShellStyle(root, null)
        assertFalse(root.hasAttribute("data-dt-style"), "null (default) must not stamp data-dt-style")
    }

    @Test
    fun cleanStampsTheAttribute() {
        applyShellStyle(root, ShellStyle.Clean)
        assertEquals("clean", root.getAttribute("data-dt-style"))
    }

    /**
     * Classic renders exactly as before Clean existed, for both selection
     * languages, and stays so after a round trip through Clean.
     */
    @Test
    fun classicComputedStylesAreUnchanged() {
        renderShell()
        for (selection in SelectionStyle.entries) {
            applySelectionStyle(root, selection)
            applyShellStyle(root, null)
            val before = snapshot()
            applyShellStyle(root, ShellStyle.Classic)
            assertEquals(before, snapshot(), "Classic changed a computed style under $selection")
            applyShellStyle(root, ShellStyle.Clean)
            assertTrue(before != snapshot(), "Clean changed nothing under $selection, so this test proves nothing")
            applyShellStyle(root, ShellStyle.Classic)
            assertEquals(before, snapshot(), "a round trip through Clean left a change under $selection")
        }
    }

    /**
     * Under Clean the active window is shown by background alone, under both
     * selection languages (Fill would otherwise paint an accent band).
     */
    @Test
    fun cleanPaintsWindowsByBackgroundOnly() {
        renderShell()
        applyShellStyle(root, ShellStyle.Clean)
        for (selection in SelectionStyle.entries) {
            applySelectionStyle(root, selection)
            val focused = pane("p1")
            val idle = pane("p2")
            assertTrue(focused.classList.contains(LayoutClassNames.PANE_FOCUSED), "p1 should be focused")
            assertEquals(PALETTE["--t-surface"], window.getComputedStyle(focused).backgroundColor, "focused body, $selection")
            assertEquals(PALETTE["--t-window-idle"], window.getComputedStyle(idle).backgroundColor, "idle body, $selection")
            val focusedHeader = focused.child(LayoutClassNames.PANE_HEADER)
            val idleHeader = idle.child(LayoutClassNames.PANE_HEADER)
            focusedHeader.style.transition = "none"
            idleHeader.style.transition = "none"
            assertEquals(PALETTE["--t-surface"], window.getComputedStyle(focusedHeader).backgroundColor, "focused titlebar, $selection")
            assertEquals(PALETTE["--t-window-idle"], window.getComputedStyle(idleHeader).backgroundColor, "idle titlebar, $selection")
            assertEquals("none", window.getComputedStyle(focusedHeader).backgroundImage, "no sheen, $selection")
            val focusedTitle = window.getComputedStyle(focused.child(LayoutClassNames.PANE_TITLE))
            assertEquals(PALETTE["--t-text"], focusedTitle.color, "focused title colour, $selection")
            assertEquals("600", focusedTitle.fontWeight)
            assertEquals(
                PALETTE["--t-clean-text-dim"],
                window.getComputedStyle(idle.child(LayoutClassNames.PANE_TITLE)).color,
                "idle title takes the contrast-safe Clean dim, $selection",
            )
            // Window bodies: the toolkit's content slot paints nothing and has
            // no inset, so the body area is the window's own colour.
            for ((p, token) in listOf(focused to "--t-surface", idle to "--t-window-idle")) {
                val slot = p.child(LayoutClassNames.PANE_CONTENT)
                val slotStyle = window.getComputedStyle(slot)
                assertEquals("rgba(0, 0, 0, 0)", slotStyle.backgroundColor, "content slot must be transparent, $selection")
                assertEquals("0px", slotStyle.marginLeft, "content slot inset, $selection")
                assertEquals("0px", slotStyle.marginBottom, "content slot inset, $selection")
                assertEquals(p.getBoundingClientRect().width, slot.getBoundingClientRect().width, "slot fills the window, $selection")
                assertEquals(PALETTE[token], window.getComputedStyle(p).backgroundColor, "body colour behind the slot, $selection")
            }
        }
    }

    @Test
    fun cleanTitlebarIsSentenceCaseAt44px() {
        renderShell()
        applyShellStyle(root, ShellStyle.Clean)
        val header = pane("p1").child(LayoutClassNames.PANE_HEADER)
        assertEquals(44.0, header.getBoundingClientRect().height)
        val title = window.getComputedStyle(pane("p1").child(LayoutClassNames.PANE_TITLE))
        assertEquals("none", title.textTransform)
        assertEquals("14px", title.fontSize)
        val action = pane("p1").child(PaneHeaderClassNames.ACTION)
        assertEquals(28.0, action.getBoundingClientRect().width)
    }

    /** The sheet and the ground: one `--t-bg` ground, one bordered sheet. */
    @Test
    fun cleanPaintsOneGroundAndOneSheet() {
        renderShell()
        applyShellStyle(root, ShellStyle.Clean)
        val frameStyle = window.getComputedStyle(frame)
        assertEquals(PALETTE["--t-bg"], frameStyle.backgroundColor)
        assertEquals("none", frameStyle.backgroundImage, "Depth's wash must not paint under Clean")
        val sheet = window.getComputedStyle(frame.child(LayoutClassNames.ROOT))
        assertEquals("14px", sheet.borderTopLeftRadius)
        assertEquals("1px", sheet.borderTopWidth)
        assertEquals(PALETTE["--t-border"], sheet.borderTopColor)
        // Zero gutter: the second window starts exactly where the first ends.
        assertEquals(pane("p1").getBoundingClientRect().right, pane("p2").getBoundingClientRect().left)
    }

    /** The ring and the lift a window shows, read off its computed style. */
    private fun ringOf(p: HTMLElement): String = window.getComputedStyle(p, "::after").borderTopColor
    private fun shadowOf(p: HTMLElement): String {
        p.style.transition = "none"
        return window.getComputedStyle(p).boxShadow
    }

    /** The focused window's lift: the second, wide layer of `--dt-clean-lift`. */
    private val liftLayer = "0px 12px 32px"

    /**
     * Under Clean the focused window is lifted, under both selection
     * languages: an inner ring in `--t-window-ring` and a soft drop shadow.
     * The idle window keeps its plain hairline and no ring, and neither
     * window's stacking moves (the toolkit owns z-index).
     */
    @Test
    fun cleanLiftsTheFocusedWindowWithARingAndAShadow() {
        renderShell()
        val zBefore = listOf("p1", "p2").map { window.getComputedStyle(pane(it)).zIndex }
        applyShellStyle(root, ShellStyle.Clean)
        for (selection in SelectionStyle.entries) {
            applySelectionStyle(root, selection)
            val focused = pane("p1")
            val idle = pane("p2")
            assertEquals(PALETTE["--t-window-ring"], ringOf(focused), "focused ring, $selection")
            assertEquals("rgba(0, 0, 0, 0)", ringOf(idle), "idle windows have no ring, $selection")
            assertEquals("none", window.getComputedStyle(focused, "::after").getPropertyValue("pointer-events"))
            assertTrue(liftLayer in shadowOf(focused), "focused lift, $selection: ${shadowOf(focused)}")
            assertFalse(liftLayer in shadowOf(idle), "idle windows are not lifted, $selection: ${shadowOf(idle)}")
            assertTrue(PALETTE["--t-border"]!! in shadowOf(idle), "idle windows keep their hairline, $selection")
            assertEquals(zBefore, listOf("p1", "p2").map { window.getComputedStyle(pane(it)).zIndex }, "stacking, $selection")
        }
    }

    /**
     * A window alone in its tab fills the sheet, so it is neither ringed nor
     * lifted; nor is a maximized one.
     */
    @Test
    fun aLoneOrMaximizedWindowIsNotLifted() {
        renderShell(listOf(FloatingPaneSpec(id = "p1", title = "Only window", xPct = 0.0, yPct = 0.0, widthPct = 1.0, heightPct = 1.0)))
        applyShellStyle(root, ShellStyle.Clean)
        val lone = pane("p1")
        assertTrue(lone.classList.contains(LayoutClassNames.PANE_FOCUSED))
        assertEquals("rgba(0, 0, 0, 0)", ringOf(lone), "lone window ring")
        assertFalse(liftLayer in shadowOf(lone), "lone window lift: ${shadowOf(lone)}")
        assertEquals(PALETTE["--t-surface"], window.getComputedStyle(lone).backgroundColor, "still shown as focused")

        frame.innerHTML = ""
        renderShell(
            listOf(
                FloatingPaneSpec(id = "p1", title = "Big", xPct = 0.0, yPct = 0.0, widthPct = 0.5, heightPct = 1.0, isMaximized = true),
                FloatingPaneSpec(id = "p2", title = "Other", xPct = 0.5, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
            ),
        )
        val maximized = pane("p1")
        assertTrue(maximized.classList.contains("dt-maximized"))
        assertEquals("rgba(0, 0, 0, 0)", ringOf(maximized), "maximized window ring")
        assertFalse(liftLayer in shadowOf(maximized), "maximized window lift")
    }

    /** Clean restyles the controls but must leave their hover reveal alone. */
    @Test
    fun cleanKeepsTheActionStripHiddenAtRest() {
        if (!hasHoverPointer()) return
        renderShell()
        applyShellStyle(root, ShellStyle.Clean)
        val actions = pane("p1").child(PaneHeaderClassNames.ACTIONS)
        val style = window.getComputedStyle(actions)
        assertEquals("0", style.opacity)
        assertEquals("none", style.getPropertyValue("pointer-events").trim())
    }
}
