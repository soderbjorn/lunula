/* CalmShellStyleTest.kt
 * Browser tests for the window-level style settings that used to be the single
 * Calm look style (epic LNA-16, LNA-20), on a real, rendered sample shell (an
 * app frame holding two windows from [LayoutRenderer]) with a known palette
 * written onto :root:
 *
 *  - Defaults untouched. With every setting at its default ([applyAppearance]
 *    of the toolkit defaults), the computed styles of the frame, sheet,
 *    windows and titlebars are exactly what the pre-split appliers painted,
 *    including after a round trip through the whole Calm look. Neither look
 *    writes the retired `data-dt-style` stamp, and an app that still sets it
 *    paints nothing with it.
 *  - Windows: Sheet (`applyWindowLayout`) — one bordered sheet taking the
 *    corner setting, zero gutter, the viewport inset.
 *  - Active window: Tone (`applyFocusStyle`) — the focused window on
 *    `--t-window-active`, idle ones on `--t-window-idle` (content slot
 *    transparent and un-inset), idle titles in `--t-text-dim-legible`, the
 *    focused window lifted by an inner `--t-window-ring` and a soft drop
 *    shadow that lone and maximized windows do not get, stacking untouched,
 *    and the hidden action strip lifted out of flow.
 *  - Chrome: Blended (`applyChromeStyle`) — one `--t-bg` ground and the 44px
 *    titlebar with 28px ghost buttons; Labels: Sentence — 14px, no transform.
 *  - Surfaces: Soft (`applySurfaceStyle`) — calm dialog and menu cards.
 *  - The whole Calm look ([Looks.Calm], applied with [applyAppearance]) still
 *    paints all of it together, at its own 14px corner.
 *
 * Each per-setting test applies that setting ALONE, so a rule that silently
 * depends on another setting's attribute fails here.
 *
 * @see se.soderbjorn.lunula.web.applyAppearance
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.core.resolveAppearance
import se.soderbjorn.lunula.web.POINTER_ACTIVE_BODY_CLASS
import se.soderbjorn.lunula.web.applyAppearance
import se.soderbjorn.lunula.web.applyChromeStyle
import se.soderbjorn.lunula.web.applyCornerRadiusPx
import se.soderbjorn.lunula.web.applyCssVars
import se.soderbjorn.lunula.web.applyFocusStyle
import se.soderbjorn.lunula.web.applyLabelCase
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.applyUiDensity
import se.soderbjorn.lunula.web.applyWindowLayout
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
    "--t-window-active" to "rgb(205, 206, 207)",
    "--t-window-ring" to "rgb(120, 121, 122)",
    "--t-border" to "rgb(100, 101, 102)",
    "--t-text" to "rgb(20, 21, 22)",
    "--t-text-dim" to "rgb(90, 91, 92)",
    "--t-accent" to "rgb(30, 60, 190)",
    "--t-accent-soft" to "rgb(220, 225, 240)",
    "--t-accent-on" to "rgb(255, 255, 254)",
    "--t-accent-text" to "rgb(25, 50, 150)",
    // The derived legible tokens. A real theme derives them from the ones above
    // (`ResolvedTheme.legibleTextDim` nudges textDim for contrast), so they are
    // stated here too: left out, whatever theme an earlier test applied to
    // :root would decide them, and the assertions would test that theme.
    "--t-text-dim-legible" to "rgb(70, 71, 72)",
    "--t-accent-soft-text" to "rgb(24, 48, 140)",
    "--t-accent-soft-solid" to "rgb(221, 226, 241)",
)

/** Whether the test browser hovers, which the action strip's hide is gated on. */
private fun hasHoverPointer(): Boolean =
    window.matchMedia("(hover: hover) and (pointer: fine)").matches

/** The toolkit defaults, resolved: what a shell with nothing chosen paints. */
private val DEFAULTS = resolveAppearance(AppearanceShape())

class CalmShellStyleTest {

    private val root: HTMLElement get() = document.documentElement as HTMLElement
    private lateinit var frame: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        document.body!!.classList.remove(POINTER_ACTIVE_BODY_CLASS)
        applyCssVars(root, PALETTE)
        clearStyleStamps()
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
        clearStyleStamps()
        applySelectionStyle(root, SelectionStyle.Default)
        clearCssVars(root, PALETTE)
    }

    /** Applies the whole Calm look, the way the shell does. */
    private fun applyCalmLook() = applyAppearance(root, resolveAppearance(Looks.Calm.shape))

    /**
     * Paints what the shell painted before the style settings existed, with
     * [selection]: only the four pre-split appliers, so no new attribute can
     * be present. The baseline the defaults are compared against.
     */
    private fun applyPreSplit(selection: SelectionStyle) {
        clearStyleStamps()
        applySelectionStyle(root, selection)
        applySurfaceStyle(SurfaceStyle.Depth)
        applyCornerRadiusPx(null)
        applyUiDensity(null)
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
     * The computed values a style setting would change if it leaked into the
     * defaults, for the frame, the sheet, both windows and their titlebars and
     * titles.
     */
    private fun snapshot(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val props = listOf(
            "background-color", "background-image", "border-top-width", "border-radius",
            "box-shadow", "outline-color", "outline-width", "height", "padding-left",
            "color", "font-size", "font-weight", "text-transform", "letter-spacing",
            "left", "width", "margin-left", "margin-bottom", "position",
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
            "p1.actions" to pane("p1").child(PaneHeaderClassNames.ACTIONS),
            "p1.content" to pane("p1").child(LayoutClassNames.PANE_CONTENT),
            "p2.content" to pane("p2").child(LayoutClassNames.PANE_CONTENT),
        )
        for ((name, el) in elements) {
            // Transitions would report mid-flight values after a style flip.
            el.style.transition = "none"
            val style = window.getComputedStyle(el)
            for (p in props) out["$name $p"] = style.getPropertyValue(p)
        }
        // The stacking, the ring pseudo-element Tone adds, and the published
        // window colour, for both windows.
        for (id in listOf("p1", "p2")) {
            out["$id z-index"] = window.getComputedStyle(pane(id)).zIndex
            val after = window.getComputedStyle(pane(id), "::after")
            out["$id::after content"] = after.getPropertyValue("content")
            out["$id::after border-top-color"] = after.borderTopColor
            out["$id --dt-window-bg"] = window.getComputedStyle(pane(id)).getPropertyValue("--dt-window-bg")
        }
        return out
    }

    /** The style attributes on :root, by name, for the "nothing stamped" checks. */
    private fun styleAttributes(): Map<String, String> =
        listOf(
            "data-dt-style", "data-dt-selection", "data-dt-surface", "data-dt-chrome",
            "data-dt-windows", "data-dt-focus", "data-dt-labels", "data-dt-density",
        ).mapNotNull { a -> root.getAttribute(a)?.let { a to it } }.toMap()

    // ── The retired legacy stamp ──

    /** Neither look, nor the defaults, writes the retired `data-dt-style` stamp. */
    @Test
    fun noLookWritesTheLegacyStamp() {
        for ((name, shape) in listOf("Classic" to Looks.Classic.shape, "Calm" to Looks.Calm.shape, "defaults" to AppearanceShape())) {
            applyAppearance(root, resolveAppearance(shape))
            assertFalse(root.hasAttribute("data-dt-style"), "$name wrote data-dt-style")
        }
    }

    /** lunula.css does not read `data-dt-style`: an app still stamping it paints nothing. */
    @Test
    fun theLegacyStampAlonePaintsNothing() {
        renderShell()
        applyPreSplit(SelectionStyle.Default)
        val before = snapshot()
        for (value in listOf("clean", "calm")) {
            root.setAttribute("data-dt-style", value)
            assertEquals(before, snapshot(), "data-dt-style=\"$value\" alone changed a computed style")
        }
        root.removeAttribute("data-dt-style")
    }

    // ── Defaults ──

    /**
     * Every setting at its default renders exactly as the pre-split appliers
     * did, under every selection language, stamps nothing but the selection,
     * and stays so after a round trip through the whole Calm look.
     */
    @Test
    fun defaultsRenderExactlyAsBefore() {
        renderShell()
        for (selection in SelectionStyle.entries) {
            applyPreSplit(selection)
            val before = snapshot()
            val defaults = DEFAULTS.copy(selectionStyle = selection)
            applyAppearance(root, defaults)
            assertEquals(before, snapshot(), "the defaults changed a computed style under $selection")
            val expectedAttrs = if (selection == SelectionStyle.Tint) emptyMap() else mapOf("data-dt-selection" to selection.cssValue)
            assertEquals(expectedAttrs, styleAttributes(), "the defaults stamped a style attribute under $selection")
            applyCalmLook()
            assertTrue(before != snapshot(), "Calm changed nothing under $selection, so this test proves nothing")
            applyAppearance(root, defaults)
            assertEquals(before, snapshot(), "a round trip through Calm left a change under $selection")
            assertEquals(expectedAttrs, styleAttributes(), "a round trip through Calm left an attribute under $selection")
        }
    }

    /** The toolkit defaults are Fill selection, so the only stamp is `data-dt-selection="fill"`. */
    @Test
    fun theDefaultsStampOnlyTheFillSelection() {
        applyAppearance(root, DEFAULTS)
        assertEquals(mapOf("data-dt-selection" to "fill"), styleAttributes())
        assertEquals("", root.style.getPropertyValue("--dt-corner-radius"), "a null corner clears the override")
    }

    // ── Active window: Tone ──

    /**
     * Under Tone alone the active window is shown by background, under every
     * selection language (Fill would otherwise paint an accent band).
     */
    @Test
    fun toneShowsTheActiveWindowByBackgroundOnly() {
        renderShell()
        applyFocusStyle(root, FocusStyle.Tone)
        for (selection in SelectionStyle.entries) {
            applySelectionStyle(root, selection)
            val focused = pane("p1")
            val idle = pane("p2")
            assertTrue(focused.classList.contains(LayoutClassNames.PANE_FOCUSED), "p1 should be focused")
            assertEquals(PALETTE["--t-window-active"], window.getComputedStyle(focused).backgroundColor, "focused body, $selection")
            assertEquals(PALETTE["--t-window-idle"], window.getComputedStyle(idle).backgroundColor, "idle body, $selection")
            val focusedHeader = focused.child(LayoutClassNames.PANE_HEADER)
            val idleHeader = idle.child(LayoutClassNames.PANE_HEADER)
            focusedHeader.style.transition = "none"
            idleHeader.style.transition = "none"
            assertEquals(PALETTE["--t-window-active"], window.getComputedStyle(focusedHeader).backgroundColor, "focused titlebar, $selection")
            assertEquals(PALETTE["--t-window-idle"], window.getComputedStyle(idleHeader).backgroundColor, "idle titlebar, $selection")
            assertEquals("none", window.getComputedStyle(focusedHeader).backgroundImage, "no sheen, $selection")
            val focusedTitle = window.getComputedStyle(focused.child(LayoutClassNames.PANE_TITLE))
            assertEquals(PALETTE["--t-text"], focusedTitle.color, "focused title colour, $selection")
            assertEquals("600", focusedTitle.fontWeight)
            assertEquals(
                PALETTE["--t-text-dim-legible"],
                window.getComputedStyle(idle.child(LayoutClassNames.PANE_TITLE)).color,
                "idle title takes the contrast-safe dim, $selection",
            )
            // Window bodies: the toolkit's content slot paints nothing and has
            // no inset, so the body area is the window's own colour.
            for ((p, token) in listOf(focused to "--t-window-active", idle to "--t-window-idle")) {
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

    /** The ring and the lift a window shows, read off its computed style. */
    private fun ringOf(p: HTMLElement): String = window.getComputedStyle(p, "::after").borderTopColor
    private fun shadowOf(p: HTMLElement): String {
        p.style.transition = "none"
        return window.getComputedStyle(p).boxShadow
    }

    /** The focused window's lift: the second, wide layer of `--dt-soft-lift`. */
    private val liftLayer = "0px 12px 32px"

    /**
     * Under Tone the focused window is lifted, under every selection language:
     * an inner ring in `--t-window-ring` and a soft drop shadow. The idle
     * window keeps its plain hairline and no ring, and neither window's
     * stacking moves (the toolkit owns z-index).
     */
    @Test
    fun toneLiftsTheFocusedWindowWithARingAndAShadow() {
        renderShell()
        val zBefore = listOf("p1", "p2").map { window.getComputedStyle(pane(it)).zIndex }
        applyFocusStyle(root, FocusStyle.Tone)
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
     * A window alone in its tab has nothing to stand out from, so it is
     * neither ringed nor lifted; nor is a maximized one.
     */
    @Test
    fun aLoneOrMaximizedWindowIsNotLifted() {
        renderShell(listOf(FloatingPaneSpec(id = "p1", title = "Only window", xPct = 0.0, yPct = 0.0, widthPct = 1.0, heightPct = 1.0)))
        applyFocusStyle(root, FocusStyle.Tone)
        val lone = pane("p1")
        assertTrue(lone.classList.contains(LayoutClassNames.PANE_FOCUSED))
        assertEquals("rgba(0, 0, 0, 0)", ringOf(lone), "lone window ring")
        assertFalse(liftLayer in shadowOf(lone), "lone window lift: ${shadowOf(lone)}")
        assertEquals(PALETTE["--t-window-active"], window.getComputedStyle(lone).backgroundColor, "still shown as focused")

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

    /**
     * Under Tone the hidden action strip reserves no room in the titlebar, so
     * a long title gets the whole row instead of being cut short by invisible
     * buttons; under the defaults the strip stays in flow as before. The strip
     * is still revealed by keyboard focus, and keeps its stacking.
     */
    @Test
    fun toneHiddenActionStripTakesNoRoomFromTheTitle() {
        if (!hasHoverPointer()) return
        val long = "A window title long enough that it can never fit in half the frame"
        renderShell(
            listOf(
                FloatingPaneSpec(id = "p1", title = long, xPct = 0.0, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
                FloatingPaneSpec(id = "p2", title = "Second window", xPct = 0.5, yPct = 0.0, widthPct = 0.5, heightPct = 1.0),
            ),
        )
        val title = pane("p1").child(LayoutClassNames.PANE_TITLE)
        val actions = pane("p1").child(PaneHeaderClassNames.ACTIONS)
        fun titleWidth(): Double = title.getBoundingClientRect().width
        fun titleWidthWithoutStrip(): Double {
            actions.style.display = "none"
            val w = titleWidth()
            actions.style.removeProperty("display")
            return w
        }

        applyAppearance(root, DEFAULTS)
        assertTrue(
            titleWidth() < titleWidthWithoutStrip() - 1.0,
            "under the defaults the hidden strip should still take its room, so this test proves something",
        )

        applyFocusStyle(root, FocusStyle.Tone)
        assertEquals("absolute", window.getComputedStyle(actions).position)
        assertEquals(titleWidthWithoutStrip(), titleWidth(), "under Tone the hidden strip must not shrink the title")
        assertEquals("5", window.getComputedStyle(actions).zIndex, "the strip keeps its stacking above the corner grip")
        val style = window.getComputedStyle(actions)
        assertEquals("0", style.opacity, "the strip is still hidden at rest")

        // Keyboard focus still reveals it.
        val button = actions.querySelector("button") as HTMLElement
        actions.style.transition = "none"
        button.focus()
        assertEquals("1", window.getComputedStyle(actions).opacity)
        assertEquals("auto", window.getComputedStyle(actions).getPropertyValue("pointer-events").trim())
        button.blur()
    }

    // ── Chrome: Blended, Labels: Sentence ──

    /** Blended alone: the 44px titlebar and 28px ghost window buttons; the casing is untouched. */
    @Test
    fun blendedTitlebarIs44pxWithGhostButtons() {
        renderShell()
        applyChromeStyle(root, ChromeStyle.Blended)
        val header = pane("p1").child(LayoutClassNames.PANE_HEADER)
        assertEquals(44.0, header.getBoundingClientRect().height)
        val action = pane("p1").child(PaneHeaderClassNames.ACTION)
        assertEquals(28.0, action.getBoundingClientRect().width)
        assertEquals("uppercase", window.getComputedStyle(pane("p1").child(LayoutClassNames.PANE_TITLE)).textTransform,
            "the label casing is the Labels setting's")
    }

    /** Sentence labels alone: window titles in sentence case at 14px; the titlebar height is untouched. */
    @Test
    fun sentenceLabelsLetterTitlesAt14px() {
        renderShell()
        val header = pane("p1").child(LayoutClassNames.PANE_HEADER)
        val heightBefore = header.getBoundingClientRect().height
        applyLabelCase(root, LabelCase.Sentence)
        val title = window.getComputedStyle(pane("p1").child(LayoutClassNames.PANE_TITLE))
        assertEquals("none", title.textTransform)
        assertEquals("14px", title.fontSize)
        assertTrue(title.letterSpacing == "0px" || title.letterSpacing == "normal", "tracking: ${title.letterSpacing}")
        assertEquals(heightBefore, header.getBoundingClientRect().height, "the titlebar geometry is Blended's, not Sentence's")
    }

    /** The whole Calm look: the 44px sentence-case titlebar with 28px buttons. */
    @Test
    fun calmLookTitlebarIsSentenceCaseAt44px() {
        renderShell()
        applyCalmLook()
        val header = pane("p1").child(LayoutClassNames.PANE_HEADER)
        assertEquals(44.0, header.getBoundingClientRect().height)
        val title = window.getComputedStyle(pane("p1").child(LayoutClassNames.PANE_TITLE))
        assertEquals("none", title.textTransform)
        assertEquals("14px", title.fontSize)
        val action = pane("p1").child(PaneHeaderClassNames.ACTION)
        assertEquals(28.0, action.getBoundingClientRect().width)
    }

    /** Blended alone paints one `--t-bg` ground with no Depth wash, even under Depth surfaces. */
    @Test
    fun blendedPaintsOneGround() {
        renderShell()
        applySurfaceStyle(SurfaceStyle.Depth)
        applyChromeStyle(root, ChromeStyle.Blended)
        val frameStyle = window.getComputedStyle(frame)
        assertEquals(PALETTE["--t-bg"], frameStyle.backgroundColor)
        assertEquals("none", frameStyle.backgroundImage, "Depth's wash must not paint under Blended")
    }

    // ── Windows: Sheet ──

    /** Sheet alone: one bordered sheet at the corner setting, windows meeting with no gutter. */
    @Test
    fun sheetIsOneBorderedSheetAtTheCornerSetting() {
        renderShell()
        applyWindowLayout(root, WindowLayout.Sheet)
        val sheet = window.getComputedStyle(frame.child(LayoutClassNames.ROOT))
        assertEquals("18px", sheet.borderTopLeftRadius, "the toolkit default corner")
        assertEquals("1px", sheet.borderTopWidth)
        assertEquals(PALETTE["--t-border"], sheet.borderTopColor)
        // Zero gutter: the second window starts exactly where the first ends.
        assertEquals(pane("p1").getBoundingClientRect().right, pane("p2").getBoundingClientRect().left)
        assertEquals("0px", window.getComputedStyle(pane("p1")).borderTopLeftRadius, "windows in a sheet are square")

        // The sheet takes the corner setting, whatever it is: no fixed radius.
        applyCornerRadiusPx(6)
        assertEquals("6px", window.getComputedStyle(frame.child(LayoutClassNames.ROOT)).borderTopLeftRadius)
        applyCornerRadiusPx(0)
        assertEquals("0px", window.getComputedStyle(frame.child(LayoutClassNames.ROOT)).borderTopLeftRadius)
        applyCornerRadiusPx(null)
    }

    /** The whole Calm look: its 14px corner on the sheet, one ground beneath it. */
    @Test
    fun calmLookPaintsOneGroundAndOneSheet() {
        renderShell()
        applyCalmLook()
        val frameStyle = window.getComputedStyle(frame)
        assertEquals(PALETTE["--t-bg"], frameStyle.backgroundColor)
        assertEquals("none", frameStyle.backgroundImage, "Depth's wash must not paint under Calm")
        val sheet = window.getComputedStyle(frame.child(LayoutClassNames.ROOT))
        assertEquals("14px", sheet.borderTopLeftRadius)
        assertEquals("1px", sheet.borderTopWidth)
        assertEquals(PALETTE["--t-border"], sheet.borderTopColor)
        assertEquals(pane("p1").getBoundingClientRect().right, pane("p2").getBoundingClientRect().left)
    }

    /** Blended and Tone restyle the controls but must leave their hover reveal alone. */
    @Test
    fun theActionStripStaysHiddenAtRest() {
        if (!hasHoverPointer()) return
        renderShell()
        val apply = listOf<Pair<String, () -> Unit>>(
            "Blended" to { applyChromeStyle(root, ChromeStyle.Blended) },
            "Tone" to { applyFocusStyle(root, FocusStyle.Tone) },
            "Calm" to { applyCalmLook() },
        )
        for ((name, fn) in apply) {
            clearStyleStamps()
            fn()
            val style = window.getComputedStyle(pane("p1").child(PaneHeaderClassNames.ACTIONS))
            assertEquals("0", style.opacity, name)
            assertEquals("none", style.getPropertyValue("pointer-events").trim(), name)
        }
    }

    // ── Surfaces: Soft ──

    /**
     * Floating dialogs and menus: Depth keeps its look exactly (the modal's 2px
     * accent frame included), and Soft surfaces give each the calm card: the
     * surface colour, a 1px `--t-border` hairline and no accent anywhere on
     * the frame.
     */
    @Test
    fun softSurfacesCalmDialogsAndMenusAndLeaveDepthAlone() {
        val classes = listOf("dt-modal", "pane-modal", "dt-name-prompt", "dt-hover-menu", "dt-pane-menu", "dt-popover-menu", "dt-scheme-menu")
        val els = classes.map { cls ->
            (document.createElement("div") as HTMLElement).also {
                it.className = cls
                it.style.transition = "none"
                document.body!!.appendChild(it)
            }
        }
        val props = listOf("background-color", "border-top-width", "border-top-color", "border-radius", "box-shadow")
        fun snap(): Map<String, String> = els.flatMap { el ->
            val st = window.getComputedStyle(el)
            props.map { "${el.className} $it" to st.getPropertyValue(it) }
        }.toMap()
        try {
            applySurfaceStyle(SurfaceStyle.Depth)
            val before = snap()
            assertEquals("2px", before["dt-modal border-top-width"], "the default modal frame is the 2px accent")
            applySurfaceStyle(SurfaceStyle.Soft)
            for (el in els) {
                val st = window.getComputedStyle(el)
                assertEquals(PALETTE["--t-surface"], st.backgroundColor, "${el.className} background")
                assertEquals("1px", st.borderTopWidth, "${el.className} border width")
                assertEquals(PALETTE["--t-border"], st.borderTopColor, "${el.className} border colour")
                assertFalse(PALETTE["--t-accent"]!! in st.boxShadow, "${el.className} shadow carries the accent")
            }
            applySurfaceStyle(SurfaceStyle.Depth)
            assertEquals(before, snap(), "a round trip through Soft changed a dialog under Depth")
        } finally {
            els.forEach { it.parentElement?.removeChild(it) }
        }
    }

    /**
     * Builds the real frame row (`.dt-app-frame-body` holding the left slot
     * and the main area, as `renderAppFrame` does) with the left slot in one
     * of the states the shell produces, and returns the main area.
     *
     * @param sidebar `"open"` mounts an open `.dt-sidebar`, `"collapsed"` the
     *   0-width `.dt-sidebar-collapsed` placeholder, `"none"` leaves the slot
     *   empty (Navigation = Tab strip only).
     */
    private fun renderFrameRow(sidebar: String): HTMLElement {
        val body = (document.createElement("div") as HTMLElement).also {
            it.className = AppFrameClassNames.BODY
            it.style.height = "100%"
            frame.appendChild(it)
        }
        val slot = (document.createElement("div") as HTMLElement).also {
            it.className = AppFrameClassNames.SIDEBAR_LEFT
            body.appendChild(it)
        }
        if (sidebar != "none") {
            (document.createElement("aside") as HTMLElement).also {
                it.className = "dt-sidebar dt-sidebar-left"
                if (sidebar == "collapsed") {
                    it.classList.add("dt-sidebar-collapsed")
                    it.style.width = "0px"
                    it.style.setProperty("min-width", "0")
                } else {
                    it.style.width = "200px"
                }
                slot.appendChild(it)
            }
        }
        return (document.createElement("div") as HTMLElement).also {
            it.className = AppFrameClassNames.MAIN
            body.appendChild(it)
        }
    }

    /**
     * Under Sheet the main area's left inset is the small sidebar-side one
     * only when an open sidebar is drawn beside it. With the slot empty (Tab
     * strip only) or holding the collapsed placeholder, the sheet would
     * otherwise sit 4px from the viewport edge, so the left inset matches
     * the right and bottom ones there.
     */
    @Test
    fun sheetInsetsFromTheViewportWhenNoSidebarIsDrawn() {
        applyWindowLayout(root, WindowLayout.Sheet)
        for (state in listOf("none", "collapsed", "open")) {
            frame.innerHTML = ""
            val style = window.getComputedStyle(renderFrameRow(state))
            assertEquals("16px", style.paddingRight, "right inset, $state")
            assertEquals("16px", style.paddingBottom, "bottom inset, $state")
            val expectedLeft = if (state == "open") "4px" else "16px"
            assertEquals(expectedLeft, style.paddingLeft, "left inset, $state")
        }
    }

    /** Cards (the default) keeps its one even frame padding whatever the sidebar does. */
    @Test
    fun cardsInsetIsEvenWhateverTheSidebarDoes() {
        applyAppearance(root, DEFAULTS)
        for (state in listOf("none", "collapsed", "open")) {
            frame.innerHTML = ""
            val style = window.getComputedStyle(renderFrameRow(state))
            assertEquals(style.paddingRight, style.paddingLeft, "left vs right, $state")
            assertEquals(style.paddingBottom, style.paddingLeft, "left vs bottom, $state")
        }
    }
}
