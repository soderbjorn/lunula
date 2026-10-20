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
 *    `--t-surface`, the idle one on `--t-window-idle`, the titlebar is 44px
 *    and sentence case, and the action strip's hover reveal still hides it at
 *    rest.
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
    "--t-border" to "rgb(100, 101, 102)",
    "--t-text" to "rgb(20, 21, 22)",
    "--t-text-dim" to "rgb(90, 91, 92)",
    "--t-accent" to "rgb(30, 60, 190)",
    "--t-accent-soft" to "rgb(220, 225, 240)",
    "--t-accent-on" to "rgb(255, 255, 254)",
    "--t-accent-text" to "rgb(25, 50, 150)",
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
     */
    private fun renderShell() {
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
            layout = PaneLayout(
                floatingPanes = listOf(
                    FloatingPaneSpec(id = "p1", title = "First window", xPct = 0.0, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
                    FloatingPaneSpec(id = "p2", title = "Second window", xPct = 0.5, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
                ),
            ),
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
            "left", "width",
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
        )
        for ((name, el) in elements) {
            // Transitions would report mid-flight values after a style flip.
            el.style.transition = "none"
            val style = window.getComputedStyle(el)
            for (p in props) out["$name $p"] = style.getPropertyValue(p)
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
            assertEquals(PALETTE["--t-text-dim"], window.getComputedStyle(idle.child(LayoutClassNames.PANE_TITLE)).color)
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
