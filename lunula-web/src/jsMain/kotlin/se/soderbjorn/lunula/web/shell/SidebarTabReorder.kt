/*
 * SidebarTabReorder.kt (jsMain)
 * -----------------------------
 * Drag-to-reorder for the sidebar's tab sections: a tab section's header is
 * the drag handle, and every reorderable tab section is a drop target that
 * puts the dragged tab before or after itself.
 *
 * The tab strip has always been the only place to reorder tabs. Once the
 * user's navigation mode can hide the strip
 * ([se.soderbjorn.lunula.core.NavigationMode.Sidebar]), the sidebar has to
 * offer it too, or the order is frozen. Both surfaces drag the same payload
 * ([DT_TAB_DRAG_MIME], the tab id) and fire the same
 * [TabBarCallbacks.onReorder], so a tab dragged from the strip can land in the
 * sidebar and the other way round.
 *
 * Contains [SidebarTabReorderClassNames] and [wireSidebarTabReorder].
 * Consumed by `ShellState.buildLeftSidebarContent` in AppShellMount.kt.
 *
 * @see attachSidebarTabMenu
 */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement

/**
 * DOM class names for the sidebar's tab-reorder feedback. `internal`:
 * consumers style these through `lunula.css`.
 */
internal object SidebarTabReorderClassNames {
    /** On the tab section being dragged; CSS dims it. */
    const val DRAGGING = "dt-sidebar-section-dragging"

    /** On the section under the pointer when the drop would land above it. */
    const val DROP_BEFORE = "dt-sidebar-section-drop-before"

    /** On the section under the pointer when the drop would land below it. */
    const val DROP_AFTER = "dt-sidebar-section-drop-after"
}

/**
 * Make a sidebar tab section draggable by its header, and a drop target for
 * other tabs.
 *
 * Called by `ShellState.buildLeftSidebarContent` for every tab section it
 * draws, with the callbacks the tab strip is built from. Does nothing when
 * the tab can't be reordered: no [TabBarCallbacks.onReorder] (an app-defined
 * tab set, or a source that doesn't reorder) or a tab that isn't
 * [TabSpec.isDraggable]. The same gate the strip applies in `buildTabElement`.
 *
 * The whole section, not just its header, takes the drop, so an expanded
 * section is a generous target. The pointer's half of the section decides
 * the side: the top half drops before this tab, the bottom half after it.
 * Pane rows inside the section ignore tab drags (they filter on their own
 * MIME), so the event reaches the section. A press on the header that never
 * moves still clicks it, so collapse and expand are unaffected.
 *
 * @param section the `.dt-sidebar-section` built by [renderSidebarSection].
 * @param tab     the tab the section lists.
 * @param cb      supplies [TabBarCallbacks.onReorder], fired with the dragged
 *   tab's id, this tab's id and whether the drop landed before this tab.
 * @see isTabDrag
 * @see isCursorAboveMidpointY
 */
internal fun wireSidebarTabReorder(section: HTMLElement, tab: TabSpec, cb: TabBarCallbacks) {
    val onReorder = cb.onReorder ?: return
    if (!tab.isDraggable) return
    val header = section.querySelector(".dt-sidebar-section-header") as? HTMLElement ?: return

    header.setAttribute("draggable", "true")
    header.addEventListener("dragstart", { ev ->
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData(DT_TAB_DRAG_MIME, tab.id)
        // text/plain fallback, as the strip sets: receivers read the custom
        // MIME first.
        dt.setData("text/plain", tab.id)
        section.classList.add(SidebarTabReorderClassNames.DRAGGING)
    })
    header.addEventListener("dragend", {
        section.classList.remove(SidebarTabReorderClassNames.DRAGGING)
        clearSidebarTabDropIndicators(section)
    })

    section.addEventListener("dragover", { ev ->
        if (!isTabDrag(ev.asDynamic())) return@addEventListener
        // Dropping a section onto itself is a no-op; don't advertise it.
        if (section.classList.contains(SidebarTabReorderClassNames.DRAGGING)) return@addEventListener
        ev.preventDefault()
        ev.asDynamic().dataTransfer.dropEffect = "move"
        val before = isCursorAboveMidpointY(section, ev.asDynamic())
        section.classList.toggle(SidebarTabReorderClassNames.DROP_BEFORE, before)
        section.classList.toggle(SidebarTabReorderClassNames.DROP_AFTER, !before)
    })
    section.addEventListener("dragleave", { ev ->
        // Crossing into a child (a pane row, the header) fires dragleave on
        // the section too; only clear once the pointer is really outside.
        val related = ev.asDynamic().relatedTarget as? HTMLElement
        if (related != null && section.asDynamic().contains(related) as Boolean) return@addEventListener
        section.classList.remove(SidebarTabReorderClassNames.DROP_BEFORE, SidebarTabReorderClassNames.DROP_AFTER)
    })
    section.addEventListener("drop", { ev ->
        if (!isTabDrag(ev.asDynamic())) return@addEventListener
        ev.preventDefault()
        ev.stopPropagation()
        section.classList.remove(SidebarTabReorderClassNames.DROP_BEFORE, SidebarTabReorderClassNames.DROP_AFTER)
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        val sourceId = (dt.getData(DT_TAB_DRAG_MIME) as? String)?.ifEmpty { null }
            ?: (dt.getData("text/plain") as? String)
        if (sourceId.isNullOrEmpty() || sourceId == tab.id) return@addEventListener
        onReorder(sourceId, tab.id, isCursorAboveMidpointY(section, ev.asDynamic()))
    })
}

/**
 * Remove the drop indicators from every section beside [section]. Called on
 * `dragend`, because browsers don't reliably fire `dragleave` on the last
 * section the pointer crossed.
 *
 * @param section the dragged section; its siblings are swept.
 */
private fun clearSidebarTabDropIndicators(section: HTMLElement) {
    val parent = section.parentElement ?: return
    val sections = parent.querySelectorAll(".dt-sidebar-section")
    for (i in 0 until sections.length) {
        (sections.item(i) as HTMLElement).classList.remove(
            SidebarTabReorderClassNames.DROP_BEFORE,
            SidebarTabReorderClassNames.DROP_AFTER,
        )
    }
}
