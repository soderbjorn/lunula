/*
 * WorldLayoutSwitchTest.kt (jsTest)
 *
 * Regression test for per-world pane layout: switching to another world and
 * back must NOT disturb the first world's pane arrangement. Before the
 * per-world-key change, [syncControllersWithSnapshot] pruned geometry for
 * every tab absent from the current snapshot — and because each world shows a
 * disjoint set of tabs, switching worlds destroyed the previous world's
 * geometry, which then re-seeded at random positions on the way back (the
 * "windows jump around after a world round-trip" bug).
 *
 * This mounts the real [mountAppShell] shell in source mode with a
 * [AppShellSpec.worldLayoutProvider] and [TabListSnapshot.worldId] (exactly
 * how a world-aware host drives it), applies a bespoke custom layout to world
 * "w1", switches to "w2" and back, and asserts w1's geometry is byte-for-byte
 * retained — and that each world persisted under its own key.
 *
 * @see se.soderbjorn.lunula.web.shell.AppShellMount
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory [Persister] so read/write round-trips are instant. */
private class WorldMemPersister : Persister {
    val store = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

private suspend fun waitForWorld(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

private fun HTMLElement.paneById(id: String): HTMLElement? =
    querySelector(".dt-pane[data-pane-id=\"$id\"]") as? HTMLElement

/** World "w1": one tab `t1` with panes `p1`,`p2`. */
private fun world1Snapshot() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t1",
            label = "Tab 1",
            panes = listOf(PaneSnapshotEntry("p1"), PaneSnapshotEntry("p2")),
            activePaneId = "p1",
        ),
    ),
    activeTabId = "t1",
    worldId = "w1",
)

/** World "w2": a disjoint tab `t2` with a single pane `p3`. */
private fun world2Snapshot() = TabListSnapshot(
    tabs = listOf(
        TabSnapshotEntry(
            id = "t2",
            label = "Tab 2",
            panes = listOf(PaneSnapshotEntry("p3")),
            activePaneId = "p3",
        ),
    ),
    activeTabId = "t2",
    worldId = "w2",
)

/**
 * World 1 on the **Auto** preset with `p1` maximized. Auto means the
 * world switch re-tiles t1 (every pane of an incoming world counts as
 * "changed" against the outgoing world's snapshot), so this is the shape
 * that regressed in lunamux#127.
 */
private const val W1_AUTO_MAXIMIZED_LAYOUT =
    """{"presetByTab":{"t1":"auto"},"paneOrderByTab":{"t1":["p1","p2"]},""" +
        """"geometryByTab":{"t1":{""" +
        """"p1":{"xPct":0,"yPct":0,"widthPct":0.5,"heightPct":1,"zIndex":2,"isMaximized":true,"isMinimized":false},""" +
        """"p2":{"xPct":0.5,"yPct":0,"widthPct":0.5,"heightPct":1,"zIndex":1,"isMaximized":false,"isMinimized":false}}}}"""

/** A distinctive custom layout for world 1 — Custom preset so it never re-tiles. */
private const val W1_CUSTOM_LAYOUT =
    """{"presetByTab":{"t1":"custom"},"paneOrderByTab":{"t1":["p1","p2"]},""" +
        """"geometryByTab":{"t1":{""" +
        """"p1":{"xPct":0.37,"yPct":0.11,"widthPct":0.4,"heightPct":0.5,"zIndex":1,"isMaximized":false,"isMinimized":false},""" +
        """"p2":{"xPct":0.55,"yPct":0.22,"widthPct":0.4,"heightPct":0.5,"zIndex":2,"isMaximized":false,"isMinimized":false}}}}"""

class WorldLayoutSwitchTest {

    @Test
    fun switchingWorldsAndBackRetainsTheFirstWorldsLayout() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { _, _ -> },
        )
        val persister = WorldMemPersister()
        // World-aware host: serve each world's saved blob from its own key,
        // mirroring how Lunamux's worldLayoutProvider reads the settings
        // snapshot. (The toolkit always writes the per-world suffixed key; the
        // default-world→LAYOUT_STATE aliasing is the host adapter's job and is
        // not exercised here.)
        fun worldKey(worldId: String) = "${PersistKeys.LAYOUT_STATE}.world.$worldId"

        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        try {
            val handle = mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "world-switch-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = source,
                    worldLayoutProvider = { worldId -> persister.store[worldKey(worldId)] },
                ),
            )
            waitForWorld("tab source subscribe") { push != null }

            // --- World 1: render, then apply a bespoke custom layout. ---
            push!!(world1Snapshot())
            waitForWorld("world 1 panes rendered") { root.paneById("p1") != null && root.paneById("p2") != null }
            handle.applyExternalLayoutState(W1_CUSTOM_LAYOUT)
            waitForWorld("w1 custom layout adopted") {
                handle.currentLayoutStateJson().contains("\"xPct\":0.37")
            }

            // --- Switch to World 2 (disjoint tabs). ---
            push!!(world2Snapshot())
            waitForWorld("world 2 pane rendered") { root.paneById("p3") != null }
            // World 2 self-persists under its own per-world key (async write).
            waitForWorld("world 2 layout persisted to its key") {
                persister.store.containsKey(worldKey("w2"))
            }
            // World 1's panes must be gone from the DOM (not its saved layout).
            waitForWorld("world 1 panes unmounted") { root.paneById("p1") == null && root.paneById("p2") == null }

            // --- Switch back to World 1. ---
            push!!(world1Snapshot())
            waitForWorld("world 1 panes re-rendered") { root.paneById("p1") != null && root.paneById("p2") != null }

            // The custom geometry must be retained byte-for-byte — NOT
            // re-seeded to a random default (the pre-fix bug).
            val restored = handle.currentLayoutStateJson()
            assertTrue(
                restored.contains("\"xPct\":0.37"),
                "world 1's p1 x-position must survive a world round-trip (got: $restored)",
            )
            assertTrue(
                restored.contains("\"xPct\":0.55"),
                "world 1's p2 x-position must survive a world round-trip (got: $restored)",
            )

            // World 2's layout is persisted under ITS OWN per-world key (it is
            // Auto-tiled by the toolkit, so it self-persists), and that key must
            // carry only world 2's pane — never world 1's. This proves the
            // toolkit writes per-world keys and never clobbers another world.
            // (World 1's custom layout arrived via applyExternalLayoutState —
            // the "already on the server" path — so the toolkit correctly does
            // not write it back; its retention above rides the in-memory cache,
            // exactly as a real cross-client adopt would.)
            assertTrue(
                persister.store.containsKey(worldKey("w2")),
                "world 2 layout should persist under its per-world key; keys=${persister.store.keys}",
            )
            val w2Blob = persister.store[worldKey("w2")]!!
            assertEquals(
                false,
                w2Blob.contains("\"p1\"") || w2Blob.contains("\"p2\""),
                "world 2's key must not carry world 1's panes (got: $w2Blob)",
            )
            assertTrue(
                w2Blob.contains("\"p3\""),
                "world 2's key must carry its own pane p3 (got: $w2Blob)",
            )
        } finally {
            root.remove()
        }
    }

    /**
     * A maximized pane must survive a world switch even on the **Auto**
     * preset — `isMaximized` is parallel state, orthogonal to the layout
     * preset, so the Auto re-tile that runs for the incoming world may
     * update the pane's restore box but must not clear the flag.
     *
     * Regression: [maybeReapplyPreset]'s write-back hard-coded
     * `isMaximized = false`, so every world switch silently un-maximized
     * every Auto tab in the world being switched TO — and persisted the
     * loss, so switching back didn't recover it (lunamux#127).
     */
    @Test
    fun switchingIntoAWorldKeepsItsMaximizedPaneUnderAutoPreset() = GlobalScope.promise {
        var push: ((TabListSnapshot) -> Unit)? = null
        val source = TabSource(
            subscribe = { p -> push = p },
            onSelect = { },
            onPaneSelect = { _, _ -> },
        )
        val persister = WorldMemPersister()
        fun worldKey(worldId: String) = "${PersistKeys.LAYOUT_STATE}.world.$worldId"

        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        try {
            val handle = mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "world-maximize-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = source,
                    worldLayoutProvider = { worldId -> persister.store[worldKey(worldId)] },
                ),
            )
            waitForWorld("tab source subscribe") { push != null }

            // --- World 1: Auto preset, p1 maximized. ---
            push!!(world1Snapshot())
            waitForWorld("world 1 panes rendered") { root.paneById("p1") != null && root.paneById("p2") != null }
            handle.applyExternalLayoutState(W1_AUTO_MAXIMIZED_LAYOUT)
            waitForWorld("p1 maximized in world 1") {
                root.paneById("p1")?.classList?.contains("dt-maximized") == true
            }

            // --- Away to world 2, then back. Switching back re-tiles t1
            // (Auto + every pane "changed" vs. w2's snapshot) — the re-tile
            // that used to eat the flag.
            push!!(world2Snapshot())
            waitForWorld("world 2 pane rendered") { root.paneById("p3") != null }
            waitForWorld("world 1 panes unmounted") { root.paneById("p1") == null }

            push!!(world1Snapshot())
            waitForWorld("world 1 panes re-rendered") { root.paneById("p1") != null && root.paneById("p2") != null }

            // Give the re-tile + any async persist a chance to land, so this
            // asserts the settled state rather than racing ahead of the bug.
            delay(100)
            assertTrue(
                root.paneById("p1")?.classList?.contains("dt-maximized") == true,
                "p1 must still be maximized after a world round-trip on the Auto preset",
            )
            assertTrue(
                handle.currentLayoutStateJson().contains("\"isMaximized\":true"),
                "the maximized flag must survive in layout state, not just the DOM " +
                    "(got: ${handle.currentLayoutStateJson()})",
            )
        } finally {
            root.remove()
        }
    }
}
