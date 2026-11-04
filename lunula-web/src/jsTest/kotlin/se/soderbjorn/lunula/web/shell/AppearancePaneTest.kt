/*
 * AppearancePaneTest.kt (jsTest)
 *
 * Shell-level tests, through a real [mountAppShell] with an in-memory
 * [Persister], for two things the style-settings split changed:
 *
 *  - The Appearance pane. The top bar has one palette button ("Themes and
 *    appearance"); it opens a right-hand pane with a "Themes" | "Appearance" |
 *    "Fonts" | "Behaviour" tab strip, where Themes is the Theme Manager and
 *    the other three are the settings pages (the separate "Aa" button is
 *    gone). The pane reopens
 *    on the tab last shown, and [AppShellHandle.openAppearance] opens or
 *    switches it.
 *  - Per-mode style settings. With [ModeStyles] stored (dark = the Calm look,
 *    light = the Classic look), the `:root` style stamps flip when the
 *    appearance switches between Dark and Light, whether the settings come
 *    from the persisted shape blob or from an app-supplied settings host.
 *
 * `lunula.css` is not loaded in karma here, so every assertion is structural.
 *
 * @see se.soderbjorn.lunula.web.themeeditor.buildThemeManagerSidebar
 * @see se.soderbjorn.lunula.core.ModeStyles
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
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
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.web.settings.closeSettingsSidebar
import se.soderbjorn.lunula.web.settings.isSettingsSidebarOpen
import se.soderbjorn.lunula.web.themeeditor.AppearanceTab
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.DefaultThemeManagerState
import se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.closeThemeManager
import se.soderbjorn.lunula.web.themeeditor.currentAppearanceTab
import se.soderbjorn.lunula.web.themeeditor.isThemeManagerSidebarOpen
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class PaneMemoryPersister(seed: Map<String, String>) : Persister {
    val store = mutableMapOf<String, String>().apply { putAll(seed) }
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

/** Dark = the Calm look, Light = the Classic look. */
private val DARK_CALM_LIGHT_CLASSIC = ModeStyles(dark = Looks.Calm.shape, light = Looks.Classic.shape)

/** Polls [condition] every tick until it holds or ~3s elapse. */
private suspend fun eventually(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

private fun HTMLElement.tap() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

/** The document root, where the shell stamps its `data-dt-*` attributes. */
private fun docRoot(): HTMLElement = document.documentElement as HTMLElement

/** The Appearance pane's tab button for [tab], or `null` when the pane is not mounted. */
private fun tabButton(tab: String): HTMLElement? =
    document.querySelector(".dt-appearance-tabs .dt-appearance-tab[data-dt-appearance-tab=\"$tab\"]") as? HTMLElement

/** The accessible names of the top bar's trailing buttons, in DOM order. */
private fun HTMLElement.trailingNames(): List<String> {
    val list = querySelectorAll(".dt-topbar-trailing button")
    return (0 until list.length).map {
        val b = list.item(it) as HTMLElement
        b.getAttribute("aria-label")?.takeIf { l -> l.isNotEmpty() } ?: b.getAttribute("title").orEmpty()
    }
}

/**
 * Mounts a shell seeded with [appearance] and, optionally, a persisted shape
 * [blob] or an app-supplied [settingsHost], waits for the top bar and runs
 * [body]. The pane is closed and reset to its Themes tab afterwards.
 *
 * @param appearance   the appearance the persisted theme selection starts in.
 * @param blob         the persisted [PersistKeys.APPEARANCE_SHAPE], or `null`.
 * @param settingsHost an app-supplied host, or `null` for the toolkit's own.
 * @param isElectron   the spec's [AppShellSpec.isElectron].
 * @param body         the test, given the root, the handle and the persister.
 */
private suspend fun withPaneShell(
    appearance: Appearance = Appearance.Dark,
    blob: String? = null,
    settingsHost: ThemeManagerHost? = null,
    isElectron: Boolean = false,
    body: suspend (root: HTMLElement, handle: AppShellHandle, persister: PaneMemoryPersister) -> Unit,
) {
    closeSettingsSidebar()
    resetAppearancePane()
    val snap = ThemeSnapshotV2(darkThemeName = "Dracula", lightThemeName = "Lunamux Light", appearance = appearance)
    val persister = PaneMemoryPersister(
        buildMap {
            put(PersistKeys.THEME_V2_SELECTION, snap.selectionJson())
            put(PersistKeys.THEME_V2_CUSTOM, snap.customThemesJson())
            if (blob != null) put(PersistKeys.APPEARANCE_SHAPE, blob)
        },
    )
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "appearance-pane-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = TabSource(subscribe = { }, onSelect = { }),
                settingsHost = settingsHost,
                isElectron = isElectron,
            ),
        )
        eventually("topbar rendered") { root.querySelector(APPEARANCE_PANE_BUTTON) != null }
        body(root, handle, persister)
        handle.dispose()
    } finally {
        resetAppearancePane()
        closeThemeManager()
        root.remove()
    }
}

/**
 * Presses the top bar's appearance cycle (Auto → Dark → Light → Auto) until
 * it reads [target]. The button is rebuilt on every rerender, so it is looked
 * up afresh each time.
 */
private suspend fun cycleTo(root: HTMLElement, target: String) {
    repeat(4) {
        val btn = root.querySelector("button[title=\"Appearance: $target\"]")
        if (btn != null) return
        val any = (0 until root.querySelectorAll(".dt-topbar-trailing button").length)
            .map { root.querySelectorAll(".dt-topbar-trailing button").item(it) as HTMLElement }
            .firstOrNull { (it.getAttribute("title") ?: "").startsWith("Appearance: ") }
            ?: fail("no appearance cycle button")
        any.tap()
        delay(30)
    }
    eventually("cycle reads $target") { root.querySelector("button[title=\"Appearance: $target\"]") != null }
}

/** The Appearance pane and per-mode style switching, through a mounted shell. */
class AppearancePaneTest {

    @AfterTest
    fun reset() {
        resetAppearancePane()
        clearStyleStamps()
    }

    // ── (j) The Appearance pane ──

    /** One palette button, no "Aa" button and no separate Theme manager button. */
    @Test
    fun theTopBarHasOnePaletteButtonAndNoAppearanceButton() = GlobalScope.promise {
        withPaneShell { root, _, _ ->
            val names = root.trailingNames()
            assertEquals(1, names.count { it == APPEARANCE_PANE_BUTTON_TITLE }, "names: $names")
            assertFalse("Appearance" in names, "the \"Aa\" Appearance button is gone: $names")
            assertFalse("Theme manager" in names, "the palette button replaced it: $names")
            assertNull(root.querySelector(".dt-topbar-settings-button"))
        }
    }

    /**
     * The palette button opens the pane on Themes (the Theme Manager); the
     * Appearance tab shows the style settings instead and Fonts the font
     * lines. Outside Electron the Behaviour tab is still there, for "Tabs in
     * the top bar" (the app draws both surfaces).
     */
    @Test
    fun thePaletteButtonOpensTheTabbedPane() = GlobalScope.promise {
        withPaneShell { root, handle, _ ->
            (root.querySelector(APPEARANCE_PANE_BUTTON) as HTMLElement).tap()
            eventually("pane open on Themes") { document.querySelector(".dt-theme-card") != null }
            assertTrue(isThemeManagerSidebarOpen())
            assertNotNull(document.querySelector(".dt-appearance-pane .dt-appearance-tabs"), "the tab strip")
            assertEquals("Themes", tabButton("themes")?.textContent)
            assertEquals("Appearance", tabButton("appearance")?.textContent)
            assertEquals("Fonts", tabButton("fonts")?.textContent)
            assertEquals("Behaviour", tabButton("behaviour")?.textContent)
            assertEquals("true", tabButton("themes")!!.getAttribute("aria-selected"))
            assertEquals("false", tabButton("appearance")!!.getAttribute("aria-selected"))
            assertNotNull(document.querySelector(".dt-appearance-pane-close"), "the pane's close button")
            assertNull(document.querySelector(".dt-settings-group"), "the settings are on the other tab")

            tabButton("appearance")!!.tap()
            eventually("Appearance tab shown") { document.querySelector(".dt-settings-group") != null }
            assertEquals(1, document.querySelectorAll(".dt-appearance-pane .dt-settings-group").length)
            assertNotNull(document.querySelector(".dt-settings-group[data-dt-group=\"mode-style\"]"))
            assertNull(document.querySelector(".dt-fonts-section"), "Fonts have a tab of their own")
            assertFalse(document.body!!.textContent!!.contains("Navigation"), "no navigation setting")
            assertNull(document.querySelector(".dt-theme-card"), "the Theme Manager is unmounted")
            assertEquals("true", tabButton("appearance")!!.getAttribute("aria-selected"))
            assertEquals(AppearanceTab.Appearance, currentAppearanceTab)
            assertFalse(isSettingsSidebarOpen(), "no separate settings sidebar is opened")

            tabButton("fonts")!!.tap()
            eventually("Fonts tab shown") { document.querySelector(".dt-fonts-section") != null }
            assertNull(document.querySelector(".dt-settings-group[data-dt-group=\"mode-style\"]"))
            assertEquals(AppearanceTab.Fonts, currentAppearanceTab)

            tabButton("behaviour")!!.tap()
            eventually("Behaviour tab shown") { document.querySelector("[data-dt-setting=\"tab-strip\"]") != null }
            val behaviourLabels = document.querySelectorAll(".dt-appearance-pane .dt-settings-label")
            assertEquals(
                listOf("Tabs in the top bar"),
                (0 until behaviourLabels.length).map { (behaviourLabels.item(it) as HTMLElement).textContent },
                "no Custom title bar outside Electron",
            )

            // Back to Themes through the handle, on the open pane.
            handle.openAppearance(AppearanceTab.Themes)
            eventually("Themes again") { document.querySelector(".dt-theme-card") != null }
            assertNull(document.querySelector(".dt-settings-group"))
        }
    }

    /** Under Electron the Behaviour tab adds the Custom title bar under the tab-strip toggle, with no group heading. */
    @Test
    fun theBehaviourTabHoldsTheCustomTitleBarUnderElectron() = GlobalScope.promise {
        withPaneShell(isElectron = true) { _, handle, _ ->
            handle.openAppearance(AppearanceTab.Behaviour)
            eventually("Behaviour tab shown") { document.querySelector(".dt-settings-group") != null }
            assertEquals("Behaviour", tabButton("behaviour")?.textContent)
            assertEquals("true", tabButton("behaviour")!!.getAttribute("aria-selected"))
            val labels = document.querySelectorAll(".dt-appearance-pane .dt-settings-label")
            assertEquals(
                listOf("Tabs in the top bar", "Custom title bar"),
                (0 until labels.length).map { (labels.item(it) as HTMLElement).textContent },
            )
            assertNull(document.querySelector(".dt-fonts-section"))
            // The whole tab is shared, so its group carries no heading or hint.
            assertNull(document.querySelector(".dt-appearance-pane .dt-settings-group-title"))
            assertNull(document.querySelector(".dt-appearance-pane .dt-settings-group-hint"))
        }
    }

    /** The pane remembers its tab: closed on Appearance, it reopens on Appearance. */
    @Test
    fun thePaneReopensOnTheTabLastShown() = GlobalScope.promise {
        withPaneShell { root, handle, _ ->
            handle.openAppearance(AppearanceTab.Appearance)
            eventually("Appearance tab shown") { document.querySelector(".dt-settings-group") != null }
            // Close it with Escape (the Appearance tab's own handler).
            document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
            repeat(150) {
                if (!isThemeManagerSidebarOpen()) return@repeat
                delay(20)
                val sidebars = document.querySelectorAll(".dt-sidebar, aside")
                for (i in 0 until sidebars.length) sidebars.item(i)!!.dispatchEvent(Event("transitionend"))
            }
            assertFalse(isThemeManagerSidebarOpen(), "Escape closes the pane on the Appearance tab")
            handle.refresh()
            eventually("pane gone") { document.querySelector(".dt-appearance-pane") == null }

            (root.querySelector(APPEARANCE_PANE_BUTTON) as HTMLElement).tap()
            eventually("reopened on Appearance") { document.querySelector(".dt-settings-group") != null }
            assertEquals("true", tabButton("appearance")!!.getAttribute("aria-selected"))
        }
    }

    // ── (i) Per-mode style settings at the shell level ──

    /** Asserts the root carries the Calm look's stamps. */
    private fun assertCalmStamps(why: String) {
        assertNull(docRoot().getAttribute("data-dt-style"), "the legacy stamp is never written, $why")
        assertEquals("sheet", docRoot().getAttribute("data-dt-windows"), why)
        assertEquals("blended", docRoot().getAttribute("data-dt-chrome"), why)
        assertEquals("tone", docRoot().getAttribute("data-dt-focus"), why)
        assertEquals("sentence", docRoot().getAttribute("data-dt-labels"), why)
        assertEquals("soft", docRoot().getAttribute("data-dt-selection"), why)
        assertEquals("soft", docRoot().getAttribute("data-dt-surface"), why)
        assertEquals("comfortable", docRoot().getAttribute("data-dt-density"), why)
        assertEquals("14px", docRoot().style.getPropertyValue("--dt-corner-radius").trim(), why)
    }

    /** Asserts the root carries the Classic look's stamps (only Fill's selection). */
    private fun assertClassicStamps(why: String) {
        for (attr in listOf("data-dt-style", "data-dt-windows", "data-dt-chrome", "data-dt-focus", "data-dt-labels", "data-dt-surface", "data-dt-density")) {
            assertNull(docRoot().getAttribute(attr), "$attr, $why")
        }
        assertEquals("fill", docRoot().getAttribute("data-dt-selection"), why)
        assertEquals("18px", docRoot().style.getPropertyValue("--dt-corner-radius").trim(), why)
    }

    /** Persisted per-mode settings: the stamps follow the appearance, both ways. */
    @Test
    fun persistedModeStylesFlipWithTheAppearance() = GlobalScope.promise {
        withPaneShell(
            appearance = Appearance.Dark,
            blob = ModeStyles.shapeBlob(AppearanceShape(), DARK_CALM_LIGHT_CLASSIC),
        ) { root, handle, persister ->
            eventually("dark = Calm") { docRoot().getAttribute("data-dt-windows") == "sheet" }
            assertCalmStamps("in dark mode")

            cycleTo(root, "Light")
            eventually("light = Classic") { docRoot().getAttribute("data-dt-windows") == null }
            assertClassicStamps("in light mode")

            // Light → Auto → Dark through the real button.
            cycleTo(root, "Dark")
            eventually("dark = Calm again") { docRoot().getAttribute("data-dt-windows") == "sheet" }
            assertCalmStamps("back in dark mode")

            // And a pushed snapshot switches them the same way.
            handle.setThemeSnapshot(ThemeSnapshotV2(darkThemeName = "Dracula", lightThemeName = "Lunamux Light", appearance = Appearance.Light))
            eventually("pushed light = Classic") { docRoot().getAttribute("data-dt-windows") == null }
            assertClassicStamps("after a pushed Light snapshot")

            // Switching never rewrote either mode's entry.
            assertEquals(DARK_CALM_LIGHT_CLASSIC, ModeStyles.fromJson(persister.store[PersistKeys.APPEARANCE_SHAPE]))
        }
    }

    /** The same pair held by an app-supplied host (no persisted blob) flips the same way. */
    @Test
    fun hostModeStylesFlipWithTheAppearance() = GlobalScope.promise {
        val host = DefaultThemeManagerHost(
            DefaultThemeManagerState(
                darkThemeName = "Dracula",
                lightThemeName = "Lunamux Light",
                appearance = Appearance.Dark,
                modeStyles = DARK_CALM_LIGHT_CLASSIC,
            ),
        )
        withPaneShell(appearance = Appearance.Dark, settingsHost = host) { root, _, _ ->
            eventually("dark = Calm") { docRoot().getAttribute("data-dt-windows") == "sheet" }
            assertCalmStamps("in dark mode")
            cycleTo(root, "Light")
            eventually("light = Classic") { docRoot().getAttribute("data-dt-windows") == null }
            assertClassicStamps("in light mode")
            assertEquals(DARK_CALM_LIGHT_CLASSIC, host.modeStyles, "switching must not rewrite the host's pair")
        }
    }

    /**
     * Without per-mode entries (a blob from before the split), the legacy
     * single shorthand stands for both modes: Calm stays Calm in either.
     */
    @Test
    fun aLegacyBlobAppliesToBothModes() = GlobalScope.promise {
        withPaneShell(
            appearance = Appearance.Dark,
            blob = """{"shellStyle":"clean"}""",
        ) { root, _, _ ->
            eventually("dark = Calm") { docRoot().getAttribute("data-dt-windows") == "sheet" }
            assertCalmStamps("dark, legacy blob")
            cycleTo(root, "Light")
            delay(60)
            assertCalmStamps("light, legacy blob")
        }
    }
}
