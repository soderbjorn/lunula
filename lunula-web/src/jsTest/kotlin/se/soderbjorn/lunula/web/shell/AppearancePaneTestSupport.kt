/*
 * AppearancePaneTestSupport.kt (jsTest)
 *
 * Shared helpers for tests that drive the shell's Appearance pane: the right
 * sidebar opened by the top bar's single palette button ("Themes and
 * appearance"), with a "Themes" | "Appearance" tab strip.
 *
 * The pane is module-wide state and reopens on the tab last shown
 * ([currentAppearanceTab]), so a test that leaves it on the Appearance tab
 * would make the next test that clicks the palette button (expecting theme
 * cards) open the settings instead. [resetAppearancePane] puts it back.
 */
package se.soderbjorn.lunula.web.shell

import se.soderbjorn.lunula.web.themeeditor.AppearanceTab
import se.soderbjorn.lunula.web.themeeditor.closeThemeManagerSidebar
import se.soderbjorn.lunula.web.themeeditor.currentAppearanceTab
import se.soderbjorn.lunula.web.themeeditor.openAppearancePane

/** The palette button's tooltip, which is also its `title` attribute. */
internal const val APPEARANCE_PANE_BUTTON_TITLE: String = "Themes and appearance"

/** CSS selector for the top bar's palette button. */
internal const val APPEARANCE_PANE_BUTTON: String = "button[title=\"$APPEARANCE_PANE_BUTTON_TITLE\"]"

/**
 * Closes the Appearance pane (without animation) and makes it reopen on the
 * Themes tab, as a fresh page would.
 *
 * Called from tests' cleanup. Selecting the tab of a closed pane has no public
 * setter, so this opens the pane on Themes with a no-op rebuild (state only,
 * nothing is mounted) and closes it again at once.
 */
internal fun resetAppearancePane() {
    if (currentAppearanceTab != AppearanceTab.Themes) {
        closeThemeManagerSidebar()
        openAppearancePane(AppearanceTab.Themes) { }
    }
    closeThemeManagerSidebar()
}

/**
 * Removes every style stamp `applyAppearance` writes on the document root
 * (the per-setting attributes, density and the corner-radius override, plus
 * the retired `data-dt-style` in case a test set it by hand), so the next
 * test starts from the base stylesheet.
 *
 * Called from tests' cleanup; a shell mounted by a test re-stamps the root on
 * every apply pass, so this must run after the shell is disposed.
 */
internal fun clearStyleStamps() {
    val root = kotlinx.browser.document.documentElement as org.w3c.dom.HTMLElement
    for (attr in listOf(
        "data-dt-style", "data-dt-selection", "data-dt-surface", "data-dt-chrome",
        "data-dt-windows", "data-dt-focus", "data-dt-labels", "data-dt-density",
    )) root.removeAttribute(attr)
    root.style.removeProperty("--dt-corner-radius")
}
