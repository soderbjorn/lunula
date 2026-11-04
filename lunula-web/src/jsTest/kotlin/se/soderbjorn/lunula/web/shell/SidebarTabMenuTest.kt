/*
 * SidebarTabMenuTest.kt (jsTest)
 *
 * Tests for the per-tab actions menu on every surface that lists tabs:
 *
 *  - the sidebar's tab section headers carry the strip's "Tab actions" ⋮ and
 *    answer a right-click with the same menu ([attachSidebarTabMenu]);
 *  - the strip's tabs answer a right-click too ([wireContextMenu]);
 *  - Rename from the sidebar edits the name in place, in an input that
 *    stands in for the header rather than nesting inside its <button>;
 *  - an app-defined (fixed) tab set offers no sidebar menu;
 *  - a sidebar tab section drags by its header to reorder the tabs, and
 *    takes drops from the strip too ([wireSidebarTabReorder]);
 *  - the next / previous tab hotkeys keep working once the strip is turned
 *    off, walking the tabs the sidebar lists ([installTabNavigationHotkeys]);
 *  - with no tab strip drawn, tabs hidden from the sidebar stay reachable —
 *    the active one is still drawn, the rest are listed under "Unlisted
 *    tabs" — while with a strip the sidebar keeps leaving them out, as it
 *    always has.
 *
 * The mounted tests drive the real [mountAppShell] in source mode with an
 * in-memory [Persister] and a hand-pushed [TabSource] snapshot, the harness
 * NavigationModeTest uses. They run in the karma browser environment against
 * the real DOM; `lunula.css` is not loaded, so every assertion is structural.
 *
 * @see attachSidebarTabMenu
 * @see buildSidebarUnlistedTabRow
 * @see appendTabActionRows
 * @see wireSidebarTabReorder
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
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
private class MenuMemoryPersister(seed: Map<String, String> = emptyMap()) : Persister {
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
 * Three tabs: `t1` (active), `t2`, and `t3`, which [hiddenFromSidebar] can
 * mark hidden from the sidebar.
 */
private fun threeTabSnapshot(hiddenFromSidebar: Set<String> = emptySet()) = TabListSnapshot(
    tabs = listOf("t1" to "One", "t2" to "Two", "t3" to "Three").map { (id, label) ->
        TabSnapshotEntry(
            id = id,
            label = label,
            panes = listOf(PaneSnapshotEntry("$id-p")),
            activePaneId = "$id-p",
            isHiddenFromSidebar = id in hiddenFromSidebar,
        )
    },
    activeTabId = "t1",
)

/** What the host's tab callbacks were asked to do, in order, and the mounted shell's handle. */
private class Calls {
    val log = mutableListOf<String>()
    var handle: AppShellHandle? = null
}

/** A persister seeded with the user's navigation pick. */
private fun navPersister(mode: NavigationMode): Persister = MenuMemoryPersister(
    mapOf(PersistKeys.APPEARANCE_SHAPE to AppearanceShape(navigationMode = mode).toJson()),
)

/**
 * Mounts a fresh shell on [snapshot] and runs [body] once the sidebar is
 * drawn. The root and any open menu are removed afterwards so a failed test
 * never leaks into the next one's queries.
 *
 * @param mode     the user's navigation mode.
 * @param snapshot the tabs the host pushes.
 * @param fixed    whether the host declares an app-defined tab set.
 * @param body     the test, given the root and the callback log.
 */
private suspend fun withShell(
    mode: NavigationMode,
    snapshot: TabListSnapshot = threeTabSnapshot(),
    fixed: Boolean = false,
    body: suspend (root: HTMLElement, calls: Calls) -> Unit,
) {
    val calls = Calls()
    var push: ((TabListSnapshot) -> Unit)? = null
    var current = snapshot
    val subscribe: ((TabListSnapshot) -> Unit) -> Unit = { p -> push = p }
    val onSelect: (String) -> Unit = { id ->
        calls.log += "select:$id"
        current = current.copy(activeTabId = id)
        push?.invoke(current)
    }
    val source = if (fixed) {
        TabSource.fixed(subscribe = subscribe, onSelect = onSelect)
    } else {
        TabSource(
            subscribe = subscribe,
            onSelect = onSelect,
            onClose = { id -> calls.log += "close:$id" },
            onRename = { id, label -> calls.log += "rename:$id:$label" },
            onReorder = { sourceId, targetId, before -> calls.log += "reorder:$sourceId:$targetId:$before" },
            onSetHidden = { id, hidden -> calls.log += "hideStrip:$id:$hidden" },
            onSetHiddenFromSidebar = { id, hidden -> calls.log += "hideSidebar:$id:$hidden" },
            onPaneSelect = { _, _ -> },
        )
    }
    val root = document.createElement("div") as HTMLElement
    document.body!!.appendChild(root)
    try {
        val handle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "sidebar-tab-menu-test",
                persister = navPersister(mode),
                paneContent = { document.createElement("div") as HTMLElement },
                paneLabel = { _, paneId -> "Window $paneId" },
                tabSource = source,
            ),
        )
        calls.handle = handle
        waitFor("tab source subscribe") { push != null }
        push!!(current)
        waitFor("sidebar drawn") { root.querySelector(".dt-sidebar-section") != null }
        body(root, calls)
        handle.dispose()
    } finally {
        closeTabBarMenus()
        root.remove()
    }
}

/** The sidebar section listing tab [title] (matched on its header title). */
private fun HTMLElement.sidebarSection(title: String): HTMLElement? {
    val sections = querySelectorAll(".dt-sidebar-section")
    for (i in 0 until sections.length) {
        val s = sections.item(i) as HTMLElement
        if (s.querySelector(".dt-sidebar-section-title")?.textContent == title) return s
    }
    return null
}

/** The header of [sidebarSection]. */
private fun HTMLElement.header(): HTMLElement =
    querySelector(".dt-sidebar-section-header") as HTMLElement

/** The open menu panel's row labels, or `null` when no menu is open. */
private fun openMenuLabels(): List<String>? {
    val panel = document.querySelector(".${TabMenuClassNames.PANEL}") as? HTMLElement ?: return null
    val labels = panel.querySelectorAll(".dt-hover-menu-label")
    return (0 until labels.length).map { (labels.item(it) as HTMLElement).textContent ?: "" }
}

/** Clicks the open menu's row labelled [label]. */
private fun clickMenuRow(label: String) {
    val rows = document.querySelectorAll(".${TabMenuClassNames.PANEL} .dt-hover-menu-item")
    for (i in 0 until rows.length) {
        val row = rows.item(i) as HTMLElement
        if (row.querySelector(".dt-hover-menu-label")?.textContent == label) {
            row.click()
            return
        }
    }
    fail("no menu row \"$label\" in ${openMenuLabels()}")
}

/**
 * Dispatches a right-click (`contextmenu`) on [target].
 *
 * @return `true` when a handler suppressed the browser's own menu.
 */
private fun rightClick(target: HTMLElement): Boolean {
    val init = MouseEventInit(bubbles = true, cancelable = true, clientX = 20, clientY = 20)
    val ev = MouseEvent("contextmenu", init)
    target.dispatchEvent(ev)
    return ev.defaultPrevented
}

class SidebarTabMenuTest {

    /** Never leave a menu (and its document-level Escape listener) behind. */
    @AfterTest
    fun reset() {
        closeTabBarMenus()
        (document.documentElement as HTMLElement).removeAttribute("data-dt-nav")
    }

    // ── The sidebar's tab menu ──────────────────────────────────────

    /** Every tab header carries a "Tab actions" ⋮ when the host allows edits. */
    @Test
    fun everySidebarTabHeaderHasATabActionsButton() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, _ ->
            for (title in listOf("One", "Two", "Three")) {
                val button = root.sidebarSection(title)?.header()
                    ?.querySelector(".${SidebarTabMenuClassNames.MENU} .dt-tab-menu-button") as? HTMLElement
                assertNotNull(button, "⋮ on $title")
                assertEquals("Tab actions", button.getAttribute("aria-label"))
            }
        }
    }

    /**
     * The ⋮ opens the strip's rows, and pressing it does not also collapse
     * the section it sits in.
     */
    @Test
    fun theSidebarButtonOpensTheTabMenu() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, calls ->
            val section = root.sidebarSection("Two")!!
            (section.querySelector(".dt-tab-menu-button") as HTMLElement).click()
            val labels = openMenuLabels()
            assertNotNull(labels, "menu open")
            assertTrue("Rename" in labels && "Close" in labels, "act-on-tab rows: $labels")
            assertTrue("Hide in side bar" in labels, "visibility rows: $labels")
            delay(50)
            assertTrue(
                root.sidebarSection("Two")?.classList?.contains("dt-open") == true,
                "pressing the ⋮ did not also collapse the section",
            )

            clickMenuRow("Hide in side bar")
            assertEquals(listOf("hideSidebar:t2:true"), calls.log)
            assertNull(openMenuLabels(), "the menu closes after acting")
        }
    }

    /** A right-click on a header opens the same menu and suppresses the browser's. */
    @Test
    fun rightClickingASidebarHeaderOpensTheTabMenu() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, calls ->
            val header = root.sidebarSection("Three")!!.header()
            assertTrue(rightClick(header), "the browser's own context menu is suppressed")
            val labels = openMenuLabels()
            assertNotNull(labels)
            assertTrue("Close" in labels)

            // Close confirms first, then reports the right tab.
            clickMenuRow("Close")
            val confirm = waitForConfirmButton()
            confirm.click()
            waitFor("close reported") { "close:t3" in calls.log }
        }
    }

    /** Rename from the sidebar edits the name in place and commits on Enter. */
    @Test
    fun renameFromTheSidebarEditsTheTitleInPlace() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, calls ->
            val section = root.sidebarSection("Two")!!
            rightClick(section.header())
            clickMenuRow("Rename")
            val input = section.querySelector(".${SidebarTabMenuClassNames.HEADER_INPUT}") as? HTMLInputElement
            assertNotNull(input, "the header became an input")
            assertNull(
                input.closest("button"),
                "the input is not inside the header <button>, whose activation would eat a typed space",
            )
            assertEquals("Two", input.value)
            input.value = "Renamed"
            input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true, cancelable = true)))
            assertEquals(listOf("rename:t2:Renamed"), calls.log)
        }
    }

    /** An app-defined tab set has nothing to edit, so its headers get no menu. */
    @Test
    fun aFixedTabSetGetsNoSidebarMenu() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar, fixed = true) { root, _ ->
            assertNull(root.querySelector(".${SidebarTabMenuClassNames.MENU}"))
            assertFalse(rightClick(root.sidebarSection("One")!!.header()), "right-click left to the browser")
            assertNull(openMenuLabels())
        }
    }

    // ── The strip's tabs answer a right-click too ───────────────────

    /** Right-clicking a strip tab opens its menu, inactive tabs included. */
    @Test
    fun rightClickingAStripTabOpensItsMenu() = GlobalScope.promise {
        withShell(NavigationMode.Both) { root, calls ->
            val tab = root.querySelector(".${TabBarClassNames.TAB}[data-tab-id=\"t2\"]") as HTMLElement
            assertTrue(rightClick(tab))
            assertNotNull(openMenuLabels())
            clickMenuRow("Hide in tab bar")
            assertEquals(listOf("hideStrip:t2:true"), calls.log, "acts on the tab clicked, not the active one")
        }
    }

    // ── Reordering from the sidebar ─────────────────────────────────

    /**
     * Dragging a header onto the bottom half of another tab's section moves
     * the tab after it; the top half, before it.
     */
    @Test
    fun draggingASidebarHeaderReordersTheTabs() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, calls ->
            val one = root.sidebarSection("One")!!
            val three = root.sidebarSection("Three")!!
            assertEquals("true", one.header().getAttribute("draggable"))

            val dt = newDataTransfer()
            drag(one.header(), "dragstart", dt)
            assertTrue(one.classList.contains(SidebarTabReorderClassNames.DRAGGING))
            assertFalse(
                drag(one, "dragover", dt, yIn(one, top = true)).defaultPrevented as Boolean,
                "a section is no drop target for itself",
            )

            val over = drag(three, "dragover", dt, yIn(three, top = false))
            assertTrue(over.defaultPrevented as Boolean, "another tab's section takes the drop")
            assertTrue(three.classList.contains(SidebarTabReorderClassNames.DROP_AFTER))
            drag(three, "drop", dt, yIn(three, top = false))
            drag(one.header(), "dragend", dt)
            assertFalse(three.classList.contains(SidebarTabReorderClassNames.DROP_AFTER), "indicator cleared")
            assertFalse(one.classList.contains(SidebarTabReorderClassNames.DRAGGING))

            val dt2 = newDataTransfer()
            drag(one.header(), "dragstart", dt2)
            drag(three, "drop", dt2, yIn(three, top = true))
            assertEquals(listOf("reorder:t1:t3:false", "reorder:t1:t3:true"), calls.log)
        }
    }

    /** A drop anywhere on an expanded section — a pane row included — counts. */
    @Test
    fun aDropOnAPaneRowReordersTheTab() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar) { root, calls ->
            val dt = newDataTransfer()
            drag(root.sidebarSection("Three")!!.header(), "dragstart", dt)
            val row = root.sidebarSection("One")!!.querySelector(".dt-sidebar-row") as HTMLElement
            drag(row, "drop", dt, yIn(root.sidebarSection("One")!!, top = true))
            assertEquals(listOf("reorder:t3:t1:true"), calls.log)
        }
    }

    /** A tab dragged from the strip lands in the sidebar: one payload, one callback. */
    @Test
    fun aStripTabCanBeDroppedOnASidebarSection() = GlobalScope.promise {
        withShell(NavigationMode.Both) { root, calls ->
            val stripTab = root.querySelector(".${TabBarClassNames.TAB}[data-tab-id=\"t3\"]") as HTMLElement
            val dt = newDataTransfer()
            drag(stripTab, "dragstart", dt)
            val one = root.sidebarSection("One")!!
            drag(one, "drop", dt, yIn(one, top = true))
            assertEquals(listOf("reorder:t3:t1:true"), calls.log)
        }
    }

    /** An app-defined tab set can't be reordered from the sidebar either. */
    @Test
    fun aFixedTabSetCannotBeReorderedFromTheSidebar() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar, fixed = true) { root, _ ->
            val one = root.sidebarSection("One")!!
            assertNull(one.header().getAttribute("draggable"))
            val dt = newDataTransfer()
            dt.setData(DT_TAB_DRAG_MIME, "t3")
            assertFalse(drag(one, "dragover", dt, yIn(one, top = true)).defaultPrevented as Boolean)
        }
    }

    // ── Tab hotkeys without a strip ─────────────────────────────────

    /**
     * Turning the strip off with the middle tab active must not freeze the
     * hotkeys on that moment: Next walks on through the tabs and wraps, and
     * Previous walks back. (Bound only when a strip was drawn, they kept the
     * middle tab as "current" forever, so it could never be reached again.)
     */
    @Test
    fun tabHotkeysKeepWorkingAfterTheStripIsTurnedOff() = GlobalScope.promise {
        withShell(NavigationMode.Both) { root, calls ->
            (root.querySelector(".${TabBarClassNames.TAB}[data-tab-id=\"t2\"]") as HTMLElement).click()
            waitFor("t2 active") { root.querySelector(".dt-sidebar-section.active-tab")?.let { it === root.sidebarSection("Two") } == true }
            calls.handle!!.setNavigationMode(NavigationMode.Sidebar)
            waitFor("strip gone") { root.querySelector(".${TabBarClassNames.BAR}") == null }
            calls.log.clear()

            pressTabHotkey(forward = true)
            waitFor("next → t3") { calls.log == listOf("select:t3") }
            pressTabHotkey(forward = true)
            waitFor("next wraps → t1") { calls.log == listOf("select:t3", "select:t1") }
            pressTabHotkey(forward = false)
            waitFor("previous → t3") { calls.log == listOf("select:t3", "select:t1", "select:t3") }
            pressTabHotkey(forward = false)
            waitFor("previous → t2, the middle tab") { calls.log.lastOrNull() == "select:t2" }
        }
    }

    /** Without a strip the hotkeys walk the tabs the sidebar lists, skipping those hidden from it. */
    @Test
    fun withoutAStripTabHotkeysSkipTabsHiddenFromTheSidebar() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar, threeTabSnapshot(hiddenFromSidebar = setOf("t2"))) { _, calls ->
            pressTabHotkey(forward = true)
            waitFor("next skips t2") { calls.log == listOf("select:t3") }
        }
    }

    // ── Hidden tabs stay reachable without a strip ──────────────────

    /**
     * Sidebar only: a tab hidden from the sidebar is listed under "Unlisted
     * tabs", and its row activates it.
     */
    @Test
    fun withoutAStripHiddenTabsAreListedAsUnlisted() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar, threeTabSnapshot(hiddenFromSidebar = setOf("t3"))) { root, calls ->
            assertNull(root.sidebarSection("Three"), "not drawn as a tab section")
            val unlisted = root.querySelector(".${SidebarTabMenuClassNames.UNLISTED_SECTION}") as? HTMLElement
            assertNotNull(unlisted, "unlisted section")
            val row = unlisted.querySelector(".dt-sidebar-row") as HTMLElement
            assertEquals("Three", row.querySelector(".dt-sidebar-row-label")?.textContent)

            row.click()
            assertEquals(listOf("select:t3"), calls.log)
        }
    }

    /**
     * Sidebar only: the active tab is drawn even when hidden from the sidebar
     * (marked unlisted), so its "Show in side bar" is reachable.
     */
    @Test
    fun withoutAStripTheActiveHiddenTabIsStillDrawn() = GlobalScope.promise {
        withShell(NavigationMode.Sidebar, threeTabSnapshot(hiddenFromSidebar = setOf("t1"))) { root, calls ->
            val section = root.sidebarSection("One")
            assertNotNull(section, "the active tab is drawn")
            assertTrue(section.classList.contains(SidebarTabMenuClassNames.SECTION_UNLISTED))
            assertNull(
                root.querySelector(".${SidebarTabMenuClassNames.UNLISTED_SECTION}"),
                "and not listed a second time",
            )

            rightClick(section.header())
            clickMenuRow("Show in side bar")
            assertEquals(listOf("hideSidebar:t1:false"), calls.log)
        }
    }

    /**
     * With a strip, nothing changes: the strip's overflow is the way back, so
     * the sidebar keeps leaving hidden tabs out — the active one included —
     * and draws no unlisted section.
     */
    @Test
    fun withAStripTheSidebarStillLeavesHiddenTabsOut() = GlobalScope.promise {
        withShell(NavigationMode.Both, threeTabSnapshot(hiddenFromSidebar = setOf("t1", "t3"))) { root, _ ->
            assertNull(root.sidebarSection("One"))
            assertNull(root.sidebarSection("Three"))
            assertNull(root.querySelector(".${SidebarTabMenuClassNames.UNLISTED_SECTION}"))
        }
    }
}

/**
 * Dispatches a synthetic HTML5 drag event of [type] on [target], carrying
 * [dataTransfer] with the pointer at [clientY].
 *
 * @return the event, so callers can read `defaultPrevented`.
 */
private fun drag(target: HTMLElement, type: String, dataTransfer: dynamic, clientY: Double = 0.0): dynamic {
    val init: dynamic = js("({})")
    init.bubbles = true
    init.cancelable = true
    init.dataTransfer = dataTransfer
    init.clientX = 10
    init.clientY = clientY
    val ctor: dynamic = js("DragEvent")
    val ev: dynamic = js("new ctor(type, init)")
    target.dispatchEvent(ev.unsafeCast<org.w3c.dom.events.Event>())
    return ev
}

/** A fresh, writable `DataTransfer`, as a real drag carries. */
private fun newDataTransfer(): dynamic = js("new DataTransfer()")

/** A clientY in the top (or bottom) quarter of [el]. */
private fun yIn(el: HTMLElement, top: Boolean): Double {
    val rect = el.getBoundingClientRect()
    return if (top) rect.top + rect.height / 4 else rect.bottom - rect.height / 4
}

/** Presses the default Next (or Previous) tab chord, Ctrl+Alt+Shift+→ / ←. */
private fun pressTabHotkey(forward: Boolean) {
    val init = KeyboardEventInit(
        key = if (forward) "ArrowRight" else "ArrowLeft",
        ctrlKey = true,
        altKey = true,
        shiftKey = true,
        bubbles = true,
        cancelable = true,
    )
    kotlinx.browser.window.dispatchEvent(KeyboardEvent("keydown", init))
}

/** Waits for the tab-close confirm dialog and returns its confirm button. */
private suspend fun waitForConfirmButton(): HTMLElement {
    waitFor("confirm dialog") { document.querySelector(".dt-modal-btn-confirm") != null }
    return document.querySelector(".dt-modal-btn-confirm") as HTMLElement
}
