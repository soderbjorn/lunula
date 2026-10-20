/*
 * NavigationModeTest.kt (jsTest)
 *
 * Tests for the runtime navigation mode (Sidebar / Tab strip / Both):
 *
 *  - the precedence and fallback rules in [resolveNavigationLayout] (the
 *    app's hard `showSidebar` / `showTabStrip = false` first, then the user,
 *    then the app default, then [NavigationMode.Default]), and the
 *    `data-dt-nav` token they produce;
 *  - a mounted shell switching modes through [AppShellHandle.setNavigationMode]
 *    without a remount: the sidebar and tab strip appear and disappear, the
 *    active tab and pane are kept, the sidebar's toggle-collapse survives a
 *    round trip, the choice is persisted, and an app's opt-out still wins.
 *
 * The mounted tests drive the real [mountAppShell] in source mode with an
 * in-memory [Persister] and a hand-pushed [TabSource] snapshot, the same
 * harness as SidebarMinimizedRestoreTest. They run in the karma browser
 * environment against the real DOM; `lunula.css` is not loaded, so every
 * assertion is structural.
 *
 * @see resolveNavigationLayout
 * @see AppShellHandle.setNavigationMode
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class NavMemoryPersister(seed: Map<String, String> = emptyMap()) : Persister {
    val store = mutableMapOf<String, String>().apply { putAll(seed) }
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

/** Polls [condition] every tick until it holds or ~3s elapse. */
private suspend fun waitFor(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

/**
 * Two tabs, the first with two panes, the second with one. Tab `t2` is
 * active with `p2` focused, so a mode switch that silently fell back to the
 * first tab or pane would show.
 */
private fun twoTabSnapshot() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "One",
            panes = listOf(PaneSnapshotEntry("p1"), PaneSnapshotEntry("p2")),
            activePaneId = "p2",
        ),
        TabSnapshotEntry(
            id = "t2",
            label = "Two",
            panes = listOf(PaneSnapshotEntry("p3")),
            activePaneId = "p3",
        ),
    ),
    activeTabId = "t1",
)

/** The `data-dt-nav` stamp on `:root`, or `null` when absent. */
private fun navAttr(): String? =
    (document.documentElement as HTMLElement).getAttribute("data-dt-nav")

/** The mounted left sidebar (open or collapsed placeholder), if any. */
private fun HTMLElement.leftSidebar(): HTMLElement? =
    querySelector(".dt-sidebar-left") as? HTMLElement

/** The topbar's left-sidebar toggle button, if rendered. */
private fun HTMLElement.sidebarToggle(): HTMLElement? =
    querySelector("button[title=\"Toggle left sidebar\"]") as? HTMLElement

/** The topbar's tab strip, if rendered. */
private fun HTMLElement.tabStrip(): HTMLElement? =
    querySelector(".${TabBarClassNames.BAR}") as? HTMLElement

/** The rendered pane element for [id] (not its sidebar row). */
private fun HTMLElement.pane(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

/** The selected tab in the strip, by id. */
private fun HTMLElement.selectedTabId(): String? =
    (querySelector(".${TabBarClassNames.TAB}.${TabBarClassNames.TAB_SELECTED}") as? HTMLElement)
        ?.getAttribute("data-tab-id")

/**
 * Mounts a fresh shell, pushes [twoTabSnapshot] and runs [body] once both
 * panes of the active tab are on screen. The root is removed afterwards so a
 * failed test never leaks ids into the next one's queries.
 *
 * @param persister     the shell's persister (seed it to restore a mode).
 * @param showSidebar   [AppShellSpec.showSidebar].
 * @param showTabStrip  [AppShellSpec.showTabStrip].
 * @param appDefault    the app's [AppShellSpec.defaultAppearanceShape].
 * @param body          the test, given the root and the handle.
 */
private suspend fun withShell(
    persister: Persister = NavMemoryPersister(),
    showSidebar: Boolean = true,
    showTabStrip: Boolean = true,
    appDefault: AppearanceShape = AppearanceShape(),
    body: suspend (root: HTMLElement, handle: AppShellHandle) -> Unit,
) {
    var push: ((TabListSnapshot) -> Unit)? = null
    var current = twoTabSnapshot()
    // Answers a tab switch the way a real host does: by pushing a snapshot
    // with the new active tab.
    val source = TabSource(
        subscribe = { p -> push = p },
        onSelect = { id ->
            current = current.copy(activeTabId = id)
            push?.invoke(current)
        },
        onPaneSelect = { _, _ -> },
    )
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "nav-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                paneLabel = { _, paneId -> "Window $paneId" },
                tabSource = source,
                showSidebar = showSidebar,
                showTabStrip = showTabStrip,
                defaultAppearanceShape = { appDefault },
            ),
        )
        waitFor("tab source subscribe") { push != null }
        push!!(current)
        waitFor("active tab's panes rendered") { root.pane("p1") != null && root.pane("p2") != null }
        body(root, handle)
        handle.dispose()
    } finally {
        root.remove()
    }
}

class NavigationModeTest {

    /**
     * [leftSidebarController] is module-wide, so put it back open for the
     * next test (in this file or another) after one that collapsed it.
     */
    @AfterTest
    fun reset() {
        leftSidebarController.setInitial(open = true, widthPx = leftSidebarController.widthPx)
        (document.documentElement as HTMLElement).removeAttribute("data-dt-nav")
    }

    // ── Precedence and fallback ─────────────────────────────────────

    /** Nobody chose: Both, which is what every app drew before the setting. */
    @Test
    fun unsetDrawsBothLikeTheShellAlwaysHas() {
        val layout = resolveNavigationLayout(null, null, appAllowsSidebar = true, appAllowsTabStrip = true)
        assertEquals(NavigationMode.Both, layout.mode)
        assertTrue(layout.showsSidebar)
        assertTrue(layout.showsTabStrip)
        assertEquals("both", layout.cssValue)
    }

    /** The app default applies when the user has no pick… */
    @Test
    fun appDefaultAppliesWhenTheUserHasNoPick() {
        val layout = resolveNavigationLayout(null, NavigationMode.Sidebar, true, true)
        assertEquals(NavigationMode.Sidebar, layout.mode)
        assertTrue(layout.showsSidebar)
        assertFalse(layout.showsTabStrip)
    }

    /** …and the user's own pick beats it. */
    @Test
    fun userPickBeatsTheAppDefault() {
        val layout = resolveNavigationLayout(NavigationMode.TabStrip, NavigationMode.Sidebar, true, true)
        assertEquals(NavigationMode.TabStrip, layout.mode)
        assertFalse(layout.showsSidebar)
        assertTrue(layout.showsTabStrip)
        assertEquals("tabstrip", layout.cssValue)
    }

    /**
     * An app's hard opt-out beats the user: Both in an app with no tab strip
     * draws only the sidebar (Lunicle's embeds rely on this).
     */
    @Test
    fun appOptOutBeatsTheUser() {
        val noStrip = resolveNavigationLayout(NavigationMode.Both, null, true, appAllowsTabStrip = false)
        assertTrue(noStrip.showsSidebar)
        assertFalse(noStrip.showsTabStrip)
        assertEquals(NavigationMode.Both, noStrip.mode, "the request itself is kept for the settings control")

        val noSidebar = resolveNavigationLayout(NavigationMode.Both, null, appAllowsSidebar = false, true)
        assertFalse(noSidebar.showsSidebar)
        assertTrue(noSidebar.showsTabStrip)
    }

    /**
     * When the opt-out removes the only surface the mode asks for, fall back
     * to what the app allows instead of leaving no navigation at all.
     */
    @Test
    fun aModeLeftWithNothingFallsBackToWhatIsAvailable() {
        val stripOnlyInSidebarApp = resolveNavigationLayout(NavigationMode.TabStrip, null, true, appAllowsTabStrip = false)
        assertTrue(stripOnlyInSidebarApp.showsSidebar)
        assertFalse(stripOnlyInSidebarApp.showsTabStrip)
        assertEquals("sidebar", stripOnlyInSidebarApp.cssValue)

        val sidebarOnlyInStripApp = resolveNavigationLayout(NavigationMode.Sidebar, null, appAllowsSidebar = false, true)
        assertFalse(sidebarOnlyInStripApp.showsSidebar)
        assertTrue(sidebarOnlyInStripApp.showsTabStrip)
        assertEquals("tabstrip", sidebarOnlyInStripApp.cssValue)
    }

    /** An app that disables both gets neither, whatever the user picked. */
    @Test
    fun anAppThatDisablesBothGetsNone() {
        for (mode in NavigationMode.entries) {
            val layout = resolveNavigationLayout(mode, null, appAllowsSidebar = false, appAllowsTabStrip = false)
            assertFalse(layout.showsSidebar)
            assertFalse(layout.showsTabStrip)
            assertEquals("none", layout.cssValue)
        }
    }

    // ── The mounted shell ───────────────────────────────────────────

    /** Default mount: both surfaces, stamped `both` — today's chrome. */
    @Test
    fun aDefaultMountDrawsSidebarAndTabStrip() = GlobalScope.promise {
        withShell { root, _ ->
            assertNotNull(root.leftSidebar(), "sidebar")
            assertNotNull(root.sidebarToggle(), "sidebar toggle")
            assertNotNull(root.tabStrip(), "tab strip")
            assertEquals("both", navAttr())
        }
    }

    /**
     * Switching modes at runtime shows and hides the surfaces without a
     * remount, and keeps the active tab and the focused pane through every
     * switch.
     */
    @Test
    fun switchingModesKeepsTheActiveTabAndPane() = GlobalScope.promise {
        withShell { root, handle ->
            val p2 = root.pane("p2")
            assertNotNull(p2)
            waitFor("p2 focused") { p2.classList.contains("dt-pane-focused") }

            handle.setNavigationMode(NavigationMode.TabStrip)
            waitFor("sidebar hidden") { root.leftSidebar() == null }
            assertNull(root.sidebarToggle(), "no toggle for a sidebar that is not there")
            assertNotNull(root.tabStrip())
            assertEquals("tabstrip", navAttr())
            assertEquals("t1", root.selectedTabId())
            assertTrue(root.pane("p2")?.classList?.contains("dt-pane-focused") == true)

            handle.setNavigationMode(NavigationMode.Sidebar)
            waitFor("tab strip hidden") { root.tabStrip() == null }
            assertNotNull(root.leftSidebar())
            assertEquals("sidebar", navAttr())
            val activeRow = root.querySelector(".dt-sidebar-row.dt-active") as? HTMLElement
            assertEquals("p2", activeRow?.getAttribute("data-pane-id"), "the focused pane stays the active row")
            assertTrue(root.pane("p2")?.classList?.contains("dt-pane-focused") == true)

            handle.setNavigationMode(NavigationMode.Both)
            waitFor("both back") { root.tabStrip() != null && root.leftSidebar() != null }
            assertEquals("t1", root.selectedTabId())
            assertTrue(root.pane("p2") === p2, "the pane element itself survived — no remount")
        }
    }

    /** The choice is persisted under the shape key, beside the other shape settings. */
    @Test
    fun theChoiceIsPersisted() = GlobalScope.promise {
        val persister = NavMemoryPersister()
        withShell(persister = persister) { _, handle ->
            handle.setNavigationMode(NavigationMode.TabStrip)
            waitFor("persisted") {
                AppearanceShape.fromJson(persister.store[PersistKeys.APPEARANCE_SHAPE]).navigationMode ==
                    NavigationMode.TabStrip
            }
        }
    }

    /** A persisted pick is restored at boot, before the user sees the chrome. */
    @Test
    fun aPersistedPickIsRestoredAtBoot() = GlobalScope.promise {
        val persister = NavMemoryPersister(
            mapOf(PersistKeys.APPEARANCE_SHAPE to AppearanceShape(navigationMode = NavigationMode.TabStrip).toJson()),
        )
        withShell(persister = persister) { root, _ ->
            assertNull(root.leftSidebar())
            assertNotNull(root.tabStrip())
        }
    }

    /** The app default reaches the chrome when the user has not chosen. */
    @Test
    fun theAppDefaultIsDrawnWhenTheUserHasNotChosen() = GlobalScope.promise {
        withShell(appDefault = AppearanceShape(navigationMode = NavigationMode.Sidebar)) { root, _ ->
            assertNull(root.tabStrip())
            assertNotNull(root.leftSidebar())
        }
    }

    /**
     * An app with `showTabStrip = false` keeps it off whatever the user asks,
     * and a Tab-strip-only pick falls back to the sidebar it does allow.
     */
    @Test
    fun anAppOptOutWinsAtRuntime() = GlobalScope.promise {
        withShell(showTabStrip = false) { root, handle ->
            handle.setNavigationMode(NavigationMode.Both)
            delay(50)
            assertNull(root.tabStrip())
            handle.setNavigationMode(NavigationMode.TabStrip)
            delay(50)
            assertNull(root.tabStrip(), "the app's opt-out wins over the user")
            assertNotNull(root.leftSidebar(), "falls back to the sidebar rather than to nothing")
            assertEquals("sidebar", navAttr())
        }
    }

    /**
     * A sidebar collapsed with the toggle stays collapsed through a trip to
     * Tab strip and back: the mode decides whether the sidebar is mounted,
     * the controller still decides whether it is open.
     */
    @Test
    fun aToggleCollapseSurvivesAModeChange() = GlobalScope.promise {
        withShell { root, handle ->
            handle.setSidebarOpen(false)
            waitFor("collapsed") { !leftSidebarController.isOpen }
            waitFor("collapsed placeholder") {
                root.leftSidebar()?.classList?.contains("dt-sidebar-collapsed") == true
            }

            handle.setNavigationMode(NavigationMode.TabStrip)
            waitFor("sidebar hidden") { root.leftSidebar() == null }
            // A programmatic open while the mode hides the bar must not flip
            // the remembered state behind the user's back.
            handle.setSidebarOpen(true)
            assertFalse(leftSidebarController.isOpen)

            handle.setNavigationMode(NavigationMode.Both)
            waitFor("sidebar back") { root.leftSidebar() != null }
            assertTrue(
                root.leftSidebar()!!.classList.contains("dt-sidebar-collapsed"),
                "the sidebar comes back collapsed, as the user left it",
            )
            assertFalse(leftSidebarController.isOpen)
        }
    }
}
