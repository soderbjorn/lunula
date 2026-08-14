/*
 * PaneFocusReportedToHostTest.kt (jsTest)
 *
 * The host's half of pane focus: a press inside a pane must reach
 * [TabSource.onPaneFocused], and it must reach it only once the press is over.
 *
 * Both halves are load-bearing and neither is visible in the DOM, which is why
 * they are tested here rather than left to a screenshot. Reported too late (or
 * never) and a host that models focus itself — one whose snapshots carry
 * `activePaneId`, as the contract asks — keeps re-asserting the pane the user
 * focused two gestures ago: the ring snaps back a beat after every click.
 * Reported too early, i.e. from the mousedown itself, and a host that pushes
 * synchronously rebuilds the pane chrome under the pointer, so the button the
 * user is pressing is destroyed before its `click` is dispatched.
 *
 * Mounts the real shell in source mode, like [SidebarMinimizedRestoreTest], and
 * drives it with the events the browser would send.
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
import kotlin.test.fail

/** In-memory [Persister] so the mount's read/write round-trips are instant. */
private class FocusTestPersister : Persister {
    private val store = mutableMapOf<String, String>()
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

/** One tab, two panes, `p1` focused — the shape a host pushes. */
private fun twoPanes() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "Tab 1",
            panes = listOf(PaneSnapshotEntry("p1"), PaneSnapshotEntry("p2")),
            activePaneId = "p1",
        ),
    ),
    activeTabId = "t1",
)

/**
 * Mounts a shell into its own container and removes it afterwards, so one
 * test's panes are never visible to the next test's queries.
 */
private suspend fun withShell(source: TabSource, body: suspend (root: HTMLElement) -> Unit) {
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "pane-focus-test",
                persister = FocusTestPersister(),
                paneContent = { document.createElement("div") as HTMLElement },
                tabSource = source,
            ),
        )
        body(root)
    } finally {
        root.remove()
    }
}

private fun HTMLElement.pane(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

/** A primary-button press on this element, as the browser would send it. */
private fun HTMLElement.pressAsUser() {
    dispatchEvent(
        MouseEvent("mousedown", MouseEventInit(bubbles = true, cancelable = true, button = 0)),
    )
}

private fun fireOnDocument(type: String) {
    document.dispatchEvent(Event(type))
}

class PaneFocusReportedToHostTest {

    /**
     * The whole contract in one gesture: pressed → the host knows nothing yet;
     * released → the host is told which pane took focus.
     *
     * The mid-press assertion is the one that guards the button hazard. It is
     * the reason the announcement is deferred at all, and a "simplification"
     * that reports straight from the mousedown passes every other assertion
     * here while breaking every pane-header button in a host that pushes.
     */
    @Test
    fun aPressInsideAPaneIsReportedOnlyWhenItEnds() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        var reported: Pair<String, String>? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneFocused = { tabId, paneId -> reported = tabId to paneId },
        )
        withShell(source) { root ->
            waitUntil("tab source subscribe") { push != null }
            push!!(twoPanes())
            waitUntil("both panes rendered") { root.pane("p1") != null && root.pane("p2") != null }

            fireOnDocument("pointerdown")
            root.pane("p2")!!.pressAsUser()
            assertNull(
                reported,
                "the host was told mid-press — a synchronous push would now rebuild the " +
                    "pane chrome under the pointer and swallow the click",
            )

            fireOnDocument("pointerup")
            waitUntil("host told about the focus") { reported != null }
            assertEquals("t1" to "p2", reported)
        }
    }

    /**
     * Keyboard focus moves (Ctrl+Opt+Arrow spatial navigation) arrive through
     * the same callback with no gesture to wait for, so they must not be held
     * hostage to a `pointerup` that may never come.
     *
     * Driven here by the same press the renderer turns into a focus call, minus
     * the `pointerdown` that would mark a button held — which is exactly the
     * state a keyboard move is in.
     */
    @Test
    fun aFocusWithNoButtonHeldIsReportedStraightAway() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        var reported: Pair<String, String>? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneFocused = { tabId, paneId -> reported = tabId to paneId },
        )
        withShell(source) { root ->
            waitUntil("tab source subscribe") { push != null }
            push!!(twoPanes())
            waitUntil("both panes rendered") { root.pane("p2") != null }

            root.pane("p2")!!.pressAsUser()
            assertEquals(
                "t1" to "p2",
                reported,
                "with no button held there is nothing to wait for — the host should have " +
                    "been told already",
            )
        }
    }
}
