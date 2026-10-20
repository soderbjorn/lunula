/*
 * AppearanceStyleNavRowsTest.kt (jsTest)
 *
 * Tests for the Style (Classic / Clean) and Navigation (Sidebar / Tab strip /
 * Both) rows at the top of the Appearance settings (LNA-24), driven through a
 * real [mountAppShell] with an in-memory [Persister]:
 *
 *  - both rows ring the RESOLVED value, including an app default the user
 *    never picked;
 *  - a click applies live (`data-dt-style` / `data-dt-nav` on `:root`) and is
 *    persisted under [PersistKeys.APPEARANCE_SHAPE];
 *  - Selection and Surfaces are real disabled buttons only while Clean is in
 *    effect, and their stored values are never touched;
 *  - the Theme Manager's calm-first ordering follows the resolved style, so a
 *    Clean app default counts.
 *
 * `lunula.css` is not loaded in karma, so every assertion is structural.
 *
 * @see se.soderbjorn.lunula.web.settings.SettingsSidebarSpec
 * @see se.soderbjorn.lunula.web.appliedShellStyle
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
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.builtinTheme
import se.soderbjorn.lunula.core.isCalmTheme
import se.soderbjorn.lunula.web.appliedShellStyle
import se.soderbjorn.lunula.web.applyShellStyle
import se.soderbjorn.lunula.web.settings.CLEAN_LOCKED_NOTE
import se.soderbjorn.lunula.web.settings.closeSettingsSidebar
import se.soderbjorn.lunula.web.settings.toggleSettingsSidebar
import se.soderbjorn.lunula.web.themeeditor.closeThemeManager
import se.soderbjorn.lunula.web.themeeditor.closeThemeManagerSidebar
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

    /** The persisted shape, decoded (empty when nothing is stored). */
    fun shape(): AppearanceShape = AppearanceShape.fromJson(store[PersistKeys.APPEARANCE_SHAPE])
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

/** The settings section stamped `data-dt-setting="[id]"`, if mounted. */
private fun settingSection(id: String): HTMLElement? =
    document.querySelector(".dt-settings-sidebar-sections [data-dt-setting=\"$id\"]") as? HTMLElement

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

/** True when every choice in [id] is a disabled button marked aria-disabled. */
private fun isLocked(id: String): Boolean {
    val buttons = choiceButtons(id)
    return buttons.isNotEmpty() && buttons.all { it.disabled && it.getAttribute("aria-disabled") == "true" }
}

/** True when no choice in [id] is disabled and none carries aria-disabled. */
private fun isUnlocked(id: String): Boolean {
    val buttons = choiceButtons(id)
    return buttons.isNotEmpty() && buttons.none { it.disabled || it.hasAttribute("aria-disabled") }
}

/** The muted "set by Clean" note under setting [id], or `null`. */
private fun lockedNote(id: String): String? =
    (settingSection(id)?.querySelector(".dt-settings-locked-note") as? HTMLElement)?.textContent

private fun HTMLElement.dispatchClick() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

/**
 * Mounts a fresh shell, opens the Appearance settings once the chrome is up,
 * and runs [body]. The root is removed and the sidebar closed afterwards.
 *
 * @param persister  the shell's persister (seed it to restore a shape).
 * @param appDefault the app's [AppShellSpec.defaultAppearanceShape].
 * @param openSettings whether to open the Appearance settings before [body].
 * @param body       the test, given the root and the handle.
 */
private suspend fun withSettingsOpen(
    persister: Persister = RowsMemoryPersister(),
    appDefault: AppearanceShape = AppearanceShape(),
    openSettings: Boolean = true,
    body: suspend (root: HTMLElement, handle: AppShellHandle) -> Unit,
) {
    // The right-slot panels are module-wide state: start from all closed, so
    // an earlier test (in any file) that left one open cannot turn the
    // toggle below into a close, or take the slot first.
    closeSettingsSidebar()
    closeThemeManagerSidebar()
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
        // Classic draws a Theme manager button; Clean folds it into the
        // settings menu (LNA-23), so either one says the top bar is up.
        until("topbar rendered") {
            root.querySelector("button[title=\"Theme manager\"], .$SETTINGS_MENU_BUTTON_CLASS") != null
        }
        if (openSettings) {
            toggleSettingsSidebar { handle.refresh() }
            until("settings rows rendered") { settingSection("style") != null && settingSection("surfaces") != null }
        }
        body(root, handle)
        handle.dispose()
    } finally {
        closeSettingsSidebar()
        closeThemeManagerSidebar()
        closeThemeManager()
        root.remove()
    }
}

class AppearanceStyleNavRowsTest {

    /** Leaves `:root` as later tests (in any file) expect to find it. */
    @AfterTest
    fun reset() {
        closeSettingsSidebar()
        rootEl().removeAttribute("data-dt-style")
        rootEl().removeAttribute("data-dt-nav")
        rootEl().removeAttribute("data-dt-selection")
        rootEl().removeAttribute("data-dt-surface")
        leftSidebarController.setInitial(open = true, widthPx = leftSidebarController.widthPx)
    }

    /** Nothing stored, no app default: Classic and Both, nothing locked. */
    @Test
    fun rowsLeadThePanelAndRingTheToolkitDefaults() = GlobalScope.promise {
        withSettingsOpen { _, _ ->
            val sections = document.querySelectorAll(".dt-settings-sidebar-sections > .dt-settings-section")
            assertEquals("style", (sections.item(0) as HTMLElement).getAttribute("data-dt-setting"))
            assertEquals("navigation", (sections.item(1) as HTMLElement).getAttribute("data-dt-setting"))
            assertEquals(listOf("Classic", "Clean"), choiceButtons("style").map { it.textContent })
            assertEquals(listOf("Sidebar", "Tab strip", "Both"), choiceButtons("navigation").map { it.textContent })
            assertEquals("Classic", selectedLabel("style"))
            assertEquals("Both", selectedLabel("navigation"))
            assertTrue(isUnlocked("selection"))
            assertTrue(isUnlocked("surfaces"))
            assertNull(lockedNote("selection"))
        }
    }

    /** An app default the user never picked is what the rows ring — and what locks. */
    @Test
    fun rowsRingTheAppDefaultShape() = GlobalScope.promise {
        withSettingsOpen(
            appDefault = AppearanceShape(shellStyle = ShellStyle.Clean, navigationMode = NavigationMode.Sidebar),
        ) { _, _ ->
            assertEquals("Clean", selectedLabel("style"))
            assertEquals("Sidebar", selectedLabel("navigation"))
            assertTrue(isLocked("selection"), "Selection must be disabled under a Clean app default")
            assertTrue(isLocked("surfaces"), "Surfaces must be disabled under a Clean app default")
            assertEquals(CLEAN_LOCKED_NOTE, lockedNote("selection"))
            assertEquals(CLEAN_LOCKED_NOTE, lockedNote("surfaces"))
        }
    }

    /** A stored user pick wins over the app default in the rings. */
    @Test
    fun aStoredPickWinsOverTheAppDefault() = GlobalScope.promise {
        val persister = RowsMemoryPersister(
            mapOf(
                PersistKeys.APPEARANCE_SHAPE to AppearanceShape(
                    shellStyle = ShellStyle.Classic,
                    navigationMode = NavigationMode.TabStrip,
                ).toJson(),
            ),
        )
        withSettingsOpen(
            persister = persister,
            appDefault = AppearanceShape(shellStyle = ShellStyle.Clean, navigationMode = NavigationMode.Sidebar),
        ) { _, _ ->
            assertEquals("Classic", selectedLabel("style"))
            assertEquals("Tab strip", selectedLabel("navigation"))
            assertTrue(isUnlocked("selection"))
        }
    }

    /**
     * Clean → applied, persisted, Selection and Surfaces locked with their
     * stored values intact; Classic → all of it reverses, values still intact.
     */
    @Test
    fun pickingStyleAppliesPersistsAndLocksWithoutTouchingStoredValues() = GlobalScope.promise {
        val persister = RowsMemoryPersister(
            mapOf(
                PersistKeys.APPEARANCE_SHAPE to AppearanceShape(
                    selectionStyle = SelectionStyle.Fill,
                    surfaceStyle = SurfaceStyle.Flat,
                ).toJson(),
            ),
        )
        withSettingsOpen(persister = persister) { _, _ ->
            assertNull(rootEl().getAttribute("data-dt-style"))
            assertEquals("Filled", selectedLabel("selection"))
            assertEquals("Flat", selectedLabel("surfaces"))

            choice("style", "Clean").dispatchClick()
            assertEquals("clean", rootEl().getAttribute("data-dt-style"), "Clean applies live")
            until("Clean persisted") { persister.shape().shellStyle == ShellStyle.Clean }
            assertEquals("Clean", selectedLabel("style"))
            assertTrue(isLocked("selection"))
            assertTrue(isLocked("surfaces"))
            assertEquals(CLEAN_LOCKED_NOTE, lockedNote("surfaces"))
            // The stored picks are kept, and still ringed (dimmed) in the UI.
            assertEquals(SelectionStyle.Fill, persister.shape().selectionStyle)
            assertEquals(SurfaceStyle.Flat, persister.shape().surfaceStyle)
            assertEquals("Filled", selectedLabel("selection"))
            assertEquals("Flat", selectedLabel("surfaces"))

            // A disabled choice ignores clicks: the stored value cannot move.
            choice("selection", "Tinted").click()
            assertEquals("Filled", selectedLabel("selection"))
            assertEquals(SelectionStyle.Fill, persister.shape().selectionStyle)

            choice("style", "Classic").dispatchClick()
            assertNull(rootEl().getAttribute("data-dt-style"), "Classic removes the stamp")
            until("Classic persisted") { persister.shape().shellStyle == ShellStyle.Classic }
            assertTrue(isUnlocked("selection"))
            assertTrue(isUnlocked("surfaces"))
            assertNull(lockedNote("selection"))
            assertEquals(SelectionStyle.Fill, persister.shape().selectionStyle)
            assertEquals(SurfaceStyle.Flat, persister.shape().surfaceStyle)
        }
    }

    /** Navigation picks redraw live (`data-dt-nav`) and persist; the ring follows. */
    @Test
    fun pickingNavigationAppliesAndPersists() = GlobalScope.promise {
        val persister = RowsMemoryPersister()
        withSettingsOpen(persister = persister) { _, _ ->
            assertEquals("both", rootEl().getAttribute("data-dt-nav"))

            choice("navigation", "Tab strip").dispatchClick()
            until("tab strip applied") { rootEl().getAttribute("data-dt-nav") == "tabstrip" }
            until("tab strip persisted") { persister.shape().navigationMode == NavigationMode.TabStrip }
            // The chrome rebuild re-mounts the panel; the new one rings the pick.
            until("ring follows") { selectedLabel("navigation") == "Tab strip" }

            choice("navigation", "Sidebar").dispatchClick()
            until("sidebar applied") { rootEl().getAttribute("data-dt-nav") == "sidebar" }
            until("sidebar persisted") { persister.shape().navigationMode == NavigationMode.Sidebar }
            until("ring follows again") { selectedLabel("navigation") == "Sidebar" }
        }
    }

    /** [appliedShellStyle] reads back exactly what [applyShellStyle] stamps. */
    @Test
    fun appliedShellStyleReadsTheStamp() {
        val el = document.createElement("div") as HTMLElement
        assertEquals(ShellStyle.Classic, appliedShellStyle(el))
        applyShellStyle(el, ShellStyle.Clean)
        assertEquals(ShellStyle.Clean, appliedShellStyle(el))
        applyShellStyle(el, ShellStyle.Classic)
        assertEquals(ShellStyle.Classic, appliedShellStyle(el))
    }

    /**
     * The Theme Manager lifts the calm themes under a Clean APP DEFAULT — the
     * case `host.shellStyle` (null there) used to miss.
     */
    @Test
    fun themePickerIsCalmFirstUnderACleanAppDefault() = GlobalScope.promise {
        withSettingsOpen(
            appDefault = AppearanceShape(shellStyle = ShellStyle.Clean),
            openSettings = false,
        ) { root, _ ->
            // Under Clean the theme manager lives in the top bar's settings
            // menu (LNA-23) rather than on a button of its own.
            (root.querySelector(".$SETTINGS_MENU_BUTTON_CLASS") as HTMLElement).dispatchClick()
            until("settings menu open") { document.querySelector(".dt-pane-menu") != null }
            val themesRow = document.querySelectorAll(".dt-pane-menu .dt-pane-menu-item").let { rows ->
                (0 until rows.length).map { rows.item(it) as HTMLElement }
                    .first { it.textContent?.contains("Themes") == true }
            }
            themesRow.dispatchClick()
            until("theme manager open") { document.querySelector(".dt-theme-card") != null }
            assertTrue(isCalmTheme(firstCardTheme()), "first card under Clean should be calm")
        }
    }

    /** …and does not under Classic. */
    @Test
    fun themePickerIsNotCalmFirstUnderClassic() = GlobalScope.promise {
        withSettingsOpen(openSettings = false) { root, _ ->
            (root.querySelector("button[title=\"Theme manager\"]") as HTMLElement).dispatchClick()
            until("theme manager open") { document.querySelector(".dt-theme-card") != null }
            assertFalse(isCalmTheme(firstCardTheme()), "first card under Classic should not be calm")
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
