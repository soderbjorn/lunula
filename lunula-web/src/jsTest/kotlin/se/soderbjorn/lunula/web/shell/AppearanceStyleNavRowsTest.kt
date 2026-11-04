/*
 * AppearanceStyleNavRowsTest.kt (jsTest)
 *
 * Tests for the settings pages of the Appearance pane, driven through a real
 * [mountAppShell] with an in-memory [Persister] and opened the way the shell
 * opens them ([AppShellHandle.openAppearance]):
 *
 *  - Appearance: "Style for dark mode" / "Style for light mode"
 *    (`data-dt-group="mode-style"`), the per-setting rows (chrome, windows,
 *    focus, labels, selection, surfaces, corner roundness, spacing),
 *    remembered per appearance mode. No presets.
 *  - Fonts: "Shared by both modes" (`data-dt-group="shared"`) with the font lines.
 *
 * Navigation has no row on these pages (the Behaviour tab's "Tabs in the top
 * bar" is its one control; see NavigationModeTest), but a stored or
 * app-default mode still draws.
 *
 * Checked: the rows ring the RESOLVED values (including an app default the
 * user never picked), a pick applies live on `:root` and is persisted in the
 * shape blob under [PersistKeys.APPEARANCE_SHAPE] as per-mode entries, editing
 * one mode leaves the other's entry alone, and the theme picker's order does not depend on the style.
 *
 * `lunula.css` is not loaded in karma, so every assertion is structural.
 *
 * @see se.soderbjorn.lunula.web.settings.SettingsSidebarSpec
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.core.builtinTheme
import se.soderbjorn.lunula.core.isCalmTheme
import se.soderbjorn.lunula.web.settings.closeSettingsSidebar
import se.soderbjorn.lunula.web.themeeditor.AppearanceTab
import se.soderbjorn.lunula.web.themeeditor.closeThemeManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class RowsMemoryPersister(seed: Map<String, String> = emptyMap()) : Persister {
    val store = mutableMapOf<String, String>().apply { putAll(seed) }
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }

    /** The persisted shape's top-level (shared and legacy) fields. */
    fun shape(): AppearanceShape = AppearanceShape.fromJson(store[PersistKeys.APPEARANCE_SHAPE])

    /** The persisted per-mode entries, or `null` when none were written. */
    fun modes(): ModeStyles? = ModeStyles.fromJson(store[PersistKeys.APPEARANCE_SHAPE])
}

/**
 * A persister seeded with a theme selection in [appearance] (so the active mode
 * is known, whatever the test browser's colour scheme) and, optionally, a
 * shape blob.
 *
 * @param appearance the appearance to start in.
 * @param shapeBlob  the stored [PersistKeys.APPEARANCE_SHAPE], or `null`.
 * @return the persister.
 */
private fun seeded(appearance: Appearance = Appearance.Dark, shapeBlob: String? = null): RowsMemoryPersister {
    val snap = ThemeSnapshotV2(appearance = appearance)
    val seed = mutableMapOf(
        PersistKeys.THEME_V2_SELECTION to snap.selectionJson(),
        PersistKeys.THEME_V2_CUSTOM to snap.customThemesJson(),
    )
    if (shapeBlob != null) seed[PersistKeys.APPEARANCE_SHAPE] = shapeBlob
    return RowsMemoryPersister(seed)
}

/** Polls [condition] every tick until it holds or ~3s elapse. */
private suspend fun until(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

/** The document root, where the shell stamps its `data-dt-*` attributes. */
private fun rootEl(): HTMLElement = document.documentElement as HTMLElement

/** The settings group stamped `data-dt-group="[id]"`, if mounted. */
private fun group(id: String): HTMLElement? =
    document.querySelector(".dt-settings-sidebar-sections .dt-settings-group[data-dt-group=\"$id\"]") as? HTMLElement

/** The settings section stamped `data-dt-setting="[id]"`, if mounted. */
private fun settingSection(id: String): HTMLElement? =
    document.querySelector(".dt-settings-sidebar-sections [data-dt-setting=\"$id\"]") as? HTMLElement

/** The `data-dt-setting` ids inside [groupEl], in render order. */
private fun settingIds(groupEl: HTMLElement): List<String> {
    val list = groupEl.querySelectorAll("[data-dt-setting]")
    return (0 until list.length).map { (list.item(it) as HTMLElement).getAttribute("data-dt-setting")!! }
}

/** The choice buttons of setting [id], in render order. */
private fun choiceButtons(id: String): List<HTMLButtonElement> {
    val section = settingSection(id) ?: return emptyList()
    val list = section.querySelectorAll(".dt-settings-choice-btn")
    return (0 until list.length).map { list.item(it) as HTMLButtonElement }
}

/** The label of the ringed choice in setting [id], or `null`. */
private fun selectedLabel(id: String): String? =
    choiceButtons(id).firstOrNull { it.classList.contains("dt-selected") }?.textContent

/** The choice labelled [label] in setting [id]; fails when absent. */
private fun choice(id: String, label: String): HTMLButtonElement =
    choiceButtons(id).firstOrNull { it.textContent == label }
        ?: fail("no '$label' choice in the $id row")

/** The style group's heading text, or `null` when not mounted. */
private fun styleHeading(): String? =
    (group("mode-style")?.querySelector(".dt-settings-group-title") as? HTMLElement)?.textContent

private fun HTMLElement.dispatchClick() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

/**
 * Mounts a fresh shell, opens the Appearance pane on [tab] once the chrome is
 * up, and runs [body]. The root is removed and the pane closed
 * (and set back to its Themes tab) afterwards.
 *
 * @param persister    the shell's persister (seed it to restore a shape).
 * @param appDefault   the app's [AppShellSpec.defaultAppearanceShape].
 * @param openSettings whether to open the pane before [body].
 * @param tab          the tab to open it on.
 * @param body         the test, given the root and the handle.
 */
private suspend fun withSettingsOpen(
    persister: Persister = seeded(),
    appDefault: AppearanceShape = AppearanceShape(),
    openSettings: Boolean = true,
    tab: AppearanceTab = AppearanceTab.Appearance,
    body: suspend (root: HTMLElement, handle: AppShellHandle) -> Unit,
) {
    // The right-slot panels are module-wide state: start from all closed, so
    // an earlier test (in any file) that left one open cannot take the slot.
    closeSettingsSidebar()
    resetAppearancePane()
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "style-nav-rows-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = TabSource(subscribe = { }, onSelect = { }),
                defaultAppearanceShape = { appDefault },
            ),
        )
        until("topbar rendered") { root.querySelector(APPEARANCE_PANE_BUTTON) != null }
        if (openSettings) {
            showTab(handle, tab)
        }
        body(root, handle)
        handle.dispose()
    } finally {
        closeSettingsSidebar()
        resetAppearancePane()
        closeThemeManager()
        root.remove()
    }
}

/** Switches the open (or closed) pane to [tab] and waits for its rows. */
private suspend fun showTab(handle: AppShellHandle, tab: AppearanceTab) {
    handle.openAppearance(tab)
    when (tab) {
        AppearanceTab.Appearance -> until("style rows rendered") { settingSection("chrome") != null }
        AppearanceTab.Fonts -> until("font lines rendered") { document.querySelector(".dt-fonts-section") != null }
        AppearanceTab.Behaviour, AppearanceTab.Themes -> until("theme cards rendered") { document.querySelector(".dt-theme-card") != null }
    }
}

/** The Appearance pane's settings pages, driven through a mounted shell. */
class AppearanceStyleNavRowsTest {

    /** Leaves `:root` as later tests (in any file) expect to find it. */
    @AfterTest
    fun reset() {
        closeSettingsSidebar()
        resetAppearancePane()
        clearStyleStamps()
        rootEl().removeAttribute("data-dt-nav")
        leftSidebarController.setInitial(open = true, widthPx = leftSidebarController.widthPx)
    }

    /** The Appearance tab holds the style group alone, with no Presets block. */
    @Test
    fun theAppearanceTabHoldsTheStyleGroupAlone() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            val groups = document.querySelectorAll(".dt-settings-sidebar-sections > .dt-settings-group")
            assertEquals(1, groups.length)
            assertEquals("mode-style", (groups.item(0) as HTMLElement).getAttribute("data-dt-group"))
            assertEquals("Style for dark mode", styleHeading())

            assertEquals(
                listOf("chrome", "windows", "focus", "labels", "selection", "surfaces", "spacing"),
                settingIds(group("mode-style")!!),
            )
            assertTrue(group("mode-style")!!.textContent!!.contains("Corner roundness"))
            assertNull(settingSection("look"), "the Presets block is gone")
            assertNull(document.querySelector(".dt-settings-preset-btn"))
            assertNull(document.querySelector(".dt-fonts-section"), "Fonts have a tab of their own")
            assertNull(settingSection("navigation"), "no navigation row")
            // The pre-split rows and lock are gone.
            assertNull(settingSection("style"))
            assertNull(document.querySelector(".dt-settings-locked-note"))
        }
    }

    /** The Fonts tab holds the font lines in the shared group. */
    @Test
    fun theFontsTabHoldsTheFontLines() = GlobalScope.promise {
        withSettingsOpen(tab = AppearanceTab.Fonts) { _, _ ->
            val groups = document.querySelectorAll(".dt-settings-sidebar-sections > .dt-settings-group")
            assertEquals(1, groups.length)
            assertTrue(group("shared")!!.querySelector(".dt-fonts-section") != null, "Fonts are shared")
            assertNull(group("mode-style"))
        }
    }

    /** Nothing stored, no app default: every toolkit default ringed, nothing locked. */
    @Test
    fun rowsRingTheToolkitDefaults() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            assertEquals("Themed", selectedLabel("chrome"))
            assertEquals("Cards", selectedLabel("windows"))
            assertEquals("Accent", selectedLabel("focus"))
            assertEquals("Uppercase", selectedLabel("labels"))
            assertEquals("Filled", selectedLabel("selection"))
            assertEquals("Depth", selectedLabel("surfaces"))
            assertEquals("Compact", selectedLabel("spacing"))
            assertEquals(listOf("Filled", "Tinted", "Soft"), choiceButtons("selection").map { it.textContent })
            assertEquals(listOf("Depth", "Flat", "Soft"), choiceButtons("surfaces").map { it.textContent })
            for (id in listOf("chrome", "windows", "focus", "labels", "selection", "surfaces", "spacing")) {
                assertTrue(choiceButtons(id).none { it.disabled }, "$id must not be locked")
            }
            assertNull(rootEl().getAttribute("data-dt-style"), "the legacy stamp is never written")
            assertEquals("both", rootEl().getAttribute("data-dt-nav"))
        }
    }

    /** An app default the user never picked is what the rows ring. */
    @Test
    fun rowsRingTheAppDefaultShape() = GlobalScope.promise {
        withSettingsOpen(
            appDefault = AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar),
        ) { _, _ ->
            assertEquals("Blended", selectedLabel("chrome"))
            assertEquals("Sheet", selectedLabel("windows"))
            assertEquals("Tone", selectedLabel("focus"))
            assertEquals("Sentence case", selectedLabel("labels"))
            assertEquals("Soft", selectedLabel("selection"))
            assertEquals("Soft", selectedLabel("surfaces"))
            assertEquals("Comfortable", selectedLabel("spacing"))
            assertEquals("14px", (settingSectionByLabel("Corner roundness")
                ?.querySelector(".dt-selected") as? HTMLElement)?.textContent)
            // Selection and Surfaces are ordinary rows now, not locked by Calm.
            assertTrue(choiceButtons("selection").none { it.disabled })
            assertTrue(choiceButtons("surfaces").none { it.disabled })
            assertNull(rootEl().getAttribute("data-dt-style"), "the legacy stamp is never written")
            assertEquals("sidebar", rootEl().getAttribute("data-dt-nav"), "the app default still draws")
        }
    }

    /** A stored legacy pick (from before the split) wins over the app default. */
    @Test
    fun aStoredLegacyPickWinsOverTheAppDefault() = GlobalScope.promise {
        val persister = seeded(
            shapeBlob = AppearanceShape(
                shellStyle = ShellStyle.Classic,
                navigationMode = NavigationMode.TabStrip,
            ).toJson(),
        )
        withSettingsOpen(
            persister = persister,
            appDefault = AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar),
        ) { _, _ ->
            assertEquals("Themed", selectedLabel("chrome"))
            assertEquals("Cards", selectedLabel("windows"))
            assertEquals("tabstrip", rootEl().getAttribute("data-dt-nav"), "a stored mode still draws")
        }
    }

    /**
     * One pick applies live and is persisted as the active mode's entry, and
     * the panel re-rings it.
     */
    @Test
    fun pickingOneSettingAppliesAndPersistsIt() = GlobalScope.promise {
        val persister = seeded()
        withSettingsOpen(persister = persister) { _, _ ->
            assertNull(rootEl().getAttribute("data-dt-windows"))

            choice("windows", "Sheet").dispatchClick()
            until("Sheet applied") { rootEl().getAttribute("data-dt-windows") == "sheet" }
            until("Sheet persisted") { persister.modes()?.dark == AppearanceShape(windowLayout = WindowLayout.Sheet) }
            until("ring follows") { selectedLabel("windows") == "Sheet" }
            assertNull(rootEl().getAttribute("data-dt-chrome"), "the other settings stay")

            choice("selection", "Tinted").dispatchClick()
            // Tint is the unstamped default (see applySelectionStyle).
            until("Tinted applied") { !rootEl().hasAttribute("data-dt-selection") }
            assertEquals("sheet", rootEl().getAttribute("data-dt-windows"))
        }
    }

    /**
     * Each mode keeps its own entry: an edit in dark mode leaves light mode's
     * alone, switching the appearance re-titles the group and re-rings it, and
     * an edit in light mode leaves dark mode's alone.
     */
    @Test
    fun editingOneModeLeavesTheOtherModesEntryAlone() = GlobalScope.promise {
        val persister = seeded(appearance = Appearance.Dark)
        val darkSheet = AppearanceShape(windowLayout = WindowLayout.Sheet)
        withSettingsOpen(persister = persister) { _, handle ->
            assertEquals("Style for dark mode", styleHeading())
            choice("windows", "Sheet").dispatchClick()
            until("dark persisted") { persister.modes()?.dark == darkSheet }
            assertEquals(AppearanceShape(), persister.modes()!!.light, "light mode was not edited")

            handle.setThemeSnapshot(ThemeSnapshotV2(appearance = Appearance.Light))
            until("root follows light") { rootEl().getAttribute("data-dt-windows") == null }
            if (styleHeading() != "Style for light mode") {
                // The open panel re-renders on the next rebuild; reopen to be sure.
                handle.openAppearance(AppearanceTab.Appearance)
            }
            until("group re-titled") { styleHeading() == "Style for light mode" }
            assertEquals("Cards", selectedLabel("windows"))

            choice("labels", "Sentence case").dispatchClick()
            until("light persisted") { persister.modes()?.light?.labelCase == LabelCase.Sentence }
            assertEquals(darkSheet, persister.modes()!!.dark, "dark mode was not edited")
            assertEquals("sentence", rootEl().getAttribute("data-dt-labels"))
        }
    }

    /**
     * The theme picker's order no longer depends on the style: a Calm app
     * default does not lift the calm themes, and the first card is the same
     * as under Classic.
     */
    @Test
    fun themePickerOrderDoesNotDependOnTheStyle() = GlobalScope.promise {
        var underCalm: String? = null
        withSettingsOpen(
            appDefault = AppearanceShape(shellStyle = ShellStyle.Calm),
            openSettings = false,
        ) { root, _ ->
            (root.querySelector(APPEARANCE_PANE_BUTTON) as HTMLElement).dispatchClick()
            until("theme manager open") { document.querySelector(".dt-theme-card") != null }
            underCalm = firstCardTheme().name
            assertFalse(isCalmTheme(firstCardTheme()), "first card under Calm should not be calm")
        }
        withSettingsOpen(openSettings = false) { root, _ ->
            (root.querySelector(APPEARANCE_PANE_BUTTON) as HTMLElement).dispatchClick()
            until("theme manager open") { document.querySelector(".dt-theme-card") != null }
            assertEquals(underCalm, firstCardTheme().name)
        }
    }

    /** The built-in theme named on the first card of the open Theme Manager. */
    private fun firstCardTheme() = run {
        val name = (document.querySelector(".dt-theme-card .dt-theme-card-name") as? HTMLElement)
            ?.textContent?.trim()
        assertNotNull(name, "no theme card name")
        builtinTheme(name) ?: fail("first card '$name' is not a built-in theme")
    }
}

/** The settings section whose label reads [label] (for rows with no `data-dt-setting`). */
private fun settingSectionByLabel(label: String): HTMLElement? {
    val list = document.querySelectorAll(".dt-settings-sidebar-sections .dt-settings-section")
    return (0 until list.length).map { list.item(it) as HTMLElement }
        .firstOrNull { (it.querySelector(".dt-settings-label") as? HTMLElement)?.textContent == label }
}
