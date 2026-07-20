/*
 * HotkeyConfigDialog.kt (jsMain)
 *
 * Modal editor for one configurable hotkey action's bindings. Opened by
 * the host app when the user clicks an action row in its keyboard-
 * shortcuts pane (see termtastic's HotkeysSidebarContent for the
 * canonical consumer).
 *
 * The dialog shows the action's current *effective* chords (defaults or
 * the user's custom set), lets the user remove chords, add new ones via
 * **key capture** (press the combo; free-text entry is impossible, so
 * invalid key codes can't be typed in), and reset the action back to its
 * defaults. Capture-time validation rejects modifier-less chords and
 * chords already bound to another action; chords a real browser reserves
 * for itself are accepted but clearly tagged **Mac only** (they still
 * work in the Electron desktop shell).
 *
 * Saving routes through [HotkeyBindings.setCustomChords], which rebinds
 * the live [HotkeyRegistry] entries and fires [HotkeyBindings.onPersist]
 * so the host persists the new blob. While the dialog is open,
 * [HotkeyRegistry.suppressed] is set so recording a chord can't trigger
 * the action it currently belongs to.
 *
 * Visual language mirrors [ToolkitHotkeysModal] (same backdrop, panel
 * chrome and the toolkit's flat `--t-*` theme tokens — see
 * [se.soderbjorn.lunula.web.toCssVarMap] for the canonical token list).
 * The footer buttons reuse the shared `.dt-modal-btn*` classes from
 * `lunula.css` (the same ones [se.soderbjorn.lunula.web.showConfirmDialog]
 * uses) so the primary/cancel buttons follow the active theme exactly like
 * every other toolkit dialog; hosts must have called
 * `injectLunulaStyles` at boot (they already must for ConfirmDialog).
 * Everything dialog-specific is self-contained under the `dt-hkcfg-`
 * class prefix.
 *
 * commonMain rules don't apply here — this is jsMain only.
 *
 * @see HotkeyBindings
 * @see isBrowserReservedChord
 */
package se.soderbjorn.lunula.web.hotkey

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Open the binding editor for [actionId].
 *
 * The action must have been registered via [HotkeyBindings.registerAction]
 * (otherwise the dialog opens with an empty default set — harmless but
 * pointless), so hosts should only wire this to rows that map to
 * registered actions.
 *
 * Only one instance can be open at a time; a second call closes the
 * first. Dismissal (Escape / backdrop / Cancel) discards edits; Save
 * commits them through [HotkeyBindings.setCustomChords] (a working set
 * identical to the defaults is stored as "no customization").
 *
 * @param actionId the registered action to edit.
 * @param label optional title override; defaults to the registered
 *   action label ([HotkeyBindings.actionLabel]). Lets hosts keep their
 *   own vocabulary (e.g. termtastic says "window" where the toolkit
 *   says "pane").
 * @param onSaved invoked after a successful save (not on cancel) so the
 *   host can re-render its shortcuts pane with the new chords.
 */
fun openHotkeyConfigDialog(
    actionId: String,
    label: String? = null,
    onSaved: () -> Unit = {},
) {
    HotkeyConfigDialogHost.open(actionId, label, onSaved)
}

/**
 * Singleton owner of the dialog DOM + state. Internal — hosts go through
 * [openHotkeyConfigDialog].
 */
private object HotkeyConfigDialogHost {

    /** Mounted backdrop element, or null when closed. */
    private var backdropEl: HTMLElement? = null

    /** Document-level keydown handler active while the dialog is open. */
    private var keyHandler: ((Event) -> Unit)? = null

    /** Window-level capture handlers active only while recording. */
    private var captureKeyDown: ((Event) -> Unit)? = null
    private var captureKeyUp: ((Event) -> Unit)? = null

    /** The action being edited. */
    private var actionId: String = ""

    /** Working chord list (the dialog's uncommitted state). */
    private var working: MutableList<Hotkey> = mutableListOf()

    /** True while the capture box is armed and listening for a combo. */
    private var capturing: Boolean = false

    /** Elements re-rendered as state changes. */
    private var listEl: HTMLElement? = null
    private var captureRowEl: HTMLElement? = null
    private var errorEl: HTMLElement? = null

    private var onSaved: () -> Unit = {}

    /**
     * Mount the dialog for [actionId]. Closes any prior instance first.
     */
    fun open(actionId: String, label: String?, onSaved: () -> Unit) {
        close()
        this.actionId = actionId
        this.onSaved = onSaved
        working = HotkeyBindings.effectiveChords(actionId).toMutableList()
        capturing = false

        ensureStylesheet()
        HotkeyRegistry.suppressed = true

        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "dt-hkcfg-backdrop"
        backdrop.addEventListener("mousedown", { e ->
            if ((e as MouseEvent).target === backdrop) {
                e.preventDefault(); e.stopPropagation()
                close()
            }
        })

        val panel = document.createElement("div") as HTMLElement
        panel.className = "dt-hkcfg-panel"
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")

        val resolvedTitle = label ?: HotkeyBindings.actionLabel(actionId) ?: actionId
        panel.appendChild(buildHeader(resolvedTitle))
        panel.appendChild(buildBody())
        panel.appendChild(buildFooter())

        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)
        backdropEl = backdrop

        // Escape closes the dialog — but only when not recording (while
        // recording, Escape merely cancels the recording; that handler
        // runs on `window` in capture phase and stops propagation, so
        // this one never sees it).
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                e.preventDefault(); e.stopPropagation()
                close()
            }
        }
        document.addEventListener("keydown", handler, /* capture = */ true)
        keyHandler = handler

        renderList()
    }

    /** Tear down DOM + listeners and restore the registry. Idempotent. */
    fun close() {
        stopCapture()
        keyHandler?.let { document.removeEventListener("keydown", it, /* capture = */ true) }
        keyHandler = null
        backdropEl?.parentNode?.removeChild(backdropEl!!)
        backdropEl = null
        listEl = null
        captureRowEl = null
        errorEl = null
        HotkeyRegistry.suppressed = false
    }

    // ── DOM builders ────────────────────────────────────────────────

    private fun buildHeader(title: String): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "dt-hkcfg-header"

        val titleEl = document.createElement("div") as HTMLElement
        titleEl.className = "dt-hkcfg-title"
        titleEl.textContent = title
        header.appendChild(titleEl)

        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "dt-hkcfg-close"
        closeBtn.setAttribute("type", "button")
        closeBtn.title = "Close"
        closeBtn.setAttribute("aria-label", "Close")
        closeBtn.innerHTML = "&times;"
        closeBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault(); e.stopPropagation()
            close()
        })
        header.appendChild(closeBtn)
        return header
    }

    private fun buildBody(): HTMLElement {
        val body = document.createElement("div") as HTMLElement
        body.className = "dt-hkcfg-body"

        val hint = document.createElement("p") as HTMLElement
        hint.className = "dt-hkcfg-hint"
        hint.textContent =
            "Shortcuts for this action. Remove any you don't want, or add your own."
        body.appendChild(hint)

        val list = document.createElement("div") as HTMLElement
        list.className = "dt-hkcfg-list"
        body.appendChild(list)
        listEl = list

        val captureRow = document.createElement("div") as HTMLElement
        captureRow.className = "dt-hkcfg-capture-row"
        body.appendChild(captureRow)
        captureRowEl = captureRow

        val error = document.createElement("div") as HTMLElement
        error.className = "dt-hkcfg-error"
        body.appendChild(error)
        errorEl = error

        return body
    }

    private fun buildFooter(): HTMLElement {
        val footer = document.createElement("div") as HTMLElement
        footer.className = "dt-hkcfg-footer"

        val reset = document.createElement("button") as HTMLElement
        // Shared modal-button metrics + a quiet ghost variant (see stylesheet).
        reset.className = "dt-modal-btn dt-hkcfg-btn-ghost"
        reset.setAttribute("type", "button")
        reset.textContent = "Reset to defaults"
        reset.addEventListener("click", {
            stopCapture()
            working = HotkeyBindings.defaultChords(actionId).toMutableList()
            setError(null)
            renderList()
        })
        footer.appendChild(reset)

        val spacer = document.createElement("div") as HTMLElement
        spacer.className = "dt-hkcfg-footer-spacer"
        footer.appendChild(spacer)

        // Cancel/Save reuse ConfirmDialog's `.dt-modal-btn*` classes so they
        // pick up the theme (accent primary, bg/text cancel) with zero
        // dialog-specific colour rules.
        val cancel = document.createElement("button") as HTMLElement
        cancel.className = "dt-modal-btn dt-modal-btn-cancel"
        cancel.setAttribute("type", "button")
        cancel.textContent = "Cancel"
        cancel.addEventListener("click", { close() })
        footer.appendChild(cancel)

        val save = document.createElement("button") as HTMLElement
        save.className = "dt-modal-btn dt-modal-btn-confirm"
        save.setAttribute("type", "button")
        save.textContent = "Save"
        save.addEventListener("click", { commit() })
        footer.appendChild(save)

        return footer
    }

    /** Re-render the chord list + capture row from [working]. */
    private fun renderList() {
        val list = listEl ?: return
        list.innerHTML = ""
        val defaults = HotkeyBindings.defaultChords(actionId)

        if (working.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "dt-hkcfg-empty"
            empty.textContent = "No shortcuts — this action is unbound."
            list.appendChild(empty)
        }

        for ((index, chord) in working.withIndex()) {
            val row = document.createElement("div") as HTMLElement
            row.className = "dt-hkcfg-chord-row"

            val caps = document.createElement("span") as HTMLElement
            caps.className = "dt-hkcfg-chord"
            for (cap in chord.toChordLabel()) {
                val capEl = document.createElement("kbd") as HTMLElement
                capEl.className = "dt-hkcfg-kbd"
                capEl.textContent = cap
                caps.appendChild(capEl)
            }
            row.appendChild(caps)

            val pills = document.createElement("span") as HTMLElement
            pills.className = "dt-hkcfg-pills"
            pills.appendChild(buildPill("Mac", macOnly = false))
            if (isBrowserReservedChord(chord)) {
                // Browser-reserved: works in the Electron shell only.
                pills.appendChild(buildMacOnlyTag())
            } else {
                pills.appendChild(buildPill("Web", macOnly = false))
            }
            if (chord in defaults) {
                val def = document.createElement("span") as HTMLElement
                def.className = "dt-hkcfg-default-tag"
                def.textContent = "default"
                pills.appendChild(def)
            }
            row.appendChild(pills)

            val remove = document.createElement("button") as HTMLElement
            remove.className = "dt-hkcfg-remove"
            remove.setAttribute("type", "button")
            remove.title = "Remove this shortcut"
            remove.setAttribute("aria-label", "Remove this shortcut")
            remove.innerHTML = "&times;"
            remove.addEventListener("click", {
                working.removeAt(index)
                setError(null)
                renderList()
            })
            row.appendChild(remove)

            list.appendChild(row)
        }

        renderCaptureRow()
    }

    /** One availability pill ("Mac" / "Web"), muted outline style. */
    private fun buildPill(text: String, macOnly: Boolean): HTMLElement {
        val pill = document.createElement("span") as HTMLElement
        pill.className = if (macOnly) "dt-hkcfg-pill dt-hkcfg-pill-warn" else "dt-hkcfg-pill"
        pill.textContent = text
        return pill
    }

    /** The warn-tinted (`--t-warn`) "Mac only" tag for browser-reserved chords. */
    private fun buildMacOnlyTag(): HTMLElement {
        val tag = document.createElement("span") as HTMLElement
        tag.className = "dt-hkcfg-pill dt-hkcfg-pill-warn"
        tag.title = "Browsers reserve this key combination for themselves; " +
            "it only works in the Mac desktop app."
        tag.textContent = "Mac only"
        return tag
    }

    /** Render either the "Add shortcut" button or the live capture box. */
    private fun renderCaptureRow() {
        val row = captureRowEl ?: return
        row.innerHTML = ""
        if (!capturing) {
            val add = document.createElement("button") as HTMLElement
            add.className = "dt-hkcfg-add"
            add.setAttribute("type", "button")
            add.textContent = "+ Add shortcut"
            add.addEventListener("click", {
                setError(null)
                startCapture()
            })
            row.appendChild(add)
            return
        }
        val box = document.createElement("div") as HTMLElement
        box.className = "dt-hkcfg-capture-box"
        box.textContent = "Press a key combination… (Esc to cancel)"
        row.appendChild(box)
    }

    // ── capture ─────────────────────────────────────────────────────

    /**
     * Arm capture mode: window-level capture-phase key listeners that
     * pre-empt everything (including the browser's own page handlers)
     * so the pressed combo is recorded rather than executed. Modifier
     * keydowns update the live preview; the first non-modifier keydown
     * is the recorded chord.
     */
    private fun startCapture() {
        if (capturing) return
        capturing = true
        renderCaptureRow()

        val down: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            e.preventDefault(); e.stopPropagation()
            if (ke.key == "Escape") {
                stopCapture()
                renderCaptureRow()
                return@lambda
            }
            if (ke.key in MODIFIER_KEYS || ke.key == "Dead") {
                updateCapturePreview(ke)
                return@lambda
            }
            val chord = Hotkey(
                key = if (ke.key.length == 1) ke.key.lowercase() else ke.key,
                ctrl = ke.ctrlKey,
                alt = ke.altKey,
                shift = ke.shiftKey,
                meta = ke.metaKey,
            )
            recordChord(chord)
        }
        val up: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (!capturing) return@lambda
            updateCapturePreview(ke)
        }
        window.addEventListener("keydown", down, /* capture = */ true)
        window.addEventListener("keyup", up, /* capture = */ true)
        captureKeyDown = down
        captureKeyUp = up
    }

    /** Disarm capture mode (idempotent). */
    private fun stopCapture() {
        capturing = false
        captureKeyDown?.let { window.removeEventListener("keydown", it, /* capture = */ true) }
        captureKeyUp?.let { window.removeEventListener("keyup", it, /* capture = */ true) }
        captureKeyDown = null
        captureKeyUp = null
    }

    /** Show the currently-held modifiers in the capture box. */
    private fun updateCapturePreview(ke: KeyboardEvent) {
        val row = captureRowEl ?: return
        val box = row.firstElementChild as? HTMLElement ?: return
        val held = Hotkey(key = "", ctrl = ke.ctrlKey, alt = ke.altKey, shift = ke.shiftKey, meta = ke.metaKey)
        val caps = held.toChordLabel().dropLast(1) // drop the empty key cap
        box.textContent = if (caps.isEmpty()) {
            "Press a key combination… (Esc to cancel)"
        } else {
            caps.joinToString(" ") + " …"
        }
    }

    /**
     * Validate the captured [chord]; on success append it to [working]
     * and leave capture mode, otherwise show the reason and keep
     * listening so the user can try again.
     */
    private fun recordChord(chord: Hotkey) {
        val problem = validateChord(chord)
        if (problem != null) {
            setError(problem)
            return
        }
        stopCapture()
        working.add(chord)
        // Browser-reserved chords are accepted, so this is informational —
        // render it in the warn tint, not the danger tint used for rejections.
        setError(
            if (isBrowserReservedChord(chord)) {
                "Note: browsers reserve ${chord.toChordLabel().joinToString(" ")} for themselves — " +
                    "this shortcut will only work in the Mac app."
            } else null,
            isNotice = true,
        )
        renderList()
    }

    /**
     * Capture-time validation.
     *
     * @return a human-readable rejection reason, or `null` when [chord]
     *   is acceptable.
     */
    private fun validateChord(chord: Hotkey): String? {
        val isFunctionKey = chord.key.length >= 2 &&
            chord.key[0] == 'F' && chord.key.drop(1).toIntOrNull() != null
        if (!chord.ctrl && !chord.alt && !chord.meta && !isFunctionKey) {
            return "Add a modifier (Ctrl, Option/Alt or Cmd) — bare keys would interfere with typing."
        }
        if (chord in working) {
            return "That shortcut is already in the list."
        }
        HotkeyBindings.conflictLabel(chord, actionId)?.let {
            return "Already used by “$it” — remove it there first."
        }
        return null
    }

    /**
     * Show ([msg] != null) or clear the inline error/notice line.
     *
     * @param msg the message to show, or `null` to hide the line.
     * @param isNotice `true` renders in the theme's warn tint (informational,
     *   e.g. the browser-reserved note after a successful capture); `false`
     *   (the default) renders in the danger tint (a validation rejection).
     */
    private fun setError(msg: String?, isNotice: Boolean = false) {
        val el = errorEl ?: return
        el.textContent = msg ?: ""
        el.className = if (isNotice) "dt-hkcfg-error dt-hkcfg-error--notice" else "dt-hkcfg-error"
        el.style.display = if (msg == null) "none" else "block"
    }

    // ── commit ──────────────────────────────────────────────────────

    /**
     * Persist the working set through [HotkeyBindings.setCustomChords].
     * A working set equal to the defaults is stored as `null` ("not
     * customized") so a later change to the toolkit's defaults reaches
     * users who never customized the action.
     */
    private fun commit() {
        stopCapture()
        val defaults = HotkeyBindings.defaultChords(actionId)
        val custom: List<Hotkey>? = if (working == defaults) null else working.toList()
        HotkeyBindings.setCustomChords(actionId, custom)
        val cb = onSaved
        close()
        cb()
    }

    private val MODIFIER_KEYS = setOf("Control", "Alt", "Shift", "Meta", "OS")

    // ── stylesheet ──────────────────────────────────────────────────

    private fun ensureStylesheet() {
        val id = "dt-hkcfg-style"
        if (document.getElementById(id) != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = id
        style.textContent = STYLESHEET
        document.head?.appendChild(style)
    }

    // Theme-aware chrome via the toolkit's flat `--t-*` tokens (--t-surface,
    // --t-text, --t-border, --t-warn, --t-danger, … — the exact names
    // ThemeCssVars.toCssVarMap writes), matching the .dt-modal-* rules in
    // lunula.css. Fallback values after each token mirror the
    // toolkit stylesheet's own fallbacks and only apply when no theme has
    // been painted at all. The footer buttons carry the shared
    // `.dt-modal-btn*` classes and need no colour rules here. Sits one
    // z-layer above the cheatsheet so the editor can be opened on top of
    // other panels.
    private const val STYLESHEET: String = """
        .dt-hkcfg-backdrop {
            position: fixed; inset: 0;
            background: rgba(0, 0, 0, 0.45);
            z-index: 2147483641;
            display: flex; align-items: center; justify-content: center;
        }
        .dt-hkcfg-panel {
            width: min(480px, 92vw);
            max-height: 80vh;
            display: flex; flex-direction: column;
            background: var(--t-surface, #1e1e1e);
            color: var(--t-text, #e6e6e6);
            border: 1px solid var(--t-border, rgba(255,255,255,0.18));
            border-radius: 10px;
            box-shadow: 0 28px 72px rgba(0, 0, 0, 0.65), 0 10px 24px rgba(0, 0, 0, 0.45);
            overflow: hidden;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        }
        .dt-hkcfg-header {
            display: flex; align-items: center; gap: 12px;
            padding: 10px 14px;
            border-bottom: 1px solid var(--t-border, rgba(255,255,255,0.08));
        }
        .dt-hkcfg-title { font-size: 14px; font-weight: 600; opacity: 0.9; margin-right: auto; }
        .dt-hkcfg-close {
            background: transparent; border: none; color: inherit;
            font-size: 22px; line-height: 1; padding: 0 4px;
            cursor: pointer; opacity: 0.7;
        }
        .dt-hkcfg-close:hover { opacity: 1; }
        .dt-hkcfg-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 12px 14px; }
        .dt-hkcfg-hint { margin: 0 0 10px; font-size: 12px; opacity: 0.6; }
        .dt-hkcfg-list { display: flex; flex-direction: column; gap: 6px; }
        .dt-hkcfg-empty { font-size: 12px; opacity: 0.55; font-style: italic; padding: 4px 2px; }
        .dt-hkcfg-chord-row {
            display: flex; align-items: center; gap: 10px;
            padding: 6px 8px; border-radius: 6px;
            background: var(--t-surface-alt, rgba(255,255,255,0.04));
        }
        .dt-hkcfg-chord { display: inline-flex; gap: 4px; align-items: center; }
        .dt-hkcfg-kbd {
            display: inline-flex; align-items: center; justify-content: center;
            min-width: 22px; padding: 2px 6px;
            font-size: 11px; line-height: 1.4;
            color: var(--t-text, #e6e6e6);
            background: var(--t-surface-alt, rgba(255,255,255,0.08));
            border: 1px solid var(--t-border, rgba(255,255,255,0.18));
            border-bottom-width: 2px;
            border-radius: 4px;
        }
        .dt-hkcfg-pills { display: inline-flex; gap: 4px; align-items: center; margin-right: auto; }
        .dt-hkcfg-pill {
            font-size: 10px; line-height: 1;
            padding: 3px 7px; border-radius: 999px;
            border: 1px solid var(--t-border, rgba(255,255,255,0.18));
            opacity: 0.7;
        }
        .dt-hkcfg-pill-warn {
            border-color: color-mix(in srgb, var(--t-warn, #FFD60A) 65%, transparent);
            color: var(--t-warn, #FFD60A);
            opacity: 0.95;
        }
        .dt-hkcfg-default-tag { font-size: 10px; opacity: 0.45; font-style: italic; }
        .dt-hkcfg-remove {
            background: transparent; border: none; color: inherit;
            font-size: 16px; line-height: 1; padding: 0 4px;
            cursor: pointer; opacity: 0.55;
        }
        .dt-hkcfg-remove:hover { opacity: 1; }
        .dt-hkcfg-capture-row { margin-top: 10px; }
        .dt-hkcfg-capture-box {
            padding: 10px 12px;
            border: 1px dashed var(--t-accent, #ff8a3d);
            border-radius: 6px;
            font-size: 12px; opacity: 0.85;
            background: var(--t-surface-alt, rgba(255,255,255,0.04));
        }
        .dt-hkcfg-error {
            display: none;
            margin-top: 8px;
            font-size: 12px;
            color: var(--t-danger, #e5534b);
        }
        .dt-hkcfg-error--notice {
            color: var(--t-warn, #FFD60A);
        }
        .dt-hkcfg-footer {
            display: flex; align-items: center; gap: 8px;
            padding: 10px 14px;
            border-top: 1px solid var(--t-border, rgba(255,255,255,0.08));
        }
        .dt-hkcfg-footer-spacer { flex: 1 1 auto; }
        .dt-hkcfg-btn-ghost {
            background: transparent;
            border-color: transparent;
            color: var(--t-text-dim, #b8b8b8);
        }
        .dt-hkcfg-btn-ghost:hover {
            background: var(--t-surface-alt, rgba(255,255,255,0.06));
        }
        .dt-hkcfg-add {
            width: 100%; text-align: center;
            font-size: 12px; line-height: 1;
            padding: 8px 12px; border-radius: 6px;
            font-family: inherit;
            color: var(--t-text, #e6e6e6);
            background: transparent;
            border: 1px dashed var(--t-border, rgba(255,255,255,0.25));
            cursor: pointer;
        }
        .dt-hkcfg-add:hover { background: var(--t-surface-alt, rgba(255,255,255,0.06)); }
    """
}
