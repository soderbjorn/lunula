/**
 * Web-side renderer input for the toolkit's windowing/layout system.
 *
 * The pure data model — [LayoutPreset], [LayoutBox], [PaneId], [GridSpec],
 * and the `computeBoxes` algorithm — lives in lunula-core's commonMain
 * so termtastic's JVM server and notegrow's Kotlin/JS shell can share it.
 * This file keeps only the JS-flavored renderer input ([PaneLayout]),
 * which references [FloatingPaneSpec] and is consumed by [LayoutRenderer].
 *
 * Historically this file also defined a recursive split-tree (`PaneTree`,
 * `PaneNode`, `PaneTreeOps`); those types were removed when the family
 * standardised on floats-only — every consumer in the family (notegrow
 * via the toolkit, termtastic in its own app code) now uses absolutely-
 * positioned panes with z-index stacking.
 *
 * @see LayoutRenderer
 * @see FloatingPaneSpec
 * @see LayoutPreset
 */
package se.soderbjorn.lunula.web.layout

/**
 * Renderer input describing what to paint inside a [LayoutRenderer]'s
 * container.
 *
 * @property floatingPanes one entry per pane. Each is rendered as an
 *   absolutely-positioned `.dt-pane` over the renderer's container, with
 *   stacking via [FloatingPaneSpec.zIndex]. Empty list paints an empty
 *   container.
 *
 * @see FloatingPaneSpec
 */
data class PaneLayout(
    val floatingPanes: List<FloatingPaneSpec> = emptyList(),
)
