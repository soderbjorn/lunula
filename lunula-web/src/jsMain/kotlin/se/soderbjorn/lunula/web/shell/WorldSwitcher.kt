/*
 * WorldSwitcher.kt (jsMain)
 * -------------------------
 * The topbar "world switcher": a globe icon button placed in the leading
 * cluster (left of the tab strip). It is a split control that mirrors the
 * topbar "+" new-pane button: a direct **click** advances to the next world
 * (wrapping past the end), while **hovering** it opens — after a short dwell
 * — a popover listing the worlds (a checkmark marks the active one). Each
 * popover row carries a per-world "⋮" **dot menu**, revealed on row hover, that
 * offers Rename and Close — the very same pattern (and shared [wireMenuToggle]
 * / [menuRow] helpers + `.dt-tab-menu*` chrome) the tab bar uses for its
 * per-tab dot menu ([appendTabDotMenu]), so the two read identically.
 * New worlds are created from the topbar "+" button's "New world" row, so the
 * switcher popover intentionally has no create action of its own. Rename and
 * Close reuse the toolkit's own name-prompt and confirmation-dialog chrome so
 * the world switcher matches the tab bar's rename / close-confirm
 * behaviour.
 *
 * Presentational only: every mutation routes back through the host's
 * [WorldSource] callbacks (the toolkit never owns the world model). The
 * assembler ([mountAppShell]) rebuilds this button on each rerender from
 * the latest [WorldListSnapshot] the host has pushed.
 *
 * @see WorldSource
 * @see buildWorldSwitcher
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunula.web.escapeHtmlForConfirm
import se.soderbjorn.lunula.web.showConfirmDialog

/**
 * Inline SVG: a globe (circle + one meridian + two parallels) — the
 * world switcher's mark. 24-unit viewBox / 16×16 render / 1.6px
 * currentColor stroke so it sits cleanly beside the other leading and
 * trailing topbar icons.
 */
internal const val ICON_GLOBE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
        "<line x1=\"3\" y1=\"12\" x2=\"21\" y2=\"12\"/>" +
        "<ellipse cx=\"12\" cy=\"12\" rx=\"4\" ry=\"9\"/></svg>"

/** Inline SVG: a small check mark used to flag the active world row. */
private const val ICON_CHECK: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<polyline points=\"20 6 9 17 4 12\"/></svg>"

/**
 * Builds the world switcher globe button for the topbar leading cluster.
 *
 * The globe is a split control (mirrors the topbar "+" new-pane button):
 * a direct **click** advances to the next world via [selectNextWorld], while
 * **hovering** opens — after a short dwell — a popover listing [snapshot]'s
 * worlds; the active world (by [WorldListSnapshot.activeWorldId]) carries a
 * checkmark. Selecting a row fires [WorldSource.onSelect]; the per-row "⋮" dot
 * menu (revealed on hover) fires [WorldSource.onRename] / [WorldSource.onClose]
 * (each behind the toolkit's prompt / confirm chrome). Callbacks left `null` on
 * the source suppress the corresponding affordance.
 *
 * Called by [mountAppShell] when [AppShellSpec.worldSource] is non-null.
 *
 * @param snapshot the latest world list to render.
 * @param source   the host's world callbacks.
 * @return the globe `<button>` element, ready to append to the leading
 *   cluster.
 * @see installWorldSwitcherHover
 */
fun buildWorldSwitcher(
    snapshot: WorldListSnapshot,
    source: WorldSource,
): HTMLElement {
    val globe = buildTopbarIconButton(ICON_GLOBE, "Workspaces") {
        selectNextWorld(snapshot, source)
    }
    globe.classList.add("dt-world-switcher-globe")
    installWorldSwitcherHover(globe, snapshot, source)
    return globe
}

/**
 * Advances the active-world selection to the world after [WorldListSnapshot
 * .activeWorldId] (wrapping past the end), firing [WorldSource.onSelect].
 * The globe's primary click action — no-op with fewer than two worlds or
 * when the active id isn't present in the list.
 *
 * @param snapshot the current world list + active id.
 * @param source   the host's world callbacks.
 */
private fun selectNextWorld(snapshot: WorldListSnapshot, source: WorldSource) {
    val worlds = snapshot.worlds
    if (worlds.size < 2) return
    val idx = worlds.indexOfFirst { it.id == snapshot.activeWorldId }
    val next = worlds[if (idx < 0) 0 else (idx + 1) % worlds.size]
    if (next.id != snapshot.activeWorldId) source.onSelect(next.id)
}

/** Hover dwell before the world popover opens (ms) — matches the topbar "+" menu. */
private const val WORLD_HOVER_SHOW_MS = 120

/** Grace period before an un-hovered world popover closes (ms). */
private const val WORLD_HOVER_HIDE_MS = 180

/** Pending open/close timers for the hover-driven world popover (one switcher at a time). */
private var worldHoverShowTimer: Int? = null
private var worldHoverHideTimer: Int? = null

/**
 * After a globe click the topbar rebuilds and the fresh globe lands under the
 * still-parked cursor, whose `mouseenter` would immediately re-open the
 * popover the click just dismissed. These record the clicked globe's rect and
 * suppress auto-open until the cursor leaves it — mirroring HoverMenuButton's
 * cross-render suppression.
 */
private var worldSuppressRectLeft = 0.0
private var worldSuppressRectTop = 0.0
private var worldSuppressRectRight = 0.0
private var worldSuppressRectBottom = 0.0
private var worldSuppressActive = false
private var worldSuppressCleanupInstalled = false

/** Lazily install one document `mousemove` listener that lifts the suppression once the cursor exits the rect. */
private fun ensureWorldSuppressCleanup() {
    if (worldSuppressCleanupInstalled) return
    worldSuppressCleanupInstalled = true
    document.addEventListener("mousemove", { ev: Event ->
        if (!worldSuppressActive) return@addEventListener
        val me = ev as? MouseEvent ?: return@addEventListener
        if (me.clientX < worldSuppressRectLeft || me.clientX > worldSuppressRectRight ||
            me.clientY < worldSuppressRectTop || me.clientY > worldSuppressRectBottom
        ) {
            worldSuppressActive = false
        }
    })
}

/** Cancel a pending hover-open. */
private fun cancelWorldHoverShow() {
    worldHoverShowTimer?.let { window.clearTimeout(it) }
    worldHoverShowTimer = null
}

/** Cancel a pending hover-close. */
private fun cancelWorldHoverHide() {
    worldHoverHideTimer?.let { window.clearTimeout(it) }
    worldHoverHideTimer = null
}

/** Schedule the hover popover to close after [WORLD_HOVER_HIDE_MS] unless re-entered. */
private fun scheduleWorldHoverHide() {
    // While a per-world ⋮ dropdown is open, keep the popover chain alive — closing
    // it would yank away the menu the pointer is heading for. (The dropdown mounts
    // on document.body with a full-viewport backdrop, so the popover gets no stray
    // hover events meanwhile; this only guards the leave that fired as the pointer
    // crossed from the row toward the dropdown.)
    if (document.querySelector(".dt-world-row-menu-list.dt-open") != null) return
    cancelWorldHoverHide()
    worldHoverHideTimer = window.setTimeout({ closeAllWorldPopovers() }, WORLD_HOVER_HIDE_MS)
}

/**
 * Wires [box] (a list or manage popover) so the pointer keeps it open while
 * over it and closes it shortly after leaving — the menu half of the globe's
 * hover-to-open behaviour. Moving between the globe, the list, and a manage
 * sub-menu (all matched structurally) keeps the popover chain alive.
 *
 * @param box the popover element to keep alive on hover.
 */
private fun wireWorldPopoverHover(box: HTMLElement) {
    box.addEventListener("mouseenter", { _: Event -> cancelWorldHoverHide() })
    box.addEventListener("mouseleave", { ev: Event ->
        val related = (ev as? MouseEvent)?.relatedTarget as? HTMLElement
        // Staying within the globe or another world popover keeps the chain open.
        if (related != null &&
            (related.closest(".dt-world-switcher-globe") != null ||
                related.closest(".dt-world-popover") != null)
        ) {
            return@addEventListener
        }
        scheduleWorldHoverHide()
    })
}

/**
 * Installs the hover-to-open behaviour on the globe [anchor]: entering it
 * schedules the world-list popover to open after [WORLD_HOVER_SHOW_MS];
 * leaving (without landing in the popover) schedules a close. A direct click
 * cancels any pending open and dismisses an open popover — the click commits
 * [selectNextWorld] instead of surfacing the menu.
 *
 * @param anchor   the globe button.
 * @param snapshot the world list to render when the popover opens.
 * @param source   the host's world callbacks.
 */
private fun installWorldSwitcherHover(
    anchor: HTMLElement,
    snapshot: WorldListSnapshot,
    source: WorldSource,
) {
    anchor.addEventListener("mouseenter", { ev: Event ->
        cancelWorldHoverHide()
        if (document.querySelector(".dt-world-popover") != null) return@addEventListener
        // Don't auto-reopen while the cursor is still parked on a just-clicked globe.
        if (worldSuppressActive) {
            val me = ev as? MouseEvent
            if (me != null &&
                me.clientX >= worldSuppressRectLeft && me.clientX <= worldSuppressRectRight &&
                me.clientY >= worldSuppressRectTop && me.clientY <= worldSuppressRectBottom
            ) {
                return@addEventListener
            }
            worldSuppressActive = false
        }
        cancelWorldHoverShow()
        worldHoverShowTimer = window.setTimeout({
            openWorldListPopover(anchor, snapshot, source)
        }, WORLD_HOVER_SHOW_MS)
    })
    anchor.addEventListener("mouseleave", { ev: Event ->
        cancelWorldHoverShow()
        val related = (ev as? MouseEvent)?.relatedTarget as? HTMLElement
        if (related != null && related.closest(".dt-world-popover") != null) return@addEventListener
        scheduleWorldHoverHide()
    })
    // A click commits the primary action (next world); dismiss any hover popover
    // and cancel a pending open so a quick hover-then-click can't race a menu in.
    anchor.addEventListener("click", { _: Event ->
        cancelWorldHoverShow()
        cancelWorldHoverHide()
        closeAllWorldPopovers()
        val rect = anchor.getBoundingClientRect()
        worldSuppressRectLeft = rect.left
        worldSuppressRectTop = rect.top
        worldSuppressRectRight = rect.right
        worldSuppressRectBottom = rect.bottom
        worldSuppressActive = true
        ensureWorldSuppressCleanup()
    })
}

/**
 * Opens the "New world" name prompt and forwards the confirmed name to
 * [onAdd]. Exposed so the topbar "+" split-button dropdown can offer a
 * "New world" row that shares the switcher's prompt chrome.
 *
 * @param onAdd invoked with the trimmed, non-blank world name.
 */
internal fun promptNewWorldName(onAdd: (String) -> Unit) {
    promptWorldName(title = "New workspace", initial = "", onCommit = onAdd)
}

/** Detaches the outside-click / Escape listeners of the currently-open popover chain, if any. */
private var worldPopoverDismiss: (() -> Unit)? = null

/**
 * Removes any open world-switcher popover / sub-menu currently in the DOM and
 * tears down the timers and outside-click / Escape listeners that fed it, so a
 * hover-driven close leaks nothing.
 */
private fun closeAllWorldPopovers() {
    cancelWorldHoverShow()
    cancelWorldHoverHide()
    worldPopoverDismiss?.invoke()
    worldPopoverDismiss = null
    val open = document.querySelectorAll(".dt-world-popover")
    for (i in 0 until open.length) (open.item(i) as HTMLElement).remove()
    // The per-world ⋮ dropdowns (the tab-menu pattern) mount their list on
    // document.body, so removing the popover rows above doesn't take them down —
    // sweep any world dot-menu list, plus any stray menu backdrop wireMenuToggle
    // left up, so a hover-driven close leaks nothing.
    val dotLists = document.querySelectorAll(".dt-world-row-menu-list")
    for (i in 0 until dotLists.length) (dotLists.item(i) as HTMLElement).remove()
    val backdrops = document.querySelectorAll(".dt-menu-backdrop")
    for (i in 0 until backdrops.length) (backdrops.item(i) as HTMLElement).remove()
}

/**
 * Opens the world-list popover anchored under [anchor], tearing down any
 * previously-open instance first. Each row selects its world; the trailing
 * per-world "⋮" dot menu (revealed on hover) holds Rename / Close. New-world
 * creation is not offered here (it lives on the topbar "+" button). Wired for
 * hover-close so leaving both the globe and the popover dismisses it.
 *
 * @param anchor   the globe button the popover hangs from.
 * @param snapshot the world list + active id to render.
 * @param source   the host's world callbacks.
 */
private fun openWorldListPopover(
    anchor: HTMLElement,
    snapshot: WorldListSnapshot,
    source: WorldSource,
) {
    closeAllWorldPopovers()

    val box = document.createElement("div") as HTMLElement
    box.className = "dt-hover-menu dt-world-popover dt-world-list"
    box.setAttribute("role", "menu")

    for (world in snapshot.worlds) {
        box.appendChild(buildWorldRow(world, world.id == snapshot.activeWorldId, snapshot, source))
    }

    anchorPopover(box, anchor.getBoundingClientRect(), alignLeft = true)
    wireWorldPopoverHover(box)
    installDismissal(box, anchor)
}

/**
 * Builds one world row: a click-to-select button carrying a leading checkmark
 * slot (filled when [active]) and a trailing per-world "⋮" dot menu (revealed
 * on hover) for Rename / Close — see [appendWorldRowDotMenu].
 */
private fun buildWorldRow(
    world: WorldSnapshotEntry,
    active: Boolean,
    snapshot: WorldListSnapshot,
    source: WorldSource,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-hover-menu-item dt-world-row" + if (active) " dt-active" else ""
    row.setAttribute("role", "menuitem")
    row.setAttribute("data-id", world.id)

    val check = document.createElement("span") as HTMLElement
    check.className = "dt-hover-menu-icon dt-world-check"
    check.innerHTML = if (active) ICON_CHECK else ""

    val label = document.createElement("span") as HTMLElement
    label.className = "dt-hover-menu-label"
    label.textContent = world.label

    row.appendChild(check)
    row.appendChild(label)

    // The row body selects the world; the trailing ⋮ dot menu opens the manage
    // actions without also selecting (its button stops propagation).
    row.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        closeAllWorldPopovers()
        source.onSelect(world.id)
    })

    appendWorldRowDotMenu(row, world, snapshot, source)
    return row
}

/**
 * Appends the per-world "⋮" **dot menu** to a world row — the exact pattern the
 * tab bar uses for its per-tab actions ([appendTabDotMenu]): a small "⋮" button
 * the CSS reveals on row hover, opening a body-mounted dropdown (via the shared
 * [wireMenuToggle], with the shared [menuRow] rows + `.dt-tab-menu*` /
 * `.dt-tabbar-menu-*` chrome) that holds Rename and Close. Reusing the tab
 * helpers keeps the two menus pixel- and behaviour-identical.
 *
 * Rename opens the modal name prompt seeded with the current name; Close opens
 * the destructive confirm dialog. The last remaining world's Close row is
 * omitted (a world can't be closed to nothing — the host guards this too), so a
 * lone world's menu shows only Rename; when neither action is available the
 * button isn't rendered at all.
 *
 * @param rowEl    the `.dt-world-row` to attach the "⋮" button to (its dropdown
 *   list mounts on `document.body`, tagged `.dt-world-row-menu-list`).
 * @param world    the world this menu acts on.
 * @param snapshot the current world list (for the last-world Close guard).
 * @param source   the host's world callbacks.
 * @see appendTabDotMenu
 * @see wireMenuToggle
 */
private fun appendWorldRowDotMenu(
    rowEl: HTMLElement,
    world: WorldSnapshotEntry,
    snapshot: WorldListSnapshot,
    source: WorldSource,
) {
    val canRename = source.onRename != null
    // A world can't be closed to nothing, so the last world offers no Close.
    val canClose = source.onClose != null && snapshot.worlds.size > 1
    if (!canRename && !canClose) return

    val menuWrap = document.createElement("div") as HTMLElement
    menuWrap.className = "dt-tab-menu dt-world-row-menu"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "dt-tab-menu-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.title = "Workspace options"
    menuBtn.setAttribute("aria-label", "Workspace options")
    menuBtn.textContent = "⋮"

    val menuList = document.createElement("div") as HTMLElement
    menuList.className = "dt-tabbar-menu-list dt-tab-menu-list dt-world-row-menu-list"

    val closeMenu = wireMenuToggle(menuWrap, menuBtn, menuList)

    source.onRename?.let { onRename ->
        menuList.appendChild(menuRow("Rename", ICON_RENAME) {
            closeMenu()
            closeAllWorldPopovers()
            promptWorldName(title = "Rename workspace", initial = world.label) { name ->
                onRename(world.id, name)
            }
        })
    }
    if (canClose) {
        val onClose = source.onClose!!
        menuList.appendChild(menuRow("Close", ICON_CLOSE_TAB) {
            closeMenu()
            closeAllWorldPopovers()
            showConfirmDialog(
                title = "Close workspace",
                message = "Close <strong>${escapeHtmlForConfirm(world.label)}</strong>? " +
                    "This deletes every tab and session inside it.",
                confirmLabel = "Close workspace",
                destructive = true,
                messageIsHtml = true,
                onConfirm = { onClose(world.id) },
            )
        })
    }

    menuWrap.appendChild(menuBtn)
    rowEl.appendChild(menuWrap)
    document.body?.appendChild(menuList)
}

/** Positions [box] under the anchor rect [a], clamped to the viewport. */
private fun anchorPopover(box: HTMLElement, a: org.w3c.dom.DOMRect, alignLeft: Boolean) {
    document.body?.appendChild(box)
    val b = box.getBoundingClientRect()
    val left = if (alignLeft) {
        a.left.coerceAtMost(window.innerWidth - b.width - 4.0)
    } else {
        (a.right - b.width).coerceAtLeast(4.0)
    }.coerceAtLeast(4.0)
    box.style.left = "${left}px"
    box.style.top = "${a.bottom + 4}px"
}

/**
 * Wires outside-click + Escape dismissal for a world popover and records the
 * detach into [worldPopoverDismiss] so a hover-driven [closeAllWorldPopovers]
 * removes these document listeners too (rather than leaking one pair per hover).
 */
private fun installDismissal(box: HTMLElement, anchor: HTMLElement) {
    lateinit var outside: (Event) -> Unit
    lateinit var esc: (Event) -> Unit
    fun detach() {
        document.removeEventListener("click", outside)
        document.removeEventListener("keydown", esc)
        if (worldPopoverDismiss != null) worldPopoverDismiss = null
    }
    outside = handler@{ ev ->
        val target = ev.target as? HTMLElement ?: return@handler
        if (box.contains(target) || anchor.contains(target)) return@handler
        detach()
        box.remove()
    }
    esc = handler@{ ev ->
        if ((ev as? KeyboardEvent)?.key == "Escape") {
            closeAllWorldPopovers()
        }
    }
    document.addEventListener("click", outside)
    document.addEventListener("keydown", esc)
    worldPopoverDismiss = ::detach
}

/**
 * Opens a modal name prompt reusing the `.dt-modal*` chrome. Commits the
 * trimmed, non-empty value on OK / Enter; cancels otherwise. Mirrors the
 * tab bar's inline-rename ergonomics (Enter commits, Escape cancels) in a
 * modal form so the same gesture works for world New / Rename.
 *
 * @param title   dialog title.
 * @param initial pre-filled text (the current name for Rename; "" for New).
 * @param onCommit invoked with the trimmed name when the user confirms a
 *   non-blank value.
 */
private fun promptWorldName(title: String, initial: String, onCommit: (String) -> Unit) {
    val backdrop = document.createElement("div") as HTMLElement
    backdrop.className = "dt-modal-backdrop"
    val card = document.createElement("div") as HTMLElement
    card.className = "dt-modal"

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "dt-modal-title"
    titleEl.textContent = title
    card.appendChild(titleEl)

    val input = document.createElement("input") as HTMLInputElement
    input.className = "dt-modal-input"
    input.type = "text"
    input.value = initial
    input.placeholder = "Workspace name"
    card.appendChild(input)

    val buttons = document.createElement("div") as HTMLElement
    buttons.className = "dt-modal-buttons"
    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.setAttribute("type", "button")
    cancelBtn.textContent = "Cancel"
    cancelBtn.className = "dt-modal-btn dt-modal-btn-cancel"
    val okBtn = document.createElement("button") as HTMLElement
    okBtn.setAttribute("type", "button")
    okBtn.textContent = "OK"
    okBtn.className = "dt-modal-btn dt-modal-btn-confirm"
    buttons.appendChild(cancelBtn)
    buttons.appendChild(okBtn)
    card.appendChild(buttons)
    backdrop.appendChild(card)
    document.body?.appendChild(backdrop)
    input.focus()
    input.select()

    var done = false
    fun close() {
        if (done) return
        done = true
        backdrop.parentNode?.removeChild(backdrop)
    }
    fun commit() {
        val name = input.value.trim()
        if (name.isEmpty()) { close(); return }
        close()
        onCommit(name)
    }

    cancelBtn.addEventListener("click", { close() })
    okBtn.addEventListener("click", { commit() })
    backdrop.addEventListener("click", { ev ->
        if (ev.target === backdrop) close()
    })
    input.addEventListener("keydown", { ev ->
        when ((ev as KeyboardEvent).key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); close() }
        }
    })
}
