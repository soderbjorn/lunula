/*
 * CalmTopBarTest.kt (jsTest)
 *
 * Tests for the top bar under the style settings (epic LNA-16, LNA-23):
 *
 *  - The defaults are exactly what they were: the leading and trailing
 *    buttons, by accessible name and in order, match the top bar with the
 *    single palette button ("Themes and appearance") that replaced the
 *    separate Theme manager and "Aa" Appearance buttons.
 *  - The Calm look shows the very same buttons, in the same order ("+",
 *    Layout, the appearance cycle, Themes and appearance, App settings,
 *    flanked by the app's own buttons), and each one still does what it does
 *    under the defaults; the palette button opens the Appearance pane, whose
 *    Appearance tab is the settings' only entry point.
 *  - Switching style at runtime restamps `:root` without rebuilding the top
 *    bar, both ways, and keeps the same buttons.
 *  - Under Blended chrome (alone, or as part of the Calm look), with the
 *    stylesheet loaded, the icon buttons are 34×34 ghosts with an 8px
 *    radius, 18px glyphs in the legible dim, the bar is a 52px border-box,
 *    and an open button wears the `--t-border` chip.
 *  - Dark mode: under Calm, cycling the appearance to Dark really applies
 *    the dark slot's theme (`--t-bg` and `color-scheme`), both with the
 *    toolkit's own theme host and with an app-supplied
 *    [AppShellSpec.settingsHost].
 *
 * The mounted tests drive the real [mountAppShell] with an in-memory
 * [Persister] and a hand-pushed [TabSource] snapshot, as NavigationModeTest
 * does. The right-hand panels (the Appearance pane, App settings) keep
 * their open state module-wide and close with a CSS transition, so
 * [resetRightPanels] puts them all away between steps, driving the theme
 * manager's `transitionend` itself in case no transition runs.
 *
 * @see AppShellHandle.setShellStyle
 * @see buildAppearanceCycleButton
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.web.injectLunulaStyles
import se.soderbjorn.lunula.web.settings.closeAppSettingsSidebar
import se.soderbjorn.lunula.web.settings.closeHotkeysSidebar
import se.soderbjorn.lunula.web.settings.closeNotificationsSidebar
import se.soderbjorn.lunula.web.settings.closeSettingsSidebar
import se.soderbjorn.lunula.web.settings.isAppSettingsSidebarOpen
import se.soderbjorn.lunula.web.settings.isSettingsSidebarOpen
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerState
import se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.isThemeManagerSidebarOpen
import se.soderbjorn.lunula.web.themeeditor.toggleThemeManagerSidebar
import se.soderbjorn.lunula.web.toCssVarMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class TopBarMemoryPersister(seed: Map<String, String>) : Persister {
    val store = mutableMapOf<String, String>().apply { putAll(seed) }
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

/** Polls [condition] every tick until it holds or ~3s elapse. */
private suspend fun waitUntil(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

/** A click as a script or the keyboard produces it (`detail == 0`). */
private fun HTMLElement.click0() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

/** Dispatches a keydown for [key] on the focused element (or the body). */
private fun pressKey(key: String) {
    val target = (document.activeElement as? HTMLElement) ?: document.body!!
    target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/** A button's accessible name as the top bar sets it: `aria-label`, else `title`. */
private fun HTMLElement.accessibleName(): String =
    getAttribute("aria-label")?.takeIf { it.isNotEmpty() } ?: getAttribute("title").orEmpty()

/** The accessible names of the buttons in [selector] inside the top bar, in DOM order. */
private fun HTMLElement.topBarNames(selector: String): List<String> {
    val list = querySelectorAll("$selector button")
    return (0 until list.length).map { (list.item(it) as HTMLElement).accessibleName() }
}

/** The trailing top-bar button whose accessible name is [name]. */
private fun HTMLElement.trailingButton(name: String): HTMLElement {
    val list = querySelectorAll(".dt-topbar-trailing button")
    for (i in 0 until list.length) {
        val b = list.item(i) as HTMLElement
        if (b.accessibleName() == name) return b
    }
    fail("no top-bar button '$name' in ${topBarNames(".dt-topbar-trailing")}")
}

/** The trailing top-bar button whose name starts with "Appearance: " (the cycle). */
private fun HTMLElement.appearanceCycleButton(): HTMLElement {
    val list = querySelectorAll(".dt-topbar-trailing button")
    for (i in 0 until list.length) {
        val b = list.item(i) as HTMLElement
        if (b.accessibleName().startsWith("Appearance: ")) return b
    }
    fail("no appearance cycle button")
}

/** The persisted appearance, read back the way the shell will on the next boot. */
private fun persistedAppearance(p: TopBarMemoryPersister): Appearance = ThemeSnapshotV2.fromStrings(
    selectionJson = p.store[PersistKeys.THEME_V2_SELECTION],
    customThemesJson = p.store[PersistKeys.THEME_V2_CUSTOM],
).appearance

/** The `:root` inline value of the CSS custom property or property [name]. */
private fun rootStyle(name: String): String =
    (document.documentElement as HTMLElement).style.getPropertyValue(name).trim()

/**
 * Puts every right-hand panel away, synchronously where the toolkit allows
 * it and by driving the theme manager's close transition where it does not.
 */
private suspend fun resetRightPanels(handle: AppShellHandle) {
    closeSettingsSidebar()
    closeAppSettingsSidebar()
    closeHotkeysSidebar()
    closeNotificationsSidebar()
    if (isThemeManagerSidebarOpen()) {
        toggleThemeManagerSidebar { handle.refresh() }
        repeat(150) {
            if (!isThemeManagerSidebarOpen()) return@repeat
            delay(20)
            val sidebars = document.querySelectorAll(".dt-sidebar, aside")
            for (i in 0 until sidebars.length) {
                sidebars.item(i)!!.dispatchEvent(Event("transitionend"))
            }
        }
        if (isThemeManagerSidebarOpen()) fail("could not close the theme manager between steps")
    }
    // The pane reopens on the tab last shown: put it back on Themes.
    resetAppearancePane()
    handle.refresh()
    // Put away anything a step left floating on <body>.
    for (sel in listOf(".dt-pane-menu", ".dt-layout-preset-grid")) {
        val stale = document.querySelectorAll(sel)
        for (i in 0 until stale.length) (stale.item(i) as HTMLElement).remove()
    }
}

/**
 * The top bar's trailing cluster for the harness below, the same under every
 * style: the app's "Before" button, the toolkit's standard cluster in its
 * order, then the app's "After" button. The appearance cycle reads
 * "Appearance: Dark" because the harness seeds a Dark appearance. There is
 * one palette button for themes and appearance; the "Aa" button is gone.
 */
private val TRAILING = listOf(
    "Before",
    "New",
    "Layout",
    "Appearance: Dark",
    APPEARANCE_PANE_BUTTON_TITLE,
    "App settings",
    "After",
)

/** The calm pair the dark-mode tests bind to the two slots. */
private const val CALM_DARK = "Lunicle Calm Dark"
private const val CALM_LIGHT = "Lunicle Calm Light"

/**
 * Mounts a shell with app buttons on both flanks, a "+" (via `onAdd`) and
 * App settings content, then runs [body] once the active tab's pane is on
 * screen.
 *
 * @param style the persisted legacy shell style to boot with, or `null` for none.
 * @param blob  a persisted shape blob to boot with instead (wins over [style]).
 * @param seed the persisted theme selection (slots and appearance).
 * @param settingsHost an app-supplied theme host, or `null` for the
 *   toolkit's own.
 * @param body the test, given the root, the handle and the persister.
 */
private suspend fun withTopBarShell(
    style: ShellStyle? = null,
    blob: String? = null,
    seed: ThemeSnapshotV2 = ThemeSnapshotV2(
        darkThemeName = "Dracula",
        lightThemeName = "Lunamux Light",
        appearance = Appearance.Dark,
    ),
    settingsHost: ThemeManagerHost? = null,
    body: suspend (root: HTMLElement, handle: AppShellHandle, persister: TopBarMemoryPersister) -> Unit,
) {
    val persister = TopBarMemoryPersister(
        buildMap {
            put(PersistKeys.THEME_V2_SELECTION, seed.selectionJson())
            put(PersistKeys.THEME_V2_CUSTOM, seed.customThemesJson())
            val stored = blob ?: style?.let { AppearanceShape(shellStyle = it).toJson() }
            if (stored != null) put(PersistKeys.APPEARANCE_SHAPE, stored)
        },
    )
    var push: ((TabListSnapshot) -> Unit)? = null
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "topbar-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = TabSource(subscribe = { p -> push = p }, onSelect = { }, onAdd = { }),
                extraTopbarBeforeStandard = listOf(TopbarAction(id = "tb-before", label = "Before")),
                extraTopbarTrailing = listOf(TopbarAction(id = "tb-after", label = "After")),
                appSettingsContent = {
                    (document.createElement("div") as HTMLElement).also { it.id = "tb-app-settings-body" }
                },
                settingsHost = settingsHost,
            ),
        )
        waitUntil("tab source subscribe") { push != null }
        push!!(
            TabListSnapshot(
                tabs = listOf(TabSnapshotEntry(id = "t1", label = "One", panes = listOf(PaneSnapshotEntry("p1")))),
                activeTabId = "t1",
            ),
        )
        waitUntil("pane rendered") { root.querySelector(".dt-pane[data-pane-id=\"p1\"]") != null }
        waitUntil("seeded appearance applied") { root.topBarNames(".dt-topbar-trailing").isNotEmpty() }
        resetRightPanels(handle)
        body(root, handle, persister)
        resetRightPanels(handle)
        handle.dispose()
    } finally {
        root.remove()
    }
}

/**
 * Seeds the calm pair on a Light appearance under Calm, presses the top
 * bar's appearance cycle until it reads Dark, and checks that `:root` now
 * carries the dark slot's theme: its `--t-bg` and `color-scheme: dark`.
 *
 * Shared by the toolkit-host and app-host variants of the dark-mode test.
 *
 * @param root the mounted shell's root.
 */
private suspend fun cycleToDarkAndCheck(root: HTMLElement) {
    val seeded = ThemeSnapshotV2(darkThemeName = CALM_DARK, lightThemeName = CALM_LIGHT)
    val darkBg = seeded.copy(appearance = Appearance.Dark).resolve(systemIsDark = false).toCssVarMap()["--t-bg"]
    val lightBg = seeded.copy(appearance = Appearance.Light).resolve(systemIsDark = true).toCssVarMap()["--t-bg"]
    assertNotNull(darkBg)
    assertNotEquals(lightBg, darkBg, "the calm pair must differ for this test to mean anything")

    assertEquals("sheet", (document.documentElement as HTMLElement).getAttribute("data-dt-windows"), "the Calm look is applied")
    waitUntil("light slot painted") { rootStyle("--t-bg") == lightBg }
    assertEquals("light", rootStyle("color-scheme"))

    // Light → Auto → Dark, through the real button each time (it is rebuilt
    // on every rerender, so look it up afresh).
    repeat(3) {
        if (root.appearanceCycleButton().accessibleName() == "Appearance: Dark") return@repeat
        root.appearanceCycleButton().click0()
        delay(20)
    }
    waitUntil("button reads Dark") { root.appearanceCycleButton().accessibleName() == "Appearance: Dark" }
    waitUntil("dark slot painted") { rootStyle("--t-bg") == darkBg }
    assertEquals("dark", rootStyle("color-scheme"))
    // And it stays dark: nothing in the rerender that followed put it back.
    delay(100)
    assertEquals(darkBg, rootStyle("--t-bg"))
    assertEquals("dark", rootStyle("color-scheme"))
}

class CalmTopBarTest {

    @AfterTest
    fun reset() {
        resetAppearancePane()
        clearStyleStamps()
    }

    /** The defaults: the same buttons, in the same order, with one palette button. */
    @Test
    fun classicTopBarIsUnchanged() = GlobalScope.promise {
        withTopBarShell { root, _, _ ->
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-style"))
            assertEquals(listOf("Toggle left sidebar"), root.topBarNames(".dt-topbar-leading"))
            assertEquals(TRAILING, root.topBarNames(".dt-topbar-trailing"))
            assertTrue("Appearance" !in root.topBarNames(".dt-topbar-trailing"), "the \"Aa\" button is gone")
            assertTrue("Theme manager" !in root.topBarNames(".dt-topbar-trailing"))
        }
    }

    /** Calm: exactly the default buttons, in the default order, and no settings menu. */
    @Test
    fun calmTopBarHasClassicsButtonsInClassicsOrder() = GlobalScope.promise {
        withTopBarShell(style = ShellStyle.Calm) { root, _, _ ->
            assertEquals("sheet", (document.documentElement as HTMLElement).getAttribute("data-dt-windows"), "the Calm look is applied")
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-style"), "the legacy stamp is never written")
            assertEquals(listOf("Toggle left sidebar"), root.topBarNames(".dt-topbar-leading"))
            assertEquals(TRAILING, root.topBarNames(".dt-topbar-trailing"))
            assertNull(root.querySelector(".dt-topbar-settings-button"), "no folded settings menu")
        }
    }

    /** Under Calm each button does what it does under the defaults. */
    @Test
    fun eachButtonWorksUnderCalm() = GlobalScope.promise {
        withTopBarShell(style = ShellStyle.Calm) { root, handle, persister ->
            // Appearance cycle: Dark → Light → Auto, persisted, label follows.
            root.appearanceCycleButton().click0()
            waitUntil("appearance cycled to Light") { persistedAppearance(persister) == Appearance.Light }
            waitUntil("label reads Light") { root.appearanceCycleButton().accessibleName() == "Appearance: Light" }
            root.appearanceCycleButton().click0()
            waitUntil("appearance cycled to Auto") { persistedAppearance(persister) == Appearance.Auto }

            // Themes and appearance: opens on Themes, and its button reads as pressed.
            resetRightPanels(handle)
            root.trailingButton(APPEARANCE_PANE_BUTTON_TITLE).click0()
            waitUntil("theme manager open") {
                isThemeManagerSidebarOpen() && document.querySelector(".dt-theme-card") != null
            }
            waitUntil("palette button pressed") {
                root.trailingButton(APPEARANCE_PANE_BUTTON_TITLE).classList.contains("dt-active")
            }

            // Its Appearance tab is where the settings live now.
            (document.querySelector(".dt-appearance-tab[data-dt-appearance-tab=\"appearance\"]") as HTMLElement).click0()
            waitUntil("Appearance settings shown") {
                document.querySelector(".dt-settings-group[data-dt-group=\"mode-style\"]") != null
            }
            assertTrue(!isSettingsSidebarOpen(), "the settings live in the pane, not a separate sidebar")

            // App settings: the app's own sidebar content.
            resetRightPanels(handle)
            root.trailingButton("App settings").click0()
            waitUntil("App settings open") {
                isAppSettingsSidebarOpen() && document.getElementById("tb-app-settings-body") != null
            }

            // Layout: the layout grid, Auto first.
            resetRightPanels(handle)
            root.trailingButton("Layout").click0()
            waitUntil("layout grid open") { document.querySelector(".dt-layout-preset-grid") != null }
            assertNotNull(
                document.querySelector(".dt-layout-preset-grid .dt-layout-preset-tile-auto"),
                "the grid offers the same presets, Auto first",
            )
            pressKey("Escape")
            waitUntil("layout grid closed") { document.querySelector(".dt-layout-preset-grid") == null }
        }
    }

    /**
     * A style switch at runtime restamps `:root` in place, both ways, and
     * persists it as the active mode's entry. The top bar is not rebuilt (the
     * stylesheet draws the style from the stamps; only a navigation-surface
     * change rebuilds the chrome) and keeps the same buttons.
     */
    @Test
    fun switchingStyleAtRuntimeRestampsWithoutRebuildingTheTopBar() = GlobalScope.promise {
        withTopBarShell { root, handle, persister ->
            assertEquals(TRAILING, root.topBarNames(".dt-topbar-trailing"))
            val layoutButton = root.trailingButton("Layout")

            handle.setShellStyle(ShellStyle.Calm)
            waitUntil("Calm stamped") {
                (document.documentElement as HTMLElement).getAttribute("data-dt-windows") == "sheet"
            }
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-style"), "the legacy stamp is never written")
            assertEquals("blended", (document.documentElement as HTMLElement).getAttribute("data-dt-chrome"))
            assertEquals(TRAILING, root.topBarNames(".dt-topbar-trailing"))
            waitUntil("Calm persisted for the active (dark) mode") {
                ModeStyles.fromJson(persister.store[PersistKeys.APPEARANCE_SHAPE])?.dark == Looks.Calm.shape
            }
            assertTrue(root.trailingButton("Layout") === layoutButton, "a style change must not rebuild the top bar")

            handle.setShellStyle(ShellStyle.Classic)
            waitUntil("Classic stamped") {
                (document.documentElement as HTMLElement).getAttribute("data-dt-windows") == null
            }
            assertEquals(null, (document.documentElement as HTMLElement).getAttribute("data-dt-chrome"))
            assertEquals(TRAILING, root.topBarNames(".dt-topbar-trailing"))
            waitUntil("Classic persisted for the active (dark) mode") {
                ModeStyles.fromJson(persister.store[PersistKeys.APPEARANCE_SHAPE])?.dark == Looks.Classic.shape
            }
        }
    }

    /**
     * With the stylesheet loaded, Blended chrome (alone, and as part of the
     * Calm look) draws the design's ghost buttons on a 52px bar: 34×34,
     * radius 8, 18px glyphs in the legible dim, and an open button wears a
     * chip. The defaults keep their own 30px buttons.
     */
    @Test
    fun blendedTopBarMetrics() = GlobalScope.promise {
        injectLunulaStyles()
        withTopBarShell { root, _, _ ->
            assertEquals("30px", window.getComputedStyle(root.trailingButton("Layout")).width, "the defaults are unchanged")
        }
        // The new settings are per mode, so they live in the blob's mode entries.
        val blendedBlob = ModeStyles.shapeBlob(
            AppearanceShape(),
            ModeStyles(dark = AppearanceShape(chromeStyle = ChromeStyle.Blended), light = AppearanceShape(chromeStyle = ChromeStyle.Blended)),
        )
        withTopBarShell(blob = blendedBlob) { root, _, _ ->
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-windows"), "Blended alone is not Calm")
            val css = window.getComputedStyle(root.trailingButton("Layout"))
            assertEquals("34px", css.width, "Blended alone draws the ghost buttons")
            assertEquals("8px", css.borderTopLeftRadius)
            // 9px block padding around the 34px row; the min-height token is
            // the Spacing setting's (Compact here), not Blended's.
            assertEquals(52.0, (root.querySelector(".dt-topbar") as HTMLElement).getBoundingClientRect().height)
        }
        withTopBarShell(style = ShellStyle.Calm) { root, handle, _ ->
            for (name in TRAILING.filter { it != "Before" && it != "After" && !it.startsWith("Appearance: ") } +
                root.appearanceCycleButton().accessibleName()
            ) {
                val css = window.getComputedStyle(root.trailingButton(name))
                assertEquals("34px", css.width, "$name width")
                assertEquals("34px", css.height, "$name height")
                assertEquals("8px", css.borderTopLeftRadius, "$name radius")
            }
            val layout = root.trailingButton("Layout")
            val glyph = assertNotNull(layout.querySelector("svg"), "Layout has an SVG glyph")
            assertEquals("18px", window.getComputedStyle(glyph).width)
            // The legible dim, resolved the way the rule resolves it.
            val probe = (document.createElement("span") as HTMLElement).also {
                it.style.setProperty("color", "var(--dt-dim-text)")
                root.querySelector(".dt-topbar")!!.appendChild(it)
            }
            assertEquals(window.getComputedStyle(probe).color, window.getComputedStyle(layout).color)
            probe.remove()

            val bar = root.querySelector(".dt-topbar") as HTMLElement
            val barCss = window.getComputedStyle(bar)
            assertEquals("border-box", barCss.boxSizing)
            assertEquals("52px", barCss.minHeight)

            // Open, the palette button wears the border-toned chip.
            root.trailingButton(APPEARANCE_PANE_BUTTON_TITLE).click0()
            // Let the manager finish opening before it is put away again, so
            // no half-open panel outlives this test.
            waitUntil("theme manager open") {
                isThemeManagerSidebarOpen() && document.querySelector(".dt-theme-card") != null
            }
            waitUntil("theme manager button pressed") {
                root.trailingButton(APPEARANCE_PANE_BUTTON_TITLE).classList.contains("dt-active")
            }
            val open = window.getComputedStyle(root.trailingButton(APPEARANCE_PANE_BUTTON_TITLE))
            assertTrue(open.backgroundColor != "rgba(0, 0, 0, 0)", "the open button shows a chip")
            assertEquals("none", open.boxShadow)
            resetRightPanels(handle)
        }
    }

    /** Calm, the toolkit's own host: cycling to Dark paints the dark slot. */
    @Test
    fun cyclingToDarkUnderCalmAppliesTheDarkSlot() = GlobalScope.promise {
        injectLunulaStyles()
        withTopBarShell(
            style = ShellStyle.Calm,
            seed = ThemeSnapshotV2(darkThemeName = CALM_DARK, lightThemeName = CALM_LIGHT, appearance = Appearance.Light),
        ) { root, _, persister ->
            cycleToDarkAndCheck(root)
            waitUntil("Dark persisted") { persistedAppearance(persister) == Appearance.Dark }
        }
    }

    /** Calm, an app-supplied settings host: cycling to Dark paints the dark slot too. */
    @Test
    fun cyclingToDarkUnderCalmWithAnAppHostAppliesTheDarkSlot() = GlobalScope.promise {
        injectLunulaStyles()
        val appState = DefaultThemeManagerState(
            darkThemeName = CALM_DARK,
            lightThemeName = CALM_LIGHT,
            appearance = Appearance.Light,
            shellStyle = ShellStyle.Calm,
        )
        withTopBarShell(
            seed = ThemeSnapshotV2(darkThemeName = CALM_DARK, lightThemeName = CALM_LIGHT, appearance = Appearance.Light),
            settingsHost = DefaultThemeManagerHost(appState),
        ) { root, _, _ ->
            cycleToDarkAndCheck(root)
        }
    }
}
