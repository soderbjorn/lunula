/**
 * Invisible draggable separator bars between adjacent panes.
 *
 * After every [LayoutRenderer.render] call the toolkit computes a fresh
 * set of [SeparatorSpec]s — one per shared edge between two or more
 * panes — and mounts an invisible `.dt-pane-separator` element over each.
 * The user can grab a separator and drag it to resize all panes whose
 * edges meet at that line in one gesture, like in tiling window managers
 * (i3, yabai). The corner-grip resize gesture remains available for
 * situations where panes overlap or there is no clean shared edge.
 *
 * Design choices:
 *  - **Pure compute is independent of the DOM** — [computeSeparators]
 *    takes the current `List<FloatingPaneSpec>` and returns a deterministic
 *    list of separators. It is exhaustively unit-tested in
 *    `PaneSeparatorsTest`.
 *  - **No new persistence** — separators emit per-pane updates through the
 *    existing [PaneCallbacks.onFloatingMoved] / [PaneCallbacks.onFloatingResized]
 *    callbacks. Hosts persist the resulting [FloatingPaneSpec] geometry the
 *    same way they do for corner drags. Whatever the host does in its
 *    existing resize handler — calling [LayoutController.markCustom],
 *    persisting to disk, re-rendering — happens for separator drags too,
 *    free of charge.
 *  - **Hidden in the [LayoutPreset.Auto] preset** — Auto recomputes layout
 *    on every pane change, so a manually-dragged separator would be undone
 *    the moment a new pane spawned. The renderer skips emission whenever
 *    `presetIsAuto = true`.
 *  - **Skips overlapping segments** — when a third pane crosses a candidate
 *    separator's extent, that segment is dropped. The corner handles still
 *    work for the overlapping case; we just do not pretend the edge is a
 *    clean boundary.
 *
 * @see LayoutRenderer
 * @see FloatingPaneSpec
 * @see LayoutPreset.Auto
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/** Orientation of a [SeparatorSpec]. */
enum class Orientation {
    /** Vertical bar — drag changes pane widths. */
    Vertical,
    /** Horizontal bar — drag changes pane heights. */
    Horizontal,
}

/**
 * One invisible separator bar derived from the current pane layout.
 *
 * Coordinates are container fractions (0..1) matching the [FloatingPaneSpec]
 * convention used everywhere in the layout system.
 *
 * @property orientation whether the bar is vertical or horizontal.
 * @property positionPct the bar's fixed-axis position. For
 *   [Orientation.Vertical] this is the x-coordinate; for
 *   [Orientation.Horizontal] this is the y-coordinate.
 * @property startPct  start of the bar along its long axis (y for V, x for H).
 * @property endPct    end of the bar along its long axis (must satisfy
 *   `endPct > startPct`).
 * @property leftPaneIds ids of panes whose right edge (V) or bottom edge (H)
 *   sits at [positionPct]. Dragging the bar mutates each of these panes'
 *   width or height.
 * @property rightPaneIds ids of panes whose left edge (V) or top edge (H)
 *   sits at [positionPct]. Dragging mutates each pane's origin and size.
 */
data class SeparatorSpec(
    val orientation: Orientation,
    val positionPct: Double,
    val startPct: Double,
    val endPct: Double,
    val leftPaneIds: List<PaneId>,
    val rightPaneIds: List<PaneId>,
)

/**
 * Callbacks the renderer exposes for separator drag.
 *
 * @property onFloatingMoved    fired per affected pane on `mouseup` whose
 *   origin (xPct or yPct) changed. Hosts persist and re-render. The
 *   host's handler is responsible for transitioning the layout
 *   controller to [LayoutPreset.Custom] (the same way it does in
 *   response to a corner-drag), so a separator drag implicitly exits
 *   any non-Custom preset.
 * @property onFloatingResized  fired per affected pane on `mouseup` whose
 *   size (widthPct or heightPct) changed.
 * @property snap               unary snap-to-grid function applied to the
 *   raw cursor fraction. Hosts pass the same `snapPct` used for corner
 *   drags so separator drags land in the same grid cells.
 * @property minSize            minimum pane size on either axis, in
 *   container fractions. The drag refuses to push any affected pane below
 *   this floor — matches the corner-drag clamp.
 * @property paneById           lookup function returning the freshest
 *   [FloatingPaneSpec] for a pane id, used to read the panes' opposite
 *   edges at drag start. Returning `null` for an id excludes it from the
 *   gesture (defensive — should not happen in practice).
 */
class SeparatorCallbacks(
    val onFloatingMoved: (PaneId, Double, Double) -> Unit,
    val onFloatingResized: (PaneId, Double, Double) -> Unit,
    val snap: (Double) -> Double,
    val minSize: Double,
    val paneById: (PaneId) -> FloatingPaneSpec?,
)

/**
 * CSS class names used by [mountSeparators].
 *
 * Public so the toolkit stylesheet (`lunula.css`) can reference
 * the same constants without divergence.
 */
object SeparatorClassNames {
    /** Base class set on every separator element. */
    const val SEPARATOR: String = "dt-pane-separator"
    /** Variant class for vertical bars (drag changes width). */
    const val SEPARATOR_VERTICAL: String = "dt-pane-separator-v"
    /** Variant class for horizontal bars (drag changes height). */
    const val SEPARATOR_HORIZONTAL: String = "dt-pane-separator-h"
    /** Class added during an active drag (mirrors `dt-dragging` on grips). */
    const val DRAGGING: String = "dt-dragging"
}

/**
 * Computes the set of internal separator bars implied by [panes].
 *
 * For each candidate edge coordinate (a value where some pane's left/right
 * or top/bottom edge sits, modulo [epsilon]), the function partitions panes
 * into a "left group" (right/bottom edge ≈ coord) and a "right group"
 * (left/top edge ≈ coord). Where the union of one group's perpendicular
 * ranges overlaps the other's, a separator is emitted for each contiguous
 * overlap segment. Segments crossed by a third pane (one whose body
 * intersects the segment without sharing the candidate edge) are dropped
 * — a clean tiled boundary is required.
 *
 * Pure function: no allocations escape, no DOM reads, deterministic given
 * the same input.
 *
 * @param panes   the host's current pane specs. Minimised panes should be
 *   filtered out by the caller before passing them in.
 * @param epsilon coordinate-equality tolerance, used both for grouping
 *   candidate edges and for matching pane edges. Defaults to 0.02 (≈⅖ of
 *   the 0.05 snap grid) so that visually adjacent panes — gaps up to 2% of
 *   the container, the kind that arise when the user drags two panes
 *   "close enough" without snapping them together — produce a separator.
 *   Stays well below the snap-cell width, so two distinct grid cells cannot
 *   be conflated into one candidate. The bar's [SeparatorSpec.positionPct]
 *   is the midpoint of the closest left/right pair, so the 8 px hit zone
 *   sits in the centre of the visible gap rather than on one pane's edge.
 * @return separator specs in deterministic order: vertical bars first
 *   (sorted by x ascending, then start ascending), horizontal bars second.
 */
fun computeSeparators(
    panes: List<FloatingPaneSpec>,
    epsilon: Double = 0.02,
): List<SeparatorSpec> {
    if (panes.size < 2) return emptyList()
    return computeSeparatorsForAxis(panes, vertical = true, epsilon = epsilon) +
        computeSeparatorsForAxis(panes, vertical = false, epsilon = epsilon)
}

/**
 * Internal: computes separators for one axis.
 *
 * When [vertical] is `true`, candidate coordinates are x-values and groups
 * are matched on right/left edges; when `false`, candidates are y-values
 * and groups are matched on bottom/top edges. Mirrored implementation,
 * factored to a single pass via lambda accessors.
 */
private fun computeSeparatorsForAxis(
    panes: List<FloatingPaneSpec>,
    vertical: Boolean,
    epsilon: Double,
): List<SeparatorSpec> {
    // Accessor lambdas: along-axis (where the bar sits) and perpendicular-axis.
    val alongStart: (FloatingPaneSpec) -> Double =
        if (vertical) { p -> p.xPct } else { p -> p.yPct }
    val alongEnd: (FloatingPaneSpec) -> Double =
        if (vertical) { p -> p.xPct + p.widthPct } else { p -> p.yPct + p.heightPct }
    val perpStart: (FloatingPaneSpec) -> Double =
        if (vertical) { p -> p.yPct } else { p -> p.xPct }
    val perpEnd: (FloatingPaneSpec) -> Double =
        if (vertical) { p -> p.yPct + p.heightPct } else { p -> p.xPct + p.widthPct }

    // 1. Collect candidate coordinates: every distinct right/bottom edge
    //    where at least one other pane has a matching left/top edge.
    val candidates = mutableListOf<Double>()
    for (p in panes) {
        val end = alongEnd(p)
        // Exclude the container's outer boundaries; they aren't internal
        // edges and dragging the screen edge is meaningless.
        if (end >= 1.0 - epsilon) continue
        if (end <= epsilon) continue
        if (candidates.none { kotlin.math.abs(it - end) < epsilon }) {
            candidates += end
        }
    }
    candidates.sort()

    val orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal
    val out = mutableListOf<SeparatorSpec>()
    for (coord in candidates) {
        val leftGroup = panes.filter { kotlin.math.abs(alongEnd(it) - coord) < epsilon }
        val rightGroup = panes.filter { kotlin.math.abs(alongStart(it) - coord) < epsilon }
        if (leftGroup.isEmpty() || rightGroup.isEmpty()) continue

        // Centre the bar in the visible gap rather than on one pane's edge.
        // When edges are close-but-not-equal (within `epsilon`), the original
        // candidate is one pane's literal edge and the 8 px separator hit
        // zone would sit off-centre; the midpoint of the closest left-end
        // and right-start lands the bar in the middle of the gap.
        val seedCoord = (leftGroup.maxOf { alongEnd(it) } + rightGroup.minOf { alongStart(it) }) / 2.0

        val leftRanges = leftGroup.map { perpStart(it) to perpEnd(it) }
        val rightRanges = rightGroup.map { perpStart(it) to perpEnd(it) }

        // Contiguous overlap segments between leftRanges and rightRanges.
        val rawSegments = intersectRangeUnions(
            mergeRanges(leftRanges, epsilon),
            mergeRanges(rightRanges, epsilon),
            epsilon,
        )

        // Drop any segment that a third pane's body crosses (i.e. a pane
        // not in either group whose perpendicular range intersects the
        // segment AND whose body straddles the seed coordinate).
        val crossingPanes = panes.filter { p ->
            alongStart(p) < seedCoord - epsilon && alongEnd(p) > seedCoord + epsilon
        }
        for ((segStart, segEnd) in rawSegments) {
            if (segEnd - segStart < epsilon) continue
            val crossed = crossingPanes.any { p ->
                val ps = perpStart(p)
                val pe = perpEnd(p)
                pe > segStart + epsilon && ps < segEnd - epsilon
            }
            if (crossed) continue

            // Trim the segment to the actual extent of overlap with the
            // groups' panes. (mergeRanges already handles unions; the
            // filtering below excludes panes whose body doesn't reach into
            // the segment — guards against degenerate inputs.)
            val leftIds = leftGroup.filter {
                perpEnd(it) > segStart + epsilon && perpStart(it) < segEnd - epsilon
            }.map { it.id }
            val rightIds = rightGroup.filter {
                perpEnd(it) > segStart + epsilon && perpStart(it) < segEnd - epsilon
            }.map { it.id }
            if (leftIds.isEmpty() || rightIds.isEmpty()) continue

            out += SeparatorSpec(
                orientation = orientation,
                positionPct = seedCoord,
                startPct = segStart,
                endPct = segEnd,
                leftPaneIds = leftIds,
                rightPaneIds = rightIds,
            )
        }
    }
    return out
}

/**
 * Merge a list of `(start, end)` ranges into a sorted, non-overlapping
 * union. Ranges within [epsilon] of each other are merged into one.
 */
private fun mergeRanges(
    ranges: List<Pair<Double, Double>>,
    epsilon: Double,
): List<Pair<Double, Double>> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val merged = mutableListOf<Pair<Double, Double>>()
    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    for (i in 1 until sorted.size) {
        val (s, e) = sorted[i]
        if (s <= curEnd + epsilon) {
            if (e > curEnd) curEnd = e
        } else {
            merged += curStart to curEnd
            curStart = s
            curEnd = e
        }
    }
    merged += curStart to curEnd
    return merged
}

/**
 * Intersect two unions of ranges, returning their overlapping segments
 * as a sorted list. Both inputs are assumed to be the output of
 * [mergeRanges] (sorted, non-overlapping).
 */
private fun intersectRangeUnions(
    a: List<Pair<Double, Double>>,
    b: List<Pair<Double, Double>>,
    epsilon: Double,
): List<Pair<Double, Double>> {
    val out = mutableListOf<Pair<Double, Double>>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val s = kotlin.math.max(a[i].first, b[j].first)
        val e = kotlin.math.min(a[i].second, b[j].second)
        if (e - s > epsilon) out += s to e
        if (a[i].second < b[j].second) i++ else j++
    }
    return out
}

/**
 * Mounts an invisible draggable element for each of [separators] into
 * [container]. Reuses [callbacks] for drag wiring.
 *
 * Existing `.dt-pane-separator` children are removed first, so calling
 * `mountSeparators` on every render produces a fresh set without
 * accumulating stale bars. Reconciliation is intentionally simple — the
 * bars carry no per-element state worth preserving across renders.
 *
 * The bars are appended after panes so they win pointer events on the
 * shared edge (panes occupy `--dt-fp-z`, separators occupy
 * `z-index: 999`). On shared corners the corner-resize grip's larger
 * `z-index: 4` plus its proximity hit zone wins by stacking — but the
 * separator's vertical/horizontal cursor still appears outside the
 * grip's 14×14 box, which is what we want.
 *
 * @param container the same root container [LayoutRenderer] paints into.
 * @param separators bars to mount. Empty list clears any prior bars.
 * @param callbacks  drag wiring + per-pane callbacks.
 */
fun mountSeparators(
    container: HTMLElement,
    separators: List<SeparatorSpec>,
    callbacks: SeparatorCallbacks,
) {
    // Strip prior bars.
    val existing = container.querySelectorAll(".${SeparatorClassNames.SEPARATOR}")
    for (i in 0 until existing.length) {
        val node = existing.item(i) ?: continue
        node.parentNode?.removeChild(node)
    }

    for (spec in separators) {
        container.appendChild(buildSeparatorElement(container, spec, callbacks))
    }
}

/**
 * Builds one separator element with the geometry CSS vars and the drag
 * wiring already attached. Returned element is detached — the caller
 * mounts it.
 */
private fun buildSeparatorElement(
    container: HTMLElement,
    spec: SeparatorSpec,
    callbacks: SeparatorCallbacks,
): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = when (spec.orientation) {
        Orientation.Vertical ->
            "${SeparatorClassNames.SEPARATOR} ${SeparatorClassNames.SEPARATOR_VERTICAL}"
        Orientation.Horizontal ->
            "${SeparatorClassNames.SEPARATOR} ${SeparatorClassNames.SEPARATOR_HORIZONTAL}"
    }
    // Geometry vars consumed by the toolkit stylesheet. Kept distinct from
    // .dt-fp-* so a pane and a separator never collide on a shared var.
    el.style.setProperty("--dt-sep-pos", "${spec.positionPct * 100.0}%")
    el.style.setProperty("--dt-sep-start", "${spec.startPct * 100.0}%")
    el.style.setProperty("--dt-sep-len", "${(spec.endPct - spec.startPct) * 100.0}%")

    wireSeparatorDrag(container, el, spec, callbacks)
    return el
}

/**
 * Wires the `mousedown → mousemove → mouseup` gesture on a separator.
 * Mirrors the corner-resize template in [LayoutRenderer.wireFloatingCornerResize]
 * but applies the cursor delta to every pane in [SeparatorSpec.leftPaneIds]
 * / [SeparatorSpec.rightPaneIds] simultaneously.
 *
 * On `mousedown`:
 *   - Snapshots each affected pane's pre-drag geometry from
 *     [SeparatorCallbacks.paneById].
 *   - Calls [SeparatorCallbacks.markCustom] so subsequent pane
 *     adds/removes do not retile under the user's gesture.
 * On `mousemove`:
 *   - Maps cursor to a container fraction, snaps it, clamps so no pane
 *     shrinks below [SeparatorCallbacks.minSize], and live-updates each
 *     affected pane's CSS vars (`--dt-fp-x/y/w/h`) for frame-perfect
 *     visual feedback.
 * On `mouseup`:
 *   - Fires [SeparatorCallbacks.onFloatingMoved] / `onFloatingResized`
 *     once per affected pane so the host persists and re-renders.
 */
private fun wireSeparatorDrag(
    container: HTMLElement,
    el: HTMLElement,
    spec: SeparatorSpec,
    callbacks: SeparatorCallbacks,
) {
    el.addEventListener("mousedown", { ev ->
        val mouseEv = ev as MouseEvent
        if (mouseEv.button.toInt() != 0) return@addEventListener
        mouseEv.preventDefault()
        // Don't focus a pane just because a separator was grabbed.
        mouseEv.stopPropagation()

        val rect = container.getBoundingClientRect()
        val containerW = rect.width
        val containerH = rect.height
        if (containerW <= 0 || containerH <= 0) return@addEventListener

        // Snapshot pre-drag geometry. If a pane id has gone missing
        // (host raced a re-render), drop it from the gesture rather than
        // applying stale data.
        data class Snap(val spec: FloatingPaneSpec)
        val leftSnaps: Map<PaneId, Snap> = spec.leftPaneIds.mapNotNull { id ->
            callbacks.paneById(id)?.let { id to Snap(it) }
        }.toMap()
        val rightSnaps: Map<PaneId, Snap> = spec.rightPaneIds.mapNotNull { id ->
            callbacks.paneById(id)?.let { id to Snap(it) }
        }.toMap()
        if (leftSnaps.isEmpty() || rightSnaps.isEmpty()) return@addEventListener

        // Lower bound for the drag: no left-side pane may shrink below
        // minSize, so the bar can't move below `max(leftStart) + minSize`.
        // Upper bound symmetrically clamps right-side panes.
        val vertical = spec.orientation == Orientation.Vertical
        val leftMin = leftSnaps.values.maxOf { snap ->
            val s = if (vertical) snap.spec.xPct else snap.spec.yPct
            s + callbacks.minSize
        }
        val rightMax = rightSnaps.values.minOf { snap ->
            val end = if (vertical) snap.spec.xPct + snap.spec.widthPct
            else snap.spec.yPct + snap.spec.heightPct
            end - callbacks.minSize
        }
        val lowerBound = leftMin
        val upperBound = rightMax
        if (upperBound <= lowerBound) return@addEventListener

        el.classList.add(SeparatorClassNames.DRAGGING)

        // Live-update CSS vars on each affected pane element. We look
        // panes up by `data-pane-id` attribute on every move (cheap
        // querySelector by attribute) so we don't cache element refs
        // that could be detached by an unrelated rerender mid-drag.
        var liveCoord = spec.positionPct

        val onMove: (org.w3c.dom.events.Event) -> Unit = { e ->
            val m = e as MouseEvent
            val raw = if (vertical) {
                (m.clientX.toDouble() - rect.left) / containerW
            } else {
                (m.clientY.toDouble() - rect.top) / containerH
            }
            val snapped = callbacks.snap(raw)
            val clamped = snapped.coerceIn(lowerBound, upperBound)
            liveCoord = clamped

            // Move the separator itself so the highlighted drag bar tracks
            // the cursor instead of being left behind as an orange "ghost"
            // at the pre-drag position.
            el.style.setProperty("--dt-sep-pos", "${clamped * 100.0}%")

            for ((id, snap) in leftSnaps) {
                val pane = container.querySelector(
                    "[data-pane-id=\"$id\"]"
                ) as? HTMLElement ?: continue
                // Floating panes carry a 220ms left/top/width/height
                // transition; opt out for the duration of the drag so the
                // edge tracks the cursor frame-by-frame rather than lagging.
                pane.classList.add("dt-pane-no-anim")
                if (vertical) {
                    val newW = clamped - snap.spec.xPct
                    pane.style.setProperty("--dt-fp-w", "${newW * 100.0}%")
                } else {
                    val newH = clamped - snap.spec.yPct
                    pane.style.setProperty("--dt-fp-h", "${newH * 100.0}%")
                }
            }
            for ((id, snap) in rightSnaps) {
                val pane = container.querySelector(
                    "[data-pane-id=\"$id\"]"
                ) as? HTMLElement ?: continue
                pane.classList.add("dt-pane-no-anim")
                if (vertical) {
                    val origEnd = snap.spec.xPct + snap.spec.widthPct
                    pane.style.setProperty("--dt-fp-x", "${clamped * 100.0}%")
                    pane.style.setProperty("--dt-fp-w", "${(origEnd - clamped) * 100.0}%")
                } else {
                    val origEnd = snap.spec.yPct + snap.spec.heightPct
                    pane.style.setProperty("--dt-fp-y", "${clamped * 100.0}%")
                    pane.style.setProperty("--dt-fp-h", "${(origEnd - clamped) * 100.0}%")
                }
            }
        }

        lateinit var onUp: (org.w3c.dom.events.Event) -> Unit
        onUp = { _ ->
            document.removeEventListener("mousemove", onMove)
            document.removeEventListener("mouseup", onUp)
            el.classList.remove(SeparatorClassNames.DRAGGING)

            // Restore floating-pane transitions on every pane we touched
            // (mirrors the cleanup in LayoutRenderer corner-resize / drag).
            for (id in spec.leftPaneIds + spec.rightPaneIds) {
                (container.querySelector("[data-pane-id=\"$id\"]") as? HTMLElement)
                    ?.classList?.remove("dt-pane-no-anim")
            }

            // Persist via the host's per-pane callbacks. The renderer
            // will paint a fresh subtree on the next render, which
            // implicitly remounts the separator at its new position.
            for ((id, snap) in leftSnaps) {
                if (vertical) {
                    val newW = liveCoord - snap.spec.xPct
                    callbacks.onFloatingResized(id, newW, snap.spec.heightPct)
                } else {
                    val newH = liveCoord - snap.spec.yPct
                    callbacks.onFloatingResized(id, snap.spec.widthPct, newH)
                }
            }
            for ((id, snap) in rightSnaps) {
                if (vertical) {
                    val origEnd = snap.spec.xPct + snap.spec.widthPct
                    val newX = liveCoord
                    val newW = origEnd - liveCoord
                    callbacks.onFloatingMoved(id, newX, snap.spec.yPct)
                    callbacks.onFloatingResized(id, newW, snap.spec.heightPct)
                } else {
                    val origEnd = snap.spec.yPct + snap.spec.heightPct
                    val newY = liveCoord
                    val newH = origEnd - liveCoord
                    callbacks.onFloatingMoved(id, snap.spec.xPct, newY)
                    callbacks.onFloatingResized(id, snap.spec.widthPct, newH)
                }
            }
        }
        document.addEventListener("mousemove", onMove)
        document.addEventListener("mouseup", onUp)
    })
}
