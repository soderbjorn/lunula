/*
 * AppearanceStyleNavRowsTest.kt (jsTest)
 *
 * Tests for the Appearance settings panel's two groups, driven through a real
 * [mountAppShell] with an in-memory [Persister] and opened the way the shell
 * now opens them: the Appearance pane's Appearance tab
 * ([AppShellHandle.openAppearance]).
 *
 *  - "Style for dark mode" / "Style for light mode" (`data-dt-group="mode-style"`):
 *    the Presets block (a Classic and a Calm card, and a status line naming
 *    the matching preset or "custom") and the
 *    per-setting rows (chrome, windows, focus, labels, selection, surfaces,
 *    corner roundness, spacing), remembered per appearance mode;
 *  - "Shared by both modes" (`data-dt-group="shared"`): Navigation and Fonts.
 *
 * Checked: the rows ring the RESOLVED values (including an app default the
 * user never picked), a pick applies live on `:root` and is persisted in the
 * shape blob under [PersistKeys.APPEARANCE_SHAPE] as per-mode entries, the
 * Presets status reads custom after one setting changes, editing one mode leaves
 * the other's entry alone, and the theme picker's order does not depend on the
 * style.
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
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.ThemeSnapshotV2
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

/** The Presets block's cards, in render order. */
private fun presetCards(): List<HTMLButtonElement> {
    val section = settingSection("look") ?: return emptyList()
    val list = section.querySelectorAll("button.dt-settings-preset-btn")
    return (0 until list.length).map { list.item(it) as HTMLButtonElement }
}

/** The Presets block's card for look id [id] (`classic` or `calm`); fails when absent. */
private fun lookButton(id: String): HTMLButtonElement =
    presetCards().firstOrNull { it.getAttribute("data-dt-look") == id }
        ?: fail("no '$id' card in the Presets block")

/** The look ids of the selected preset cards (at most one is expected). */
private fun selectedPresets(): List<String> =
    presetCards().filter { it.classList.contains("dt-selected") }.map { it.getAttribute("data-dt-look")!! }

/** The look ids of the preset cards carrying the "In use" badge. */
private fun badgedPresets(): List<String> =
    presetCards().filter { it.querySelector(".dt-settings-preset-badge") != null }.map { it.getAttribute("data-dt-look")!! }

/** The Presets block's status line, or `null` when not mounted. */
private fun presetStatus(): HTMLElement? =
    settingSection("look")?.querySelector(".dt-settings-preset-status") as? HTMLElement

/** The look id the status line names (`classic`, `calm` or `custom`), or `null`. */
private fun presetStatusLook(): String? = presetStatus()?.getAttribute("data-dt-look")

/** The style group's heading text, or `null` when not mounted. */
private fun styleHeading(): String? =
    (group("mode-style")?.querySelector(".dt-settings-group-title") as? HTMLElement)?.textContent

private fun HTMLElement.dispatchClick() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

/**
 * Mounts a fresh shell, opens the Appearance pane on its Appearance tab once
 * the chrome is up, and runs [body]. The root is removed and the pane closed
 * (and set back to its Themes tab) afterwards.
 *
 * @param persister    the shell's persister (seed it to restore a shape).
 * @param appDefault   the app's [AppShellSpec.defaultAppearanceShape].
 * @param openSettings whether to open the Appearance tab before [body].
 * @param body         the test, given the root and the handle.
 */
private suspend fun withSettingsOpen(
    persister: Persister = seeded(),
    appDefault: AppearanceShape = AppearanceShape(),
    openSettings: Boolean = true,
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
            handle.openAppearance(AppearanceTab.Appearance)
            until("settings rows rendered") { settingSection("look") != null && settingSection("navigation") != null }
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

/** The Appearance settings' two groups, driven through a mounted shell. */
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

    /** Two groups, in order, with the style rows in the first and Navigation in the second. */
    @Test
    fun thePanelHasAStyleGroupThenASharedGroup() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            val groups = document.querySelectorAll(".dt-settings-sidebar-sections > .dt-settings-group")
            assertEquals(2, groups.length)
            assertEquals("mode-style", (groups.item(0) as HTMLElement).getAttribute("data-dt-group"))
            assertEquals("shared", (groups.item(1) as HTMLElement).getAttribute("data-dt-group"))
            assertEquals("Style for dark mode", styleHeading())

            assertEquals(
                listOf("look", "chrome", "windows", "focus", "labels", "selection", "surfaces", "spacing"),
                settingIds(group("mode-style")!!),
            )
            assertTrue(group("mode-style")!!.textContent!!.contains("Corner roundness"))
            val shared = settingIds(group("shared")!!)
            assertEquals("navigation", shared.first(), "Navigation leads the shared group")
            assertFalse("look" in shared)
            assertTrue(group("shared")!!.querySelector(".dt-fonts-section") != null, "Fonts are shared")
            // The pre-split rows and lock are gone.
            assertNull(settingSection("style"))
            assertNull(document.querySelector(".dt-settings-locked-note"))
        }
    }

    /** Nothing stored, no app default: Classic, every default ringed, nothing locked. */
    @Test
    fun rowsRingTheToolkitDefaults() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            assertEquals(listOf("classic", "calm"), presetCards().map { it.getAttribute("data-dt-look") })
            assertEquals(listOf("Classic", "Calm"), presetCards().map {
                (it.querySelector(".dt-settings-preset-name") as? HTMLElement)?.textContent
            })
            assertTrue(presetCards().all { it.querySelector(".dt-settings-preset-desc") != null }, "every card describes its preset")
            assertTrue(choiceButtons("look").isEmpty(), "the presets are cards, not choice pills")
            assertNull(settingSection("look")!!.querySelector("[data-dt-look=\"custom\"].dt-settings-preset-btn"), "no Custom card")
            assertEquals(listOf("classic"), selectedPresets())
            assertEquals(listOf("classic"), badgedPresets())
            assertEquals("In use", (lookButton("classic").querySelector(".dt-settings-preset-badge") as HTMLElement).textContent)
            assertEquals("classic", presetStatusLook())
            assertEquals("Your settings match the Classic preset.", presetStatus()!!.textContent)
            assertEquals("Themed", selectedLabel("chrome"))
            assertEquals("Cards", selectedLabel("windows"))
            assertEquals("Accent", selectedLabel("focus"))
            assertEquals("Uppercase", selectedLabel("labels"))
            assertEquals("Filled", selectedLabel("selection"))
            assertEquals("Depth", selectedLabel("surfaces"))
            assertEquals("Compact", selectedLabel("spacing"))
            assertEquals(listOf("Tinted", "Filled", "Soft"), choiceButtons("selection").map { it.textContent })
            assertEquals(listOf("Depth", "Flat", "Soft"), choiceButtons("surfaces").map { it.textContent })
            assertEquals(listOf("Sidebar", "Tab strip", "Both"), choiceButtons("navigation").map { it.textContent })
            assertEquals("Both", selectedLabel("navigation"))
            for (id in listOf("chrome", "windows", "focus", "labels", "selection", "surfaces", "spacing")) {
                assertTrue(choiceButtons(id).none { it.disabled }, "$id must not be locked")
            }
            assertNull(rootEl().getAttribute("data-dt-style"), "the legacy stamp is never written")
        }
    }

    /** The Presets block is labelled "Presets" and keeps its `data-dt-setting` and class. */
    @Test
    fun thePresetsBlockIsLabelledPresets() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            val section = settingSection("look")!!
            assertTrue(section.classList.contains("dt-settings-presets"))
            assertEquals("Presets", (section.querySelector(".dt-settings-label") as? HTMLElement)?.textContent)
        }
    }

    /** An app default the user never picked is what the rows ring. */
    @Test
    fun rowsRingTheAppDefaultShape() = GlobalScope.promise {
        withSettingsOpen(
            appDefault = AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar),
        ) { _, _ ->
            assertEquals(listOf("calm"), selectedPresets())
            assertEquals(listOf("calm"), badgedPresets())
            assertEquals("calm", presetStatusLook())
            assertEquals("Your settings match the Calm preset.", presetStatus()!!.textContent)
            assertEquals("Blended", selectedLabel("chrome"))
            assertEquals("Sheet", selectedLabel("windows"))
            assertEquals("Tone", selectedLabel("focus"))
            assertEquals("Sentence case", selectedLabel("labels"))
            assertEquals("Soft", selectedLabel("selection"))
            assertEquals("Soft", selectedLabel("surfaces"))
            assertEquals("Comfortable", selectedLabel("spacing"))
            assertEquals("14px", (settingSectionByLabel("Corner roundness")
                ?.querySelector(".dt-selected") as? HTMLElement)?.textContent)
            assertEquals("Sidebar", selectedLabel("navigation"))
            // Selection and Surfaces are ordinary rows now, not locked by Calm.
            assertTrue(choiceButtons("selection").none { it.disabled })
            assertTrue(choiceButtons("surfaces").none { it.disabled })
            assertNull(rootEl().getAttribute("data-dt-style"), "the legacy stamp is never written")
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
            assertEquals(listOf("classic"), selectedPresets())
            assertEquals("Cards", selectedLabel("windows"))
            assertEquals("Tab strip", selectedLabel("navigation"))
        }
    }

    /**
     * Clicking the Calm preset applies every part of it live, persists it as
     * the active mode's entry and the status names Calm; changing one setting
     * afterwards makes the status read custom, with no card selected or
     * badged, and drops only that part.
     */
    @Test
    fun pickingAPresetThenOneSettingTurnsTheStatusToCustom() = GlobalScope.promise {
        val persister = seeded()
        withSettingsOpen(persister = persister) { _, _ ->
            assertNull(rootEl().getAttribute("data-dt-windows"))

            lookButton("calm").dispatchClick()
            until("Calm applied") { rootEl().getAttribute("data-dt-windows") == "sheet" }
            assertEquals("blended", rootEl().getAttribute("data-dt-chrome"))
            assertEquals("tone", rootEl().getAttribute("data-dt-focus"))
            assertEquals("sentence", rootEl().getAttribute("data-dt-labels"))
            assertEquals("soft", rootEl().getAttribute("data-dt-selection"))
            assertEquals("soft", rootEl().getAttribute("data-dt-surface"))
            assertNull(rootEl().getAttribute("data-dt-style"), "the legacy stamp is never written")
            until("Calm persisted") { persister.modes()?.dark == Looks.Calm.shape }
            until("status names Calm") { presetStatusLook() == "calm" }
            assertEquals("Your settings match the Calm preset.", presetStatus()!!.textContent)
            assertEquals(listOf("calm"), selectedPresets())
            assertEquals(listOf("calm"), badgedPresets())

            choice("chrome", "Themed").dispatchClick()
            until("chrome applied") { rootEl().getAttribute("data-dt-chrome") == null }
            until("status reads custom") { presetStatusLook() == "custom" }
            assertEquals("Your own mix: the settings below match no preset.", presetStatus()!!.textContent)
            assertEquals(emptyList(), selectedPresets(), "no card is selected")
            assertEquals(emptyList(), badgedPresets(), "no card is badged")
            assertEquals("sheet", rootEl().getAttribute("data-dt-windows"), "the other settings stay")
            assertEquals("tone", rootEl().getAttribute("data-dt-focus"))
            until("the edit persisted") { persister.modes()?.dark == Looks.Calm.shape.copy(chromeStyle = ChromeStyle.Themed) }

            lookButton("classic").dispatchClick()
            until("Classic applied") { rootEl().getAttribute("data-dt-windows") == null }
            for (attr in listOf("data-dt-chrome", "data-dt-focus", "data-dt-labels", "data-dt-surface", "data-dt-style")) {
                assertNull(rootEl().getAttribute(attr), "$attr after Classic")
            }
            assertEquals("fill", rootEl().getAttribute("data-dt-selection"))
            until("status names Classic") { presetStatusLook() == "classic" }
            assertEquals(listOf("classic"), selectedPresets())
        }
    }

    /** The status line only describes: clicking it produces no combination. */
    @Test
    fun theStatusLineCannotBeClicked() = GlobalScope.promise {
        val persister = seeded()
        withSettingsOpen(persister = persister) { _, _ ->
            presetStatus()!!.dispatchClick()
            delay(50)
            assertEquals("classic", presetStatusLook())
            assertNull(persister.modes(), "nothing was written")
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
        withSettingsOpen(persister = persister) { _, handle ->
            assertEquals("Style for dark mode", styleHeading())
            lookButton("calm").dispatchClick()
            until("dark persisted") { persister.modes()?.dark == Looks.Calm.shape }
            assertEquals(AppearanceShape(), persister.modes()!!.light, "light mode was not edited")

            handle.setThemeSnapshot(ThemeSnapshotV2(appearance = Appearance.Light))
            until("root follows light") { rootEl().getAttribute("data-dt-windows") == null }
            if (styleHeading() != "Style for light mode") {
                // The open panel re-renders on the next rebuild; reopen to be sure.
                handle.openAppearance(AppearanceTab.Appearance)
            }
            until("group re-titled") { styleHeading() == "Style for light mode" }
            assertEquals("classic", presetStatusLook())

            choice("labels", "Sentence case").dispatchClick()
            until("light persisted") { persister.modes()?.light?.labelCase == LabelCase.Sentence }
            assertEquals(Looks.Calm.shape, persister.modes()!!.dark, "dark mode was not edited")
            assertEquals("sentence", rootEl().getAttribute("data-dt-labels"))
            until("light status reads custom") { presetStatusLook() == "custom" }
        }
    }

    /** Navigation picks redraw live (`data-dt-nav`) and persist at the top level; the ring follows. */
    @Test
    fun pickingNavigationAppliesAndPersists() = GlobalScope.promise {
        val persister = seeded()
        withSettingsOpen(persister = persister) { _, _ ->
            assertEquals("both", rootEl().getAttribute("data-dt-nav"))
            assertTrue(group("shared")!!.contains(settingSection("navigation")))

            choice("navigation", "Tab strip").dispatchClick()
            until("tab strip applied") { rootEl().getAttribute("data-dt-nav") == "tabstrip" }
            until("tab strip persisted") { persister.shape().navigationMode == NavigationMode.TabStrip }
            // The chrome rebuild re-mounts the panel; the new one rings the pick.
            until("ring follows") { selectedLabel("navigation") == "Tab strip" }

            choice("navigation", "Sidebar").dispatchClick()
            until("sidebar applied") { rootEl().getAttribute("data-dt-nav") == "sidebar" }
            until("sidebar persisted") { persister.shape().navigationMode == NavigationMode.Sidebar }
            until("ring follows again") { selectedLabel("navigation") == "Sidebar" }
            // Navigation is shared, so it never lands in a mode's entry.
            assertTrue(persister.modes() == null || persister.modes()!!.dark.navigationMode == null)
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
