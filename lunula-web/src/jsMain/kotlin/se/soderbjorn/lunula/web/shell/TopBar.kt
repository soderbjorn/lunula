/**
 * Drop-in top-bar component for the toolkit's app shell.
 *
 * Renders a horizontal strip with a logo / title slot on the left, a
 * tab strip in the middle, and an actions slot on the right. Apps wire
 * up the slots; the toolkit only owns the layout and theming.
 *
 * Visual styles ride on the `.dt-topbar*` classes shipped in
 * `lunula.css`; consumers must call `injectLunulaStyles()`
 * once at boot.
 *
 * @see TopBarSpec
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * One tab in the top bar.
 *
 * @property id         stable id used by [TopBarSpec.activeTabId] and the
 *   [TopBarSpec.onTabSelected] callback.
 * @property label      visible tab label.
 */
data class TopBarTab(
    val id: String,
    val label: String,
)

/**
 * Top-bar configuration passed to [renderTopBar].
 *
 * Two ways to populate the tab strip in the middle:
 *
 *  - **Simple path:** pass [tabs] / [activeTabId] / [onTabSelected]. The
 *    bar renders click-only tab buttons. Suitable for fixed-tab apps that
 *    don't need close, add, drag, or rename.
 *  - **Full path:** pass [tabBar] (a [TabBarSpec]) and the bar delegates
 *    rendering to [renderTabBar], wiring up per-tab close, optional `+`
 *    add, drag-to-reorder, and inline rename. When [tabBar] is non-null,
 *    [tabs] / [activeTabId] / [onTabSelected] are ignored.
 *
 * @property leadingContent     element shown at the far left (logo, app
 *   title). May be null for no leading content.
 * @property tabs               legacy click-only tab list. Pass an empty
 *   list for no tab strip. Ignored when [tabBar] is non-null.
 * @property activeTabId        id of the currently active tab in the
 *   legacy [tabs] list. Ignored when [tabBar] is non-null.
 * @property onTabSelected      callback when a legacy tab is clicked.
 *   Ignored when [tabBar] is non-null.
 * @property tabBar             full-featured [TabBarSpec], or null to
 *   fall back to the legacy [tabs] list.
 * @property centerContent      element placed in the middle slot, centered
 *   between the leading and trailing clusters. Only rendered when the middle
 *   slot is not already claimed by a tab strip ([tabBar] non-null or a
 *   non-empty [tabs] list) — tabs win the slot. For apps whose middle is
 *   otherwise empty (no tab strip) and want a centered brand / status line
 *   there instead of on an edge. May be null.
 * @property trailingContent    element shown at the far right (action
 *   buttons, menus). May be null.
 *
 * @see TabBarSpec
 */
class TopBarSpec(
    val leadingContent: HTMLElement? = null,
    val tabs: List<TopBarTab> = emptyList(),
    val activeTabId: String? = null,
    val onTabSelected: (String) -> Unit = {},
    val tabBar: TabBarSpec? = null,
    val centerContent: HTMLElement? = null,
    val trailingContent: HTMLElement? = null,
    /**
     * When `true`, attach a vertical resize handle on the bar's bottom
     * edge so the user can drag to grow/shrink the topbar height. With
     * [minHeightPx] = 0 the bar collapses on drag (effectively hiding
     * itself); the host can read that in [onResize] to flip its
     * persisted visibility state.
     */
    val isResizable: Boolean = false,
    /** Minimum height the resize gesture allows. Defaults to 0 (drag-to-hide). */
    val minHeightPx: Int = 0,
    /** Maximum height the resize gesture allows. Defaults to 240. */
    val maxHeightPx: Int = 240,
    /**
     * When non-null, the bar's natural height. Drag releases snap to 0,
     * to this default, or — if [allowGrowBeyondDefault] — to the user's
     * chosen larger height. Half of this value is the snap threshold.
     */
    val defaultHeightPx: Int? = null,
    /**
     * When `false` (the default), drag is capped at [defaultHeightPx];
     * the bar can shrink to 0 but never grow taller than its default.
     */
    val allowGrowBeyondDefault: Boolean = false,
    /** Fired once on mouseup with the user's chosen height. */
    val onResize: ((heightPx: Int) -> Unit)? = null,
)

/**
 * Builds the top-bar element for the given [spec]. The returned element
 * is a sibling-style flex row that the host app inserts wherever it wants
 * the bar to appear.
 *
 * @param spec the top-bar specification
 * @return a fresh top-bar [HTMLElement]
 */
fun renderTopBar(spec: TopBarSpec): HTMLElement {
    val bar = document.createElement("div") as HTMLElement
    bar.className = "dt-topbar"

    val leading = document.createElement("div") as HTMLElement
    leading.className = "dt-topbar-leading"
    spec.leadingContent?.let { leading.appendChild(it) }
    bar.appendChild(leading)

    val tabStrip = document.createElement("div") as HTMLElement
    tabStrip.className = "dt-topbar-tabstrip"
    if (spec.tabBar != null) {
        // Full-featured TabBar: hand off rendering to renderTabBar so we
        // get close / add / drag / rename for free. The .dt-topbar-tabstrip
        // wrapper still supplies flex-grow + min-width:0 so the bar fills
        // the middle slot and overflow-scrolls instead of pushing trailing
        // content off-screen.
        tabStrip.appendChild(renderTabBar(spec.tabBar))
    } else if (spec.tabs.isEmpty() && spec.centerContent != null) {
        // No tab strip: the middle slot is free, so an app-supplied center
        // element (a brand / status line) takes it, horizontally centered
        // between the leading and trailing clusters. The `--center` modifier
        // flips the strip's justify/align to center this single item; tabs
        // (either path above) always win the slot when present.
        tabStrip.classList.add("dt-topbar-tabstrip--center")
        tabStrip.appendChild(spec.centerContent)
    } else {
        for (tab in spec.tabs) {
            val tabEl = document.createElement("button") as HTMLButtonElement
            tabEl.type = "button"
            tabEl.className = "dt-topbar-tab" +
                if (tab.id == spec.activeTabId) " dt-selected" else ""
            tabEl.setAttribute("data-tab-id", tab.id)
            tabEl.textContent = tab.label
            tabEl.addEventListener("click", { spec.onTabSelected(tab.id) })
            tabStrip.appendChild(tabEl)
        }
    }
    bar.appendChild(tabStrip)

    val trailing = document.createElement("div") as HTMLElement
    trailing.className = "dt-topbar-trailing"
    spec.trailingContent?.let { trailing.appendChild(it) }
    bar.appendChild(trailing)

    if (spec.isResizable) {
        attachBarVerticalResizeHandle(
            bar = bar,
            edge = BarResizeEdge.BOTTOM,
            minHeightPx = spec.minHeightPx,
            maxHeightPx = spec.maxHeightPx,
            defaultHeightPx = spec.defaultHeightPx,
            allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
            onResize = spec.onResize,
        )
    }

    return bar
}
