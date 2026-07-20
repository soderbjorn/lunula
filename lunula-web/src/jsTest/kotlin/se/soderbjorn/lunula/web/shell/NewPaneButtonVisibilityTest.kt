/*
 * NewPaneButtonVisibilityTest.kt (jsTest)
 *
 * Tests for [shouldShowNewPaneButton] — the pure rule deciding whether the
 * topbar's "+" (New) button is rendered at all. The DOM side is exercised at
 * the app level; this covers the combinations, and in particular the one that
 * changed: a host supplying both onPaneAdd and a paneAddMenuItems provider
 * that currently returns nothing.
 *
 * That case is why the rule is worth a test rather than being read off. It is
 * the one where two host callbacks disagree, and the previous answer — show
 * the button, because onPaneAdd is wired — produced a "+" with an empty
 * dropdown and a dead click for anyone whose permissions emptied the list.
 *
 * @see shouldShowNewPaneButton
 * @see TabSource.paneAddMenuItems
 */
package se.soderbjorn.lunula.web.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewPaneButtonVisibilityTest {

    /** Nothing wired anywhere: no button. */
    @Test
    fun hiddenWhenNothingCanBeAdded() {
        assertFalse(show())
    }

    /** A tab-managing host: "New tab" alone is enough to earn the button. */
    @Test
    fun shownForANewTabActionAlone() {
        assertTrue(show(hasNewTabAction = true))
    }

    /** A world-managing host, likewise. */
    @Test
    fun shownForANewWorldActionAlone() {
        assertTrue(show(hasNewWorldAction = true))
    }

    /**
     * The historical plain-button host: onPaneAdd, no menu described.
     *
     * It never offered a dropdown, so there is no list to be empty and the
     * callback is the whole of what the button does. This must keep working —
     * it is every host that predates paneAddMenuItems.
     */
    @Test
    fun shownForPaneAddWithNoMenuDescribed() {
        assertTrue(show(hasPaneAddAction = true))
    }

    /** A split-button host with something to offer. */
    @Test
    fun shownWhenTheMenuHasItems() {
        assertTrue(show(hasPaneAddAction = true, describesPaneAddMenu = true, paneAddItemCount = 2))
    }

    /**
     * The one that changed: the menu is described and comes back empty.
     *
     * onPaneAdd is the default of *that* menu, not a separate offer, so an
     * empty list is the host saying there is nothing to add. Showing the
     * button here is a "+" that opens on nothing and does nothing when
     * clicked.
     */
    @Test
    fun hiddenWhenTheDescribedMenuIsEmpty() {
        assertFalse(show(hasPaneAddAction = true, describesPaneAddMenu = true, paneAddItemCount = 0))
    }

    /**
     * An empty pane menu does not veto the other two rows.
     *
     * "New tab" and "New workspace" are the toolkit's own entries and are not
     * what the host's provider is talking about, so a host with both still
     * gets a button — with a menu that is not empty.
     */
    @Test
    fun anEmptyPaneMenuDoesNotHideANewTabRow() {
        assertTrue(
            show(
                hasNewTabAction = true,
                hasPaneAddAction = true,
                describesPaneAddMenu = true,
                paneAddItemCount = 0,
            ),
        )
    }

    /**
     * Items but no onPaneAdd: still a button, because the rows are clickable.
     *
     * The icon click has nothing to route to and does nothing — which is the
     * host's own arrangement, not a state this rule created.
     */
    @Test
    fun shownForItemsWithoutADefaultAction() {
        assertTrue(show(describesPaneAddMenu = true, paneAddItemCount = 1))
    }

    private fun show(
        hasNewTabAction: Boolean = false,
        hasPaneAddAction: Boolean = false,
        hasNewWorldAction: Boolean = false,
        describesPaneAddMenu: Boolean = false,
        paneAddItemCount: Int = 0,
    ): Boolean = shouldShowNewPaneButton(
        hasNewTabAction = hasNewTabAction,
        hasPaneAddAction = hasPaneAddAction,
        hasNewWorldAction = hasNewWorldAction,
        describesPaneAddMenu = describesPaneAddMenu,
        paneAddItemCount = paneAddItemCount,
    )
}
