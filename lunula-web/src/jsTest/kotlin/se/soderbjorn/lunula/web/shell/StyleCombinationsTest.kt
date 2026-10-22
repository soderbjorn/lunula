/* StyleCombinationsTest.kt
 * Browser tests for combinations of the independent style settings that must
 * hold together. The settings that used to be the single Calm look style
 * are now separate (chrome, windows, active window, labels, selection,
 * surfaces), and any combination is a valid shell, so a rule written for one
 * setting must never paint over another setting's signal.
 *
 * Rendered on a real sample shell (two windows from [LayoutRenderer], a tab
 * strip and a left sidebar tree inside a `.dt-app-frame`), with a palette
 * whose every colour is distinct written onto :root, so a computed colour
 * names exactly which token painted it. Each test stamps exactly the settings
 * it names and leaves every other at its default.
 *
 * Covered:
 *  - Blended + Fill + Accent: the focused window's title and buttons, and the
 *    active sidebar row and tab, keep Fill's legible accent-on foreground.
 *  - Tone + Fill: the focused titlebar is not accent-filled.
 *  - Sheet + Tint + Accent: the focused window keeps a visible accent outline,
 *    idle windows none.
 *  - Tone + Cards: the focused window's `::after` ring follows the window's
 *    own corner radius.
 *  - Soft selection: the active tab is painted `--t-accent-soft-solid`.
 *  - `--dt-window-bg` resolves to the idle / active fills under Tone and is
 *    unset under Accent.
 *  - [applyAppearance] never writes the retired `data-dt-style` stamp, for
 *    any look or combination.
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
import se.soderbjorn.lunula.core.DEFAULT_CORNER_RADIUS_PX
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ResolvedAppearance
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.UiDensity
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.core.resolveAppearance
import se.soderbjorn.lunula.web.POINTER_ACTIVE_BODY_CLASS
import se.soderbjorn.lunula.web.applyAppearance
import se.soderbjorn.lunula.web.applyChromeStyle
import se.soderbjorn.lunula.web.applyCornerRadiusPx
import se.soderbjorn.lunula.web.applyCssVars
import se.soderbjorn.lunula.web.applyFocusStyle
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.applyWindowLayout
import se.soderbjorn.lunula.web.appliedAppearance
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A palette whose every colour is distinct, written as `rgb()` because that is
 * how `getComputedStyle` reports colours back. The chrome zone deliberately
 * differs from the content zone (its own accent and accent-on), so a rule that
 * pairs a remapped fill with an un-remapped foreground shows up.
 */
private val COMBO_PALETTE = mapOf(
    "--t-bg" to "rgb(10, 11, 12)",
    "--t-canvas" to "rgb(13, 14, 15)",
    "--t-chrome-bg" to "rgb(16, 17, 18)",
    "--t-chrome-text" to "rgb(40, 41, 42)",
    "--t-chrome-text-dim" to "rgb(50, 51, 52)",
    "--t-chrome-accent" to "rgb(60, 160, 62)",
    "--t-chrome-accent-on" to "rgb(1, 2, 3)",
    "--t-chrome-accent-soft" to "rgb(64, 65, 66)",
    "--t-chrome-border" to "rgb(68, 69, 70)",
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
    "--t-text-dim-legible" to "rgb(70, 71, 72)",
    "--t-accent-soft-text" to "rgb(24, 48, 140)",
    "--t-accent-soft-solid" to "rgb(221, 226, 241)",
)

/** The fully transparent colour, as `getComputedStyle` reports it. */
private const val TRANSPARENT = "rgba(0, 0, 0, 0)"

/**
 * Combinations of style settings rendered on one sample shell; each test
 * stamps only the settings it names (see [stamp]).
 */
class StyleCombinationsTest {

    private val root: HTMLElement get() = document.documentElement as HTMLElement
    private lateinit var frame: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        document.body!!.classList.remove(POINTER_ACTIVE_BODY_CLASS)
        applyCssVars(root, COMBO_PALETTE)
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
        clearCssVars(root, COMBO_PALETTE)
    }

    /**
     * Stamps exactly the given settings on :root (each through its own
     * applier) and leaves the rest at their defaults.
     */
    private fun stamp(
        selection: SelectionStyle = SelectionStyle.Default,
        surface: SurfaceStyle = SurfaceStyle.Default,
        chrome: ChromeStyle = ChromeStyle.Default,
        windows: WindowLayout = WindowLayout.Default,
        focus: FocusStyle = FocusStyle.Default,
    ) {
        clearStyleStamps()
        applySelectionStyle(root, selection)
        applySurfaceStyle(surface)
        applyChromeStyle(root, chrome)
        applyWindowLayout(root, windows)
        applyFocusStyle(root, focus)
    }

    /**
     * Renders two side-by-side windows (`p1` focused, `p2` idle) into a
     * `.dt-app-frame-main`, each with one action, and a probe inside each
     * window's content that paints `var(--dt-window-bg, …)`.
     */
    private fun renderWindows() {
        val main = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame-main"
            it.style.height = "100%"
            frame.appendChild(it)
        }
        val container = (document.createElement("div") as HTMLElement).also { main.appendChild(it) }
        LayoutRenderer(
            container = container,
            callbacks = PaneCallbacks(
                contentRenderer = { _, slot ->
                    slot.innerHTML = ""
                    slot.appendChild((document.createElement("div") as HTMLElement).also {
                        it.className = "combo-probe"
                        it.style.setProperty("background-color", "var(--dt-window-bg, rgb(7, 8, 9))")
                        it.style.height = "10px"
                    })
                },
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
        noTransitions()
    }

    /** Renders a tab strip with a selected and an idle tab. */
    private fun renderTabs() {
        val bar = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-tabbar"
            frame.appendChild(it)
        }
        for ((id, selected) in listOf("tab-on" to true, "tab-off" to false)) {
            (document.createElement("div") as HTMLElement).also {
                it.id = id
                it.className = "dt-tab" + if (selected) " dt-selected" else ""
                it.textContent = id
                bar.appendChild(it)
            }
        }
        noTransitions()
    }

    /** Renders a left sidebar with one tab holding an active and an idle row. */
    private fun renderSidebar() {
        val content = document.createElement("div") as HTMLElement
        val section = renderSidebarSection(
            SidebarSectionSpec(
                title = "Work tab",
                isOpen = true,
                items = listOf(SidebarRow(label = "Editor", isActive = true), SidebarRow(label = "Shell")),
            ),
        )
        section.classList.add("active-tab")
        content.appendChild(section)
        frame.appendChild(renderLeftSidebar(SidebarSpec(widthPx = 260, content = content)))
        noTransitions()
    }

    /** Turns off transitions under [frame], which would report mid-flight values. */
    private fun noTransitions() {
        val all = frame.querySelectorAll("*")
        for (i in 0 until all.length) (all.item(i) as? HTMLElement)?.style?.transition = "none"
    }

    private fun pane(id: String): HTMLElement = assertNotNull(
        frame.querySelector(".${LayoutClassNames.PANE}[data-pane-id=\"$id\"]") as? HTMLElement,
        "no pane $id rendered",
    )

    private fun HTMLElement.child(cls: String): HTMLElement = assertNotNull(
        querySelector(".$cls") as? HTMLElement,
        "no .$cls under $className",
    )

    private fun css(el: HTMLElement) = window.getComputedStyle(el)

    /**
     * The colour a solid selection fill is drawn in: its background colour,
     * or under Depth (which paints the fill as a sheen gradient) the
     * gradient's base, `--dt-sheen-base`.
     */
    private fun fillOf(el: HTMLElement): String {
        val st = css(el)
        return if (st.backgroundImage != "none") st.getPropertyValue("--dt-sheen-base").trim() else st.backgroundColor
    }

    private fun el(selector: String): HTMLElement = assertNotNull(
        frame.querySelector(selector) as? HTMLElement,
        "nothing matches $selector",
    )

    // ── (b) Blended + Fill + Accent ──

    /**
     * Blended dims the chrome's controls, but must not dim what sits on Fill's
     * solid accent band: the focused window's title, icon and buttons keep
     * `--t-accent-on`, under every surface style.
     */
    @Test
    fun blendedKeepsFillsAccentOnForegroundOnTheFocusedBand() {
        renderWindows()
        for (surface in SurfaceStyle.entries) {
            stamp(selection = SelectionStyle.Fill, surface = surface, chrome = ChromeStyle.Blended)
            val focused = pane("p1")
            val header = focused.child(LayoutClassNames.PANE_HEADER)
            assertEquals(COMBO_PALETTE["--t-accent"], fillOf(header), "the band is still the accent, $surface")
            assertEquals(COMBO_PALETTE["--t-accent-on"], css(focused.child(LayoutClassNames.PANE_TITLE)).color, "title, $surface")
            assertEquals(COMBO_PALETTE["--t-accent-on"], css(focused.child(PaneHeaderClassNames.ACTION)).color, "action button, $surface")
            // Blended's ghost geometry still applies to the buttons.
            assertEquals(28.0, focused.child(PaneHeaderClassNames.ACTION).getBoundingClientRect().width, "ghost size, $surface")
            // And the idle window's buttons are Blended's dim ghosts.
            assertEquals(COMBO_PALETTE["--t-text-dim-legible"], css(pane("p2").child(PaneHeaderClassNames.ACTION)).color, "idle button, $surface")
        }
    }

    /**
     * Under Blended + Fill the active sidebar row and tab are still Fill's
     * solid pills, and their label is legible on the fill: the chrome accent
     * is remapped to the content accent, so its foreground must be the content
     * accent's `--t-accent-on` (not the theme's chrome-zone foreground, which
     * was chosen against a different colour).
     */
    @Test
    fun blendedKeepsFillsAccentOnTextOnTheActiveRowAndTab() {
        renderSidebar()
        renderTabs()
        for (surface in SurfaceStyle.entries) {
            stamp(selection = SelectionStyle.Fill, surface = surface, chrome = ChromeStyle.Blended)
            val row = css(el(".dt-sidebar-row.dt-active"))
            assertEquals(COMBO_PALETTE["--t-accent"], fillOf(el(".dt-sidebar-row.dt-active")), "active row fill, $surface")
            assertEquals(COMBO_PALETTE["--t-accent-on"], row.color, "active row label, $surface")
            val tab = css(el("#tab-on"))
            assertEquals(COMBO_PALETTE["--t-accent"], fillOf(el("#tab-on")), "selected tab fill, $surface")
            assertEquals(COMBO_PALETTE["--t-accent-on"], tab.color, "selected tab label, $surface")
            // The idle row is Blended's quiet text.
            assertEquals(COMBO_PALETTE["--t-text"], css(el(".dt-sidebar-row:not(.dt-active)")).color, "idle row, $surface")
        }
    }

    /** Under Themed chrome Fill keeps the theme's own chrome accent pair. */
    @Test
    fun themedFillKeepsTheChromeAccentPair() {
        renderSidebar()
        stamp(selection = SelectionStyle.Fill)
        for (surface in SurfaceStyle.entries) {
            stamp(selection = SelectionStyle.Fill, surface = surface)
            val row = css(el(".dt-sidebar-row.dt-active"))
            assertEquals(COMBO_PALETTE["--t-chrome-accent"], fillOf(el(".dt-sidebar-row.dt-active")), "fill, $surface")
            assertEquals(COMBO_PALETTE["--t-chrome-accent-on"], row.color, "label, $surface")
        }
    }

    // ── (c) Tone + Fill ──

    /** Tone wins over Fill on window headers: the focused titlebar is the active tone, not the accent. */
    @Test
    fun toneWithFillDoesNotAccentFillTheFocusedHeader() {
        renderWindows()
        for (surface in SurfaceStyle.entries) for (chrome in ChromeStyle.entries) {
            stamp(selection = SelectionStyle.Fill, surface = surface, chrome = chrome, focus = FocusStyle.Tone)
            val combo = "$surface/$chrome"
            val header = css(pane("p1").child(LayoutClassNames.PANE_HEADER))
            assertNotEquals(COMBO_PALETTE["--t-accent"], header.backgroundColor, "focused header accent-filled, $combo")
            assertEquals(COMBO_PALETTE["--t-window-active"], header.backgroundColor, "focused header, $combo")
            assertEquals("none", header.backgroundImage, "no Depth sheen on the band, $combo")
            assertEquals(COMBO_PALETTE["--t-text"], css(pane("p1").child(LayoutClassNames.PANE_TITLE)).color, "focused title, $combo")
            assertEquals(COMBO_PALETTE["--t-text-dim-legible"], css(pane("p1").child(PaneHeaderClassNames.ACTION)).color, "focused buttons, $combo")
        }
    }

    // ── (d) Sheet + Tint + Accent ──

    /**
     * In a sheet the resting outlines go (they would double every seam), but
     * under Tint with an Accent active window the focused window's accent
     * outline is the focus signal and must stay; idle windows have none.
     */
    @Test
    fun sheetWithTintKeepsTheFocusedAccentOutline() {
        renderWindows()
        for (surface in SurfaceStyle.entries) {
            stamp(selection = SelectionStyle.Tint, surface = surface, windows = WindowLayout.Sheet)
            val focused = css(pane("p1"))
            val idle = css(pane("p2"))
            assertEquals(COMBO_PALETTE["--t-accent"], focused.outlineColor, "focused outline colour, $surface")
            assertTrue(focused.outlineStyle != "none" && focused.outlineWidth != "0px", "focused outline drawn, $surface")
            assertEquals(TRANSPARENT, idle.outlineColor, "idle windows have no outline, $surface")
        }
        // Under Fill (or Tone) the sheet draws no focused outline: the band or
        // the tone is the signal there.
        stamp(selection = SelectionStyle.Fill, windows = WindowLayout.Sheet)
        assertEquals(TRANSPARENT, css(pane("p1")).outlineColor, "Fill in a sheet")
        stamp(selection = SelectionStyle.Tint, windows = WindowLayout.Sheet, focus = FocusStyle.Tone)
        assertEquals(TRANSPARENT, css(pane("p1")).outlineColor, "Tone in a sheet")
    }

    // ── (e) Tone + Cards ──

    /** On a rounded card the focused window's inner ring takes the card's own corner. */
    @Test
    fun toneRingFollowsTheCardsRadius() {
        renderWindows()
        stamp(focus = FocusStyle.Tone)
        for (px in listOf<Int?>(null, 6, 0, 24)) {
            applyCornerRadiusPx(px)
            val p = pane("p1")
            val paneRadius = css(p).borderTopLeftRadius
            val ringRadius = window.getComputedStyle(p, "::after").borderTopLeftRadius
            assertEquals("${px ?: DEFAULT_CORNER_RADIUS_PX}px", paneRadius, "fixture: the card's corner, $px")
            assertEquals(paneRadius, ringRadius, "ring radius at corner $px")
            assertEquals(COMBO_PALETTE["--t-window-ring"], window.getComputedStyle(p, "::after").borderTopColor)
        }
        applyCornerRadiusPx(null)
        // In a sheet the windows are square, and so is the ring.
        stamp(focus = FocusStyle.Tone, windows = WindowLayout.Sheet)
        assertEquals("0px", window.getComputedStyle(pane("p1"), "::after").borderTopLeftRadius)
    }

    // ── (f) Soft selection ──

    /** Soft selection paints the active tab in the opaque accent wash, under either chrome and every surface. */
    @Test
    fun softSelectionPaintsTheActiveTabInTheSolidWash() {
        renderTabs()
        for (surface in SurfaceStyle.entries) for (chrome in ChromeStyle.entries) {
            stamp(selection = SelectionStyle.Soft, surface = surface, chrome = chrome)
            val combo = "$surface/$chrome"
            val tab = css(el("#tab-on"))
            assertEquals(COMBO_PALETTE["--t-accent-soft-solid"], tab.backgroundColor, "selected tab, $combo")
            assertEquals(COMBO_PALETTE["--t-accent-soft-text"], tab.color, "selected tab label, $combo")
            assertEquals("none", tab.boxShadow, "no ring, $combo")
            assertNotEquals(COMBO_PALETTE["--t-accent-soft-solid"], css(el("#tab-off")).backgroundColor, "idle tab, $combo")
        }
    }

    /** Soft selection with an Accent active window: the focused titlebar takes the same wash, no glow. */
    @Test
    fun softSelectionWashesTheFocusedTitlebarUnderAccent() {
        renderWindows()
        stamp(selection = SelectionStyle.Soft)
        val header = css(pane("p1").child(LayoutClassNames.PANE_HEADER))
        assertEquals(COMBO_PALETTE["--t-accent-soft-solid"], header.backgroundColor)
        assertEquals(COMBO_PALETTE["--t-accent-soft-text"], css(pane("p1").child(LayoutClassNames.PANE_TITLE)).color)
        assertEquals(TRANSPARENT, css(pane("p1")).getPropertyValue("--t-glow").trim().let {
            if (it == "transparent") TRANSPARENT else it
        })
    }

    // ── (g) --dt-window-bg ──

    /**
     * Under Tone each window publishes its fill as `--dt-window-bg`, so host
     * content painting `var(--dt-window-bg, own)` follows it; under Accent the
     * variable is unset and the content keeps its own colour.
     */
    @Test
    fun windowBgIsPublishedUnderToneAndUnsetUnderAccent() {
        renderWindows()
        fun probe(id: String) = css(pane(id).child("combo-probe")).backgroundColor
        stamp(focus = FocusStyle.Tone)
        assertEquals(COMBO_PALETTE["--t-window-active"], probe("p1"), "focused window content")
        assertEquals(COMBO_PALETTE["--t-window-idle"], probe("p2"), "idle window content")
        stamp(focus = FocusStyle.Accent)
        assertEquals("rgb(7, 8, 9)", probe("p1"), "Accent: unset, so the content's own fallback")
        assertEquals("rgb(7, 8, 9)", probe("p2"))
        assertEquals("", css(pane("p1")).getPropertyValue("--dt-window-bg").trim())
    }

    // ── (h) The legacy stamp ──

    /**
     * The retired `data-dt-style` stamp is never written: not for either look,
     * not for the defaults, and not for any combination of the enumerated
     * style settings (no app reads it any more).
     */
    @Test
    fun applyAppearanceNeverWritesTheLegacyStamp() {
        val calm = resolveAppearance(Looks.Calm.shape)
        val named: List<Pair<String, ResolvedAppearance>> = listOf(
            "calm" to calm,
            "classic" to resolveAppearance(Looks.Classic.shape),
            "defaults" to resolveAppearance(AppearanceShape()),
            "corner unset" to calm.copy(cornerRadiusPx = null),
            "compact" to calm.copy(uiDensity = UiDensity.Compact),
        )
        for ((name, r) in named) {
            applyAppearance(root, r)
            assertNull(root.getAttribute("data-dt-style"), "the legacy stamp was written for $name")
        }
        for (chrome in ChromeStyle.entries) for (windows in WindowLayout.entries) for (focus in FocusStyle.entries)
            for (labels in LabelCase.entries) for (selection in SelectionStyle.entries) for (surface in SurfaceStyle.entries) {
                val r = calm.copy(
                    chromeStyle = chrome, windowLayout = windows, focusStyle = focus,
                    labelCase = labels, selectionStyle = selection, surfaceStyle = surface,
                )
                applyAppearance(root, r)
                assertNull(
                    root.getAttribute("data-dt-style"),
                    "the legacy stamp was written for $chrome/$windows/$focus/$labels/$selection/$surface",
                )
            }
    }

    /** [appliedAppearance] reads back exactly what [applyAppearance] stamped. */
    @Test
    fun appliedAppearanceReadsBackTheStamps() {
        for (r in listOf(resolveAppearance(Looks.Calm.shape), resolveAppearance(AppearanceShape()), resolveAppearance(Looks.Classic.shape))) {
            applyAppearance(root, r)
            assertEquals(r, appliedAppearance(root))
        }
        // Nothing stamped reads as the base stylesheet (Tint, which is what
        // an unstamped root paints).
        clearStyleStamps()
        val bare = appliedAppearance(root)
        assertEquals(SelectionStyle.Tint, bare.selectionStyle)
        assertFalse(Looks.Calm.matches(bare))
    }
}
