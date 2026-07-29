/**
 * The pane titlebar's `⋮` overflow menu — the toolkit's own, rather than
 * one every host rebuilds.
 *
 * Two of the rows a pane kebab wants are about the *window model*, which
 * the toolkit already owns: renaming the window, and moving it to another
 * tab. Before this file both lived in each consuming app, which meant each
 * app also hand-rolled the kebab button that opened them and hand-computed
 * the list of tabs a pane could move to — from its own model, in its own
 * way, drifting from the next app's copy.
 *
 * What lives here:
 *  - [PaneOverflowSpec], the host's opt-in: which built-ins to include and
 *    what else to put in the menu.
 *  - [PaneOverflowExtrasPlacement], whether the host's rows sit above or
 *    below the built-ins.
 *  - [PaneOverflowMenuIcons], the two built-in rows' glyphs.
 *  - [buildPaneOverflowItems], the pure row builder the mount calls at
 *    menu-*open* time.
 *
 * What does not live here: the button itself and the popover. The button is
 * an ordinary [se.soderbjorn.lunula.web.layout.PaneAction] appended to the
 * pane's action strip by [mountAppShell], and the popover is the existing
 * [se.soderbjorn.lunula.web.layout.openPaneMenu] primitive — so the kebab
 * gets `.dt-pane-action` chrome and its `dt-open` pressed paint, and the
 * menu is the shared `.dt-pane-menu` panel, for free.
 *
 * Nothing here mutates the tab tree. "Move to tab" raises
 * [TabSource.onPaneMove] and stops; the host moves the pane in its own
 * model and pushes a new [TabListSnapshot], exactly as it does for a close
 * or an add.
 *
 * @see AppShellSpec.paneOverflowMenu
 * @see TabSource.onPaneMove
 * @see se.soderbjorn.lunula.web.layout.openPaneMenu
 */
package se.soderbjorn.lunula.web.shell

import se.soderbjorn.lunula.web.layout.PaneMenuItem

/**
 * Where a host's [PaneOverflowSpec.extraItems] sit relative to the
 * toolkit's built-in rows.
 *
 * A host that wants a visual break between the two groups puts a
 * [se.soderbjorn.lunula.web.layout.PaneMenuItems.separator] at the
 * appropriate end of its own list — the toolkit does not insert one,
 * because whether the two groups read as one list or two is a judgement
 * about the host's rows, not about the built-ins.
 *
 * @see PaneOverflowSpec.extrasPlacement
 */
enum class PaneOverflowExtrasPlacement {
    /** Host rows first, then Rename window / Move to tab. */
    ABOVE,

    /** Rename window / Move to tab first, then the host rows. */
    BELOW,
}

/**
 * A host's opt-in to the toolkit's pane overflow (`⋮`) menu, returned per
 * pane from [AppShellSpec.paneOverflowMenu].
 *
 * Returning `null` from that callback (rather than an empty spec) is how a
 * pane says it should have no kebab at all. An empty spec — no built-ins,
 * no extras — is treated the same way, since a button that opens an empty
 * panel is worse than no button.
 *
 * Built by the host at menu-**open** time, so the rows can reflect live
 * state (a "Reset terminal" row that only exists while the pane holds a
 * PTY, a row disabled while a save is in flight).
 *
 * @property includeRename whether to offer the built-in **Rename window**
 *   row, which arms the toolkit's inline pane-title rename. Silently
 *   dropped when the host supplied no [AppShellSpec.paneRename] — the row
 *   would be a no-op, and an item that does nothing is worse than an item
 *   that is not there. Defaults `true`.
 * @property includeMoveToTab whether to offer the built-in **Move to tab ▸**
 *   submenu, listing every other tab in the live [TabListSnapshot]. Silently
 *   dropped when the host wired no [TabSource.onPaneMove], for the same
 *   reason. Defaults `true`.
 * @property extraItems the host's own rows. Ordinary
 *   [PaneMenuItem]s, so hosts keep submenus, danger styling, disabled rows
 *   and separators — the toolkit adds nothing to and takes nothing from
 *   them. Empty by default, which is the Lunicle case: the two built-ins
 *   are the whole menu.
 * @property extrasPlacement whether [extraItems] render above or below the
 *   built-ins. Defaults [PaneOverflowExtrasPlacement.BELOW] — the window
 *   model's own commands lead, app-specific ones follow, which is the order
 *   Lunamux's hand-rolled menu already had.
 * @see AppShellSpec.paneOverflowMenu
 * @see buildPaneOverflowItems
 */
data class PaneOverflowSpec(
    val includeRename: Boolean = true,
    val includeMoveToTab: Boolean = true,
    val extraItems: List<PaneMenuItem> = emptyList(),
    val extrasPlacement: PaneOverflowExtrasPlacement = PaneOverflowExtrasPlacement.BELOW,
)

/**
 * Glyphs for the two rows the toolkit owns.
 *
 * Same 24-viewBox / round-capped hand as
 * [se.soderbjorn.lunula.web.layout.PaneActions] and
 * [se.soderbjorn.lunula.web.layout.PaneMenuItems], rendered at 14×14, so a
 * host's [PaneOverflowSpec.extraItems] built with the toolkit's own
 * factories sit beside these without a visible seam. Public so a host that
 * wants the same glyph elsewhere (a command palette entry for the same
 * action) can reuse it rather than redraw it.
 */
object PaneOverflowMenuIcons {

    /** Pencil over a line — the inline-rename affordance. */
    const val RENAME: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M4 20h4l10-10a2.1 2.1 0 0 0-3-3L5 17v3z\"/>" +
            "<line x1=\"13.5\" y1=\"6.5\" x2=\"17.5\" y2=\"10.5\"/></svg>"

    /** Arrow travelling into a framed panel — "put this window in that tab". */
    const val MOVE_TO_TAB: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M15 4h4a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 19 20h-4\"/>" +
            "<line x1=\"3\" y1=\"12\" x2=\"13\" y2=\"12\"/>" +
            "<polyline points=\"9 8 13 12 9 16\"/></svg>"
}

/** The visible label of the built-in rename row. */
internal const val PANE_OVERFLOW_RENAME_LABEL: String = "Rename window"

/** The visible label of the built-in move-to-tab parent row. */
internal const val PANE_OVERFLOW_MOVE_LABEL: String = "Move to tab"

/**
 * Suffix appended to a strip-hidden tab's label in the Move to tab
 * submenu.
 *
 * Hidden tabs are deliberately offered as destinations — they keep their
 * panes (and, in Lunamux, their PTY sessions) and are perfectly valid
 * targets — but a pane that vanishes from the strip on being moved needs
 * to have said so first.
 */
internal const val PANE_OVERFLOW_HIDDEN_SUFFIX: String = " (hidden)"

/**
 * Whether [overflow] would produce at least one row — i.e. whether the pane
 * should get a `⋮` button at all.
 *
 * Separate from [buildPaneOverflowItems] because it answers a question
 * asked at a different moment and with less to hand: [mountAppShell] needs
 * it on every pane-header render, where the tab list and the row handlers
 * are irrelevant and only the *shape* of the spec matters. A button that
 * opens an empty panel is worse than no button, and this is what keeps one
 * from being drawn.
 *
 * @param overflow  the host's per-pane opt-in.
 * @param canRename whether [AppShellSpec.paneRename] is wired.
 * @param canMove   whether the move row can be honoured.
 * @return `true` when the menu would have rows.
 * @see buildPaneOverflowItems
 */
internal fun paneOverflowHasRows(
    overflow: PaneOverflowSpec,
    canRename: Boolean,
    canMove: Boolean,
): Boolean = overflow.extraItems.isNotEmpty() ||
    (overflow.includeRename && canRename) ||
    (overflow.includeMoveToTab && canMove)

/**
 * Builds the rows of one pane's overflow menu.
 *
 * Called by [mountAppShell] from the kebab's `handlerWithAnchor` — i.e. at
 * menu-**open** time, never at pane-render time — so the tab list is a
 * fresh snapshot and the host's [PaneOverflowSpec] reflects live state.
 *
 * A pure function over its arguments (no DOM, no mount state) so the row
 * rules — which built-ins appear, in what order, which tabs are offered,
 * when the parent row is disabled — are testable without a shell.
 *
 * Rules:
 *  - **Rename window** appears when [PaneOverflowSpec.includeRename] and
 *    [canRename]; omitted rather than shown disabled when the host wired no
 *    rename, because a permanently dead row teaches the user nothing.
 *  - **Move to tab ▸** appears when [PaneOverflowSpec.includeMoveToTab] and
 *    [canMove]. Its submenu is every tab in [tabs] except [ownTabId], in
 *    strip order, with strip-hidden tabs suffixed
 *    [PANE_OVERFLOW_HIDDEN_SUFFIX]. The parent row is *disabled* (not
 *    omitted) when that leaves nothing: unlike a missing callback, "there is
 *    nowhere to move to" is a state the user can change, and the row says
 *    the capability exists.
 *  - The host's [PaneOverflowSpec.extraItems] are spliced in verbatim,
 *    above or below per [PaneOverflowSpec.extrasPlacement].
 *
 * @param overflow  the host's per-pane opt-in.
 * @param tabs      the live tab list, in strip order — the toolkit's own
 *   [TabListSnapshot.tabs], which is already world-scoped because the host
 *   pushes one snapshot per world.
 * @param ownTabId  the tab whose header this kebab belongs to; excluded
 *   from the move targets.
 * @param canRename whether [AppShellSpec.paneRename] is wired.
 * @param canMove   whether [TabSource.onPaneMove] is wired.
 * @param onRename  invoked when the Rename window row is chosen.
 * @param onMove    invoked with the chosen destination tab id.
 * @return the rows in display order; **empty** when the spec asks for
 *   nothing that can be honoured, which the caller reads as "this pane gets
 *   no kebab".
 * @see PaneOverflowSpec
 * @see AppShellSpec.paneOverflowMenu
 */
internal fun buildPaneOverflowItems(
    overflow: PaneOverflowSpec,
    tabs: List<TabSnapshotEntry>,
    ownTabId: String,
    canRename: Boolean,
    canMove: Boolean,
    onRename: () -> Unit,
    onMove: (targetTabId: String) -> Unit,
): List<PaneMenuItem> {
    val builtIns = buildList {
        if (overflow.includeRename && canRename) {
            add(
                PaneMenuItem(
                    label = PANE_OVERFLOW_RENAME_LABEL,
                    iconHtml = PaneOverflowMenuIcons.RENAME,
                    handler = onRename,
                ),
            )
        }
        if (overflow.includeMoveToTab && canMove) {
            val targets = tabs
                .filter { it.id != ownTabId }
                .map { tab ->
                    PaneMenuItem(
                        label = if (tab.isHidden) tab.label + PANE_OVERFLOW_HIDDEN_SUFFIX else tab.label,
                        handler = { onMove(tab.id) },
                    )
                }
            add(
                PaneMenuItem(
                    label = PANE_OVERFLOW_MOVE_LABEL,
                    iconHtml = PaneOverflowMenuIcons.MOVE_TO_TAB,
                    isEnabled = targets.isNotEmpty(),
                    // `submenu` is only honoured when non-empty; a disabled
                    // parent row never opens a flyout anyway, so the empty
                    // case degrades to a plain greyed-out row.
                    submenu = targets,
                ),
            )
        }
    }
    return when (overflow.extrasPlacement) {
        PaneOverflowExtrasPlacement.ABOVE -> overflow.extraItems + builtIns
        PaneOverflowExtrasPlacement.BELOW -> builtIns + overflow.extraItems
    }
}
