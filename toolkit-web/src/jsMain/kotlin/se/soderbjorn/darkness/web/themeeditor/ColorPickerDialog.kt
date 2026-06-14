/**
 * Per-scheme colour picker editor pane: dark/light fg+bg pickers plus a
 * flat per-token list of semantic overrides (one row per token × appearance).
 * Default schemes are read-only with a "Clone to edit" CTA.
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.escapeHtml
import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * Right-hand editor for a single colour scheme. Default schemes are
 * displayed read-only with a prominent "Clone to edit" CTA; custom
 * schemes expose dark/light fg+bg pickers plus a flat list of semantic
 * override tokens (each editable for both appearance modes).
 */
internal fun renderSchemeEditor(
    container: HTMLElement,
    selectedName: String?,
    refresh: () -> Unit,
) {
    container.innerHTML = ""
    val state = themeManagerHost()
    val name = selectedName ?: return
    val custom = state.customSchemes[name]
    val source = custom?.toColorScheme() ?: recommendedColorSchemes.firstOrNull { it.name == name }
    if (source == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "dt-theme-manager-empty"
        empty.textContent = "Scheme not found."
        container.appendChild(empty)
        return
    }
    val isDefault = custom == null
    isEditorDirty = false
    var syncActionsEnabled: () -> Unit = {}
    val markDirty: () -> Unit = {
        isEditorDirty = true
        syncActionsEnabled()
    }

    val header = document.createElement("div") as HTMLElement
    header.className = "dt-theme-editor-header"

    val preview = document.createElement("div") as HTMLElement
    preview.className = "dt-theme-editor-preview dt-scheme-preview"
    preview.innerHTML = themeManagerHost().renderThemeSwatchHtml(source)
    header.appendChild(preview)

    val info = document.createElement("div") as HTMLElement
    info.className = "dt-theme-editor-info"

    val nameLabel = document.createElement("div") as HTMLElement
    nameLabel.className = "dt-theme-editor-label"
    nameLabel.textContent = "Name"
    info.appendChild(nameLabel)

    val nameInput = document.createElement("input") as HTMLInputElement
    nameInput.className = "dt-theme-editor-input"
    nameInput.type = "text"
    nameInput.value = source.name
    nameInput.disabled = isDefault
    nameInput.addEventListener("input", { _: Event -> markDirty() })
    info.appendChild(nameInput)

    header.appendChild(info)
    container.appendChild(header)

    val fgbg = document.createElement("div") as HTMLElement
    fgbg.className = "dt-scheme-editor-fgbg"
    fgbg.innerHTML = ""

    data class ColorField(val label: String, val getter: () -> String, val setter: (String) -> Unit)

    val mutable = object {
        var darkFg = source.darkFg
        var lightFg = source.lightFg
        var darkBg = source.darkBg
        var lightBg = source.lightBg
        val overrides = (source.overrides ?: emptyMap()).toMutableMap()
    }

    var refreshDerivedSwatches: () -> Unit = {}

    val fields = listOf(
        ColorField("Dark background", { mutable.darkBg }, { mutable.darkBg = it }),
        ColorField("Dark foreground", { mutable.darkFg }, { mutable.darkFg = it }),
        ColorField("Light background", { mutable.lightBg }, { mutable.lightBg = it }),
        ColorField("Light foreground", { mutable.lightFg }, { mutable.lightFg = it }),
    )
    for (f in fields) {
        val row = document.createElement("div") as HTMLElement
        row.className = "dt-scheme-editor-fgbg-row"
        val lbl = document.createElement("label") as HTMLElement
        lbl.className = "dt-scheme-editor-fgbg-label"
        lbl.textContent = f.label
        row.appendChild(lbl)
        val picker = document.createElement("input") as HTMLInputElement
        picker.className = "dt-scheme-editor-fgbg-picker"
        picker.type = "color"
        picker.value = normalizeHex(f.getter())
        picker.disabled = isDefault
        picker.addEventListener("input", { _: Event ->
            f.setter(picker.value)
            markDirty()
            refreshDerivedSwatches()
        })
        row.appendChild(picker)
        fgbg.appendChild(row)
    }
    container.appendChild(fgbg)

    val ovrSection = document.createElement("div") as HTMLElement
    ovrSection.className = "dt-scheme-editor-overrides"
    val ovrTitle = document.createElement("div") as HTMLElement
    ovrTitle.className = "dt-scheme-editor-overrides-title"
    ovrTitle.textContent = "Semantic overrides"
    ovrSection.appendChild(ovrTitle)

    if (isDefault) {
        val hint = document.createElement("div") as HTMLElement
        hint.className = "dt-theme-editor-hint"
        hint.textContent = "Clone to edit overrides."
        ovrSection.appendChild(hint)
    } else {
        val hint = document.createElement("div") as HTMLElement
        hint.className = "dt-theme-editor-hint"
        hint.textContent =
            "Every swatch shows the colour that will be used. Swatches with a " +
                "highlighted border are explicit overrides — click × to clear " +
                "them and let the value derive from the scheme's fg/bg live."
        ovrSection.appendChild(hint)

        // Float tokens with at least one override (dark or light) to the top,
        // preserving relative order within each group. Sorted once at open
        // time — rows stay stable while the user edits, so clearing an
        // override doesn't cause the row to jump.
        val tokens = OVERRIDE_TOKENS.sortedByDescending { tok ->
            mutable.overrides.containsKey("$tok.dark") ||
                mutable.overrides.containsKey("$tok.light")
        }
        val grid = document.createElement("div") as HTMLElement
        grid.className = "dt-scheme-editor-overrides-grid"

        val head = document.createElement("div") as HTMLElement
        head.className = "dt-scheme-editor-overrides-head"
        head.innerHTML = "<span>Token</span><span>Dark</span><span>Light</span>"
        grid.appendChild(head)

        data class PickerSlot(val tok: String, val isDark: Boolean, val input: HTMLInputElement)
        val slots = mutableListOf<PickerSlot>()

        refreshDerivedSwatches = run@{
            val themeNow = ColorScheme(
                name = source.name,
                darkFg = mutable.darkFg,
                lightFg = mutable.lightFg,
                darkBg = mutable.darkBg,
                lightBg = mutable.lightBg,
                overrides = mutable.overrides.toMap().ifEmpty { null },
            )
            val darkPal = themeNow.resolve(isDark = true)
            val lightPal = themeNow.resolve(isDark = false)
            val darkBgArgb = hexToArgb(mutable.darkBg)
            val lightBgArgb = hexToArgb(mutable.lightBg)
            for (slot in slots) {
                if (slot.input.getAttribute("data-set") == "1") continue
                val pal = if (slot.isDark) darkPal else lightPal
                val argb = derivedTokenValue(pal, slot.tok) ?: continue
                val bg = if (slot.isDark) darkBgArgb else lightBgArgb
                slot.input.value = flattenHexForPicker(argb, bg)
            }
        }

        for (tok in tokens) {
            val row = document.createElement("div") as HTMLElement
            row.className = "dt-scheme-editor-overrides-row"

            val lbl = document.createElement("span") as HTMLElement
            lbl.className = "dt-scheme-editor-overrides-label"
            lbl.textContent = tok
            row.appendChild(lbl)

            // Each appearance (dark/light) gets its own picker + × pair so
            // the user can clear one side without disturbing the other.
            fun cell(mode: String): Pair<HTMLElement, HTMLInputElement> {
                val key = "$tok.$mode"
                val wrap = document.createElement("div") as HTMLElement
                wrap.className = "dt-scheme-editor-overrides-cell"

                val p = document.createElement("input") as HTMLInputElement
                p.type = "color"
                p.className = "dt-scheme-editor-overrides-picker"
                val cur = mutable.overrides[key]
                if (cur != null) {
                    p.value = argbToHex(cur)
                    p.setAttribute("data-set", "1")
                }

                val clear = document.createElement("button") as HTMLElement
                clear.className = "dt-scheme-editor-overrides-reset"
                clear.innerHTML = "&times;"
                clear.title = "Clear the $mode override for this token — the " +
                    "swatch will derive from the scheme's fg/bg"

                fun syncClearEnabled() {
                    val active = p.getAttribute("data-set") == "1"
                    clear.asDynamic().disabled = !active
                    clear.classList.toggle("dt-inactive", !active)
                }

                p.addEventListener("input", { _: Event ->
                    val argb = 0xFF000000L or hexToArgb(p.value).and(0x00FFFFFFL)
                    mutable.overrides[key] = argb
                    p.setAttribute("data-set", "1")
                    syncClearEnabled()
                    markDirty()
                    // Overriding one token can cascade to tokens whose
                    // derivation references it (e.g. text.tertiary →
                    // syntax.comment), so re-derive every unset swatch.
                    refreshDerivedSwatches()
                })
                clear.addEventListener("click", {
                    mutable.overrides.remove(key)
                    p.removeAttribute("data-set")
                    syncClearEnabled()
                    markDirty()
                    refreshDerivedSwatches()
                })

                syncClearEnabled()
                wrap.appendChild(p)
                wrap.appendChild(clear)
                return wrap to p
            }

            val (darkCell, darkPick) = cell("dark")
            row.appendChild(darkCell)
            val (lightCell, lightPick) = cell("light")
            row.appendChild(lightCell)
            slots += PickerSlot(tok, isDark = true, input = darkPick)
            slots += PickerSlot(tok, isDark = false, input = lightPick)

            grid.appendChild(row)
        }
        ovrSection.appendChild(grid)
        refreshDerivedSwatches()
    }
    container.appendChild(ovrSection)

    val actions = document.createElement("div") as HTMLElement
    actions.className = "dt-theme-editor-actions"

    if (isDefault) {
        val cloneHint = document.createElement("div") as HTMLElement
        cloneHint.className = "dt-theme-editor-hint"
        cloneHint.textContent = "Default schemes are read-only. Clone to edit."
        actions.appendChild(cloneHint)

        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "dt-theme-editor-btn dt-primary"
        cloneBtn.textContent = "Clone to edit"
        cloneBtn.addEventListener("click", { showCloneSchemePrompt(source) })
        actions.appendChild(cloneBtn)
    } else {
        val saveBtn = document.createElement("button") as HTMLElement
        saveBtn.className = "dt-theme-editor-btn dt-primary"
        saveBtn.textContent = "Save"
        saveBtn.addEventListener("click", { _: Event ->
            val newName = nameInput.value.trim()
            if (newName.isEmpty()) return@addEventListener
            val next = CustomScheme(
                name = newName,
                darkFg = mutable.darkFg,
                lightFg = mutable.lightFg,
                darkBg = mutable.darkBg,
                lightBg = mutable.lightBg,
                overrides = mutable.overrides.toMap(),
            )
            GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (newName != source.name) {
                    themeManagerHost().deleteCustomScheme(source.name)
                }
                themeManagerHost().saveCustomScheme(next)
            }
            isEditorDirty = false
            pokeManager()
        })
        actions.appendChild(saveBtn)

        val revertBtn = document.createElement("button") as HTMLElement
        revertBtn.className = "dt-theme-editor-btn"
        revertBtn.textContent = "Revert"
        revertBtn.addEventListener("click", {
            isEditorDirty = false
            refresh()
        })
        actions.appendChild(revertBtn)

        syncActionsEnabled = {
            val clean = !isEditorDirty
            console.log("[theme-editor] syncActionsEnabled scheme clean=$clean isEditorDirty=$isEditorDirty")
            saveBtn.asDynamic().disabled = clean
            revertBtn.asDynamic().disabled = clean
            saveBtn.classList.toggle("dt-disabled", clean)
            revertBtn.classList.toggle("dt-disabled", clean)
        }
        syncActionsEnabled()

        val deleteBtn = document.createElement("button") as HTMLElement
        deleteBtn.className = "dt-theme-editor-btn dt-danger"
        deleteBtn.textContent = "Delete"
        deleteBtn.addEventListener("click", {
            showConfirmDialog(
                title = "Delete scheme",
                message = "Delete colour scheme <b>${escapeHtml(source.name)}</b>?",
                confirmLabel = "Delete",
                messageIsHtml = true,
                onConfirm = {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        themeManagerHost().deleteCustomScheme(source.name)
                    }
                    isEditorDirty = false
                    pokeManager()
                },
            )
        })
        actions.appendChild(deleteBtn)
    }

    container.appendChild(actions)
}

/**
 * Flat list of semantic token keys used in the scheme editor's overrides
 * grid. Every entry is an override key that [ColorScheme.resolve] reads,
 * so edits in the grid always round-trip.
 */
private val OVERRIDE_TOKENS: List<String> = listOf(
    "surface.base", "surface.raised", "surface.sunken", "surface.overlay",
    "text.primary", "text.secondary", "text.tertiary", "text.disabled", "text.inverse",
    "border.subtle", "border.default", "border.strong", "border.focus", "border.focusGlow",
    "accent.primary", "accent.primarySoft", "accent.primaryGlow", "accent.onPrimary",
    "semantic.danger", "semantic.warn", "semantic.success", "semantic.info",
    "terminal.bg", "terminal.fg", "terminal.cursor", "terminal.selection", "terminal.selectionText",
    "chrome.titlebar", "chrome.titleText", "chrome.border", "chrome.shadow",
    "chrome.closeDot", "chrome.minDot", "chrome.maxDot",
    "sidebar.bg", "sidebar.text", "sidebar.textDim", "sidebar.activeBg", "sidebar.activeText",
    "diff.addBg", "diff.addFg", "diff.addGutter",
    "diff.removeBg", "diff.removeFg", "diff.removeGutter", "diff.contextFg",
    "syntax.keyword", "syntax.string", "syntax.number", "syntax.comment",
    "syntax.function", "syntax.type", "syntax.operator", "syntax.constant",
)

/**
 * Returns the derived value of [tok] in [palette], or null when [tok] is
 * not a known semantic token. Used by the scheme editor to show what the
 * resolver would produce for any token that isn't explicitly overridden.
 */
private fun derivedTokenValue(palette: ResolvedPalette, tok: String): Long? = when (tok) {
    "surface.base" -> palette.surface.base
    "surface.raised" -> palette.surface.raised
    "surface.sunken" -> palette.surface.sunken
    "surface.overlay" -> palette.surface.overlay
    "text.primary" -> palette.text.primary
    "text.secondary" -> palette.text.secondary
    "text.tertiary" -> palette.text.tertiary
    "text.disabled" -> palette.text.disabled
    "text.inverse" -> palette.text.inverse
    "border.subtle" -> palette.border.subtle
    "border.default" -> palette.border.default
    "border.strong" -> palette.border.strong
    "border.focus" -> palette.border.focus
    "border.focusGlow" -> palette.border.focusGlow
    "accent.primary" -> palette.accent.primary
    "accent.primarySoft" -> palette.accent.primarySoft
    "accent.primaryGlow" -> palette.accent.primaryGlow
    "accent.onPrimary" -> palette.accent.onPrimary
    "semantic.danger" -> palette.semantic.danger
    "semantic.warn" -> palette.semantic.warn
    "semantic.success" -> palette.semantic.success
    "semantic.info" -> palette.semantic.info
    "terminal.bg" -> palette.terminal.bg
    "terminal.fg" -> palette.terminal.fg
    "terminal.cursor" -> palette.terminal.cursor
    "terminal.selection" -> palette.terminal.selection
    "terminal.selectionText" -> palette.terminal.selectionText
    "chrome.titlebar" -> palette.chrome.titlebar
    "chrome.titleText" -> palette.chrome.titleText
    "chrome.border" -> palette.chrome.border
    "chrome.shadow" -> palette.chrome.shadow
    "chrome.closeDot" -> palette.chrome.closeDot
    "chrome.minDot" -> palette.chrome.minDot
    "chrome.maxDot" -> palette.chrome.maxDot
    "sidebar.bg" -> palette.sidebar.bg
    "sidebar.text" -> palette.sidebar.text
    "sidebar.textDim" -> palette.sidebar.textDim
    "sidebar.activeBg" -> palette.sidebar.activeBg
    "sidebar.activeText" -> palette.sidebar.activeText
    "diff.addBg" -> palette.diff.addBg
    "diff.addFg" -> palette.diff.addFg
    "diff.addGutter" -> palette.diff.addGutter
    "diff.removeBg" -> palette.diff.removeBg
    "diff.removeFg" -> palette.diff.removeFg
    "diff.removeGutter" -> palette.diff.removeGutter
    "diff.contextFg" -> palette.diff.contextFg
    "syntax.keyword" -> palette.syntax.keyword
    "syntax.string" -> palette.syntax.string
    "syntax.number" -> palette.syntax.number
    "syntax.comment" -> palette.syntax.comment
    "syntax.function" -> palette.syntax.function
    "syntax.type" -> palette.syntax.type
    "syntax.operator" -> palette.syntax.operator
    "syntax.constant" -> palette.syntax.constant
    else -> null
}

/**
 * Flatten an ARGB value that may carry partial alpha to an opaque hex
 * string suitable for an `<input type=color>` swatch, compositing against
 * [bg] when the source is translucent. Some derived tokens
 * (e.g. `border.subtle`, `terminal.selection`) use alpha to tint against
 * the background — showing their raw RGB would misrepresent how they
 * render in the app, so we pre-composite for display only.
 */
private fun flattenHexForPicker(argb: Long, bg: Long): String {
    val alphaByte = ((argb shr 24) and 0xFF).toInt()
    if (alphaByte == 0xFF) return argbToHex(argb)
    val alphaF = alphaByte / 255.0
    return argbToHex(mixColors(bg, argb, alphaF))
}

/** Normalise any hex colour string to `#rrggbb` for the native colour picker. */
private fun normalizeHex(raw: String): String {
    if (raw.startsWith("#") && raw.length == 7) return raw
    if (raw.startsWith("#") && raw.length == 4) {
        val r = raw[1]; val g = raw[2]; val b = raw[3]
        return "#$r$r$g$g$b$b"
    }
    return "#000000"
}
