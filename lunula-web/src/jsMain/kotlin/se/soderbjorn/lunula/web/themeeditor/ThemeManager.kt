/**
 * Theme Manager modal entry point (post-revamp theme system).
 *
 * Public API:
 *  - [showThemeManager] — open the right-side sidebar that lets users browse,
 *    assign, clone, edit, and delete themes.
 *  - [closeThemeManager] — slide-out and detach.
 *  - [refreshThemeManager] — repaint when upstream state changes.
 *
 * This file owns the panel chrome (header, filter controls, Escape handling)
 * and the shared module state, then renders all themes as a single reflowing
 * thumbnail grid — no "Dark"/"Light" section headings (issue #107). The list is
 * ordered starred first, then the house themes, then everything else
 * alphabetically (see [se.soderbjorn.lunula.core.orderThemesForPicker]).
 *
 * The header carries two filters over that list: a free-text box matching name,
 * tag and description, and a category dropdown. The categories are *derived
 * from each palette* rather than from a label the theme carries — see
 * [se.soderbjorn.lunula.core.ThemeCategory] — so "Dark/Light Split" finds the
 * dark-chrome-over-light-content themes, and "White two-tone" the ones that are
 * light throughout but still split into two zones, including any the user
 * clones. Both filters live in the header so a list repaint never disturbs the
 * caret; see [themeFilterQuery]. Each card shows the theme
 * name above its thumbnail; clicking the card assigns the theme to the active
 * slot. On hover the card reveals two controls in its top-right corner: a star
 * (favorite / unfavorite, synced via the host) and, to its right, a small arrow
 * that opens the theme's editor — which holds the token swatches plus the Clone
 * and Delete actions. Colour-scheme tabs and per-pane sections are gone; the
 * editor view is the flat 20-token [renderThemeColorEditor].
 *
 * @see ThemeManagerHost
 */
package se.soderbjorn.lunula.web.themeeditor

import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.Theme
import se.soderbjorn.lunula.core.ThemeCategory
import se.soderbjorn.lunula.core.allThemes
import se.soderbjorn.lunula.core.argbToCss
import se.soderbjorn.lunula.core.builtinTheme
import se.soderbjorn.lunula.core.filterThemesForPicker
import se.soderbjorn.lunula.web.isDarkActive

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
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

/**
 * Last known scroll offset of the theme-manager body (`.dt-theme-manager-body`).
 *
 * The app shell rerenders on state changes unrelated to the theme manager —
 * notably, in termtastic every chunk of terminal output pushes a tab-source
 * snapshot that runs the shell's `rerender()`, which clears the right-sidebar
 * slot (`innerHTML = ""`) and then re-appends this *same* preserved panel
 * element (see [showThemeManager]'s orphan-recovery branch). Detaching a
 * scroll container from the DOM zeroes its `scrollTop`, so without this memory
 * the theme list snapped back to the top on every output tick (issue #106).
 *
 * A `scroll` listener installed on the body in [showThemeManager] keeps this
 * value current; the re-append branch restores it once the panel is back in
 * the laid-out document.
 */
private var themeManagerBodyScrollTop: Double = 0.0

/**
 * Current contents of the list's free-text filter box.
 *
 * Module-level for the same reason as [themeManagerBodyScrollTop]: the panel is
 * torn down and rebuilt by unrelated shell rerenders. The input element itself
 * lives in the *header*, which [renderAll] never touches, so within one open
 * session the caret and selection survive a list repaint for free — this
 * mirror exists so the filter also survives the panel being detached and
 * re-appended. Reset when the panel is built fresh, so opening the picker
 * always starts from the whole catalog.
 */
private var themeFilterQuery: String = ""

/** Currently-selected category filter. Reset alongside [themeFilterQuery]. */
private var themeFilterCategory: ThemeCategory = ThemeCategory.All

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
        // Re-inserting a detached subtree makes the browser replay every CSS
        // transition on its descendants. The only transitioned elements here
        // are the hover-revealed per-card controls — the favorite star
        // (`.dt-theme-card-star`) and the open-editor arrow
        // (`.dt-theme-card-open`), which fade in via an `opacity` transition on
        // `.dt-theme-card:hover`. So on every output-driven shell rerender (each
        // one detaches + re-appends this panel) those two buttons visibly
        // flashed on macOS (issue #106 follow-up). Suppress transitions across
        // the reattach: add `dt-no-transitions` (kills them via CSS), append,
        // force a synchronous reflow so the transition-free computed style is
        // committed, then drop the class on the next frame. The hovered button
        // snaps to its current opacity with no animation, and re-enabling
        // transitions afterwards causes no style change — so genuine hover
        // fades still animate on real pointer moves.
        existing.classList.add("dt-no-transitions")
        mountInto.appendChild(existing)
        // Force layout/style flush so `dt-no-transitions` takes effect for this
        // reattach before we re-enable transitions below.
        existing.getBoundingClientRect()
        // Re-appending a previously-detached scroll container resets its
        // scrollTop to 0. This branch runs when the shell's rerender cleared
        // the right-sidebar slot (`innerHTML = ""`) and orphaned this panel —
        // in termtastic that fires on every chunk of terminal output. Restore
        // the user's scroll offset once the panel is back in the laid-out
        // document so the theme list no longer jumps to the top (issue #106).
        // Deferred a frame because on the shell's re-mount path `mountInto` is
        // not itself attached yet when this runs.
        val body = existing.querySelector(".dt-theme-manager-body") as? HTMLElement
        kotlinx.browser.window.requestAnimationFrame {
            if (body != null) body.scrollTop = themeManagerBodyScrollTop
            existing.classList.remove("dt-no-transitions")
        }
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

    // ── Filter row: free-text box + category dropdown ──
    //
    // Deliberately in the header rather than the body. `renderAll` clears the
    // body on every repaint — including the ones driven by unrelated app state
    // (in termtastic, every chunk of terminal output) — so an input mounted
    // there would lose its caret mid-word. Here it is built once and simply
    // outlives the list it filters.
    //
    // Both controls start from module state so a panel rebuilt underneath the
    // user comes back showing the filter they had applied.
    themeFilterQuery = ""
    themeFilterCategory = ThemeCategory.All

    val filterRow = document.createElement("div") as HTMLElement
    filterRow.className = "dt-theme-filter"

    val filterInput = document.createElement("input") as HTMLInputElement
    filterInput.className = "dt-theme-filter-input"
    // `search` rather than `text` for the browser's built-in clear affordance.
    filterInput.type = "search"
    filterInput.placeholder = "Filter themes…"
    filterInput.value = themeFilterQuery
    filterInput.setAttribute("autocomplete", "off")
    filterInput.setAttribute("spellcheck", "false")
    filterInput.setAttribute("aria-label", "Filter themes by name")
    filterRow.appendChild(filterInput)

    val filterSelect = document.createElement("select") as HTMLSelectElement
    filterSelect.className = "dt-theme-filter-select"
    filterSelect.setAttribute("aria-label", "Filter themes by category")
    for (category in ThemeCategory.entries) {
        val opt = document.createElement("option") as HTMLOptionElement
        opt.value = category.name
        opt.text = category.label
        if (category == themeFilterCategory) opt.selected = true
        filterSelect.appendChild(opt)
    }
    filterRow.appendChild(filterSelect)

    header.appendChild(filterRow)

    panel.appendChild(header)

    // ── Body (single column: list or editor) ──
    val body = document.createElement("div") as HTMLElement
    body.className = "dt-theme-manager-body"
    panel.appendChild(body)

    // Remember the body's scroll offset as the user scrolls, so a later shell
    // rerender (which detaches and re-appends this panel — see
    // [themeManagerBodyScrollTop]) can restore it instead of snapping the theme
    // list back to the top on unrelated state changes such as terminal output
    // (issue #106). Reset to 0 here because this is a fresh panel build.
    themeManagerBodyScrollTop = 0.0
    body.addEventListener("scroll", { themeManagerBodyScrollTop = body.scrollTop })

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
        // The filter belongs to the list; the editor view has nothing to filter.
        filterRow.style.display = if (view == ManagerView.List) "" else "none"
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

    // Typing or changing the category repaints the list only — the controls
    // themselves are in the header and are not rebuilt, so focus and caret
    // position are untouched and filtering feels continuous.
    filterInput.addEventListener("input", {
        themeFilterQuery = filterInput.value
        renderAll()
    })
    // Escape inside a non-empty filter box clears the filter instead of closing
    // the whole panel — the document-level handler below would otherwise take
    // the keystroke, which reads as the panel slamming shut over a typo.
    filterInput.addEventListener("keydown", { ev: Event ->
        if ((ev as? KeyboardEvent)?.key == "Escape" && filterInput.value.isNotEmpty()) {
            ev.stopPropagation()
            filterInput.value = ""
            themeFilterQuery = ""
            renderAll()
        }
    })
    filterSelect.addEventListener("change", {
        themeFilterCategory = ThemeCategory.entries
            .firstOrNull { it.name == filterSelect.value } ?: ThemeCategory.All
        renderAll()
    })

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

    // Scroll the currently-assigned theme into view the first time the picker
    // opens, so the active theme is visible instead of the list always starting
    // at the top (issue #105). Done only here — on the fresh panel build — and
    // never from [renderAll]/rerenders, so it doesn't fight the scroll-offset
    // preservation that keeps the user's place across output-driven shell
    // rerenders (issue #106). Only meaningful in the list view; the editor view
    // (opened via the clone flow's `focusTheme`) has nothing to centre.
    if (view == ManagerView.List) {
        kotlinx.browser.window.requestAnimationFrame { scrollActiveThemeIntoView(body) }
    }
}

/**
 * Scrolls the theme-manager [body] so the currently-assigned theme card
 * (`.dt-theme-card-assigned`) is vertically centred in view.
 *
 * Called once from [showThemeManager] right after the panel is first mounted
 * (issue #105), so opening the picker reveals the active theme rather than
 * always starting at the top of the list. Deliberately not called from
 * rerenders, so it does not fight the scroll-offset preservation that keeps the
 * user's place across output-driven shell rerenders (issue #106).
 *
 * No-op when no card is assigned (e.g. the active theme is not in the catalog)
 * or when the body has no overflow to scroll. Also updates
 * [themeManagerBodyScrollTop] so the first #106 restore lands on the centred
 * position rather than snapping back to the top.
 *
 * @param body the `.dt-theme-manager-body` scroll container.
 */
private fun scrollActiveThemeIntoView(body: HTMLElement) {
    val card = body.querySelector(".dt-theme-card-assigned") as? HTMLElement ?: return
    val bodyRect = body.getBoundingClientRect()
    val cardRect = card.getBoundingClientRect()
    // Card's top relative to the body's current scroll position, shifted up by
    // half the leftover vertical space so the card lands centred.
    val target = body.scrollTop + (cardRect.top - bodyRect.top) -
        (bodyRect.height - cardRect.height) / 2.0
    val max = (body.scrollHeight - body.clientHeight).toDouble().coerceAtLeast(0.0)
    body.scrollTop = target.coerceIn(0.0, max)
    themeManagerBodyScrollTop = body.scrollTop
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
 * Renders the theme catalog into [container] as a single reflowing thumbnail
 * grid — one flat list with no "Dark"/"Light" section headings (issue #107).
 * The themes are ordered starred first, then the house themes, then the rest by
 * name, and narrowed by the header's category dropdown and filter box, all by
 * [filterThemesForPicker]; each card is built by [renderThemeCard].
 * The grid packs as many thumbnails per row as the (resizable) sidebar width
 * allows. The appearance (Auto/Dark/Light) is chosen from the app's toolbar,
 * not here.
 *
 * When the filters exclude everything, an explanatory row is rendered in place
 * of an empty grid — a blank panel reads as a broken picker rather than as a
 * search with no hits.
 *
 * @param container the body element to fill.
 * @param onOpen    invoked with a theme's name and a read-only flag when its
 *   open-editor arrow is pressed (read-only for built-ins, editable for custom);
 *   the caller switches to the editor view.
 */
private fun renderThemeList(container: HTMLElement, onOpen: (String, Boolean) -> Unit) {
    val matching = filterThemesForPicker(
        themes = allThemes(host.customThemes),
        favorites = host.favoriteThemeNames,
        category = themeFilterCategory,
        query = themeFilterQuery,
    )
    if (matching.isEmpty()) {
        container.appendChild(buildNoThemesMatchRow())
        return
    }
    val list = document.createElement("div") as HTMLElement
    list.className = "dt-theme-list"
    for (theme in matching) list.appendChild(renderThemeCard(theme, onOpen))
    container.appendChild(list)
}

/**
 * The row shown when the category dropdown and filter box between them match no
 * theme. Names both active filters, so the user can see which one to relax
 * rather than guessing why the panel is empty.
 *
 * @return the message element.
 * @see renderThemeList
 */
private fun buildNoThemesMatchRow(): HTMLElement {
    val empty = document.createElement("div") as HTMLElement
    empty.className = "dt-theme-list-empty"
    val query = themeFilterQuery.trim()
    empty.textContent = when {
        query.isNotEmpty() && themeFilterCategory != ThemeCategory.All ->
            "No ${themeFilterCategory.label} theme matches “$query”."
        query.isNotEmpty() -> "No theme matches “$query”."
        themeFilterCategory == ThemeCategory.Starred ->
            "No starred themes yet — use the ☆ on a theme card."
        else -> "No ${themeFilterCategory.label} themes."
    }
    return empty
}

/**
 * Builds one theme card: the name plus two hover-revealed top-right controls (a
 * favorite star and an "open editor" arrow) above a mini-shell thumbnail
 * ([buildThemeThumb]).
 * Clicking the card body assigns the theme to the slot for the currently-active
 * appearance (dark mode → dark slot, light mode → light slot); the assigned
 * theme is highlighted via `dt-theme-card-assigned`.
 *
 * The star toggles the theme's favorite state ([ThemeManagerHost.toggleFavorite])
 * — filled when starred, hollow otherwise — and is always visible while starred
 * (so the user can see which themes are favorites without hovering). The arrow
 * opens the theme's editor view ([onOpen]) — read-only for built-in (default)
 * themes, editable for custom ones. Every other per-theme action (Clone, Delete)
 * lives inside that editor, so the card stays compact and the grid can pack many
 * thumbnails per row.
 *
 * @param theme  the theme to render.
 * @param onOpen invoked with the theme name and a read-only flag when the
 *   open-editor arrow is pressed (read-only for built-ins, editable for custom).
 * @return the card element.
 */
private fun renderThemeCard(theme: Theme, onOpen: (String, Boolean) -> Unit): HTMLElement {
    val isCustom = builtinTheme(theme.name) == null
    val isFavorite = theme.name in host.favoriteThemeNames
    // The slot a click fills is whichever mode is *currently active* (the
    // appearance preference, or the OS when Auto) — not the theme's own group.
    // So clicking any theme while in light mode sets the light slot, etc.
    val activeIsDark = isDarkActive(host.appearance)
    val assigned = if (activeIsDark) host.darkThemeName == theme.name
        else host.lightThemeName == theme.name

    val card = document.createElement("div") as HTMLElement
    card.className = "dt-theme-card" +
        (if (assigned) " dt-theme-card-assigned" else "") +
        (if (isFavorite) " dt-theme-card-favorite" else "")
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

    // Favorite star, sitting just to the left of the open-editor arrow. Filled
    // (★) when starred, hollow (☆) otherwise. Toggling re-sorts the list, so we
    // poke the manager to repaint after the host persists the change.
    val starBtn = document.createElement("button") as HTMLElement
    starBtn.className = "dt-theme-card-star" + if (isFavorite) " dt-theme-card-star-on" else ""
    starBtn.innerHTML = if (isFavorite) "&#9733;" else "&#9734;"
    starBtn.title = if (isFavorite) "Unstar theme" else "Star theme"
    starBtn.setAttribute("aria-pressed", isFavorite.toString())
    starBtn.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        host.toggleFavorite(theme.name)
        pokeManager()
    })
    titleRow.appendChild(starBtn)

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
 * The corner radius the shell uses when the user has set none — the fallback
 * baked into `--dt-frame-radius` in `lunula.css`. Kept in sync by hand; the
 * thumbnail is the only place that has to know the number, because CSS reads
 * its own fallback and this builder cannot.
 */
private const val DEFAULT_CORNER_RADIUS_PX = 18

/**
 * The cap `lunula.css` applies when deriving `--dt-tab-radius` from
 * `--dt-corner-radius` (`min(radius, 11px)`), so a very round pane setting
 * doesn't turn the tab pills into circles.
 */
private const val TAB_RADIUS_CAP_PX = 11

/**
 * How much of a real corner radius survives into the thumbnail.
 *
 * A true-to-scale mapping (thumbnail height ÷ window height, roughly 1:13)
 * would render the default 18px radius as 1.4px and every setting between 0
 * and 24 as "slightly soft" — technically faithful, visually nothing. These
 * two factors are a deliberate exaggeration, chosen so the *ratio* of radius
 * to element size lands near the real one: a pane at the default setting reads
 * as gently rounded, a pill reads as a pill, and 0 still reads as square.
 */
private const val PANE_RADIUS_SCALE = 0.18
private const val TAB_RADIUS_SCALE = 0.30

/**
 * Builds the mini-shell silhouette thumbnail for [theme] — a miniature of what
 * the app actually looks like wearing it.
 *
 * The silhouette is the real shell's layer order, not a generic window mockup:
 * a **chrome** frame (top bar with its tab strip, sidebar column, bottom status
 * bar) wrapping an inset, rounded **canvas** that holds two panes, the first
 * focused. That structure is load-bearing, because the two zones are separately
 * themable. A theme like *Lunamux Split* — dark navy chrome around a white
 * workspace — paints `chromeBg`/`canvas` nowhere near its `bg`, and any preview
 * that fills one flat background renders it identically to plain *Lunamux
 * Light*. Splitting the frame from the canvas is what makes those themes
 * distinguishable at 135px wide.
 *
 * Two things beyond the palette decide how the real shell looks, and both are
 * honoured here rather than assumed:
 *
 *  - **Selection style** ([ThemeManagerHost.selectionStyle]). Under
 *    [SelectionStyle.Fill] the active tab, the active sidebar row and the
 *    focused pane's header are solid accent fields with `accentOn` type, and
 *    panes carry no outline; under [SelectionStyle.Tint] the same three
 *    surfaces are an accent wash under a 1px accent ring, and the focused pane
 *    keeps its accent outline and glow. Chrome-zone selections fill with
 *    `chromeAccent` and content-zone ones with `accent` — on a theme that
 *    splits those two, that difference *is* the look.
 *  - **Corner radius** ([ThemeManagerHost.cornerRadiusPx]), scaled down for the
 *    thumbnail — see [PANE_RADIUS_SCALE].
 *
 * Density ([ThemeManagerHost.uiDensity]) is deliberately *not* reflected: its
 * three steps differ by a few pixels of padding, which is under one pixel at
 * this scale and would only add noise.
 *
 * The `.dt-config-silhouette` / `.dt-cs-*` class hierarchy (sizing,
 * proportions) is defined in `lunula.css`; this builder assigns every colour
 * inline, because they come from the theme being previewed rather than the
 * theme in force.
 *
 * @param theme the theme to preview.
 * @return the thumbnail element (a `.dt-config-silhouette` flex column).
 * @see SelectionStyle
 * @see se.soderbjorn.lunula.core.ResolvedTheme
 */
private fun buildThemeThumb(theme: Theme): HTMLElement {
    val r = theme.resolve()
    fun c(v: Long) = argbToCss(v)

    val fill = (host.selectionStyle ?: SelectionStyle.Default) == SelectionStyle.Fill
    val cornerPx = host.cornerRadiusPx ?: DEFAULT_CORNER_RADIUS_PX
    val paneRadius = (cornerPx * PANE_RADIUS_SCALE).coerceIn(0.0, 6.0)
    val tabRadius = (minOf(cornerPx, TAB_RADIUS_CAP_PX) * TAB_RADIUS_SCALE).coerceIn(0.0, 4.0)

    // ── Chrome zone: top bar, sidebar, status bar ───────────────────────
    val chromeBg = c(r.chromeBg)
    val chromeText = c(r.chromeText)
    val chromeDim = c(r.chromeTextDim)
    val chromeBorder = c(r.chromeBorder)
    // The active tab and the active sidebar row are one treatment in two
    // places (`.dt-tab.dt-selected` / `.dt-sidebar-row.dt-active`), so they
    // share these two values rather than computing the same thing twice.
    val chromeSelBg = if (fill) "background:${c(r.chromeAccent)}"
        else "background:${c(r.chromeAccentSoft)};box-shadow:inset 0 0 0 1px ${c(r.chromeAccent)}"
    val chromeSelFg = if (fill) c(r.chromeAccentOn) else c(r.chromeTextBright)

    // ── Content zone: canvas, panes, pane content ───────────────────────
    val canvas = c(r.canvas)
    val surface = c(r.surface)
    val text = c(r.text)
    val textDim = c(r.textDim)
    // Focused pane header: solid accent under Fill, accent wash under Tint.
    val headerSel = if (fill) "background:${c(r.accent)}" else "background:${c(r.accentSoft)}"
    val headerSelFg = if (fill) c(r.accentOn) else c(r.textBright)
    // …and the resting header lifts to the sunken tone under Fill, exactly as
    // `:root[data-dt-selection="fill"] .dt-pane-header` does.
    val headerRest = if (fill) c(r.surfaceAlt) else surface
    // Fill drops the pane outline and the focus glow entirely — with a solid
    // header band doing the work, the ring is a second voice saying the same
    // word. Under Tint both are what marks the focused pane.
    val paneRing = if (fill) "" else "box-shadow:inset 0 0 0 1px ${c(r.border)};"
    val focusRing = if (fill) "" else
        "box-shadow:inset 0 0 0 1px ${c(r.accent)}, 0 0 5px -1px ${c(r.glow)};"

    val thumb = document.createElement("div") as HTMLElement
    thumb.className = "dt-config-silhouette"
    thumb.style.background = chromeBg
    thumb.innerHTML = """
        <span class="dt-cs-topbar">
            <span class="dt-cs-toggle" style="background:$chromeDim"></span>
            <span class="dt-cs-tab" style="$chromeSelBg;border-radius:${tabRadius}px">
                <span class="dt-cs-tab-label" style="background:$chromeSelFg"></span>
            </span>
            <span class="dt-cs-tab" style="box-shadow:inset 0 0 0 1px $chromeBorder;border-radius:${tabRadius}px">
                <span class="dt-cs-tab-label" style="background:$chromeDim"></span>
            </span>
            <span class="dt-cs-tab" style="box-shadow:inset 0 0 0 1px $chromeBorder;border-radius:${tabRadius}px">
                <span class="dt-cs-tab-label" style="background:$chromeDim"></span>
            </span>
            <span class="dt-cs-spacer"></span>
            <span class="dt-cs-topbar-icons">
                <span class="dt-cs-topbar-icon" style="background:$chromeText"></span>
                <span class="dt-cs-topbar-icon" style="background:$chromeText"></span>
                <span class="dt-cs-topbar-icon" style="background:$chromeText"></span>
            </span>
        </span>
        <span class="dt-cs-body">
            <span class="dt-cs-sidebar" style="border-right:1px solid $chromeBorder">
                <span class="dt-cs-sb-header" style="background:$chromeDim"></span>
                <span class="dt-cs-sb-row" style="$chromeSelBg;border-radius:${tabRadius}px">
                    <span class="dt-cs-sb-row-label" style="background:$chromeSelFg"></span>
                </span>
                <span class="dt-cs-sb-row">
                    <span class="dt-cs-sb-row-label" style="background:$chromeText"></span>
                </span>
                <span class="dt-cs-sb-header" style="background:$chromeDim"></span>
                <span class="dt-cs-sb-row">
                    <span class="dt-cs-sb-row-label" style="background:$chromeText"></span>
                </span>
            </span>
            <span class="dt-cs-canvas" style="background:$canvas;border-radius:${paneRadius}px">
                <span class="dt-cs-pane" style="background:$surface;border-radius:${paneRadius}px;$focusRing">
                    <span class="dt-cs-pane-header" style="$headerSel">
                        <span class="dt-cs-pane-icon" style="background:$headerSelFg"></span>
                        <span class="dt-cs-pane-title" style="background:$headerSelFg"></span>
                    </span>
                    <span class="dt-cs-pane-body">
                        <span class="dt-cs-line">
                            <span class="dt-cs-prompt" style="background:${c(r.accent)}"></span>
                            <span class="dt-cs-text dt-cs-text-long" style="background:$text"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-mid" style="background:$textDim"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-short" style="background:$text"></span>
                        </span>
                    </span>
                </span>
                <span class="dt-cs-pane" style="background:$surface;border-radius:${paneRadius}px;$paneRing">
                    <span class="dt-cs-pane-header" style="background:$headerRest">
                        <span class="dt-cs-pane-icon" style="background:$textDim"></span>
                        <span class="dt-cs-pane-title" style="background:$textDim"></span>
                    </span>
                    <span class="dt-cs-pane-body">
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-mid" style="background:${c(r.synKeyword)}"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-long" style="background:${c(r.synString)}"></span>
                        </span>
                        <span class="dt-cs-line">
                            <span class="dt-cs-text dt-cs-text-indent dt-cs-text-short" style="background:$text"></span>
                        </span>
                    </span>
                </span>
            </span>
        </span>
        <span class="dt-cs-statusbar">
            <span class="dt-cs-status-tick dt-cs-status-tick-wide" style="background:$chromeDim"></span>
            <span class="dt-cs-status-tick dt-cs-status-tick-narrow" style="background:$chromeDim"></span>
            <span class="dt-cs-spacer"></span>
            <span class="dt-cs-status-tick dt-cs-status-tick-end" style="background:$chromeDim"></span>
        </span>
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
