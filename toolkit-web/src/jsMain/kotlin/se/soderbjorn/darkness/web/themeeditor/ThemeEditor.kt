/**
 * Per-theme editor pane.
 *
 * Layout (top to bottom):
 *  1. Header — name input, mode dropdown, silhouette preview.
 *  2. Sections area — one row per universal [Sections] entry. Always visible.
 *     The "Main content" row binds to [Theme.colorScheme] (the theme's
 *     baseline scheme); every other row binds to [Theme.sections].
 *  3. Action row — Save / Revert / Delete (or "Clone to edit" for
 *     read-only default themes).
 *
 * Per the universal-sections design, an empty value in any picker means
 * "inherit from the Main row's scheme". Themes only address the universal
 * [Sections]; pinning specific app-local panes is not editable from the UI.
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.escapeHtml
import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

/**
 * Render the right-hand editor for the currently-selected theme. Default
 * themes show a read-only preview with a prominent "Clone to edit" button;
 * custom themes show editable name, mode, and per-section scheme rows.
 */
internal fun renderThemeEditor(
    container: HTMLElement,
    selectedName: String?,
    refresh: () -> Unit,
) {
    container.innerHTML = ""
    val state = themeManagerHost()
    val name = selectedName ?: return
    val preset = state.customThemes[name] ?: defaultThemes.firstOrNull { it.name == name }
    if (preset == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "dt-theme-manager-empty"
        empty.textContent = "Theme not found."
        container.appendChild(empty)
        return
    }
    val isDefault = preset.name !in state.customThemes
    isEditorDirty = false

    // Snapshot of the theme as it was when the editor opened, captured so
    // [Revert] (and the discard-changes prompt) can roll the live theme
    // back to this state. We apply picker/mode changes to the live theme
    // as the user makes them (see [applyDraftLive] below) — without this
    // snapshot there would be no way to back out of a previewed edit.
    // Default themes are read-only so they don't get live applied and
    // don't need a snapshot.
    val originalSnapshot: Theme? = if (!isDefault) preset else null
    themeEditorDiscardLiveEdits = if (originalSnapshot != null) {
        {
            GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                themeManagerHost().saveCustomTheme(originalSnapshot)
            }
            Unit
        }
    } else null

    var syncActionsEnabled: () -> Unit = {}
    val markDirty: () -> Unit = {
        isEditorDirty = true
        syncActionsEnabled()
    }
    // Forward-reference shim: the mode-select listener and the picker
    // callbacks need to call into [applyDraftLive], but it can't be
    // declared yet (its body reads [sectionValues], which hasn't been
    // built). Declared as a no-op here and reassigned once the map is
    // populated, so callers always see the latest impl without
    // forward-reference compile errors.
    var applyDraftLive: () -> Unit = {}

    val header = document.createElement("div") as HTMLElement
    header.className = "dt-theme-editor-header"

    val preview = document.createElement("div") as HTMLElement
    preview.className = "dt-theme-editor-preview"
    preview.innerHTML = themeManagerHost().renderConfigSilhouetteHtml(preset)
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
    nameInput.value = preset.name
    nameInput.disabled = isDefault
    nameInput.addEventListener("input", { _: Event -> markDirty() })
    info.appendChild(nameInput)

    val modeLabel = document.createElement("div") as HTMLElement
    modeLabel.className = "dt-theme-editor-label"
    modeLabel.textContent = "Optimized for"
    info.appendChild(modeLabel)

    val modeSelect = document.createElement("select") as HTMLSelectElement
    modeSelect.className = "dt-theme-editor-select"
    modeSelect.disabled = isDefault
    for (m in ConfigMode.values()) {
        val opt = document.createElement("option") as org.w3c.dom.HTMLOptionElement
        opt.value = m.name
        opt.textContent = when (m) {
            ConfigMode.Dark -> "Dark mode"
            ConfigMode.Light -> "Light mode"
            ConfigMode.Both -> "Both dark and light mode"
        }
        if (m == preset.mode) opt.selected = true
        modeSelect.appendChild(opt)
    }
    modeSelect.addEventListener("change", { _: Event ->
        markDirty()
        applyDraftLive()
    })
    info.appendChild(modeSelect)

    header.appendChild(info)
    container.appendChild(header)

    // ── Sections (always visible) ──
    //
    // sectionValues[Sections.Main] tracks the theme's baseline
    // [Theme.colorScheme]; all other entries track [Theme.sections][key].
    // An empty value in a non-Main row means "inherit from Main".
    val sectionValues = mutableMapOf<String, String>()
    sectionValues[Sections.Main] = preset.colorScheme
    for (s in Sections.all) {
        if (s == Sections.Main) continue
        sectionValues[s] = preset.sections[s].orEmpty()
    }

    /**
     * Commit the current editor inputs to the live theme so the rest of
     * the UI previews the user's edits without them having to click Save.
     * Persists via [ThemeManagerHost.saveCustomTheme] (the host's
     * `onChange` then re-applies the CSS vars / writes to disk) and
     * re-renders the silhouette preview from the new draft.
     *
     * Skipped for read-only default themes — the editor disables their
     * inputs so this is defensive.
     *
     * The draft keeps [preset.name]: renaming is destructive (deletes the
     * old key, re-points the active light/dark slot) and would be hostile
     * to do on every keystroke, so the name input stays deferred to
     * [Save] which already handles the rename dance.
     */
    applyDraftLive = lambda@{
        if (isDefault) return@lambda
        val newMode = runCatching { ConfigMode.valueOf(modeSelect.value) }.getOrDefault(ConfigMode.Both)
        val newColorScheme = sectionValues[Sections.Main]
            ?.takeIf { it.isNotEmpty() } ?: preset.colorScheme
        val newSections = buildMap {
            for (s in Sections.all) {
                if (s == Sections.Main) continue
                sectionValues[s]?.takeIf { it.isNotEmpty() }?.let { put(s, it) }
            }
        }
        val draft = preset.copy(
            name = preset.name,
            mode = newMode,
            colorScheme = newColorScheme,
            sections = newSections,
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().saveCustomTheme(draft)
        }
        // Keep the in-editor silhouette in sync with the live preview so
        // the user can see their edit reflected in the small preview too.
        preview.innerHTML = themeManagerHost().renderConfigSilhouetteHtml(draft)
    }

    val sectionsHeader = document.createElement("h3") as HTMLElement
    sectionsHeader.className = "dt-theme-editor-area-header"
    sectionsHeader.textContent = "Sections"
    container.appendChild(sectionsHeader)

    val sectionsWrap = document.createElement("div") as HTMLElement
    sectionsWrap.className = "dt-theme-editor-sections"

    // Section row refreshers — collected so a Main change re-renders
    // every sibling row whose selection is "Default" (their swatch tracks
    // the Main scheme).
    val sectionRowRefreshers = mutableListOf<() -> Unit>()
    for (sectionKey in Sections.all) {
        val (row, refreshRow) = buildSectionRow(
            sectionKey = sectionKey,
            sectionLabel = sectionDisplayLabel(sectionKey),
            currentSchemeName = sectionValues[sectionKey] ?: "",
            // Main can never show "Inherit" — it IS the inheritance root.
            isReadonly = isDefault,
            getMainSchemeName = { sectionValues[Sections.Main] ?: "" },
            allowInherit = sectionKey != Sections.Main,
        ) { newName ->
            sectionValues[sectionKey] = newName
            if (sectionKey == Sections.Main) {
                sectionRowRefreshers.forEach { it() }
            }
            markDirty()
            applyDraftLive()
        }
        sectionRowRefreshers += refreshRow
        sectionsWrap.appendChild(row)
    }
    container.appendChild(sectionsWrap)

    val actions = document.createElement("div") as HTMLElement
    actions.className = "dt-theme-editor-actions"

    if (isDefault) {
        val cloneHint = document.createElement("div") as HTMLElement
        cloneHint.className = "dt-theme-editor-hint"
        cloneHint.textContent = "Default themes are read-only. Clone to edit."
        actions.appendChild(cloneHint)

        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "dt-theme-editor-btn dt-primary"
        cloneBtn.textContent = "Clone to edit"
        cloneBtn.addEventListener("click", { showCloneThemePrompt(preset) })
        actions.appendChild(cloneBtn)
    } else {
        val saveBtn = document.createElement("button") as HTMLElement
        saveBtn.className = "dt-theme-editor-btn dt-primary"
        saveBtn.textContent = "Save"
        saveBtn.addEventListener("click", { _: Event ->
            val newName = nameInput.value.trim()
            if (newName.isEmpty()) return@addEventListener
            val newMode = runCatching { ConfigMode.valueOf(modeSelect.value) }.getOrDefault(ConfigMode.Both)
            // The Main row binds to [Theme.colorScheme] (the theme's
            // baseline scheme), not to a [Theme.sections] entry. An empty
            // value falls back to the preset's existing baseline.
            val newColorScheme = sectionValues[Sections.Main]
                ?.takeIf { it.isNotEmpty() } ?: preset.colorScheme
            val newSections = buildMap {
                for (s in Sections.all) {
                    if (s == Sections.Main) continue
                    sectionValues[s]?.takeIf { it.isNotEmpty() }?.let { put(s, it) }
                }
            }
            val next = preset.copy(
                name = newName,
                mode = newMode,
                colorScheme = newColorScheme,
                sections = newSections,
            )
            GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (newName != preset.name) {
                    themeManagerHost().deleteCustomTheme(preset.name)
                }
                themeManagerHost().saveCustomTheme(next)
            }
            // Saved state becomes the new baseline — drop the
            // discard-live-edits hook so a later close doesn't try to
            // restore the pre-edit snapshot over the user's just-saved
            // changes.
            themeEditorDiscardLiveEdits = null
            isEditorDirty = false
            pokeManager()
        })
        actions.appendChild(saveBtn)

        val revertBtn = document.createElement("button") as HTMLElement
        revertBtn.className = "dt-theme-editor-btn"
        revertBtn.textContent = "Revert"
        revertBtn.addEventListener("click", {
            // Restore the live theme to the snapshot taken when the
            // editor opened — without this, the live applies stay in
            // [state.customThemes] and Revert only resets the editor
            // inputs while the rest of the UI keeps showing the edits.
            if (originalSnapshot != null) {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    themeManagerHost().saveCustomTheme(originalSnapshot)
                }
            }
            themeEditorDiscardLiveEdits = null
            isEditorDirty = false
            refresh()
        })
        actions.appendChild(revertBtn)

        syncActionsEnabled = {
            val clean = !isEditorDirty
            console.log("[theme-editor] syncActionsEnabled theme clean=$clean isEditorDirty=$isEditorDirty")
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
                title = "Delete theme",
                message = "Delete theme <b>${escapeHtml(preset.name)}</b>?",
                confirmLabel = "Delete",
                messageIsHtml = true,
                onConfirm = {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        themeManagerHost().deleteCustomTheme(preset.name)
                    }
                    // Theme is gone — there's nothing for the discard
                    // hook to restore, and leaving it set would re-create
                    // the deleted theme on the next discard prompt.
                    themeEditorDiscardLiveEdits = null
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
 * Resolve a colour-scheme name to its [ColorScheme], checking custom
 * schemes first and falling back to [recommendedColorSchemes]. Returns `null`
 * when the name is empty or unknown.
 */
internal fun resolveSchemeByName(name: String): ColorScheme? {
    if (name.isEmpty()) return null
    val state = themeManagerHost()
    return state.customSchemes[name]?.toColorScheme()
        ?: recommendedColorSchemes.firstOrNull { it.name == name }
}

/**
 * Populate [container] with the visual row content for [schemeName]: a
 * mini swatch and the scheme name. An empty [schemeName] renders the
 * "Default" (inherit from main) variant with [mainSchemeName]'s swatch so
 * the user can see what it resolves to.
 */
fun fillSchemeRowContent(
    container: HTMLElement,
    schemeName: String,
    mainSchemeName: String,
) {
    container.innerHTML = ""
    val isDefault = schemeName.isEmpty()
    val effectiveName = if (isDefault) mainSchemeName else schemeName
    val resolved = resolveSchemeByName(effectiveName)

    val swatchWrap = document.createElement("span") as HTMLElement
    swatchWrap.className = "dt-scheme-row-swatch"
    if (resolved != null) swatchWrap.innerHTML = themeManagerHost().renderThemeSwatchHtml(resolved)
    container.appendChild(swatchWrap)

    val labelWrap = document.createElement("span") as HTMLElement
    labelWrap.className = "dt-scheme-row-label" + if (isDefault) " dt-muted" else ""
    labelWrap.textContent = if (isDefault) "Default" else schemeName
    container.appendChild(labelWrap)
}

/**
 * Group scheme names for the picker dropdowns. Returns an ordered list of
 * `(headerLabel, names)` pairs — Favorites first, then Custom, then Default
 * (built-ins). Each group is alpha-sorted internally; empty groups are
 * omitted.
 *
 * @return ordered groups; callers render a header row followed by one
 *   option per name
 */
fun buildSchemeGroups(): List<Pair<String, List<String>>> {
    val state = themeManagerHost()
    val favs = state.favoriteSchemes.sorted()
    val customs = state.customSchemes.keys.sorted()
    val defaults = recommendedColorSchemes.map { it.name }.sorted()
    return buildList {
        if (favs.isNotEmpty()) add("Favorites" to favs)
        if (customs.isNotEmpty()) add("Custom" to customs)
        add("Default" to defaults)
    }
}

/**
 * Open the scheme-picker popover anchored below [anchor]. Lists every
 * recommended, custom, and favourited scheme as a rich row (swatch + name),
 * grouped under "Favorites", "Custom", and "Default" headers (in that
 * order). When [allowInherit] is `true`, a leading "Inherit" entry
 * resolves the picker back to the inherited scheme (the main scheme for
 * section rows, the section's resolved scheme for per-pane override rows).
 *
 * @param allowInherit whether to render an "Inherit" entry that picks the
 *   empty value. Set `false` for the Main row (the inheritance root) and
 *   any other row that must always carry an explicit choice.
 * @see buildSchemeGroups for the shared grouping/ordering
 */
private fun openSectionSchemeMenu(
    anchor: HTMLElement,
    selected: String,
    getMainSchemeName: () -> String,
    allowInherit: Boolean,
    onPick: (String) -> Unit,
) {
    document.querySelectorAll(".dt-scheme-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }

    val menu = document.createElement("div") as HTMLElement
    menu.className = "dt-scheme-menu"

    fun addOption(schemeName: String) {
        val item = document.createElement("button") as HTMLElement
        item.className = "dt-scheme-menu-option" + if (schemeName == selected) " dt-selected" else ""
        item.setAttribute("type", "button")
        val inner = document.createElement("span") as HTMLElement
        inner.className = "dt-scheme-menu-option-content"
        fillSchemeRowContent(inner, schemeName, getMainSchemeName())
        item.appendChild(inner)
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            menu.remove()
            onPick(schemeName)
        })
        menu.appendChild(item)
    }

    fun addHeader(text: String) {
        val h = document.createElement("div") as HTMLElement
        h.className = "dt-scheme-menu-header"
        h.textContent = text
        menu.appendChild(h)
    }

    if (allowInherit) {
        addHeader("Inherit")
        addOption("")
    }

    for ((header, names) in buildSchemeGroups()) {
        addHeader(header)
        for (n in names) addOption(n)
    }

    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.left = "${rect.left}px"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.minWidth = "${rect.width}px"
    document.body?.appendChild(menu)

    val viewportH = window.innerHeight
    val menuH = menu.getBoundingClientRect().height
    if (rect.bottom + 4 + menuH > viewportH - 8) {
        val above = rect.top - menuH - 4
        if (above >= 8) {
            menu.style.top = "${above}px"
        } else {
            menu.style.maxHeight = "${(viewportH - rect.bottom - 12).coerceAtLeast(120.0)}px"
        }
    }

    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}

/**
 * Build a single "label + scheme picker" row for the theme editor.
 *
 * Caller context: invoked once per universal section when rendering
 * the sections area of [renderThemeEditor].
 *
 * @param sectionKey stable identifier for the row (used by the editor to
 *   key its `sectionValues` map; opaque to the row implementation)
 * @param sectionLabel display text shown to the left of the picker
 * @param currentSchemeName the scheme to preselect; empty string renders
 *   as "Default" and shows the inherited swatch
 * @param isReadonly disables the picker (used for read-only default themes)
 * @param getMainSchemeName lazy accessor for the inherited scheme to show
 *   when the row's selection is "Default" — the theme's main scheme.
 * @param allowInherit whether the picker exposes the "Inherit" entry.
 *   The Main section row passes `false` (it IS the inheritance root);
 *   every other row passes `true`.
 * @param onChange invoked with the newly-picked scheme name (or empty
 *   string when the user picks "Inherit")
 * @return the row element paired with a `refresh` lambda the caller can
 *   invoke when an upstream scheme assignment changes — the row will
 *   re-render its swatch if it currently shows "Default".
 */
private fun buildSectionRow(
    @Suppress("UNUSED_PARAMETER") sectionKey: String,
    sectionLabel: String,
    currentSchemeName: String,
    isReadonly: Boolean,
    getMainSchemeName: () -> String,
    allowInherit: Boolean = true,
    onChange: (String) -> Unit,
): Pair<HTMLElement, () -> Unit> {
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-theme-editor-section-row"

    val label = document.createElement("div") as HTMLElement
    label.className = "dt-theme-editor-section-label"
    label.textContent = sectionLabel
    row.appendChild(label)

    val trigger = document.createElement("button") as HTMLElement
    trigger.className = "dt-scheme-picker"
    trigger.setAttribute("type", "button")
    trigger.asDynamic().disabled = isReadonly

    val content = document.createElement("span") as HTMLElement
    content.className = "dt-scheme-picker-content"
    trigger.appendChild(content)

    val chevron = document.createElement("span") as HTMLElement
    chevron.className = "dt-scheme-picker-chevron"
    chevron.textContent = "▾"
    trigger.appendChild(chevron)

    var selected = currentSchemeName
    val refreshTrigger = {
        fillSchemeRowContent(content, selected, getMainSchemeName())
    }
    refreshTrigger()

    trigger.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        if (isReadonly) return@addEventListener
        openSectionSchemeMenu(
            anchor = trigger,
            selected = selected,
            getMainSchemeName = getMainSchemeName,
            allowInherit = allowInherit,
        ) { picked ->
            if (picked != selected) {
                selected = picked
                refreshTrigger()
                onChange(picked)
            }
        }
    })

    row.appendChild(trigger)
    return row to refreshTrigger
}

/**
 * Display label for a universal [Sections] constant. Toolkit-defined so
 * every Darkness app uses the same wording in its Theme Editor regardless
 * of how the host app names its concrete panes.
 *
 * @param sectionKey one of the [Sections] constants
 * @return the human-readable label shown next to the section's scheme picker
 */
private fun sectionDisplayLabel(sectionKey: String): String = when (sectionKey) {
    Sections.Main -> "Main content"
    Sections.Sidebar -> "Sidebar"
    Sections.Tabs -> "Tab strip"
    Sections.Chrome -> "Window chrome"
    Sections.Active -> "Active indicators"
    Sections.Accent -> "Accent"
    Sections.Windows -> "Window frames"
    Sections.Auxiliary -> "Auxiliary panels"
    Sections.BottomBar -> "Bottom bar"
    else -> sectionKey
}

