/**
 * Flat 20-token colour viewer/editor for a single [Theme].
 *
 * Replaces the old seed-based `ThemeEditor` + `ColorPickerDialog`: the new
 * theme model stores every one of its 20 editable semantic tokens as a literal
 * hex value, so the editor is simply one `<input type="color">` per token.
 *
 * The same view serves two modes:
 *  - **read-only** (built-ins and any not-yet-cloned theme): inputs are
 *    disabled so the user can inspect every token without mutating anything;
 *    a "Clone to edit" action makes an editable copy.
 *  - **editable** (custom themes): each change rebuilds the theme via
 *    [Theme.withToken] and persists it through [ThemeManagerHost.saveCustomTheme];
 *    a live preview swatch reflects the current colours. Clone and Delete
 *    actions live in the footer.
 *
 * Rendered by [showThemeManager]'s editor view; the top back arrow returns to
 * the list via the supplied callback (there is no Done button — edits persist
 * live, so nothing needs committing).
 *
 * @see Theme.TOKEN_IDS
 * @see renderThemeColorEditor
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * Human-readable labels for the 28 token ids, keyed by [Theme.TOKEN_IDS].
 * Unknown ids fall back to the raw id.
 *
 * The 8 chrome/canvas ids are optional on a [Theme]; the editor shows their
 * effective value (via [Theme.token]) and pins an explicit one on edit, so
 * they need no labelling beyond the "Chrome:" prefix that groups them.
 */
private val TOKEN_LABELS: Map<String, String> = mapOf(
    "bg" to "Background",
    "canvas" to "Pane canvas",
    "surface" to "Surface",
    "surfaceAlt" to "Surface (sunken)",
    "border" to "Border",
    "text" to "Text",
    "textDim" to "Text (dim)",
    "textBright" to "Text (bright)",
    "accent" to "Accent",
    "warn" to "Warning",
    "danger" to "Danger",
    "add" to "Add",
    "addText" to "Add text",
    "chromeBg" to "Chrome: background",
    "chromeText" to "Chrome: text",
    "chromeTextDim" to "Chrome: text (dim)",
    "chromeTextBright" to "Chrome: text (bright)",
    "chromeBorder" to "Chrome: border",
    "chromeAccent" to "Chrome: accent",
    "chromeTrack" to "Chrome: track",
    "synKeyword" to "Syntax: keyword",
    "synString" to "Syntax: string",
    "synNumber" to "Syntax: number",
    "synComment" to "Syntax: comment",
    "synFunction" to "Syntax: function",
    "synType" to "Syntax: type",
    "synOperator" to "Syntax: operator",
    "synConstant" to "Syntax: constant",
)

/**
 * Renders the colour viewer/editor for the theme named [themeName] into
 * [container].
 *
 * Looks the theme up in the full catalog ([allThemes] = built-ins ∪ custom);
 * if it is missing (e.g. deleted while open) it immediately invokes [onBack].
 * Otherwise it renders a back bar, a live preview swatch, and one labeled
 * colour input per [Theme.TOKEN_IDS] entry.
 *
 * When [readOnly] is true the inputs are disabled (so the user can inspect a
 * built-in's tokens without mutating anything) and a "Clone to edit" action is
 * shown that invokes [onCloneAndEdit] with the source theme's name. When
 * false, editing a token calls [ThemeManagerHost.saveCustomTheme] with the
 * updated theme and repaints the preview in place.
 *
 * @param container     the element to fill.
 * @param themeName     the theme's name, or `null` (→ [onBack]).
 * @param readOnly      true to inspect without editing (built-ins / unmodified).
 * @param onBack        invoked by the Done/Back control to return to the list.
 * @param onCloneAndEdit invoked (read-only only) with the theme name when the
 *   user chooses to clone it into an editable copy.
 */
internal fun renderThemeColorEditor(
    container: HTMLElement,
    themeName: String?,
    readOnly: Boolean,
    onBack: () -> Unit,
    onCloneAndEdit: (String) -> Unit,
) {
    val host = themeManagerHost()
    val initial = themeName?.let { name -> allThemes(host.customThemes).firstOrNull { it.name == name } }
    if (initial == null) {
        onBack()
        return
    }
    // Local working copy so successive single-token edits compose.
    var working: Theme = initial

    // ── Back bar ──
    val backBar = document.createElement("div") as HTMLElement
    backBar.className = "dt-theme-manager-back-bar"
    val backBtn = document.createElement("button") as HTMLElement
    backBtn.className = "dt-theme-manager-back-btn"
    backBtn.innerHTML = "&larr;"
    backBtn.title = "Back to themes"
    backBtn.addEventListener("click", { onBack() })
    backBar.appendChild(backBtn)
    val backLabel = document.createElement("span") as HTMLElement
    backLabel.className = "dt-theme-manager-back-label"
    backLabel.textContent = working.name
    backBar.appendChild(backLabel)
    container.appendChild(backBar)

    // ── Read-only notice ──
    if (readOnly) {
        val note = document.createElement("div") as HTMLElement
        note.className = "dt-theme-editor-readonly-note"
        note.textContent = "Viewing a built-in theme. Clone it to make an editable copy."
        container.appendChild(note)
    }

    // ── Live preview swatch ──
    val preview = document.createElement("div") as HTMLElement
    preview.className = "dt-theme-editor-preview"
    container.appendChild(preview)

    fun repaintPreview() {
        preview.innerHTML = ""
        val resolved = working.resolve()
        for (color in listOf(
            resolved.bg, resolved.surface, resolved.surfaceAlt, resolved.border,
            resolved.text, resolved.accent, resolved.add, resolved.danger,
        )) {
            val chip = document.createElement("span") as HTMLElement
            chip.className = "dt-theme-editor-preview-chip"
            chip.style.background = argbToCss(color)
            preview.appendChild(chip)
        }
    }
    repaintPreview()

    // ── Token inputs ──
    val grid = document.createElement("div") as HTMLElement
    grid.className = "dt-theme-editor-grid"
    if (readOnly) grid.classList.add("dt-theme-editor-grid-readonly")
    container.appendChild(grid)

    for (id in Theme.TOKEN_IDS) {
        val rowEl = document.createElement("label") as HTMLElement
        rowEl.className = "dt-theme-editor-token"

        val labelEl = document.createElement("span") as HTMLElement
        labelEl.className = "dt-theme-editor-token-label"
        labelEl.textContent = TOKEN_LABELS[id] ?: id
        rowEl.appendChild(labelEl)

        val input = document.createElement("input") as HTMLInputElement
        input.className = "dt-theme-editor-token-input"
        input.type = "color"
        input.value = working.token(id)
        if (readOnly) {
            // Disabled so the swatch is inspectable but immutable.
            input.disabled = true
        } else {
            input.addEventListener("input", { _: Event ->
                working = working.withToken(id, input.value)
                host.saveCustomTheme(working)
                repaintPreview()
            })
        }
        rowEl.appendChild(input)

        // Show the literal hex alongside each swatch (useful when read-only).
        val hexEl = document.createElement("span") as HTMLElement
        hexEl.className = "dt-theme-editor-token-hex"
        hexEl.textContent = working.token(id)
        if (!readOnly) {
            input.addEventListener("input", { _: Event -> hexEl.textContent = input.value })
        }
        rowEl.appendChild(hexEl)

        grid.appendChild(rowEl)
    }

    // ── Action row (Clone / Delete) ──
    // No "Done" button: edits save live as you change each token, so the top
    // back arrow is the only control needed to return to the list.
    val doneRow = document.createElement("div") as HTMLElement
    doneRow.className = "dt-theme-editor-done-row"
    if (readOnly) {
        // Built-in: cloning is the only way to get an editable copy.
        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "dt-theme-editor-done-btn dt-theme-editor-clone-btn"
        cloneBtn.textContent = "Clone to edit"
        cloneBtn.addEventListener("click", { onCloneAndEdit(working.name) })
        doneRow.appendChild(cloneBtn)
    } else {
        // Custom: clone into a fresh editable copy, or delete this theme.
        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "dt-theme-editor-secondary-btn"
        cloneBtn.textContent = "Clone"
        cloneBtn.addEventListener("click", { onCloneAndEdit(working.name) })
        doneRow.appendChild(cloneBtn)

        val deleteBtn = document.createElement("button") as HTMLElement
        deleteBtn.className = "dt-theme-editor-secondary-btn dt-theme-editor-delete-btn"
        deleteBtn.textContent = "Delete"
        deleteBtn.addEventListener("click", {
            showConfirmDialog(
                title = "Delete theme?",
                message = "Delete \"${working.name}\"? This cannot be undone.",
                confirmLabel = "Delete",
                onConfirm = {
                    host.deleteCustomTheme(working.name)
                    onBack()
                },
            )
        })
        doneRow.appendChild(deleteBtn)
    }
    container.appendChild(doneRow)
}
