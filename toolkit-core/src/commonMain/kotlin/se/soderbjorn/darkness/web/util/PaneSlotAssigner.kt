/**
 * Sticky 1-based slot assignment for panes — the source of truth for the
 * encircled-digit identifiers shown in pane titlebars and sidebar rows.
 *
 * Apps (termtastic, notegrow) own one assigner per session and call
 * [PaneSlotAssigner.assign] when a pane opens, [PaneSlotAssigner.release]
 * when a pane closes, and [PaneSlotAssigner.indexOf] from their pane-header
 * factory and sidebar row builder. The assigner enforces the contract that
 * a pane keeps the same slot for its entire lifetime, so users build muscle
 * memory around "pane ③" without surprise renumbering.
 *
 * The pure data structure lives in commonMain so any host (JVM server-side,
 * Kotlin/JS web shell, future Compose previews) can share the same logic.
 *
 * @see encircledIndexGlyph for the rendering side that turns these indices
 *   into glyphs.
 */
package se.soderbjorn.darkness.web.util

import se.soderbjorn.darkness.web.layout.PaneId

/**
 * Maintains a sticky 1-based slot index per pane id.
 *
 * Slots run `1..[SLOT_RANGE.last]` (35 by default — matching the
 * renderable range of [encircledIndexGlyph]). When all 35 slots are
 * taken, additional panes get *no* slot — [assign] returns `null` and
 * [indexOf] continues to return `null` for those panes — and the toolkit
 * skips the trailing badge entirely. The numbering scheme deliberately
 * never duplicates; running past the cap is a graceful degradation, not
 * a collision.
 *
 * As soon as a slot-holder closes, its slot returns to the free pool;
 * the next [syncTo] call reclaims it for the lowest-ranked pane that's
 * currently unassigned (in iteration order).
 *
 * Slots are session-only. Apps that want them to survive a restart
 * should persist `slotByPane` themselves and prime the assigner with
 * the persisted order via [syncTo] on launch.
 */
class PaneSlotAssigner {
    private val slotByPane: MutableMap<PaneId, Int> = mutableMapOf()
    private val freeSlots: MutableSet<Int> = sortedSetOfInts(SLOT_RANGE)

    /**
     * Assigns a slot to [paneId] if it doesn't already have one. Subsequent
     * calls for the same id are idempotent and return the same slot — the
     * sticky contract.
     *
     * Picks the lowest free slot in `1..35` when one is available; returns
     * `null` (and stores nothing) when all 35 slots are occupied. The
     * caller then renders the pane without a badge.
     *
     * @param paneId stable pane identifier.
     * @return the 1-based slot, or `null` if no slot is currently free.
     */
    fun assign(paneId: PaneId): Int? {
        slotByPane[paneId]?.let { return it }
        if (freeSlots.isEmpty()) return null
        val first = freeSlots.min()
        freeSlots.remove(first)
        slotByPane[paneId] = first
        return first
    }

    /**
     * Releases the slot held by [paneId], if any. The slot returns to the
     * free pool so the next [assign] / [syncTo] call can reclaim it for
     * another pane. Calling [release] for an unknown id is a no-op.
     *
     * @param paneId pane identifier whose slot should be released.
     */
    fun release(paneId: PaneId) {
        val slot = slotByPane.remove(paneId) ?: return
        freeSlots.add(slot)
    }

    /**
     * Returns the slot currently held by [paneId], or `null` if the pane
     * has never been registered, was released, or was seen while the
     * assigner was full.
     *
     * @param paneId pane identifier to look up.
     * @return the 1-based slot, or `null`.
     */
    fun indexOf(paneId: PaneId): Int? = slotByPane[paneId]

    /**
     * Reconciles the assigner with the current live pane set: any id in
     * `slotByPane` no longer present in [livePaneIds] is [release]d
     * first, then every id in [livePaneIds] (in iteration order) gets a
     * slot via [assign] if it doesn't already have one. Releasing first
     * matters at capacity: it lets a pane whose tab was just closed free
     * up its slot before the loop tries to assign newcomers.
     *
     * Apps call this on every snapshot push (after their tab/pane state
     * changes) so the assigner stays in lock-step with the rendered set
     * without needing to thread per-event open/close hooks through every
     * call site. The first call effectively seeds the assigner from
     * scratch in iteration order — pass an ordered list (not a hashed
     * set) on cold start to get a deterministic initial numbering.
     *
     * Panes beyond the 35-slot cap remain unassigned (their [indexOf]
     * stays `null`); they get a slot the next time one frees up.
     *
     * @param livePaneIds the current pane ids in their canonical order.
     */
    fun syncTo(livePaneIds: Iterable<PaneId>) {
        val live = livePaneIds.toSet()
        val departed = slotByPane.keys.filterNot { it in live }
        departed.forEach(::release)
        livePaneIds.forEach(::assign)
    }

    companion object {
        /** Slots that map to a unique encircled glyph. */
        val SLOT_RANGE: IntRange = 1..35

        private fun sortedSetOfInts(range: IntRange): MutableSet<Int> {
            // commonMain has no sortedSetOf; a HashSet works because the
            // caller picks the minimum explicitly. Using a HashSet over the
            // tiny 35-element range keeps removal O(1).
            val set = HashSet<Int>(range.last - range.first + 1)
            range.forEach { set.add(it) }
            return set
        }
    }
}
