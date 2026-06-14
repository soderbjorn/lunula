/**
 * Theme Manager modal entry point.
 *
 * Public API:
 *  - [showThemeManager] — open the right-side sidebar that lets users browse,
 *    favorite, clone, delete, and edit themes & colour schemes.
 *  - [closeThemeManager] — slide-out and detach.
 *  - [refreshThemeManager] — repaint when upstream state changes.
 *
 * This file is a thin composer: it owns the panel chrome (header, tabs,
 * Escape handling) and the shared module state, then delegates list/editor
 * rendering to the focused files in this package — see [renderThemesLeft]
 * (theme grid), [renderThemeEditor] (theme editor), [renderSchemesLeft]
 * (scheme grid), and [renderSchemeEditor] (scheme/color editor).
 *
 * @see ThemeManagerHost
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.applyCssVars
import se.soderbjorn.darkness.web.toCssVarMap
import se.soderbjorn.darkness.web.toCssAliasMap
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Module-level host reference set by [showThemeManager]. */
private lateinit var host: ThemeManagerHost

/** Currently-mounted right-sidebar element, or null when the manager is closed. */
private var themeManagerPanel: HTMLElement? = null

/** Document-level Escape key handler installed while the manager is open. */
private var themeManagerEscHandler: ((Event) -> Unit)? = null

/**
 * When set, the panel's close button + Escape route through this callback
 * instead of [closeThemeManager], so a wrapper that owns layout space (e.g.
 * the right-side sidebar built by `buildThemeManagerSidebar`) can play its
 * own close animation and rebuild the host shell to reclaim the slot. The
 * direct [closeThemeManager] path only detaches the inner panel — without
 * this hook the outer sidebar stays mounted at its full width with empty
 * content. Reset to null inside [closeThemeManager] so a subsequent direct
 * `showThemeManager` mount (no wrapper) starts clean.
 */
internal var themeManagerOnCloseRequested: (() -> Unit)? = null

/** Accessor for the host bound to the currently-open manager. */
internal fun themeManagerHost(): ThemeManagerHost = host

/**
 * Resolves the [ColorScheme] currently bound to the main theme slot, looking
 * up [ThemeManagerHost.mainSchemeName] in the user's custom schemes first,
 * then falling back to the built-in [recommendedColorSchemes] list.
 */
private fun currentMainScheme(): ColorScheme {
    val n = host.mainSchemeName
    return host.customSchemes[n]?.toColorScheme()
        ?: recommendedColorSchemes.firstOrNull { it.name == n }
        ?: recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }
}

/** Resolves the full semantic [ResolvedPalette] for the currently active theme + appearance. */
private fun currentResolvedPalette(): ResolvedPalette {
    val isDark = isDarkActive(host.appearance)
    return currentMainScheme().resolve(isDark)
}

/** Per-section [ResolvedPalette] for the currently active theme. */
private fun sectionPalette(@Suppress("UNUSED_PARAMETER") section: String): ResolvedPalette {
    return currentResolvedPalette()
}

/** Top-level tabs in the manager. */
private enum class ManagerTab { Themes, Schemes }

/** Drill-down view state for a single tab in the sidebar. */
private enum class ManagerView { List, Editor }

/**
 * Set to `true` by any editor input whose value diverges from what was in
 * the preset when the editor was opened, and reset to `false` when the
 * editor is opened, saved, or reverted. Consulted by every exit path
 * (back arrow, tab switch, close button, opening another sidebar, Escape)
 * to prompt for a discard confirmation before the in-flight edits vanish.
 */
internal var isEditorDirty: Boolean = false

/**
 * Optional callback that restores the live theme to its pre-edit state.
 *
 * The theme editor applies picker / mode changes to the live theme as the
 * user makes them so the rest of the UI previews the edit immediately
 * (see [renderThemeEditor]). That means closing the editor on a discard
 * confirmation has to roll the live theme back too — clearing
 * [isEditorDirty] alone would leak the in-flight edits into the saved
 * state. The editor sets this callback when it opens an editable theme
 * (snapshotting the original [Theme]) and clears it on Save / Revert /
 * Delete; [confirmDiscardIfDirty] invokes it before running [onProceed].
 */
internal var themeEditorDiscardLiveEdits: (() -> Unit)? = null

/**
 * Show a discard-changes confirmation when the editor is dirty, otherwise
 * run [onProceed] immediately. On confirm the dirty flag is cleared,
 * [themeEditorDiscardLiveEdits] (if set) restores the live theme to its
 * pre-edit state, and [onProceed] runs; on cancel the dialog closes and
 * nothing else happens.
 */
private fun confirmDiscardIfDirty(onProceed: () -> Unit) {
    if (!isEditorDirty) { onProceed(); return }
    showConfirmDialog(
        title = "Discard changes?",
        message = "You have unsaved changes. Discard them?",
        confirmLabel = "Discard",
        onConfirm = {
            themeEditorDiscardLiveEdits?.invoke()
            themeEditorDiscardLiveEdits = null
            isEditorDirty = false
            onProceed()
        },
    )
}

/**
 * Closes the Theme Manager right-sidebar with a slide-out transition.
 *
 * @param onClosed optional callback invoked once the slide-out transition has
 *                 finished and the panel's DOM node has been detached.
 *                 Invoked immediately (synchronously) when the panel was not
 *                 open to begin with.
 * @see showThemeManager
 */
fun closeThemeManager(onClosed: (() -> Unit)? = null) {
    val panel = themeManagerPanel ?: run { onClosed?.invoke(); return }
    // Intercept close when the editor has unsaved changes. On cancel we
    // abort — [onClosed] is intentionally not invoked so any chained
    // handoff (e.g. settings panel opening next) doesn't proceed behind
    // the user's back.
    if (isEditorDirty) {
        confirmDiscardIfDirty { closeThemeManager(onClosed) }
        return
    }
    var done = false
    panel.classList.remove("dt-open")
    panel.addEventListener("transitionend", {
        if (!done && !panel.classList.contains("dt-open")) {
            done = true
            panel.remove()
            Unit
            onClosed?.invoke()
        }
    })
    themeManagerEscHandler?.let { document.removeEventListener("keydown", it) }
    themeManagerEscHandler = null
    themeManagerRerender = null
    themeManagerFocusTheme = null
    themeManagerFocusScheme = null
    themeManagerOnCloseRequested = null
    themeEditorDiscardLiveEdits = null
    themeManagerPanel = null
}

/**
 * Opens the Theme Manager as a right-side sidebar. Idempotent: if already
 * open it is brought forward and any requested tab/focus target is applied
 * without rebuilding the DOM.
 *
 * Orphan recovery: if the panel reference is non-null but its node has
 * been detached from the document (e.g. a host's full rebuild path called
 * `innerHTML = ""` on the slot that contained it), the still-live panel
 * is re-appended to [mountInto] instead of rebuilt. That preserves the
 * user's open editor / current selection / dirty state across an external
 * teardown — without it, re-mount-on-rerender shows an empty pane until
 * the user closes and reopens the manager.
 *
 * @param initialTab  which tab to show on open (`"themes"` or `"schemes"`).
 * @param focusTheme  optional theme name to preselect and drill into.
 * @param focusScheme optional scheme name to preselect and drill into.
 * @see closeThemeManager
 */
fun showThemeManager(
    hostArg: ThemeManagerHost,
    mountInto: HTMLElement,
    initialTab: String = "themes",
    focusTheme: String? = null,
    focusScheme: String? = null,
) {
    host = hostArg
    themeManagerPanel?.let { existing ->
        if (document.contains(existing)) return
        // Panel was detached by an external teardown but its JS state +
        // listeners are still alive. Reattach to the new mount target
        // rather than rebuilding from scratch — see KDoc above.
        mountInto.appendChild(existing)
        return
    }
    val appBody = mountInto

    val panel = document.createElement("aside") as HTMLElement
    panel.id = "theme-manager-sidebar"
    panel.className = "dt-theme-manager"

    // ── Header: close button + title + tab strip ──
    val header = document.createElement("div") as HTMLElement
    header.className = "dt-theme-manager-header"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "dt-theme-manager-close"
    closeBtn.innerHTML = "&times;"
    closeBtn.title = "Close"
    // Prefer the wrapper-supplied close hook when set so the outer
    // sidebar slot (which owns the layout space) collapses with the panel;
    // fall back to the bare panel teardown when there is no wrapper.
    closeBtn.addEventListener("click", {
        themeManagerOnCloseRequested?.invoke() ?: closeThemeManager()
    })
    header.appendChild(closeBtn)

    val title = document.createElement("h2") as HTMLElement
    title.className = "dt-theme-manager-title"
    title.textContent = "Themes"
    header.appendChild(title)

    val tabStrip = document.createElement("div") as HTMLElement
    tabStrip.className = "dt-theme-manager-tabs"
    val tabThemes = makeTabBtn("Themes", selected = initialTab != "schemes")
    val tabSchemes = makeTabBtn("Color schemes", selected = initialTab == "schemes")
    tabStrip.appendChild(tabThemes)
    tabStrip.appendChild(tabSchemes)
    header.appendChild(tabStrip)

    panel.appendChild(header)

    // ── Body (single-column: either list or editor view) ──
    val body = document.createElement("div") as HTMLElement
    body.className = "dt-theme-manager-body"
    panel.appendChild(body)

    // ── State ─────────────────────────────────────────────────────
    var activeTab = when (initialTab) {
        "schemes" -> ManagerTab.Schemes
        else -> ManagerTab.Themes
    }
    var themeFilter = ThemeFilter.All
    var schemeFilter = SchemeFilter.All
    // Default to the slot that matches the active appearance so opening the
    // manager in dark mode doesn't land on the *light* slot's theme (which
    // reads as "I'm in dark mode but the editor is showing a light theme").
    // `focusTheme` (the explicit "Edit this card" path) still wins.
    var selectedTheme: String? = focusTheme ?: run {
        val activeIsDark = isDarkActive(host.appearance)
        if (activeIsDark) host.darkThemeName ?: host.lightThemeName
        else host.lightThemeName ?: host.darkThemeName
    }
    var selectedScheme: String? = focusScheme
    var view: ManagerView = when {
        activeTab == ManagerTab.Themes && focusTheme != null -> ManagerView.Editor
        activeTab == ManagerTab.Schemes && focusScheme != null -> ManagerView.Editor
        else -> ManagerView.List
    }

    var renderAll: () -> Unit = {}

    fun setView(v: ManagerView) {
        view = v
        renderAll()
    }

    fun renderListView(container: HTMLElement) {
        if (activeTab == ManagerTab.Themes) {
            // Highlight follows the active-mode slot so clicking swaps the
            // highlight immediately — the click IS the assignment. When the
            // host's slot pointer is null/empty (the user has never picked a
            // theme for this mode, or persistence dropped the field), fall
            // back to the same default the active-theme resolver uses so the
            // Active section keeps surfacing the actually-painted theme
            // instead of disappearing on appearance toggles.
            val state = host
            val activeLight = !isDarkActive(state.appearance)
            val rawSlot = if (activeLight) state.lightThemeName else state.darkThemeName
            val activeSlotName = rawSlot?.takeIf { it.isNotEmpty() }
                ?: if (activeLight) DEFAULT_LIGHT_THEME_NAME else DEFAULT_DARK_THEME_NAME
            renderThemesLeft(container, themeFilter, activeSlotName,
                onFilter = { f -> themeFilter = f; renderAll() },
                onAssign = { name ->
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        if (activeLight) host.setLightThemeName(name)
                        else host.setDarkThemeName(name)
                    }
                    pokeManager()
                },
                onEdit = { name ->
                    selectedTheme = name
                    setView(ManagerView.Editor)
                })
        } else {
            renderSchemesLeft(container, schemeFilter, selectedScheme,
                onFilter = { f -> schemeFilter = f; renderAll() },
                onSelect = { name ->
                    selectedScheme = name
                    setView(ManagerView.Editor)
                })
        }
    }

    fun renderEditorView(container: HTMLElement) {
        val backBar = document.createElement("div") as HTMLElement
        backBar.className = "dt-theme-manager-back-bar"
        val backBtn = document.createElement("button") as HTMLElement
        backBtn.className = "dt-theme-manager-back-btn"
        backBtn.innerHTML = "&larr;"
        val destination = when (activeTab) {
            ManagerTab.Themes -> "Themes"
            ManagerTab.Schemes -> "Color schemes"
        }
        backBtn.title = "Back to $destination"
        backBtn.addEventListener("click", {
            confirmDiscardIfDirty { setView(ManagerView.List) }
        })
        backBar.appendChild(backBtn)
        val backLabel = document.createElement("span") as HTMLElement
        backLabel.className = "dt-theme-manager-back-label"
        backLabel.textContent = "Back to list"
        backBar.appendChild(backLabel)
        container.appendChild(backBar)

        val editorHost = document.createElement("div") as HTMLElement
        editorHost.className = "dt-theme-manager-editor-host"
        container.appendChild(editorHost)

        if (activeTab == ManagerTab.Themes) {
            renderThemeEditor(editorHost, selectedTheme) { renderAll() }
        } else {
            renderSchemeEditor(editorHost, selectedScheme) { renderAll() }
        }
    }

    renderAll = {
        // If the editor's target has disappeared since the last render (the
        // user just deleted it, or it was removed elsewhere), fall back to
        // the list view instead of showing a dead-end "not found" message.
        if (view == ManagerView.Editor) {
            val state = host
            val missing = when (activeTab) {
                ManagerTab.Themes -> {
                    val n = selectedTheme
                    n == null || (state.customThemes[n] == null &&
                        defaultThemes.none { it.name == n })
                }
                ManagerTab.Schemes -> {
                    val n = selectedScheme
                    n == null || (state.customSchemes[n] == null &&
                        recommendedColorSchemes.none { it.name == n })
                }
            }
            if (missing) {
                view = ManagerView.List
                if (activeTab == ManagerTab.Themes) selectedTheme = null
                else selectedScheme = null
            }
        }

        title.textContent = when (activeTab) {
            ManagerTab.Themes -> "Themes"
            ManagerTab.Schemes -> "Color schemes"
        }
        // Tabs only make sense when browsing the list — while editing a
        // theme/scheme they'd either duplicate the new back-bar label or
        // mislead the user into thinking a tab click would switch the
        // current edit's context.
        tabStrip.style.display = if (view == ManagerView.Editor) "none" else "flex"
        body.innerHTML = ""
        body.classList.toggle("view-editor", view == ManagerView.Editor)
        body.classList.toggle("view-list", view == ManagerView.List)
        if (view == ManagerView.List) renderListView(body) else renderEditorView(body)
    }

    tabThemes.addEventListener("click", {
        confirmDiscardIfDirty {
            activeTab = ManagerTab.Themes
            tabThemes.classList.add("dt-selected")
            tabSchemes.classList.remove("dt-selected")
            view = ManagerView.List
            renderAll()
        }
    })
    tabSchemes.addEventListener("click", {
        confirmDiscardIfDirty {
            activeTab = ManagerTab.Schemes
            tabSchemes.classList.add("dt-selected")
            tabThemes.classList.remove("dt-selected")
            if (selectedScheme == null) {
                selectedScheme = host.mainSchemeName
            }
            view = ManagerView.List
            renderAll()
        }
    })

    renderAll()

    // ── Escape-to-close ──
    val escHandler: (Event) -> Unit = { ev ->
        if ((ev as? KeyboardEvent)?.key == "Escape") {
            if (view == ManagerView.Editor) {
                confirmDiscardIfDirty { setView(ManagerView.List) }
            } else {
                themeManagerOnCloseRequested?.invoke() ?: closeThemeManager()
            }
        }
    }
    document.addEventListener("keydown", escHandler)
    themeManagerEscHandler = escHandler

    themeManagerRerender = { _: Any -> renderAll() }

    // ── Focus hooks: let clone flows drop the user straight into the
    // editor for the newly-created theme/scheme.
    themeManagerFocusTheme = { name ->
        activeTab = ManagerTab.Themes
        tabThemes.classList.add("dt-selected"); tabSchemes.classList.remove("dt-selected")
        selectedTheme = name
        view = ManagerView.Editor
        renderAll()
    }
    themeManagerFocusScheme = { name ->
        activeTab = ManagerTab.Schemes
        tabSchemes.classList.add("dt-selected"); tabThemes.classList.remove("dt-selected")
        selectedScheme = name
        view = ManagerView.Editor
        renderAll()
    }

    appBody.appendChild(panel)
    themeManagerPanel = panel

    // Apply the sidebar section theme so the panel matches the left sidebar
    // (same treatment the settings panel applies to itself).
    val sidebarPalette = sectionPalette("sidebar")
    val rootPalette = currentResolvedPalette()
    if (sidebarPalette != rootPalette) {
        val cssVars = sidebarPalette.toCssVarMap() + sidebarPalette.toCssAliasMap()
        for ((prop, value) in cssVars) panel.style.setProperty(prop, value)
    }

    kotlinx.browser.window.requestAnimationFrame { panel.classList.add("dt-open") }
    panel.addEventListener("transitionend", { Unit }, js("({once:true})"))
}

/** Callback invoked after mutations to refresh the manager UI, if open. */
private var themeManagerRerender: ((Any) -> Unit)? = null

/** Set while the manager is open; drills into the editor for a freshly-cloned theme. */
internal var themeManagerFocusTheme: ((String) -> Unit)? = null

/** Set while the manager is open; drills into the editor for a freshly-cloned scheme. */
internal var themeManagerFocusScheme: ((String) -> Unit)? = null

/** Notify the open manager (if any) to re-render. */
internal fun pokeManager() {
    themeManagerRerender?.invoke(Unit)
}

/**
 * Refresh the Theme Manager panel if it is currently open. Called from
 * upstream state observers so that appearance-dependent UI re-sorts when
 * the user toggles between light and dark. No-op when the panel is closed.
 */
fun refreshThemeManager() {
    themeManagerRerender?.invoke(Unit)
}

/** Build a tab button element. */
private fun makeTabBtn(label: String, selected: Boolean): HTMLElement {
    val btn = document.createElement("button") as HTMLElement
    btn.className = "dt-theme-manager-tab" + if (selected) " dt-selected" else ""
    btn.textContent = label
    return btn
}

/**
 * Generic modal name prompt used for clone operations.
 *
 * @param title         modal title
 * @param label         input label
 * @param initial       initial input value
 * @param validate      returns an error string or `null` if valid
 * @param onCommit      called with the final, validated name
 */
internal fun showNamePrompt(
    title: String,
    label: String,
    initial: String,
    validate: (String) -> String?,
    onCommit: (String) -> Unit,
) {
    val overlay = document.createElement("div") as HTMLElement
    overlay.className = "dt-name-prompt-overlay"
    val cardEl = document.createElement("div") as HTMLElement
    cardEl.className = "dt-name-prompt"
    cardEl.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "dt-name-prompt-title"
    titleEl.textContent = title
    cardEl.appendChild(titleEl)

    val lblEl = document.createElement("label") as HTMLElement
    lblEl.className = "dt-name-prompt-label"
    lblEl.textContent = label
    cardEl.appendChild(lblEl)

    val input = document.createElement("input") as HTMLInputElement
    input.className = "dt-name-prompt-input"
    input.type = "text"
    input.value = initial
    input.setAttribute("autocomplete", "off")
    input.setAttribute("spellcheck", "false")
    cardEl.appendChild(input)

    val errorEl = document.createElement("div") as HTMLElement
    errorEl.className = "dt-name-prompt-error"
    errorEl.style.display = "none"
    cardEl.appendChild(errorEl)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "dt-name-prompt-buttons"

    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.className = "dt-name-prompt-btn dt-name-prompt-btn-cancel"
    cancelBtn.textContent = "Cancel"
    cancelBtn.addEventListener("click", { overlay.remove() })
    btnRow.appendChild(cancelBtn)

    val okBtn = document.createElement("button") as HTMLElement
    okBtn.className = "dt-name-prompt-btn dt-name-prompt-btn-ok"
    okBtn.textContent = "OK"

    // Re-run [validate] on every input change so OK reflects validity
    // upfront. Show the error text only once the user has touched the
    // field so the initial prompt stays quiet on a valid default.
    var dirty = false
    val syncValidity = {
        val err = validate(input.value.trim())
        okBtn.asDynamic().disabled = err != null
        if (dirty && err != null) {
            errorEl.textContent = err
            errorEl.style.display = ""
        } else {
            errorEl.textContent = ""
            errorEl.style.display = "none"
        }
    }
    input.addEventListener("input", { _: Event ->
        dirty = true
        syncValidity()
    })
    syncValidity()

    val doCommit = {
        val v = input.value.trim()
        val err = validate(v)
        if (err != null) {
            dirty = true
            syncValidity()
        } else {
            overlay.remove()
            onCommit(v)
        }
    }
    okBtn.addEventListener("click", { doCommit() })
    btnRow.appendChild(okBtn)

    cardEl.appendChild(btnRow)
    overlay.appendChild(cardEl)

    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })
    input.addEventListener("keydown", { ev: Event ->
        val ke = ev as KeyboardEvent
        if (ke.key == "Enter") { ev.preventDefault(); doCommit() }
        else if (ke.key == "Escape") overlay.remove()
    })

    document.body?.appendChild(overlay)
    input.focus(); input.select()
}
