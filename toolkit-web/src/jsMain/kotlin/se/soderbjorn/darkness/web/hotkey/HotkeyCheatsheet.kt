/*
 * HotkeyCheatsheet.kt (jsMain)
 *
 * Toolkit-owned cheatsheet modal shell. Apps build a content-agnostic
 * [HotkeysModalSpec] (groups → entries → chord caps) and hand it to
 * [ToolkitHotkeysModal]; the modal owns the DOM, stylesheet, escape /
 * outside-click dismissal, and the `Cmd/Ctrl+/` registration through
 * [installCheatsheetHotkey].
 *
 * Why content-agnostic: each darkness app has its own canonical list
 * of user-facing chords (notegrow's outline-zoom set, termtastic's
 * dialog conventions, …). The toolkit can't know what those are. By
 * accepting a hand-curated [HotkeysModalSpec] from the app we keep the
 * cheatsheet labelled in app-native vocabulary while still sharing the
 * shell across apps.
 *
 * commonMain rules don't apply here — this is jsMain only.
 */
package se.soderbjorn.darkness.web.hotkey

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * One row in the cheatsheet.
 *
 * @property label human-readable description of what the chord does.
 * @property chord ordered list of cap labels — modifiers first, key
 *   last. Apps typically build these via [Hotkey.toChordLabel] for the
 *   four [StandardHotkeys] and hand-write the rest.
 * @property iconSvg optional inline SVG (16x16, currentColor stroke)
 *   shown as a leading icon. `null` to skip the icon column for this
 *   row (the row still aligns with neighbouring rows that have icons).
 */
data class HotkeyEntry(
    val label: String,
    val chord: List<String>,
    val iconSvg: String? = null,
)

/**
 * Ready-made cheat-sheet row for the positional tab-switch shortcut the
 * shell installs (Cmd/Ctrl+1..9, where 9 jumps to the last tab — see
 * `StandardHotkeys.tabSwitchHotkey`).
 *
 * Apps add this to their "Windows & tabs" [HotkeyGroup] so the
 * toolkit-owned chord is advertised with a consistent label across every
 * darkness app, instead of each hand-writing the same row. The chord is
 * a digit *range* (`1…9`) rather than a single key, so the label is built
 * here rather than via [Hotkey.toChordLabel]; the modifier glyph follows
 * the platform ([isMacPlatform]).
 *
 * The underlying behaviour is Electron-only (a browser owns this chord —
 * see [isElectronPlatform]), so apps should include this row only when
 * running in the desktop shell.
 *
 * @return the cheat-sheet entry describing the tab-switch chord.
 */
fun tabSwitchHotkeyEntry(): HotkeyEntry = HotkeyEntry(
    label = "Switch to tab 1–9 (9 = last)",
    chord = listOf(if (isMacPlatform()) "⌘" else "Ctrl", "1…9"),
)

/**
 * A titled group of [HotkeyEntry] rows, rendered as one section in the
 * modal. Groups appear in the order supplied to [HotkeysModalSpec].
 */
data class HotkeyGroup(
    val title: String,
    val entries: List<HotkeyEntry>,
)

/**
 * Full content for one render of [ToolkitHotkeysModal]. Apps construct
 * one and call [ToolkitHotkeysModal.setContent]; the modal stores the
 * spec and re-renders its panel from it on each [ToolkitHotkeysModal.open].
 *
 * @property groups groups in display order.
 * @property footerNote optional small-print line shown at the bottom
 *   (e.g. "Editor chords only fire while a pane is focused").
 */
data class HotkeysModalSpec(
    val groups: List<HotkeyGroup>,
    val footerNote: String? = null,
)

/**
 * Modal shell. Apps construct one at boot, push content via
 * [setContent], and bind `Cmd/Ctrl+/` to it via [installCheatsheetHotkey].
 *
 * The instance is reused across opens — calling [open] while already
 * open is idempotent (it re-mounts so theme / font changes take effect).
 */
class ToolkitHotkeysModal {
    private var backdropEl: HTMLElement? = null
    private var documentKeyDownHandler: ((Event) -> Unit)? = null
    private var spec: HotkeysModalSpec = HotkeysModalSpec(groups = emptyList())

    /** `true` while the modal is mounted. */
    val isOpen: Boolean get() = backdropEl != null

    /**
     * Replace the modal's content. Re-rendered on the next [open]; if
     * the modal is currently open, calling [setContent] doesn't
     * re-render — close and re-open if you need to reflect a mid-view
     * content change (apps rarely need this; content is set once at boot).
     */
    fun setContent(spec: HotkeysModalSpec) {
        this.spec = spec
    }

    /** Open the modal. If already open, the previous instance is closed
     *  first so a re-trigger always lands on a fresh panel. */
    fun open() {
        ensureStylesheet()
        if (backdropEl != null) closeInternal()

        val backdrop = buildBackdrop()
        val panel = buildPanel()
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)
        backdropEl = backdrop

        attachEscDismiss()
    }

    /** Close the modal. Idempotent. */
    fun close() {
        closeInternal()
    }

    // ── DOM ─────────────────────────────────────────────────────────

    private fun buildBackdrop(): HTMLElement {
        val b = document.createElement("div") as HTMLElement
        b.className = "dt-hotkeys-backdrop"
        b.addEventListener("mousedown", { e ->
            if ((e as MouseEvent).target === b) {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        })
        return b
    }

    private fun buildPanel(): HTMLElement {
        val panel = document.createElement("div") as HTMLElement
        panel.className = "dt-hotkeys-panel"
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        panel.setAttribute("aria-label", "Keyboard shortcuts")

        panel.appendChild(buildHeader())
        panel.appendChild(buildBody())
        spec.footerNote?.let { panel.appendChild(buildFooter(it)) }
        return panel
    }

    private fun buildHeader(): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "dt-hotkeys-header"

        val title = document.createElement("div") as HTMLElement
        title.className = "dt-hotkeys-title"
        title.textContent = "Keyboard shortcuts"
        header.appendChild(title)

        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "dt-hotkeys-close"
        closeBtn.setAttribute("type", "button")
        closeBtn.title = "Close"
        closeBtn.setAttribute("aria-label", "Close")
        closeBtn.innerHTML = "&times;"
        closeBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            closeInternal()
        })
        header.appendChild(closeBtn)
        return header
    }

    private fun buildBody(): HTMLElement {
        val body = document.createElement("div") as HTMLElement
        body.className = "dt-hotkeys-body"
        for (group in spec.groups) {
            val section = document.createElement("section") as HTMLElement
            section.className = "dt-hotkeys-group"
            val groupTitle = document.createElement("div") as HTMLElement
            groupTitle.className = "dt-hotkeys-group-title"
            groupTitle.textContent = group.title
            section.appendChild(groupTitle)
            val list = document.createElement("div") as HTMLElement
            list.className = "dt-hotkeys-list"
            for (entry in group.entries) list.appendChild(buildEntryRow(entry))
            section.appendChild(list)
            body.appendChild(section)
        }
        return body
    }

    private fun buildFooter(note: String): HTMLElement {
        val foot = document.createElement("div") as HTMLElement
        foot.className = "dt-hotkeys-footer"
        foot.textContent = note
        return foot
    }

    private fun buildEntryRow(entry: HotkeyEntry): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        row.className = "dt-hotkeys-row"

        val icon = document.createElement("span") as HTMLElement
        icon.className = "dt-hotkeys-icon"
        if (entry.iconSvg != null) icon.innerHTML = entry.iconSvg
        row.appendChild(icon)

        val label = document.createElement("span") as HTMLElement
        label.className = "dt-hotkeys-label"
        label.textContent = entry.label
        row.appendChild(label)

        val chord = document.createElement("span") as HTMLElement
        chord.className = "dt-hotkeys-chord"
        for (cap in entry.chord) {
            val capEl = document.createElement("kbd") as HTMLElement
            capEl.className = "dt-hotkeys-kbd"
            capEl.textContent = cap
            chord.appendChild(capEl)
        }
        row.appendChild(chord)
        return row
    }

    // ── lifecycle ───────────────────────────────────────────────────

    private fun closeInternal() {
        backdropEl?.parentNode?.removeChild(backdropEl!!)
        backdropEl = null
        detachEscDismiss()
    }

    private fun attachEscDismiss() {
        val handler: (Event) -> Unit = lambda@ { e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        }
        documentKeyDownHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachEscDismiss() {
        documentKeyDownHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyDownHandler = null
    }

    private fun ensureStylesheet() {
        val id = "dt-hotkeys-style"
        if (document.getElementById(id) != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = id
        style.textContent = STYLESHEET
        document.head?.appendChild(style)
    }

    private companion object {
        // Theme-aware modal chrome — uses the toolkit's standard
        // `--t-*` variables so the cheatsheet picks up whichever theme
        // the host is rendering. Keep var names in sync with darkness-toolkit.css.
        const val STYLESHEET: String = """
            .dt-hotkeys-backdrop {
                position: fixed; inset: 0;
                background: rgba(0, 0, 0, 0.45);
                z-index: 2147483640;
                display: flex; align-items: center; justify-content: center;
            }
            .dt-hotkeys-panel {
                width: min(640px, 92vw);
                max-height: 85vh;
                display: flex; flex-direction: column;
                background: var(--t-surface-base, #1e1e1e);
                color: var(--t-text-primary, #e6e6e6);
                border: 1px solid var(--t-border-strong, rgba(255,255,255,0.18));
                border-radius: 10px;
                box-shadow: 0 28px 72px rgba(0, 0, 0, 0.65), 0 10px 24px rgba(0, 0, 0, 0.45);
                overflow: hidden;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            }
            .dt-hotkeys-header {
                display: flex; align-items: center; gap: 12px;
                padding: 10px 12px;
                border-bottom: 1px solid var(--t-border-subtle, rgba(255,255,255,0.08));
            }
            .dt-hotkeys-title {
                font-size: 14px; font-weight: 600;
                opacity: 0.9; margin-right: auto;
            }
            .dt-hotkeys-close {
                background: transparent; border: none; color: inherit;
                font-size: 22px; line-height: 1; padding: 0 4px;
                cursor: pointer; opacity: 0.7;
            }
            .dt-hotkeys-close:hover { opacity: 1; }
            .dt-hotkeys-body {
                flex: 1 1 auto; min-height: 0; overflow-y: auto;
                padding: 8px 4px 14px;
            }
            .dt-hotkeys-group { padding: 6px 14px 4px; }
            .dt-hotkeys-group-title {
                font-size: 11px; font-weight: 600;
                letter-spacing: 0.08em; text-transform: uppercase;
                opacity: 0.55; padding: 10px 4px 6px;
            }
            .dt-hotkeys-list { display: flex; flex-direction: column; }
            .dt-hotkeys-row {
                display: grid;
                grid-template-columns: 22px 1fr auto;
                align-items: center; gap: 12px;
                padding: 7px 6px; border-radius: 6px;
            }
            .dt-hotkeys-row:hover {
                background: var(--t-surface-overlay, rgba(255,255,255,0.04));
            }
            .dt-hotkeys-icon {
                display: inline-flex;
                width: 16px; height: 16px;
                opacity: 0.78; justify-content: center;
            }
            .dt-hotkeys-label { font-size: 13px; opacity: 0.92; }
            .dt-hotkeys-chord { display: inline-flex; gap: 4px; align-items: center; }
            .dt-hotkeys-kbd {
                display: inline-flex; align-items: center; justify-content: center;
                min-width: 22px; padding: 2px 6px;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                font-size: 11px; line-height: 1;
                color: var(--t-text-primary, #e6e6e6);
                background: var(--t-surface-overlay, rgba(255,255,255,0.08));
                border: 1px solid var(--t-border-subtle, rgba(255,255,255,0.18));
                border-bottom-width: 2px;
                border-radius: 4px;
            }
            .dt-hotkeys-footer {
                padding: 8px 18px 12px;
                font-size: 11px;
                opacity: 0.55;
                border-top: 1px solid var(--t-border-subtle, rgba(255,255,255,0.06));
            }
        """
    }
}

/**
 * Bind `Cmd/Ctrl+/` to `modal.open()` via [HotkeyRegistry].
 *
 * Idempotent — calling twice replaces the binding (registry has
 * replace-on-register semantics). On macOS the chord uses Cmd
 * (`meta = true`); elsewhere it uses Ctrl. Apps that prefer a
 * different chord can skip this helper and call
 * [HotkeyRegistry.register] directly.
 *
 * @param modal the modal to open when the chord fires.
 */
fun installCheatsheetHotkey(modal: ToolkitHotkeysModal) {
    // Match the platform convention: Cmd-/ on macOS, Ctrl-/ elsewhere.
    val isMac = run {
        val ua = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''") as String
        ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
    }
    val chord = if (isMac) {
        Hotkey(key = "/", meta = true)
    } else {
        Hotkey(key = "/", ctrl = true)
    }
    HotkeyRegistry.register(chord) { modal.open() }
}
