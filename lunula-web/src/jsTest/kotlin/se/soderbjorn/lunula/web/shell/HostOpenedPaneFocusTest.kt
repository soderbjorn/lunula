/*
 * HostOpenedPaneFocusTest.kt (jsTest)
 *
 * A host that opens a new pane in answer to a press inside another pane —
 * Lunarbor's Shift-click on a link, which opens the target in a new window
 * (LBR-8) — must see that new pane keep the focus it gave it.
 *
 * The press focuses the pane it lands in twice over: an optimistic hold that
 * pins that pane against in-flight snapshots, and a focus announcement owed
 * to the host at the `pointerup`. Both would undo the host's choice of the
 * pane it just created. [AppShellMount]'s `yieldToHostOpenedPanes` drops
 * them when a snapshot activates a pane the previous one did not have; this
 * test pins that, and that an ordinary stale snapshot (active pane already
 * known) is still held off.
 *
 * Mounts the real shell in source mode and drives it with the events the
 * browser would send, like [PaneFocusReportedToHostTest].
 *
 * @see se.soderbjorn.lunula.web.shell.AppShellMount
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.core.Persister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class HostOpenedTestPersister : Persister {
    private val store = mutableMapOf<String, String>()
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

/** One tab holding [ids], [active] focused — the shape a host pushes. */
private fun panes(vararg ids: String, active: String) = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "Tab 1",
            panes = ids.map { PaneSnapshotEntry(it) },
            activePaneId = active,
        ),
    ),
    activeTabId = "t1",
)

/** Mounts a shell into its own container and removes it afterwards. */
private suspend fun withMountedShell(source: TabSource, body: suspend (root: HTMLElement) -> Unit) {
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "host-opened-pane-test",
                persister = HostOpenedTestPersister(),
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = source,
            ),
        )
        body(root)
    } finally {
        root.remove()
    }
}

private fun HTMLElement.paneEl(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

private fun HTMLElement.focusedPaneId(): String? =
    (querySelector(".dt-pane.dt-pane-focused") as? HTMLElement)?.getAttribute("data-pane-id")

private fun HTMLElement.primaryPress() {
    dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true, cancelable = true, button = 0)))
}

class HostOpenedPaneFocusTest {

    /**
     * Press in `p1`; while the button is down the host opens `p3` and makes
     * it active. After the release `p3` is focused and the host is never told
     * to go back to `p1`.
     */
    @Test
    fun aPaneTheHostOpensDuringAPressKeepsTheFocus() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val reported = mutableListOf<Pair<String, String>>()
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneFocused = { tabId, paneId -> reported += tabId to paneId },
        )
        withMountedShell(source) { root ->
            waitFor("tab source subscribe") { push != null }
            push!!(panes("p1", "p2", active = "p2"))
            waitFor("panes rendered") { root.paneEl("p1") != null && root.paneEl("p2") != null }

            document.dispatchEvent(Event("pointerdown"))
            root.paneEl("p1")!!.primaryPress()
            // The host's answer to the press: a new pane, focused.
            push!!(panes("p1", "p2", "p3", active = "p3"))
            document.dispatchEvent(Event("pointerup"))

            waitFor("new pane rendered") { root.paneEl("p3") != null }
            delay(100)
            assertEquals("p3", root.focusedPaneId(), "the pane the host just opened lost the focus")
            assertTrue(
                reported.none { it.second == "p1" },
                "the host was told to refocus the pressed pane after it opened another: $reported",
            )
        }
    }

    /**
     * The same when the host answers the gesture's `click` rather than its
     * press (a result row that acts on click): the press on `p1` is over,
     * its focus not yet announced, when `p3` opens focused.
     */
    @Test
    fun aPaneTheHostOpensOnTheClickKeepsTheFocus() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val reported = mutableListOf<Pair<String, String>>()
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneFocused = { tabId, paneId -> reported += tabId to paneId },
        )
        withMountedShell(source) { root ->
            waitFor("tab source subscribe") { push != null }
            push!!(panes("p1", "p2", active = "p2"))
            waitFor("panes rendered") { root.paneEl("p1") != null && root.paneEl("p2") != null }

            document.dispatchEvent(Event("pointerdown"))
            root.paneEl("p1")!!.primaryPress()
            document.dispatchEvent(Event("pointerup"))
            // The click, in the same task as the pointerup.
            push!!(panes("p1", "p2", "p3", active = "p3"))

            waitFor("new pane rendered") { root.paneEl("p3") != null }
            delay(100)
            assertEquals("p3", root.focusedPaneId(), "the pane the host just opened lost the focus")
            assertTrue(
                reported.none { it.second == "p1" },
                "the host was told to refocus the pressed pane after it opened another: $reported",
            )
        }
    }

    /**
     * The hold still does its job for a stale snapshot: press `p1` while the
     * host's in-flight push still names the old, existing `p2` as active —
     * `p1` stays focused and is announced.
     */
    @Test
    fun aStaleSnapshotNamingAnExistingPaneIsStillHeldOff() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        var reported: Pair<String, String>? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneFocused = { tabId, paneId -> reported = tabId to paneId },
        )
        withMountedShell(source) { root ->
            waitFor("tab source subscribe") { push != null }
            push!!(panes("p1", "p2", active = "p2"))
            waitFor("panes rendered") { root.paneEl("p1") != null && root.paneEl("p2") != null }

            document.dispatchEvent(Event("pointerdown"))
            root.paneEl("p1")!!.primaryPress()
            assertNull(reported)
            push!!(panes("p1", "p2", active = "p2"))
            document.dispatchEvent(Event("pointerup"))

            waitFor("host told about the focus") { reported != null }
            assertEquals("t1" to "p1", reported)
            assertEquals("p1", root.focusedPaneId())
        }
    }
}
