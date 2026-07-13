/*
 * WorldSwitchFlickerTest.kt (jsTest)
 *
 * Reproduction harness for the "left-sidebar rows re-sort 2–3 times when
 * switching worlds" flicker. It drives a world round-trip through the REAL
 * [mountAppShell] shell exactly as a world-aware host does, and — crucially —
 * models the host's LAYOUT_STATE echo (the server broadcast of the shell's own
 * per-world persist, fed back into [AppShellHandle.applyExternalLayoutState]),
 * which is what turns a single logical switch into several render passes.
 *
 * A render observer ([onAppShellRenderedForTest]) records the sidebar pane-row
 * order (read straight from the DOM) after every render pass, so the test can
 * both print the full render sequence (diagnosis) and assert that a switch
 * settles to the user's saved order without a transient reorder (regression).
 *
 * @see se.soderbjorn.darkness.web.shell.AppShellMount
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.Persister
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The world whose layout is aliased onto the legacy flat LAYOUT_STATE key. */
private const val DEFAULT_WORLD = "w1"

/** Toolkit per-world key → server key, aliasing the default world to flat. */
private fun serverKeyFor(toolkitKey: String): String {
    val prefix = "${PersistKeys.LAYOUT_STATE}.world."
    if (!toolkitKey.startsWith(prefix)) return toolkitKey
    val worldId = toolkitKey.removePrefix(prefix)
    return if (worldId == DEFAULT_WORLD) PersistKeys.LAYOUT_STATE else toolkitKey
}

private fun serverKeyForWorld(worldId: String): String =
    if (worldId == DEFAULT_WORLD) PersistKeys.LAYOUT_STATE
    else "${PersistKeys.LAYOUT_STATE}.world.$worldId"

/**
 * In-memory persister that mirrors Lunamux's [SettingsPersisterAdapter] +
 * rawLayoutState collector: it aliases the toolkit's per-world keys onto the
 * server keys, and whenever the *flat* LAYOUT_STATE key changes it echoes the
 * new blob back into the shell via [applyExternalLayoutState] on the next tick
 * — exactly the server broadcast that the real host feeds back in.
 */
private class EchoingWorldPersister : Persister {
    val store = mutableMapOf<String, String>()
    var handle: AppShellHandle? = null

    override suspend fun read(key: String): String? = store[serverKeyFor(key)]

    override suspend fun write(key: String, value: String) {
        val serverKey = serverKeyFor(key)
        store[serverKey] = value
        if (serverKey == PersistKeys.LAYOUT_STATE) {
            // Server broadcast echo — asynchronous, like the real /window push.
            GlobalScope.promise {
                delay(1)
                handle?.applyExternalLayoutState(value)
            }
        }
    }
}

private fun HTMLElement.paneByIdLocal(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

private suspend fun waitFor(what: String, condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

/** World w1 (default): tab t1 with panes p1,p2,p3, active p1. */
private fun world1Snapshot() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "LUNAMUX",
            panes = listOf(PaneSnapshotEntry("p1"), PaneSnapshotEntry("p2"), PaneSnapshotEntry("p3")),
            activePaneId = "p1",
        ),
    ),
    activeTabId = "t1",
    worldId = "w1",
)

/** World w2: disjoint tab t2 with a single pane px. */
private fun world2Snapshot() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t2",
            label = "STOREX",
            panes = listOf(PaneSnapshotEntry("px")),
            activePaneId = "px",
        ),
    ),
    activeTabId = "t2",
    worldId = "w2",
)

/**
 * w1's saved layout with the user's drag order: p3 pulled to the TOP, so the
 * saved importance order [p3,p1,p2] deliberately differs from the snapshot
 * pane order [p1,p2,p3]. Custom preset so nothing re-tiles.
 */
private const val W1_DRAGGED_LAYOUT =
    """{"presetByTab":{"t1":"custom"},"paneOrderByTab":{"t1":["p3","p1","p2"]},""" +
        """"geometryByTab":{"t1":{""" +
        """"p1":{"xPct":0.10,"yPct":0.10,"widthPct":0.4,"heightPct":0.5,"zIndex":1,"isMaximized":false,"isMinimized":false},""" +
        """"p2":{"xPct":0.30,"yPct":0.20,"widthPct":0.4,"heightPct":0.5,"zIndex":2,"isMaximized":false,"isMinimized":false},""" +
        """"p3":{"xPct":0.50,"yPct":0.30,"widthPct":0.4,"heightPct":0.5,"zIndex":3,"isMaximized":false,"isMinimized":false}}}}"""

class WorldSwitchFlickerTest {

    @AfterTest
    fun tearDown() {
        onAppShellRenderedForTest = null
    }

    private fun sidebarRowOrder(root: HTMLElement, tabId: String): List<String> =
        root.querySelectorAll("[data-tab-id=\"$tabId\"][data-pane-id]").asList()
            .mapNotNull { (it as? HTMLElement)?.getAttribute("data-pane-id") }

    @Test
    fun switchingBackToAWorldDoesNotFlickerTheSidebarOrder() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { _, _ -> },
        )
        val persister = EchoingWorldPersister()

        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)

        // Record the sidebar row order painted by EVERY render pass so a
        // transient re-sort (not just the final state) is caught.
        val renders = mutableListOf<List<String>>()
        onAppShellRenderedForTest = {
            val order = sidebarRowOrder(root, "t1")
            if (order.isNotEmpty()) renders.add(order)
        }

        try {
            val handle = mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "world-switch-flicker",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = source,
                    worldLayoutProvider = { worldId -> persister.store[serverKeyForWorld(worldId)] },
                ),
            )
            persister.handle = handle
            waitFor("tab source subscribe") { push != null }

            // --- World 1: render, then apply the user's dragged layout. ---
            push!!(world1Snapshot())
            waitFor("w1 rendered") { root.paneByIdLocal("p1") != null }
            handle.applyExternalLayoutState(W1_DRAGGED_LAYOUT)
            waitFor("w1 dragged order adopted") {
                handle.currentLayoutStateJson().contains("\"paneOrderByTab\"")
            }

            // --- Switch to w2, then back to w1. ---
            push!!(world2Snapshot())
            waitFor("w2 rendered") { root.paneByIdLocal("px") != null }

            renders.clear() // only care about the switch-back render sequence
            push!!(world1Snapshot())
            waitFor("w1 re-rendered") { root.paneByIdLocal("p1") != null }
            // Let the async LAYOUT_STATE echo (pass 3) land and settle.
            delay(200)

            // The user's saved drag order (p3 on top) must survive the round
            // trip — every render pass of the switch-back, and the settled DOM.
            val expected = listOf("p3", "p1", "p2")
            assertTrue(renders.isNotEmpty(), "expected at least one t1 sidebar render on switch-back")
            renders.forEachIndexed { i, order ->
                assertEquals(
                    expected, order,
                    "sidebar row order churned on switch-back render pass $i" +
                        " (full sequence: $renders)",
                )
            }
            assertEquals(
                expected, sidebarRowOrder(root, "t1"),
                "sidebar did not settle to the saved drag order after the switch",
            )
        } finally {
            root.remove()
        }
    }
}
