/**
 * Drop-in modal confirmation dialog.
 *
 * Generic helper that opens an overlay modal, calls the supplied confirm/
 * cancel callbacks, and tears itself down. Handles Escape-to-cancel,
 * backdrop-click-to-cancel, focus management on the confirm button, and
 * basic styling that respects the current CSS-var theme.
 *
 * Visual styles ride on the `.dt-modal*` classes shipped in
 * `lunula.css`; consumers must call [injectLunulaStyles]
 * once at boot for the modal to render correctly.
 *
 * @see ResolvedTheme
 */
package se.soderbjorn.lunula.web

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Opens a modal confirmation dialog with the given message and labels.
 *
 * Closes itself when the user confirms, cancels, presses Escape, or clicks
 * the backdrop. The relevant callback is invoked in each case (only one).
 *
 * @param title         dialog title shown in the header
 * @param message       body message shown above the buttons
 * @param confirmLabel  label for the primary (confirm) button. Defaults to "OK".
 * @param cancelLabel   label for the secondary (cancel) button. Defaults to "Cancel".
 * @param destructive   if true, styles the confirm button as destructive.
 *   The `.dt-destructive` variant paints the label/border with the theme's
 *   `--t-danger` token over a translucent danger-tinted surface — muted and
 *   in-theme rather than a solid danger-red fill (termtastic issue #90).
 *   Reserve this for genuinely dangerous, hard-to-undo actions; routine
 *   confirmations (tab close, pane close, quit) use the default accent
 *   styling so they don't read as warnings.
 * @param messageIsHtml if true, [message] is set as `innerHTML` so callers
 *   can include inline markup like `<strong>name</strong>`. When false
 *   (the default), [message] is rendered as plain text via `textContent`.
 *   HTML callers must escape any user-supplied substrings themselves.
 * @param onConfirm     invoked when the user confirms
 * @param onCancel      invoked when the user cancels (Escape, backdrop, or cancel button)
 */
fun showConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    messageIsHtml: Boolean = false,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val backdrop = document.createElement("div") as HTMLElement
    backdrop.className = "dt-modal-backdrop"

    val card = document.createElement("div") as HTMLElement
    card.className = "dt-modal"

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "dt-modal-title"
    titleEl.textContent = title
    card.appendChild(titleEl)

    val messageEl = document.createElement("p") as HTMLElement
    messageEl.className = "dt-modal-message"
    if (messageIsHtml) messageEl.innerHTML = message else messageEl.textContent = message
    card.appendChild(messageEl)

    val buttons = document.createElement("div") as HTMLElement
    buttons.className = "dt-modal-buttons"

    val cancelBtn = document.createElement("button") as HTMLButtonElement
    cancelBtn.type = "button"
    cancelBtn.textContent = cancelLabel
    cancelBtn.className = "dt-modal-btn dt-modal-btn-cancel"

    val confirmBtn = document.createElement("button") as HTMLButtonElement
    confirmBtn.type = "button"
    confirmBtn.textContent = confirmLabel
    confirmBtn.className = "dt-modal-btn dt-modal-btn-confirm" +
        if (destructive) " dt-destructive" else ""

    buttons.appendChild(cancelBtn)
    buttons.appendChild(confirmBtn)
    card.appendChild(buttons)
    backdrop.appendChild(card)
    document.body?.appendChild(backdrop)
    confirmBtn.focus()

    var done = false
    fun close(action: () -> Unit) {
        if (done) return
        done = true
        backdrop.parentNode?.removeChild(backdrop)
        action()
    }

    cancelBtn.addEventListener("click", { close(onCancel) })
    confirmBtn.addEventListener("click", { close(onConfirm) })
    backdrop.addEventListener("click", { ev ->
        if (ev.target === backdrop) close(onCancel)
    })
    val keyHandler: (org.w3c.dom.events.Event) -> Unit = { ev ->
        val k = ev as KeyboardEvent
        if (k.key == "Escape") close(onCancel)
        else if (k.key == "Enter" && document.activeElement === confirmBtn) close(onConfirm)
    }
    document.addEventListener("keydown", keyHandler)
    // We don't have a clean way to remove the listener after close without
    // capturing the function reference itself; the handler is no-op once
    // `done` is set, so it's effectively dead.
}

/**
 * One button in a [showChoiceDialog] modal.
 *
 * @property id       stable identifier reported to `onChoice` when picked.
 * @property label    visible button text.
 * @property isPrimary styles the button as the accent-colored primary
 *   action and gives it initial focus + Enter activation. At most one
 *   choice should set this; with none set, no button is highlighted and
 *   Enter is inert.
 * @property destructive styles the button with the muted danger tint
 *   (`.dt-destructive`) — for choices that discard work or delete data.
 */
data class DialogChoice(
    val id: String,
    val label: String,
    val isPrimary: Boolean = false,
    val destructive: Boolean = false,
)

/**
 * Opens a modal dialog offering an arbitrary set of buttons — the N-way
 * sibling of the two-button [showConfirmDialog], for flows like
 * unsaved-changes ("Save" / "Discard" / "Keep editing") where cancel vs.
 * confirm doesn't capture the real decision.
 *
 * Exactly one callback fires per open: `onChoice(id)` for a clicked
 * button, or `onDismiss()` for Escape / backdrop-click. Buttons render in
 * list order, left to right, in the same `.dt-modal-buttons` row the
 * two-button dialog uses.
 *
 * @param title       dialog title shown in the header.
 * @param message     body message shown above the buttons.
 * @param choices     the buttons, in display order. Must be non-empty.
 * @param messageIsHtml if true, [message] is set as `innerHTML` (callers
 *   escape user-supplied substrings via [escapeHtmlForConfirm]).
 * @param onChoice    invoked with the picked choice's [DialogChoice.id].
 * @param onDismiss   invoked on Escape or backdrop click. Defaults to a
 *   no-op; flows where dismissal means one of the choices (e.g. "Keep
 *   editing") route it to the same handler.
 */
fun showChoiceDialog(
    title: String,
    message: String,
    choices: List<DialogChoice>,
    messageIsHtml: Boolean = false,
    onChoice: (String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val backdrop = document.createElement("div") as HTMLElement
    backdrop.className = "dt-modal-backdrop"

    val card = document.createElement("div") as HTMLElement
    card.className = "dt-modal"

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "dt-modal-title"
    titleEl.textContent = title
    card.appendChild(titleEl)

    val messageEl = document.createElement("p") as HTMLElement
    messageEl.className = "dt-modal-message"
    if (messageIsHtml) messageEl.innerHTML = message else messageEl.textContent = message
    card.appendChild(messageEl)

    val buttons = document.createElement("div") as HTMLElement
    buttons.className = "dt-modal-buttons"

    var done = false
    fun close(action: () -> Unit) {
        if (done) return
        done = true
        backdrop.parentNode?.removeChild(backdrop)
        action()
    }

    var primaryBtn: HTMLButtonElement? = null
    for (choice in choices) {
        val btn = document.createElement("button") as HTMLButtonElement
        btn.type = "button"
        btn.textContent = choice.label
        // Primary rides the confirm styling, everything else the cancel
        // styling — same visual language as the two-button dialog.
        btn.className = if (choice.isPrimary) {
            "dt-modal-btn dt-modal-btn-confirm" + if (choice.destructive) " dt-destructive" else ""
        } else {
            "dt-modal-btn dt-modal-btn-cancel" + if (choice.destructive) " dt-destructive" else ""
        }
        btn.addEventListener("click", { close { onChoice(choice.id) } })
        if (choice.isPrimary && primaryBtn == null) primaryBtn = btn
        buttons.appendChild(btn)
    }

    card.appendChild(buttons)
    backdrop.appendChild(card)
    document.body?.appendChild(backdrop)
    primaryBtn?.focus()

    backdrop.addEventListener("click", { ev ->
        if (ev.target === backdrop) close(onDismiss)
    })
    val keyHandler: (org.w3c.dom.events.Event) -> Unit = { ev ->
        val k = ev as KeyboardEvent
        if (k.key == "Escape") close(onDismiss)
        else if (k.key == "Enter" && primaryBtn != null && document.activeElement === primaryBtn) {
            val primary = choices.first { it.isPrimary }
            close { onChoice(primary.id) }
        }
    }
    document.addEventListener("keydown", keyHandler)
    // Same listener-lifecycle note as [showConfirmDialog]: the handler is
    // a no-op once `done` is set, so it's effectively dead after close.
}

/**
 * Escapes a user-supplied string for safe inclusion in [showConfirmDialog]
 * messages where `messageIsHtml = true`. Replaces the five HTML special
 * characters (`&<>"'`) with their entity references; nothing else.
 *
 * @param s the raw string
 * @return an HTML-escaped version safe to embed in markup
 */
fun escapeHtmlForConfirm(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/**
 * Single source of truth for the close-a-pane confirmation dialog used
 * by both notegrow (via the toolkit's [se.soderbjorn.lunula.web.layout.LayoutRenderer])
 * and termtastic (via its own `PaneHeader.performClose`). Picks the copy
 * and button label based on how many panes will actually disappear if
 * the user confirms.
 *
 * The toolkit owns:
 * - the dialog title (`"Close pane"`),
 * - the wording for both the lone-pane and linked-pane cases,
 * - the button labels (`"Close"` vs `"Close all"`),
 * - the non-destructive styling (closing a pane preserves the underlying
 *   file/session — the danger-red destructive variant looked out-of-theme
 *   so the standard accent-themed confirm button is used).
 *
 * The host owns:
 * - whatever it needs to do on confirm (server IPC, exit animation, …),
 *   passed in via [onConfirm];
 * - knowing how many panes the gesture will close, passed in via
 *   [linkedPaneCount].
 *
 * @param paneTitle the user-visible pane title; null or blank renders as
 *   `"this pane"` in the prompt.
 * @param linkedPaneCount the total number of panes that will disappear
 *   if the user confirms. `0` or `1` is treated as a lone pane (default
 *   wording + `"Close"`). `>= 2` switches the wording to a session-level
 *   warning and the button to `"Close all"`. The wording reports
 *   `linkedPaneCount - 1` *additional* linked panes — i.e. the count not
 *   including the one the user explicitly clicked.
 * @param onConfirm invoked once the user confirms. Not invoked on cancel
 *   / Escape / backdrop-click. The toolkit does not animate the pane or
 *   talk to any server — that is the host's job inside this callback.
 *
 * @see showConfirmDialog
 * @see escapeHtmlForConfirm
 */
fun confirmClosePane(
    paneTitle: String?,
    linkedPaneCount: Int = 1,
    onConfirm: () -> Unit,
) {
    val hasLinks = linkedPaneCount >= 2
    val message = if (hasLinks) {
        val others = linkedPaneCount - 1
        val plural = if (others == 1) "" else "s"
        "This terminal session has <strong>$others linked pane$plural</strong> that will also be closed."
    } else {
        val label = paneTitle?.takeIf { it.isNotBlank() } ?: "this pane"
        "Are you sure you want to close <strong>${escapeHtmlForConfirm(label)}</strong>?"
    }
    showConfirmDialog(
        title = "Close pane",
        message = message,
        confirmLabel = if (hasLinks) "Close all" else "Close",
        // Closing a pane is reversible at content level (the file is
        // autosaved, a terminal session can be reopened). The default
        // accent-themed confirm button reads as in-theme; the destructive
        // variant looked out-of-place against the rest of the UI.
        // (The destructive variant has since been re-themed to a muted
        // danger tint — see `.dt-destructive` in lunula.css —
        // but pane close stays non-destructive by semantics.)
        destructive = false,
        messageIsHtml = true,
        onConfirm = onConfirm,
    )
}
