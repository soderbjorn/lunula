/*
 * SidebarWidthSeedTest.kt (jsTest)
 * --------------------------------
 * Tests for the three tiers that decide how wide the left sidebar opens:
 * the width the user last dragged to (persisted under
 * [se.soderbjorn.lunula.core.PersistKeys.SIDEBAR_WIDTH]), then the app's
 * [AppShellSpec.defaultSidebarWidthPx] seed, then the toolkit's own
 * [DEFAULT_LEFT_SIDEBAR_WIDTH_PX].
 *
 * The ordering is the point rather than the numbers. An app naming a
 * narrower sidebar is describing where a *new* browser should start — the
 * case that prompted this was Lunicle running embedded in another site's
 * page, where the toolkit's 240px pushed the board's last column off the
 * edge — and it must never reach someone who has already dragged the
 * handle somewhere else. Getting that backwards would mean the embed
 * silently re-imposing its default on every load, on a user who had said
 * otherwise.
 *
 * @see se.soderbjorn.lunula.web.shell.AppShellMount
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** In-memory [Persister] so read/write round-trips are instant. */
private class MemPersister(seed: Map<String, String> = emptyMap()) : Persister {
    val store = mutableMapOf<String, String>().apply { putAll(seed) }
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

/** Polls [condition] for ~3s, failing the test with [what] if it never holds. */
private suspend fun waitFor(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

/**
 * Mounts a minimal shell against [persister] with [seedWidth] as the app's
 * default, waits for the boot read to land, and returns the width the left
 * sidebar controller settled on.
 *
 * The root is removed again on the way out so the next mount in the file
 * starts from a clean document.
 *
 * @param persister the store the shell boots against.
 * @param seedWidth the app's [AppShellSpec.defaultSidebarWidthPx], or null.
 * @param settled   what the caller is waiting for, used in the timeout message.
 * @param condition polled until it holds — typically "the controller reached
 *   the width this test is about".
 * @return the controller's width once [condition] holds.
 */
private suspend fun mountAndSettle(
    persister: Persister,
    seedWidth: Int?,
    settled: String,
    condition: () -> Boolean,
): Int {
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "sidebar-width-test",
                persister = persister,
                paneContent = { document.createElement("div") as HTMLElement },
                defaultSidebarWidthPx = seedWidth,
            ),
        )
        waitFor(settled, condition)
        return leftSidebarController.widthPx
    } finally {
        root.remove()
    }
}

/**
 * Builds a bubbling mouse event carrying [clientX]. Hand-rolled from a JS
 * object literal for the reason `SidebarCollapsedPlaceholderTest` documents:
 * the stdlib's `MouseEventInit` builder loses the coordinate here, and the
 * drag then arrives at every listener at `clientX == 0`.
 */
private fun mouseAt(type: String, clientX: Int): MouseEvent {
    val init: dynamic = js("({})")
    init.bubbles = true
    init.cancelable = true
    init.clientX = clientX
    return MouseEvent(type, init.unsafeCast<MouseEventInit>())
}

/**
 * Drives a full press-drag-release of the sidebar's resize handle, as
 * `attachSidebarResizeHandle` listens for it: mousedown on the handle,
 * mousemove and mouseup on `document`.
 *
 * @param handle the `.dt-sidebar-resize-handle` element.
 * @param fromX  where the press lands, in client coordinates.
 * @param toX    where the release lands.
 */
private fun dragSidebarHandle(handle: HTMLElement, fromX: Int, toX: Int) {
    handle.dispatchEvent(mouseAt("mousedown", fromX))
    document.dispatchEvent(mouseAt("mousemove", toX))
    // Flush layout while the bar still has `transition: none` from the
    // mousedown — see the same note in SidebarCollapsedPlaceholderTest.
    handle.parentElement?.getBoundingClientRect()
    document.dispatchEvent(mouseAt("mouseup", toX))
}

class SidebarWidthSeedTest {

    @Test
    fun anAppsSeedOpensTheSidebarWhenNothingIsStored() = GlobalScope.promise {
        val persister = MemPersister()
        val width = mountAndSettle(
            persister = persister,
            seedWidth = 190,
            settled = "the shell to boot at the app's seed",
        ) { leftSidebarController.widthPx == 190 }
        assertEquals(190, width, "an app's seed should open the sidebar for a browser with no width of its own")
        assertNull(
            persister.store[PersistKeys.SIDEBAR_WIDTH],
            "seeding a width is not the user choosing one, so nothing should be written",
        )
    }

    @Test
    fun aStoredWidthOutranksTheAppsSeed() = GlobalScope.promise {
        val persister = MemPersister(mapOf(PersistKeys.SIDEBAR_WIDTH to encodeSidebarWidthJson(305)))
        val width = mountAndSettle(
            persister = persister,
            seedWidth = 190,
            settled = "the stored width to be adopted",
        ) { leftSidebarController.widthPx == 305 }
        assertEquals(305, width, "a width the user dragged to must survive an app that seeds a narrower default")
    }

    @Test
    fun withNeitherStoredNorSeededTheToolkitDefaultStands() = GlobalScope.promise {
        val width = mountAndSettle(
            persister = MemPersister(),
            seedWidth = null,
            settled = "the shell to boot at the toolkit default",
        ) { leftSidebarController.widthPx == DEFAULT_LEFT_SIDEBAR_WIDTH_PX }
        assertEquals(DEFAULT_LEFT_SIDEBAR_WIDTH_PX, width)
    }

    @Test
    fun aMalformedStoredWidthFallsBackToTheSeed() = GlobalScope.promise {
        val persister = MemPersister(
            mapOf(PersistKeys.SIDEBAR_WIDTH to """{"leftPx":"wide"}"""),
        )
        val width = mountAndSettle(
            persister = persister,
            seedWidth = 190,
            settled = "the shell to fall back to the app's seed",
        ) { leftSidebarController.widthPx == 190 }
        assertEquals(190, width, "an unreadable stored width is no width at all, not a width of zero")
    }

    /**
     * The other half of the contract: a width only outranks an app's seed
     * because dragging the handle is what puts it in the store. One
     * gesture, one write — the handle fires `onResize` on mouseup, not per
     * frame.
     */
    @Test
    fun draggingTheHandleStoresTheChosenWidth() = GlobalScope.promise {
        val persister = MemPersister()
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        try {
            mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "sidebar-width-drag-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    defaultSidebarWidthPx = 190,
                ),
            )
            waitFor("the sidebar to mount") { root.querySelector(".dt-sidebar-resize-handle") != null }
            val handle = root.querySelector(".dt-sidebar-resize-handle") as HTMLElement
            val bar = handle.parentElement as HTMLElement
            val edge = bar.getBoundingClientRect().right.toInt()
            dragSidebarHandle(handle, fromX = edge, toX = edge + 70)

            waitFor("the dragged width to be persisted") {
                persister.store[PersistKeys.SIDEBAR_WIDTH] != null
            }
            val stored = decodeSidebarWidthJson(persister.store[PersistKeys.SIDEBAR_WIDTH])
            assertEquals(
                leftSidebarController.widthPx,
                stored,
                "the stored width must be the width the bar was released at",
            )
        } finally {
            root.remove()
        }
    }

    @Test
    fun theWidthBlobRoundTrips() {
        assertEquals(210, decodeSidebarWidthJson(encodeSidebarWidthJson(210)))
    }

    @Test
    fun unusableStoredWidthsDecodeToNothing() {
        assertNull(decodeSidebarWidthJson(null), "a key that was never written is no width")
        assertNull(decodeSidebarWidthJson(""), "blank is not JSON")
        assertNull(decodeSidebarWidthJson("not json at all"))
        assertNull(decodeSidebarWidthJson("""{"rightPx":210}"""), "another field is not this one")
        assertNull(decodeSidebarWidthJson("""{"leftPx":0}"""), "a collapsed bar must not re-open at zero")
        assertNull(decodeSidebarWidthJson("""{"leftPx":-40}"""))
    }
}
