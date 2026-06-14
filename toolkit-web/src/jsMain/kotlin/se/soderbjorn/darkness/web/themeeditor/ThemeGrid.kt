/**
 * Theme tab list view: filter chips, grouped grid of theme cards, per-card
 * kebab menu, and the new/clone prompts.
 */
package se.soderbjorn.darkness.web.themeeditor

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.escapeHtml
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** Filter chip state for the theme grid. */
internal enum class ThemeFilter { All, Favorites, Default, Custom }

/**
 * Render the theme list pane: filter pills + grid of theme cards grouped by
 * compatibility mode. Groups are reordered so the one matching the
 * currently-active appearance appears first.
 *
 * @param container   the pane element to populate
 * @param filter      active filter chip
 * @param selected    name of the theme to highlight (the active-mode slot)
 * @param onFilter    callback when the user clicks a filter pill
 * @param onAssign    callback when the user clicks a theme card (assigns
 *                    the theme to the currently-live mode)
 * @param onEdit      callback when the user picks Edit from the card's
 *                    kebab menu, or creates a new theme via the tile
 */
internal fun renderThemesLeft(
    container: HTMLElement,
    filter: ThemeFilter,
    selected: String?,
    onFilter: (ThemeFilter) -> Unit,
    onAssign: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val state = themeManagerHost()

    val chips = document.createElement("div") as HTMLElement
    chips.className = "dt-theme-manager-filters"
    for (f in ThemeFilter.values()) {
        val label = when (f) {
            ThemeFilter.All -> "All"
            ThemeFilter.Favorites -> "★ Favorites"
            ThemeFilter.Default -> "Default"
            ThemeFilter.Custom -> "Custom"
        }
        val chip = document.createElement("button") as HTMLElement
        chip.className = "dt-theme-manager-chip" + if (f == filter) " dt-selected" else ""
        chip.textContent = label
        chip.addEventListener("click", { onFilter(f) })
        chips.appendChild(chip)
    }
    container.appendChild(chips)

    data class Row(val preset: Theme, val isDefault: Boolean)

    val allRows = buildList {
        for ((_, t) in state.customThemes) add(Row(t, isDefault = false))
        for (t in defaultThemes) add(Row(t, isDefault = true))
    }

    val filtered = allRows.filter { row ->
        when (filter) {
            ThemeFilter.All -> true
            ThemeFilter.Favorites -> row.preset.name in state.favoriteThemes
            ThemeFilter.Default -> row.isDefault
            ThemeFilter.Custom -> !row.isDefault
        }
    }

    val isDark = isDarkActive(state.appearance)
    val groupOrder = if (isDark) {
        listOf(ConfigMode.Dark to "Optimized for dark",
            ConfigMode.Both to "Optimized for Dark and Light",
            ConfigMode.Light to "Optimized for light")
    } else {
        listOf(ConfigMode.Light to "Optimized for light",
            ConfigMode.Both to "Optimized for Dark and Light",
            ConfigMode.Dark to "Optimized for dark")
    }

    // Pull the active theme out and render it as its own row at the very top,
    // so the currently-applied theme is always the first card the user sees.
    //
    // Look the active theme up in `allRows` (not `filtered`) so the
    // Active section persists across filter changes and across appearance
    // toggles. After flipping dark/light, the active slot can resolve to
    // a theme that the current filter excludes (e.g. filter = Custom but
    // the new appearance's slot points at a Default theme); the Active
    // section should still surface it so the user always sees what's
    // currently applied.
    //
    // Fallback chain: if [selected] doesn't match any known theme — because
    // the slot points at a deleted custom theme, or persistence handed us a
    // stale name the resolver no longer knows — fall back to the appearance-
    // appropriate default. The Active section always reflects what the
    // active-theme resolver paints with, so the user is never left
    // wondering "which of these is actually applied right now".
    val activeRow = allRows.firstOrNull { it.preset.name == selected }
        ?: run {
            val fallbackName = if (isDark) DEFAULT_DARK_THEME_NAME else DEFAULT_LIGHT_THEME_NAME
            allRows.firstOrNull { it.preset.name == fallbackName }
        }
    if (activeRow != null) {
        val activeTitle = document.createElement("div") as HTMLElement
        activeTitle.className = "dt-theme-manager-group-title"
        activeTitle.textContent = "Active"
        container.appendChild(activeTitle)

        val activeGrid = document.createElement("div") as HTMLElement
        activeGrid.className = "dt-theme-manager-grid"
        activeGrid.appendChild(buildThemeCard(activeRow.preset, activeRow.isDefault, true, onAssign, onEdit))
        container.appendChild(activeGrid)
    }

    // Group every filtered theme — including the one rendered above as
    // "Active" — by mode. The active theme deliberately appears twice:
    // once at the top and once in its mode-group below, so the user can
    // still find it where they expect it (e.g. under "Optimized for dark")
    // when scanning for alternatives.
    val grouped = filtered.groupBy { it.preset.mode }

    // Favorites bubble to the top within each mode group so the user's
    // starred themes are always one click away (mirrors SchemeGrid).
    val favs = state.favoriteThemes.toSet()
    fun sortFavoritesFirst(rows: List<Row>): List<Row> =
        rows.sortedByDescending { it.preset.name in favs }

    // The card-level `dt-selected` highlight tracks the Active section's
    // resolved theme (which already accounts for the slot fallback above)
    // so the in-group highlight stays consistent with what the user sees
    // labelled as "Active" at the top.
    val highlightName = activeRow?.preset?.name ?: selected
    for ((mode, header) in groupOrder) {
        val rows = grouped[mode] ?: continue
        if (rows.isEmpty()) continue

        val groupTitle = document.createElement("div") as HTMLElement
        groupTitle.className = "dt-theme-manager-group-title"
        groupTitle.textContent = header
        container.appendChild(groupTitle)

        val grid = document.createElement("div") as HTMLElement
        grid.className = "dt-theme-manager-grid"
        for (row in sortFavoritesFirst(rows)) {
            grid.appendChild(buildThemeCard(row.preset, row.isDefault, row.preset.name == highlightName, onAssign, onEdit))
        }
        container.appendChild(grid)
    }

    if (filtered.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "dt-theme-manager-empty"
        empty.textContent = when (filter) {
            ThemeFilter.Favorites -> "No favorites yet — star a theme to add it here."
            ThemeFilter.Custom -> "No custom themes yet — click + New theme to create one."
            else -> "No themes match this filter."
        }
        container.appendChild(empty)
    }

    if (filter != ThemeFilter.Default) {
        val trailingGrid = document.createElement("div") as HTMLElement
        trailingGrid.className = "dt-theme-manager-grid"
        val newTile = document.createElement("div") as HTMLElement
        newTile.className = "dt-new-tile"
        newTile.innerHTML =
            """<span class="dt-new-tile-plus">+</span><span class="dt-new-tile-label">New theme</span>"""
        newTile.addEventListener("click", {
            promptNewTheme { name -> onEdit(name) }
        })
        trailingGrid.appendChild(newTile)
        container.appendChild(trailingGrid)
    }
}

/**
 * Build a single theme card with silhouette, name, favorite star, and kebab
 * menu. Clicking the card assigns the theme to the currently-live mode;
 * the kebab menu exposes Edit / Clone / Delete actions.
 */
private fun buildThemeCard(
    preset: Theme,
    isDefault: Boolean,
    isSelected: Boolean,
    onAssign: (String) -> Unit,
    onEdit: (String) -> Unit,
): HTMLElement {
    val card = document.createElement("div") as HTMLElement
    card.className = "dt-theme-card" +
        if (isSelected) " dt-selected" else ""
    card.setAttribute("data-theme-name", preset.name)

    val state = themeManagerHost()
    val isFav = preset.name in state.favoriteThemes

    val previewScheme = resolveSchemeByName(preset.colorScheme)
        ?: recommendedColorSchemes.first()
    applyCardPaletteVars(card, previewScheme)

    val star = document.createElement("button") as HTMLElement
    star.className = "dt-theme-card-star" + if (isFav) " dt-active" else ""
    star.innerHTML = if (isFav) "★" else "☆"
    star.title = if (isFav) "Unfavorite" else "Favorite"
    star.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().toggleFavoriteTheme(preset.name)
        }
        pokeManager()
    })
    card.appendChild(star)

    val kebab = document.createElement("button") as HTMLElement
    kebab.className = "dt-theme-card-kebab"
    kebab.innerHTML = "⋮"
    kebab.title = "More"
    kebab.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        showThemeCardMenu(kebab, preset, isDefault, onEdit)
    })
    card.appendChild(kebab)

    val silhouette = document.createElement("span") as HTMLElement
    silhouette.className = "dt-theme-card-silhouette"
    silhouette.innerHTML = themeManagerHost().renderConfigSilhouetteHtml(preset)
    card.appendChild(silhouette)

    val nameRow = document.createElement("div") as HTMLElement
    nameRow.className = "dt-theme-card-name"
    val nameText = document.createElement("span") as HTMLElement
    nameText.textContent = preset.name
    nameRow.appendChild(nameText)

    card.appendChild(nameRow)

    card.addEventListener("click", { onAssign(preset.name) })
    return card
}

/**
 * Open a small menu anchored to the kebab button with edit / clone / delete
 * actions. Slot assignment happens via a plain click on the card itself.
 */
private fun showThemeCardMenu(
    anchor: HTMLElement,
    preset: Theme,
    isDefault: Boolean,
    onEdit: (String) -> Unit,
) {
    document.querySelectorAll(".dt-popover-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }
    val menu = document.createElement("div") as HTMLElement
    menu.className = "dt-popover-menu"

    fun addItem(label: String, onClick: () -> Unit) {
        val item = document.createElement("button") as HTMLElement
        item.className = "dt-popover-menu-item"
        item.textContent = label
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            onClick()
            menu.remove()
        })
        menu.appendChild(item)
    }

    fun addDisabledItem(label: String, tooltip: String) {
        val item = document.createElement("button") as HTMLElement
        item.className = "dt-popover-menu-item"
        item.textContent = label
        item.setAttribute("disabled", "true")
        item.title = tooltip
        item.style.opacity = "0.45"
        item.style.cursor = "not-allowed"
        // Swallow the click so the dismissal path doesn't act on it
        // (the menu would close on a stray click otherwise, which makes
        // the disabled affordance feel "broken" rather than disabled).
        item.addEventListener("click", { ev: Event -> ev.stopPropagation() })
        menu.appendChild(item)
    }

    // Default themes are read-only — editing one would let the user
    // accidentally clobber the shipped baseline that other apps still
    // expect. We surface a disabled `Edit…` row so the affordance stays
    // visible (users can see the menu shape they'd get on a custom
    // theme) and a help title that nudges them toward Clone.
    if (isDefault) {
        addDisabledItem(
            label = "Edit…",
            tooltip = "Default themes are read-only — clone to edit.",
        )
    } else {
        addItem("Edit…") {
            onEdit(preset.name)
        }
    }
    addItem("Clone…") {
        showCloneThemePrompt(preset)
    }
    if (!isDefault) {
        addItem("Delete") {
            showConfirmDialog(
                title = "Delete theme",
                message = "Delete theme <b>${escapeHtml(preset.name)}</b>?",
                confirmLabel = "Delete",
                messageIsHtml = true,
                onConfirm = {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        themeManagerHost().deleteCustomTheme(preset.name)
                    }
                    pokeManager()
                },
            )
        }
    }

    // Per-instance positioning anchored to the kebab button — kept inline.
    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.left = "${rect.right - 200}px"
    document.body?.appendChild(menu)

    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}

/**
 * Prompt the user for a name and create a clone of [source] under the new
 * name. Defaults for the new name append " Copy". After save, the manager
 * (if open) is switched to the editor view for the new theme.
 */
internal fun showCloneThemePrompt(source: Theme) {
    val defaultName = "${source.name} Copy"
    val existing = buildSet {
        for (t in defaultThemes) add(t.name)
        for ((name, _) in themeManagerHost().customThemes) add(name)
    }
    showNamePrompt(
        title = "Clone theme",
        label = "New theme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A theme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val clone = source.copy(name = newName)
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().saveCustomTheme(clone)
        }
        val focus = themeManagerFocusTheme
        if (focus != null) focus(newName) else pokeManager()
    }
}

/**
 * Prompt for a fresh custom theme built from scratch (not cloned). The new
 * preset defaults its main scheme to the currently-active main scheme so it
 * renders sensibly out of the box; mode defaults to Both and all per-section
 * overrides are empty.
 *
 * @param onCreated called with the new theme's name on success.
 */
private fun promptNewTheme(onCreated: (String) -> Unit) {
    val state = themeManagerHost()
    val existing = buildSet {
        for (t in defaultThemes) add(t.name)
        for ((name, _) in state.customThemes) add(name)
    }
    val defaultName = run {
        var n = 1
        var candidate = "New theme"
        while (candidate in existing) {
            n += 1
            candidate = "New theme $n"
        }
        candidate
    }
    val seedScheme = state.mainSchemeName.ifBlank { recommendedColorSchemes.first().name }
    showNamePrompt(
        title = "New theme",
        label = "Theme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A theme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val fresh = Theme(
            name = newName,
            mode = ConfigMode.Both,
            colorScheme = seedScheme,
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().saveCustomTheme(fresh)
        }
        // Bind the new theme to both light and dark slots so live edits
        // in the editor paint immediately, no manual "select for light /
        // dark" step needed. The default mode is Both, so binding to
        // either slot is well-defined.
        themeManagerHost().setLightThemeName(newName)
        themeManagerHost().setDarkThemeName(newName)
        onCreated(newName)
        pokeManager()
    }
}

/**
 * Apply per-card CSS variables so the absolutely-positioned star and kebab
 * buttons adapt to the card's preview palette instead of hardcoded colours.
 *
 * @param card           card element to style
 * @param previewScheme  the scheme shown inside the card. Resolved against
 *                       the active appearance to pick dark- or light-mode
 *                       colours.
 */
internal fun applyCardPaletteVars(card: HTMLElement, previewScheme: ColorScheme) {
    val isDark = isDarkActive(themeManagerHost().appearance)
    val p = previewScheme.resolve(isDark)
    card.style.setProperty("--star-fg", argbToCss(p.text.secondary))
    card.style.setProperty("--star-active-fg", argbToCss(p.accent.primary))
    card.style.setProperty("--star-bg", argbToCss(p.surface.overlay))
    card.style.setProperty("--star-bg-hover", argbToCss(p.surface.sunken))
    card.style.setProperty("--kebab-fg", argbToCss(p.text.secondary))
    card.style.setProperty("--kebab-fg-hover", argbToCss(p.text.primary))
    card.style.setProperty("--kebab-bg", argbToCss(p.surface.overlay))
    card.style.setProperty("--kebab-bg-hover", argbToCss(p.surface.sunken))
}
