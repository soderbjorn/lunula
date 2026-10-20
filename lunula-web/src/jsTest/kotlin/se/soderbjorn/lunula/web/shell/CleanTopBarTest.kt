/*
 * CleanTopBarTest.kt (jsTest)
 *
 * Tests for the top bar under the two shell styles (epic LNA-16, LNA-23):
 *
 *  - Classic is exactly what it was: the leading and trailing buttons, by
 *    accessible name and in order, match the pre-LNA-23 top bar.
 *  - Under Clean the Layout, appearance-cycle, Theme manager, "Aa" and App
 *    settings buttons are gone and a single "Settings" menu button stands in
 *    their place, between "+" and the app's trailing buttons. Each row of its
 *    menu does what the old button did: cycles the appearance, opens the
 *    theme manager, the Appearance sidebar, the layout grid, App settings.
 *  - The menu is keyboard-accessible (aria-haspopup / aria-expanded, focus
 *    on open from the keyboard, arrow keys, Escape back to the button).
 *  - Switching style at runtime rebuilds the top bar, both ways.
 *  - Under Clean, with the stylesheet loaded, the icon buttons are 34×34
 *    with an 8px radius and the bar is a 52px border-box.
 *
 * The mounted tests drive the real [mountAppShell] with an in-memory
 * [Persister] and a hand-pushed [TabSource] snapshot, as NavigationModeTest
 * does. The right-hand panels (theme manager, Appearance, App settings) keep
 * their open state module-wide and close with a CSS transition, so
 * [resetRightPanels] puts them all away between steps, driving the theme
 * manager's `transitionend` itself in case no transition runs.
 *
 * @see buildSettingsMenuButton
 * @see AppShellHandle.setShellStyle
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
import se.soderbjorn.lunula.web.themeeditor.isThemeManagerSidebarOpen
import se.soderbjorn.lunula.web.themeeditor.toggleThemeManagerSidebar
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

/** A pointer click (`detail == 1`), which must not move focus into the menu. */
private fun HTMLElement.pointerClick() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true, detail = 1)))
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

/** The Clean settings button, if drawn. */
private fun HTMLElement.settingsButton(): HTMLElement? =
    querySelector(".dt-topbar-trailing .$SETTINGS_MENU_BUTTON_CLASS") as? HTMLElement

/** The open settings menu (a `.dt-pane-menu` on `<body>`), if any. */
private fun openMenu(): HTMLElement? = document.querySelector(".dt-pane-menu") as? HTMLElement

/** The top-level row of the open menu whose label is [label]. */
private fun menuRow(label: String): HTMLElement {
    val menu = openMenu() ?: fail("no menu open")
    val rows = menu.querySelectorAll(".dt-pane-menu-item")
    for (i in 0 until rows.length) {
        val row = rows.item(i) as HTMLElement
        if (row.querySelector(".dt-pane-menu-item-label")?.textContent == label) return row
    }
    fail("no menu row '$label'")
}

/** The labels of the open menu's rows, in order. */
private fun menuLabels(): List<String> {
    val rows = openMenu()?.querySelectorAll(".dt-pane-menu-item-label") ?: return emptyList()
    return (0 until rows.length).map { rows.item(it)!!.textContent.orEmpty() }
}

/** The persisted appearance, read back the way the shell will on the next boot. */
private fun persistedAppearance(p: TopBarMemoryPersister): Appearance = ThemeSnapshotV2.fromStrings(
    selectionJson = p.store[PersistKeys.THEME_V2_SELECTION],
    customThemesJson = p.store[PersistKeys.THEME_V2_CUSTOM],
).appearance

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
    handle.refresh()
    // Put away anything a step left floating on <body>.
    for (sel in listOf(".dt-pane-menu", ".dt-layout-preset-grid")) {
        val stale = document.querySelectorAll(sel)
        for (i in 0 until stale.length) (stale.item(i) as HTMLElement).remove()
    }
}

/** The Classic top bar before LNA-23, trailing cluster, for the harness below. */
private val CLASSIC_TRAILING = listOf(
    "Before",
    "New",
    "Layout",
    "Appearance: Dark",
    "Theme manager",
    "Appearance",
    "App settings",
    "After",
)

/** The Clean trailing cluster for the same harness. */
private val CLEAN_TRAILING = listOf("Before", "New", "Settings", "After")

/**
 * Mounts a shell with app buttons on both flanks, a "+" (via `onAdd`), App
 * settings content and a Dark appearance, then runs [body] once the active
 * tab's pane is on screen.
 *
 * @param style the persisted shell style to boot with, or `null` for none.
 * @param body  the test, given the root, the handle and the persister.
 */
private suspend fun withTopBarShell(
    style: ShellStyle? = null,
    body: suspend (root: HTMLElement, handle: AppShellHandle, persister: TopBarMemoryPersister) -> Unit,
) {
    val seed = ThemeSnapshotV2(
        darkThemeName = "Dracula",
        lightThemeName = "Lunamux Light",
        appearance = Appearance.Dark,
    )
    val persister = TopBarMemoryPersister(
        buildMap {
            put(PersistKeys.THEME_V2_SELECTION, seed.selectionJson())
            put(PersistKeys.THEME_V2_CUSTOM, seed.customThemesJson())
            if (style != null) put(PersistKeys.APPEARANCE_SHAPE, AppearanceShape(shellStyle = style).toJson())
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

class CleanTopBarTest {

    @AfterTest
    fun reset() {
        (document.documentElement as HTMLElement).removeAttribute("data-dt-style")
    }

    /** Classic: the same buttons, in the same order, as before LNA-23. */
    @Test
    fun classicTopBarIsUnchanged() = GlobalScope.promise {
        withTopBarShell { root, _, _ ->
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-style"))
            assertEquals(listOf("Toggle left sidebar"), root.topBarNames(".dt-topbar-leading"))
            assertEquals(CLASSIC_TRAILING, root.topBarNames(".dt-topbar-trailing"))
            assertNull(root.settingsButton(), "Classic has no settings menu button")
        }
    }

    /** Clean: the four (plus App settings) are folded away and Settings stands in. */
    @Test
    fun cleanTopBarFoldsTheButtonsIntoSettings() = GlobalScope.promise {
        withTopBarShell(style = ShellStyle.Clean) { root, _, _ ->
            assertEquals("clean", (document.documentElement as HTMLElement).getAttribute("data-dt-style"))
            assertEquals(listOf("Toggle left sidebar"), root.topBarNames(".dt-topbar-leading"), "sidebar toggle kept")
            assertEquals(CLEAN_TRAILING, root.topBarNames(".dt-topbar-trailing"))
            val settings = assertNotNull(root.settingsButton())
            assertEquals("Settings", settings.getAttribute("aria-label"))
            assertEquals("menu", settings.getAttribute("aria-haspopup"))
            assertEquals("false", settings.getAttribute("aria-expanded"))

            settings.pointerClick()
            waitUntil("menu open") { openMenu() != null }
            assertEquals("true", settings.getAttribute("aria-expanded"))
            assertTrue(openMenu()!!.classList.contains(MenuTriggerClassNames.CHROME), "a chrome-zone menu")
            assertEquals(
                listOf("Appearance: Dark", "Themes…", "Appearance…", "Layout", "App settings"),
                menuLabels(),
            )
            // A second press closes it again.
            settings.pointerClick()
            waitUntil("menu closed") { openMenu() == null }
            assertEquals("false", settings.getAttribute("aria-expanded"))
        }
    }

    /** Each menu row runs the action its Classic button ran. */
    @Test
    fun eachMenuRowDoesWhatItsButtonDid() = GlobalScope.promise {
        withTopBarShell(style = ShellStyle.Clean) { root, handle, persister ->
            // Appearance: the same cycle — Dark → Light, persisted.
            root.settingsButton()!!.pointerClick()
            menuRow("Appearance: Dark").click0()
            waitUntil("appearance cycled to Light") { persistedAppearance(persister) == Appearance.Light }
            root.settingsButton()!!.pointerClick()
            assertNotNull(menuRow("Appearance: Light"), "the row reads the live appearance")
            menuRow("Appearance: Light").click0()
            waitUntil("appearance cycled to Auto") { persistedAppearance(persister) == Appearance.Auto }

            // Themes…: the theme manager opens, and the button reads as pressed.
            resetRightPanels(handle)
            root.settingsButton()!!.pointerClick()
            menuRow("Themes…").click0()
            waitUntil("theme manager open") {
                isThemeManagerSidebarOpen() && document.querySelector(".dt-theme-card") != null
            }
            waitUntil("settings button pressed") { root.settingsButton()?.classList?.contains("dt-active") == true }

            // Appearance…: the "Aa" sidebar.
            resetRightPanels(handle)
            root.settingsButton()!!.pointerClick()
            menuRow("Appearance…").click0()
            waitUntil("Appearance sidebar open") {
                isSettingsSidebarOpen() &&
                    root.querySelector(".dt-settings-sidebar-title")?.textContent == "Appearance"
            }

            // App settings: the app's own sidebar content.
            resetRightPanels(handle)
            root.settingsButton()!!.pointerClick()
            menuRow("App settings").click0()
            waitUntil("App settings open") {
                isAppSettingsSidebarOpen() && document.getElementById("tb-app-settings-body") != null
            }

            // Layout: the same layout grid, hung under the settings button.
            resetRightPanels(handle)
            root.settingsButton()!!.pointerClick()
            menuRow("Layout").click0()
            waitUntil("layout grid open") { document.querySelector(".dt-layout-preset-grid") != null }
            assertNotNull(
                document.querySelector(".dt-layout-preset-grid .dt-layout-preset-tile-auto"),
                "the grid offers the same presets, Auto first",
            )
            pressKey("Escape")
            waitUntil("layout grid closed") { document.querySelector(".dt-layout-preset-grid") == null }
        }
    }

    /** The menu can be driven from the keyboard alone. */
    @Test
    fun theSettingsMenuIsKeyboardAccessible() = GlobalScope.promise {
        withTopBarShell(style = ShellStyle.Clean) { root, _, _ ->
            val settings = root.settingsButton()!!
            settings.focus()
            // Enter/Space on a button arrive as a click with detail 0.
            settings.click0()
            waitUntil("menu open") { openMenu() != null }
            assertEquals(menuRow("Appearance: Dark"), document.activeElement, "focus moves to the first row")
            pressKey("ArrowDown")
            assertEquals(menuRow("Themes…"), document.activeElement)
            pressKey("End")
            assertEquals(menuRow("App settings"), document.activeElement)
            pressKey("ArrowDown")
            assertEquals(menuRow("Appearance: Dark"), document.activeElement, "wraps to the top")
            pressKey("ArrowUp")
            assertEquals(menuRow("App settings"), document.activeElement, "wraps to the bottom")
            pressKey("Escape")
            waitUntil("menu closed") { openMenu() == null }
            assertEquals(settings, document.activeElement, "Escape hands focus back to the button")
            assertEquals("false", settings.getAttribute("aria-expanded"))

            // ArrowDown on the closed button opens the menu too.
            pressKey("ArrowDown")
            waitUntil("menu open from ArrowDown") { openMenu() != null }
            assertEquals(menuRow("Appearance: Dark"), document.activeElement)
            pressKey("Escape")
            waitUntil("menu closed again") { openMenu() == null }
        }
    }

    /** A style switch at runtime rebuilds the top bar, both ways, and persists. */
    @Test
    fun switchingStyleAtRuntimeRebuildsTheTopBar() = GlobalScope.promise {
        withTopBarShell { root, handle, persister ->
            assertEquals(CLASSIC_TRAILING, root.topBarNames(".dt-topbar-trailing"))

            handle.setShellStyle(ShellStyle.Clean)
            waitUntil("Clean top bar") { root.settingsButton() != null }
            assertEquals(CLEAN_TRAILING, root.topBarNames(".dt-topbar-trailing"))
            assertEquals("clean", (document.documentElement as HTMLElement).getAttribute("data-dt-style"))
            waitUntil("Clean persisted") {
                AppearanceShape.fromJson(persister.store[PersistKeys.APPEARANCE_SHAPE]).shellStyle == ShellStyle.Clean
            }

            handle.setShellStyle(ShellStyle.Classic)
            waitUntil("Classic top bar") { root.settingsButton() == null }
            assertEquals(CLASSIC_TRAILING, root.topBarNames(".dt-topbar-trailing"))
            assertNull((document.documentElement as HTMLElement).getAttribute("data-dt-style"))
        }
    }

    /** With the stylesheet loaded, Clean draws the design's ghost buttons on a 52px bar. */
    @Test
    fun cleanTopBarMetrics() = GlobalScope.promise {
        injectLunulaStyles()
        withTopBarShell(style = ShellStyle.Clean) { root, _, _ ->
            val settings = root.settingsButton()!!
            val css = window.getComputedStyle(settings)
            assertEquals("34px", css.width)
            assertEquals("34px", css.height)
            assertEquals("8px", css.borderTopLeftRadius)
            val bar = root.querySelector(".dt-topbar") as HTMLElement
            val barCss = window.getComputedStyle(bar)
            assertEquals("border-box", barCss.boxSizing)
            assertEquals("52px", barCss.minHeight)
            // Open, the settings button wears the border-toned chip, not the accent.
            settings.pointerClick()
            waitUntil("menu open") { openMenu() != null }
            val open = window.getComputedStyle(settings)
            assertTrue(open.backgroundColor != "rgba(0, 0, 0, 0)", "the open button shows a chip")
            settings.pointerClick()
        }
    }
}
