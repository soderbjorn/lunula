/*
 * PaneOverflowMenuTest.kt (jsTest)
 *
 * Tests for the row rules of the toolkit-owned pane `⋮` overflow menu.
 *
 * The rules are small but they are the whole of what moved out of two
 * consuming apps and into the toolkit, and every one of them is a decision an
 * app used to make for itself: which built-ins appear, whether an unwired one
 * is hidden or greyed, which tabs are offered as move destinations, how a
 * hidden tab is labelled, and where the host's own rows sit. Asserting them
 * here means an app can stop asserting them at all.
 *
 * [buildPaneOverflowItems] and [paneOverflowHasRows] are pure over their
 * arguments, so none of this needs a mounted shell.
 *
 * @see PaneOverflowSpec
 * @see buildPaneOverflowItems
 */
package se.soderbjorn.lunula.web.shell

import se.soderbjorn.lunula.web.layout.PaneMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Three tabs, the middle one strip-hidden; `t1` owns the pane under test. */
private fun tabs(): List<TabSnapshotEntry> = listOf(
    TabSnapshotEntry(id = "t1", label = "Work", panes = listOf(PaneSnapshotEntry("p1"))),
    TabSnapshotEntry(id = "t2", label = "Archive", isHidden = true),
    TabSnapshotEntry(id = "t3", label = "Scratch"),
)

private fun items(
    overflow: PaneOverflowSpec = PaneOverflowSpec(),
    tabs: List<TabSnapshotEntry> = tabs(),
    ownTabId: String = "t1",
    canRename: Boolean = true,
    canMove: Boolean = true,
    onRename: () -> Unit = {},
    onMove: (String) -> Unit = {},
): List<PaneMenuItem> = buildPaneOverflowItems(
    overflow = overflow,
    tabs = tabs,
    ownTabId = ownTabId,
    canRename = canRename,
    canMove = canMove,
    onRename = onRename,
    onMove = onMove,
)

class PaneOverflowMenuTest {

    @Test
    fun bothBuiltInsAppearInOrderWhenWired() {
        assertEquals(
            listOf(PANE_OVERFLOW_RENAME_LABEL, PANE_OVERFLOW_MOVE_LABEL),
            items().map { it.label },
        )
    }

    @Test
    fun renameIsOmittedRatherThanDisabledWhenHostWiredNoRename() {
        val rows = items(canRename = false)
        assertEquals(listOf(PANE_OVERFLOW_MOVE_LABEL), rows.map { it.label })
    }

    @Test
    fun moveIsOmittedWhenHostWiredNoPaneMove() {
        val rows = items(canMove = false)
        assertEquals(listOf(PANE_OVERFLOW_RENAME_LABEL), rows.map { it.label })
    }

    @Test
    fun builtInsCanBeDeclinedIndividually() {
        assertEquals(
            listOf(PANE_OVERFLOW_MOVE_LABEL),
            items(PaneOverflowSpec(includeRename = false)).map { it.label },
        )
        assertEquals(
            listOf(PANE_OVERFLOW_RENAME_LABEL),
            items(PaneOverflowSpec(includeMoveToTab = false)).map { it.label },
        )
    }

    @Test
    fun moveTargetsAreEveryOtherTabInStripOrder() {
        val move = items().single { it.label == PANE_OVERFLOW_MOVE_LABEL }
        assertEquals(listOf("Archive (hidden)", "Scratch"), move.submenu?.map { it.label })
        assertTrue(move.isEnabled)
    }

    @Test
    fun hiddenTabsKeepTheirSuffixAndStayValidDestinations() {
        var moved: String? = null
        val move = items(onMove = { moved = it }).single { it.label == PANE_OVERFLOW_MOVE_LABEL }
        val hidden = move.submenu!!.single { it.label.endsWith(PANE_OVERFLOW_HIDDEN_SUFFIX) }
        hidden.handler()
        assertEquals("t2", moved)
    }

    @Test
    fun moveRowIsDisabledWhenThereIsNowhereToMoveTo() {
        val only = listOf(TabSnapshotEntry(id = "t1", label = "Work"))
        val move = items(tabs = only).single { it.label == PANE_OVERFLOW_MOVE_LABEL }
        assertFalse(move.isEnabled)
        assertEquals(emptyList(), move.submenu)
    }

    @Test
    fun renameRowFiresTheRenameCallback() {
        var armed = false
        items(onRename = { armed = true }).first().handler()
        assertTrue(armed)
    }

    @Test
    fun hostRowsSitBelowTheBuiltInsByDefault() {
        val rows = items(PaneOverflowSpec(extraItems = listOf(PaneMenuItem(label = "Create worktree"))))
        assertEquals(
            listOf(PANE_OVERFLOW_RENAME_LABEL, PANE_OVERFLOW_MOVE_LABEL, "Create worktree"),
            rows.map { it.label },
        )
    }

    @Test
    fun hostRowsCanSitAboveTheBuiltIns() {
        val rows = items(
            PaneOverflowSpec(
                extraItems = listOf(PaneMenuItem(label = "Create worktree")),
                extrasPlacement = PaneOverflowExtrasPlacement.ABOVE,
            ),
        )
        assertEquals(
            listOf("Create worktree", PANE_OVERFLOW_RENAME_LABEL, PANE_OVERFLOW_MOVE_LABEL),
            rows.map { it.label },
        )
    }

    @Test
    fun hostRowsSurviveWithBothBuiltInsUnwired() {
        val rows = items(
            PaneOverflowSpec(extraItems = listOf(PaneMenuItem(label = "Reset terminal"))),
            canRename = false,
            canMove = false,
        )
        assertEquals(listOf("Reset terminal"), rows.map { it.label })
    }

    @Test
    fun aSpecThatCanYieldNothingAsksForNoButton() {
        assertFalse(paneOverflowHasRows(PaneOverflowSpec(), canRename = false, canMove = false))
        assertTrue(paneOverflowHasRows(PaneOverflowSpec(), canRename = true, canMove = false))
        assertTrue(paneOverflowHasRows(PaneOverflowSpec(), canRename = false, canMove = true))
        assertTrue(
            paneOverflowHasRows(
                PaneOverflowSpec(
                    includeRename = false,
                    includeMoveToTab = false,
                    extraItems = listOf(PaneMenuItem(label = "Anything")),
                ),
                canRename = true,
                canMove = true,
            ),
        )
        assertTrue(items(canRename = false, canMove = false).isEmpty())
    }
}
