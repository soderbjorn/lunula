/**
 * Theme Manager modal entry point (post-revamp theme system).
 *
 * Public API:
 *  - [showThemeManager] — open the right-side sidebar that lets users browse,
 *    assign, clone, edit, and delete themes.
 *  - [closeThemeManager] — slide-out and detach.
 *  - [refreshThemeManager] — repaint when upstream state changes.
 *
 * This file owns the panel chrome (header, Escape handling) and the shared
 * module state, then renders all themes as a reflowing thumbnail grid grouped
 * into "Dark" and "Light" sections. Each card shows the theme name above its
 * thumbnail; clicking the card assigns the theme to the active slot, and a
 * small arrow (revealed on hover) opens the theme's editor — which holds the
 * token swatches plus the Clone and Delete actions. Colour-scheme tabs,
 * favorites, and per-pane sections are gone; the editor view is the flat
 * 20-token [renderThemeColorEditor].
 *
 * @see ThemeManagerHost
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.ThemeGroup
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.darkness.core.builtinTheme
import se.soderbjorn.darkness.web.isDarkActive

import kotlinx.browser.document
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
 * own close animation and rebuild the host shell to reclaim the slot. Reset
 * to null inside [closeThemeManager].
 */
internal var themeManagerOnCloseRequested: (() -> Unit)? = null

/** Accessor for the host bound to the currently-open manager. */
internal fun themeManagerHost(): ThemeManagerHost = host

/** Callback invoked after mutations to refresh the manager UI, if open. */
private var themeManagerRerender: (() -> Unit)? = null

/** Drill-down view state for the manager. */
private enum class ManagerView { List, Editor }

/**
 * Closes the Theme Manager right-sidebar with a slide-out transition.
 *
 * @param onClosed optional callback invoked once the slide-out transition has
 *   finished and the panel's DOM node has been detached. Invoked immediately
 *   (synchronously) when the panel was not open to begin with.
 * @see showThemeManager
 */
fun closeThemeManager(onClosed: (() -> Unit)? = null) {
    val panel = themeManagerPanel ?: run { onClosed?.invoke(); return }
    var done = false
    panel.classList.remove("dt-open")
    panel.addEventListener("transitionend", {
        if (!done && !panel.classList.contains("dt-open")) {
            done = true
            panel.remove()
            onClosed?.invoke()
        }
    })
    themeManagerEscHandler?.let { document.removeEventListener("keydown", it) }
    themeManagerEscHandler = null
    themeManagerRerender = null
    themeManagerOnCloseRequested = null
    themeManagerPanel = null
}

/**
 * Opens the Theme Manager as a right-side sidebar. Idempotent: if already
 * open it is brought forward without rebuilding the DOM.
 *
 * Orphan recovery: if the panel reference is non-null but its node has been
 * detached from the document (a host's full rebuild path called `innerHTML =
 * ""` on the slot that contained it), the still-live panel is re-appended to
 * [mountInto] instead of rebuilt.
 *
 * @param hostArg    the host whose theme state the manager reads/writes.
 * @param mountInto  the element to append the panel to.
 * @param initialTab retained for call-site compatibility; ignored (the
 *   manager no longer has tabs).
 * @param focusTheme optional theme name to open straight into the editor for
 *   (used by the clone flow). Only honoured for custom themes.
 * @see closeThemeManager
 */
fun showThemeManager(
    hostArg: ThemeManagerHost,
    mountInto: HTMLElement,
    @Suppress("UNUSED_PARAMETER") initialTab: String = "themes",
    focusTheme: String? = null,
) {
    host = hostArg
    themeManagerPanel?.let { existing ->
        if (document.contains(existing)) return
        mountInto.appendChild(existing)
        return
    }

    val panel = document.createElement("aside") as HTMLElement
    panel.id = "theme-manager-sidebar"
    panel.className = "dt-theme-manager"

    // ── Header: close button + title ──
    val header = document.createElement("div") as HTMLElement
    header.className = "dt-theme-manager-header"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "dt-theme-manager-close"
    closeBtn.innerHTML = "&times;"
    closeBtn.title = "Close"
    closeBtn.addEventListener("click", {
        themeManagerOnCloseRequested?.invoke() ?: closeThemeManager()
    })
    header.appendChild(closeBtn)

    val title = document.createElement("h2") as HTMLElement
    title.className = "dt-theme-manager-title"
    title.textContent = "Themes"
    header.appendChild(title)

    panel.appendChild(header)

    // ── Body (single column: list or editor) ──
    val body = document.createElement("div") as HTMLElement
    body.className = "dt-theme-manager-body"
    panel.appendChild(body)

    // ── State ──
    var view = if (focusTheme != null && builtinTheme(focusTheme) == null) {
        ManagerView.Editor
    } else {
        ManagerView.List
    }
    var editingTheme: String? = focusTheme?.takeIf { builtinTheme(it) == null }
    // Built-ins open read-only (inspect without cloning); custom themes edit.
    var editingReadOnly: Boolean = false

    var renderAll: () -> Unit = {}

    fun setView(v: ManagerView, themeName: String? = null, readOnly: Boolean = false) {
        view = v
        editingTheme = themeName
        editingReadOnly = readOnly
        renderAll()
    }

    renderAll = {
        // If the editor target has vanished (deleted elsewhere), drop to list.
        // A read-only built-in is always present in the catalog, so only an
        // editable (custom) target can disappear.
        if (view == ManagerView.Editor && !editingReadOnly) {
            val n = editingTheme
            if (n == null || host.customThemes.none { it.name == n }) {
                view = ManagerView.List
                editingTheme = null
            }
        }
        title.textContent = "Themes"
        body.innerHTML = ""
        body.classList.toggle("view-editor", view == ManagerView.Editor)
        body.classList.toggle("view-list", view == ManagerView.List)
        if (view == ManagerView.List) {
            renderThemeList(
                container = body,
                onOpen = { name, readOnly -> setView(ManagerView.Editor, name, readOnly) },
            )
        } else {
            renderThemeColorEditor(
                container = body,
                themeName = editingTheme,
                readOnly = editingReadOnly,
                onBack = { setView(ManagerView.List) },
                onCloneAndEdit = { srcName ->
                    val src = allThemes(host.customThemes).firstOrNull { it.name == srcName }
                    if (src != null) {
                        val copy = src.copy(name = dedupeCloneName(src.name))
                        host.saveCustomTheme(copy)
                        setView(ManagerView.Editor, copy.name, readOnly = false)
                    }
                },
            )
        }
    }

    renderAll()

    // ── Escape-to-close (or back-to-list from the editor) ──
    val escHandler: (Event) -> Unit = { ev ->
        if ((ev as? KeyboardEvent)?.key == "Escape") {
            if (view == ManagerView.Editor) setView(ManagerView.List)
            else themeManagerOnCloseRequested?.invoke() ?: closeThemeManager()
        }
    }
    document.addEventListener("keydown", escHandler)
    themeManagerEscHandler = escHandler
    themeManagerRerender = { renderAll() }

    mountInto.appendChild(panel)
    themeManagerPanel = panel

    kotlinx.browser.window.requestAnimationFrame { panel.classList.add("dt-open") }
}

/**
 * Refresh the Theme Manager panel if it is currently open. Called from
 * upstream state observers (e.g. an appearance toggle) so selection
 * highlights re-sort. No-op when the panel is closed.
 */
fun refreshThemeManager() {
    themeManagerRerender?.invoke()
}

/** Notify the open manager (if any) to re-render. Internal alias of [refreshThemeManager]. */
internal fun pokeManager() {
    themeManagerRerender?.invoke()
}

/**
 * Renders the theme catalog into [container] as a reflowing thumbnail grid: a
 * "Dark" group (themes whose [ThemeGroup] is `Dark`) and a "Light" group, each
 * card built by [renderThemeCard]. The grid packs as many thumbnails per row as
 * the (resizable) sidebar width allows. The appearance (Auto/Dark/Light) is
 * chosen from the app's toolbar, not here.
 *
 * @param container the body element to fill.
 * @param onOpen    invoked with a theme's name and a read-only flag when its
 *   open-editor arrow is pressed (read-only for built-ins, editable for custom);
 *   the caller switches to the editor view.
 */
private fun renderThemeList(container: HTMLElement, onOpen: (String, Boolean) -> Unit) {
    val all = allThemes(host.customThemes)
    fun section(label: String, group: ThemeGroup) {
        val themes = all.filter { it.group == group }
        if (themes.isEmpty()) return
        val heading = document.createElement("h3") as HTMLElement
        heading.className = "dt-theme-group-heading"
        heading.textContent = label
        container.appendChild(heading)
        val list = document.createElement("div") as HTMLElement
        list.className = "dt-theme-list"
        for (theme in themes) list.appendChild(renderThemeCard(theme, onOpen))
        container.appendChild(list)
    }
    section("Dark", ThemeGroup.Dark)
    section("Light", ThemeGroup.Light)
}

/**
 * Builds one theme card: the name plus a small "open editor" arrow above a
 * mini-window thumbnail. Clicking the card body assigns the theme to the slot
 * for the currently-active appearance (dark mode → dark slot, light mode →
 * light slot); the assigned theme is highlighted via `dt-theme-card-assigned`.
 *
 * The arrow opens the theme's editor view ([onOpen]) — read-only for built-in
 * (default) themes, editable for custom ones. Every other per-theme action
 * (Clone, Delete) lives inside that editor, so the card stays compact and the
 * grid can pack many thumbnails per row.
 *
 * @param theme  the theme to render.
 * @param onOpen invoked with the theme name and a read-only flag when the
 *   open-editor arrow is pressed (read-only for built-ins, editable for custom).
 * @return the card element.
 */
private fun renderThemeCard(theme: Theme, onOpen: (String, Boolean) -> Unit): HTMLElement {
    val isCustom = builtinTheme(theme.name) == null
    // The slot a click fills is whichever mode is *currently active* (the
    // appearance preference, or the OS when Auto) — not the theme's own group.
    // So clicking any theme while in light mode sets the light slot, etc.
    val activeIsDark = isDarkActive(host.appearance)
    val assigned = if (activeIsDark) host.darkThemeName == theme.name
        else host.lightThemeName == theme.name

    val card = document.createElement("div") as HTMLElement
    card.className = "dt-theme-card" + if (assigned) " dt-theme-card-assigned" else ""
    card.setAttribute("role", "button")
    card.title = if (activeIsDark) "Use as the dark-mode theme"
        else "Use as the light-mode theme"

    // Title row: name on the left, a small arrow on the right that opens the
    // theme's editor (read-only for built-ins, editable for custom themes).
    // The name lives in a fixed two-line-tall area (so long names get a second
    // row) with its text bottom-anchored, so single-line names sit right above
    // the thumbnail.
    val titleRow = document.createElement("div") as HTMLElement
    titleRow.className = "dt-theme-card-title"
    val nameArea = document.createElement("div") as HTMLElement
    nameArea.className = "dt-theme-card-name-area"
    val nameEl = document.createElement("span") as HTMLElement
    nameEl.className = "dt-theme-card-name"
    nameEl.textContent = theme.name
    nameArea.appendChild(nameEl)
    titleRow.appendChild(nameArea)

    val openBtn = document.createElement("button") as HTMLElement
    openBtn.className = "dt-theme-card-open"
    openBtn.innerHTML = "&rsaquo;"
    openBtn.title = if (isCustom) "Edit theme" else "View theme"
    openBtn.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        onOpen(theme.name, !isCustom)
    })
    titleRow.appendChild(openBtn)
    card.appendChild(titleRow)

    card.appendChild(buildThemeThumb(theme))

    // Clicking the card (outside the open-editor arrow) assigns this theme to
    // the slot for the currently-active appearance.
    card.addEventListener("click", {
        if (activeIsDark) host.setDarkThemeName(theme.name)
        else host.setLightThemeName(theme.name)
        pokeManager()
    })

    return card
}

/**
 * Builds the realistic mini-app silhouette thumbnail for [theme]: a tab strip,
 * a sidebar column, two floating panes (one focused) each with their own
 * titlebar and a few code lines, and a bottom accent strip — coloured entirely
 * from the theme's resolved tokens, so each card previews the real app chrome
 * at a glance.
 *
 * This mirrors the pre-revamp `defaultRenderConfigSilhouetteHtml` look (which
 * the multi-scheme theme model drove); here each region is mapped onto the flat
 * [se.soderbjorn.darkness.core.ResolvedTheme] tokens. The `.dt-config-silhouette`
 * / `.dt-cs-*` class hierarchy (sizing, proportions) is defined in
 * `darkness-toolkit.css`; this builder only assigns the per-token colours.
 *
 * @param theme the theme to preview.
 * @return the thumbnail element (a `.dt-config-silhouette` flex column).
 */
private fun buildThemeThumb(theme: Theme): HTMLElement {
    val r = theme.resolve()
    fun c(v: Long) = argbToCss(v)

    // Token → region mapping. Named locals so the markup below reads like the
    // real chrome it mimics rather than a wall of `argbToCss(...)`.
    val tabsBg         = c(r.surfaceAlt)
    val tabsActiveBg   = c(r.surface)
    val tabsActiveRing = c(r.accent)
    val tabsActiveText = c(r.textBright)
    val tabsDim        = c(r.textDim)
    val tabsAccent     = c(r.accent)

    val sidebarBg   = c(r.surface)
    val sidebarText = c(r.text)
    val sidebarDim  = c(r.textDim)

    val windowsBg           = c(r.bg)
    val mainBg              = c(r.surface)
    val mainFg              = c(r.text)
    val paneBorder          = c(r.border)
    val paneTitleBg         = c(r.surfaceAlt)
    val paneTitleText       = c(r.textDim)
    val paneTitleBgActive   = c(r.surfaceAlt)
    val paneTitleTextActive = c(r.textBright)
    val activeBg            = c(r.accent)

    val thumb = document.createElement("div") as HTMLElement
    thumb.className = "dt-config-silhouette"
    thumb.style.background = windowsBg
    thumb.innerHTML = """
        <span class="dt-cs-tabs" style="background:$tabsBg">
            <span class="dt-cs-tab-toggle" style="background:$tabsDim"></span>
            <span class="dt-cs-tab dt-cs-tab-active" style="background:$tabsActiveBg;box-shadow:inset 0 0 0 1px $tabsActiveRing">
                <span class="dt-cs-tab-label" style="background:$tabsActiveText"></span>
            </span>
            <span class="dt-cs-tab">
                <span class="dt-cs-tab-label" style="background:$tabsDim"></span>
            </span>
            <span class="dt-cs-tab">
                <span class="dt-cs-tab-label" style="background:$tabsDim"></span>
            </span>
            <span class="dt-cs-tabs-spacer"></span>
            <span class="dt-cs-tab-icons">
                <span class="dt-cs-tab-icon" style="background:$tabsAccent"></span>
                <span class="dt-cs-tab-icon" style="background:$tabsDim"></span>
                <span class="dt-cs-tab-icon" style="background:$tabsDim"></span>
            </span>
        </span>
        <span class="dt-cs-body">
            <span class="dt-cs-sidebar" style="background:$sidebarBg">
                <span class="dt-cs-sb-header" style="background:$sidebarDim"></span>
                <span class="dt-cs-sb-item dt-cs-sb-item-active" style="box-shadow:inset 0 0 0 1px $activeBg">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
                <span class="dt-cs-sb-item">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
                <span class="dt-cs-sb-header" style="background:$sidebarDim"></span>
                <span class="dt-cs-sb-item">
                    <span class="dt-cs-sb-item-label" style="background:$sidebarText"></span>
                </span>
            </span>
            <span class="dt-cs-main" style="background:$windowsBg">
                <span class="dt-cs-pane dt-cs-pane-focused" style="box-shadow:inset 0 0 0 1px $paneBorder, 0 0 0 1px $activeBg">
                    <span class="dt-cs-pane-titlebar" style="background:$paneTitleBgActive">
                        <span class="dt-cs-pane-icon" style="background:$paneTitleTextActive"></span>
                        <span class="dt-cs-pane-title" style="background:$paneTitleTextActive"></span>
                        <span class="dt-cs-pane-titlebar-spacer"></span>
                        <span class="dt-cs-pane-icon" style="background:$paneTitleTextActive"></span>
                    </span>
                    <span class="dt-cs-pane-body" style="background:$mainBg">
                        <span class="dt-cs-line">
                            <span class="dt-cs-prompt" style="background:$activeBg"></span>
                            <span class="dt-cs-text dt-cs-text-long" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-mid" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-short" style="background:$mainFg"></span>
                        </span>
                    </span>
                </span>
                <span class="dt-cs-pane" style="box-shadow:inset 0 0 0 1px $paneBorder">
                    <span class="dt-cs-pane-titlebar" style="background:$paneTitleBg">
                        <span class="dt-cs-pane-icon" style="background:$paneTitleText"></span>
                        <span class="dt-cs-pane-title" style="background:$paneTitleText"></span>
                        <span class="dt-cs-pane-titlebar-spacer"></span>
                        <span class="dt-cs-pane-icon" style="background:$paneTitleText"></span>
                    </span>
                    <span class="dt-cs-pane-body" style="background:$mainBg">
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-long" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-short" style="background:$mainFg"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-mid" style="background:$mainFg"></span>
                        </span>
                    </span>
                </span>
            </span>
        </span>
        <span class="dt-cs-accent" style="background:$activeBg"></span>
    """.trimIndent()
    return thumb
}

/**
 * Produces a unique clone name from [base]: `"<base> (copy)"`, then
 * `"<base> (copy 2)"`, `"(copy 3)"`, … until the name collides with neither a
 * built-in nor an existing custom theme.
 *
 * @param base the source theme's name.
 * @return a name not currently in use.
 */
private fun dedupeCloneName(base: String): String {
    val existing = allThemes(host.customThemes).map { it.name }.toSet()
    val first = "$base (copy)"
    if (first !in existing) return first
    var i = 2
    while ("$base (copy $i)" in existing) i++
    return "$base (copy $i)"
}

// ── Shared name-prompt helper (kept for any future flows) ───────────

/**
 * Generic modal name prompt.
 *
 * @param title    modal title.
 * @param label    input label.
 * @param initial  initial input value.
 * @param validate returns an error string or `null` if valid.
 * @param onCommit called with the final, validated name.
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
