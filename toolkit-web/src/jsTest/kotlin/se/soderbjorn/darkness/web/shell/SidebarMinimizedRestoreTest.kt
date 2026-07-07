/*
 * SidebarMinimizedRestoreTest.kt (jsTest)
 *
 * DOM-level regression tests for the sidebar-row restore path: clicking
 * the sidebar row of a minimized (docked) pane must clear its
 * `isMinimized` geometry flag, re-enter it into the layout, surface it
 * (clearing a maximized sibling that would otherwise cover it), and
 * mark it active — the same behaviour as the dock-chip restore. Mounts
 * the real [mountAppShell] shell in source mode (a fake in-memory
 * [Persister] + a hand-pushed [TabSource] snapshot, mirroring how
 * termtastic drives it) and exercises the actual pane-chrome minimize
 * button and sidebar row DOM, so a regression anywhere along the
 * click → updateGeometry → reflowAfterMinimizeChange → render chain
 * fails the test.
 *
 * @see se.soderbjorn.darkness.web.shell.AppShellMount
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.Persister
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private open class MemoryPersister : Persister {
    val store = mutableMapOf<String, String>()
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
 * One-tab / two-pane snapshot in the shape termtastic pushes.
 *
 * @param focused the tab's `activePaneId`.
 */
private fun twoPaneSnapshot(focused: String = "p1") = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "Tab 1",
            panes = listOf(PaneSnapshotEntry("p1"), PaneSnapshotEntry("p2")),
            activePaneId = focused,
        ),
    ),
    activeTabId = "t1",
)

/**
 * Mounts a fresh shell into a detached-then-attached container and runs
 * [body] against it, guaranteeing the container is removed afterwards so
 * one test's (possibly failed) shell never leaks pane/row ids into the
 * next test's queries. All DOM lookups inside tests must go through the
 * returned root, not `document`.
 */
private suspend fun withMountedShell(
    persister: Persister,
    source: TabSource,
    body: suspend (root: HTMLElement, handle: AppShellHandle) -> Unit,
) {
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "restore-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = source,
            ),
        )
        body(root, handle)
    } finally {
        root.remove()
    }
}

private fun HTMLElement.pane(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

private fun HTMLElement.sidebarRow(id: String): HTMLElement? =
    querySelector(".dt-sidebar-row[data-pane-id=\"$id\"]") as? HTMLElement

private fun HTMLElement.dockChip(id: String): HTMLElement? =
    querySelector(".dt-pane-dock-item[data-dock-pane-id=\"$id\"]") as? HTMLElement

private fun HTMLElement.clickAsUser() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

class SidebarMinimizedRestoreTest {

    @Test
    fun clickingMinimizedPaneSidebarRowRestoresAndActivatesIt() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        var lastPaneSelect: Pair<String, String>? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { tabId, paneId -> lastPaneSelect = tabId to paneId },
        )
        withMountedShell(MemoryPersister(), source) { root, _ ->
            // The mount binds the tab source from its async init job.
            waitFor("tab source subscribe") { push != null }
            push!!(twoPaneSnapshot())
            waitFor("both panes rendered") { root.pane("p1") != null && root.pane("p2") != null }

            // Minimize p2 via its pane-chrome minimize action — the same
            // affordance the user presses. (Scope to `.dt-pane`: the
            // sidebar rows carry `data-pane-id` too.)
            val minimizeBtn = root.pane("p2")!!.querySelector(".dt-pane-action-minimize") as? HTMLElement
            assertNotNull(minimizeBtn, "pane chrome should carry a minimize action")
            minimizeBtn.click()

            waitFor("p2 docked") { root.dockChip("p2") != null }
            waitFor("p2 sidebar row dimmed as minimized") {
                root.sidebarRow("p2")?.classList?.contains("dt-sidebar-row-minimized") == true
            }

            // Click the minimized pane's sidebar row — bubbling click like
            // a real user press on the row label.
            root.sidebarRow("p2")!!.clickAsUser()

            // The restore is synchronous in the click handler: the pane
            // must re-enter the layout, leave the dock, and the row must
            // both lose its minimized dimming and become the active row.
            waitFor("p2 restored into the layout") { root.pane("p2") != null }
            waitFor("dock chip for p2 gone") { root.dockChip("p2") == null }
            assertTrue(
                root.sidebarRow("p2")?.classList?.contains("dt-sidebar-row-minimized") == false,
                "sidebar row should no longer be dimmed as minimized",
            )
            waitFor("p2 marked active in the sidebar") {
                root.sidebarRow("p2")?.classList?.contains("dt-active") == true
            }
            // Source mode must also notify the host so server focus follows.
            assertTrue(
                lastPaneSelect == ("t1" to "p2"),
                "host onPaneSelect should fire for the restored pane",
            )
        }
    }

    /**
     * The maximize-heavy dock scenario: one pane maximized (full-bleed),
     * another minimized to the dock, tab preset Custom (any manual move /
     * resize lands there). Clicking the docked pane's sidebar row must
     * SURFACE it — un-minimize it AND clear the maximized sibling that
     * would otherwise keep covering it. Under non-Custom presets the
     * restore re-tile clears `isMaximized` as a side effect; Custom skips
     * the re-tile, so the restore path itself must un-maximize the sibling.
     */
    @Test
    fun restoringUnderMaximizedSiblingSurfacesThePane() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { _, _ -> },
        )
        withMountedShell(MemoryPersister(), source) { root, handle ->
            waitFor("tab source subscribe") { push != null }
            push!!(twoPaneSnapshot())
            waitFor("both panes rendered") { root.pane("p1") != null && root.pane("p2") != null }

            // Seed the user's arrangement in one adopt: Custom preset, p2
            // minimized, p1 maximized — the state a maximize-heavy session
            // reaches after docking a pane and expanding another.
            handle.applyExternalLayoutState(
                """{"presetByTab":{"t1":"custom"},"paneOrderByTab":{"t1":["p1","p2"]},""" +
                    """"geometryByTab":{"t1":{""" +
                    """"p1":{"xPct":0,"yPct":0,"widthPct":0.5,"heightPct":1,"zIndex":2,"isMaximized":true,"isMinimized":false},""" +
                    """"p2":{"xPct":0.5,"yPct":0,"widthPct":0.5,"heightPct":1,"zIndex":1,"isMaximized":false,"isMinimized":true}}}}""",
            )
            waitFor("p1 maximized") {
                root.pane("p1")?.classList?.contains("dt-maximized") == true
            }
            waitFor("p2 docked") { root.dockChip("p2") != null }

            // Click p2's sidebar row — the only visible restore affordance
            // while a maximized pane covers the layout.
            root.sidebarRow("p2")!!.clickAsUser()

            waitFor("p2 restored into the layout") { root.pane("p2") != null }
            // The restored pane must actually SURFACE: the maximized
            // sibling may no longer cover it.
            waitFor("maximized sibling cleared so the restored pane is visible") {
                root.pane("p1")?.classList?.contains("dt-maximized") == false
            }
            waitFor("p2 marked active in the sidebar") {
                root.sidebarRow("p2")?.classList?.contains("dt-active") == true
            }
        }
    }

    /**
     * Same restore, but with the host behaving like termtastic against a
     * real server: `onPaneSelect` answers with an async snapshot push
     * whose `activePaneId` confirms the selection (the config round-trip),
     * and every persisted LAYOUT_STATE write is echoed back into
     * [AppShellHandle.applyExternalLayoutState] (the server's ui-settings
     * broadcast). The restore must survive both echoes.
     */
    @Test
    fun restoreSurvivesServerRoundTripEchoes() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        var handleRef: AppShellHandle? = null
        var focused = "p1"
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { _, paneId ->
                // Server confirms the focus on its next config push.
                GlobalScope.promise {
                    delay(30)
                    focused = paneId
                    push?.invoke(twoPaneSnapshot(focused))
                }
            },
        )
        // Echo every LAYOUT_STATE write back like the server's broadcast.
        val persister = object : MemoryPersister() {
            override suspend fun write(key: String, value: String) {
                super.write(key, value)
                if (key == PersistKeys.LAYOUT_STATE) {
                    GlobalScope.promise {
                        delay(20)
                        handleRef?.applyExternalLayoutState(value)
                    }
                }
            }
        }
        withMountedShell(persister, source) { root, handle ->
            handleRef = handle
            waitFor("tab source subscribe") { push != null }
            push!!(twoPaneSnapshot(focused))
            waitFor("both panes rendered") { root.pane("p1") != null && root.pane("p2") != null }

            (root.pane("p2")!!.querySelector(".dt-pane-action-minimize") as HTMLElement).click()
            waitFor("p2 docked") { root.dockChip("p2") != null }
            // Let the minimize write's echo land before the user clicks.
            delay(100)

            root.sidebarRow("p2")!!.clickAsUser()
            waitFor("p2 restored into the layout") { root.pane("p2") != null }

            // Wait past the focus-confirm push and the layout-state echo;
            // the pane must STAY restored and become active.
            delay(200)
            assertTrue(
                root.pane("p2") != null,
                "restored pane must survive the server round-trip echoes",
            )
            assertTrue(
                root.dockChip("p2") == null,
                "dock chip must stay gone after the echoes",
            )
            waitFor("p2 marked active in the sidebar") {
                root.sidebarRow("p2")?.classList?.contains("dt-active") == true
            }
        }
        handleRef = null
    }
}
