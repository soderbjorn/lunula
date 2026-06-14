/**
 * Color schemes tab list view: filter chips, palette card grid with
 * swatches, per-card kebab menu, and the new/clone prompts.
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
import org.w3c.dom.events.Event

/** Filter chip state for the scheme grid. */
internal enum class SchemeFilter { All, Favorites, Default, Custom }

/**
 * Render the scheme list pane: filter pills + a `+ New colour scheme` tile
 * followed by a grid of colour-scheme cards.
 */
internal fun renderSchemesLeft(
    container: HTMLElement,
    filter: SchemeFilter,
    selected: String?,
    onFilter: (SchemeFilter) -> Unit,
    onSelect: (String) -> Unit,
) {
    val state = themeManagerHost()

    val chips = document.createElement("div") as HTMLElement
    chips.className = "dt-theme-manager-filters"
    for (f in SchemeFilter.values()) {
        val label = when (f) {
            SchemeFilter.All -> "All"
            SchemeFilter.Favorites -> "★ Favorites"
            SchemeFilter.Default -> "Default"
            SchemeFilter.Custom -> "Custom"
        }
        val chip = document.createElement("button") as HTMLElement
        chip.className = "dt-theme-manager-chip" + if (f == filter) " dt-selected" else ""
        chip.textContent = label
        chip.addEventListener("click", { onFilter(f) })
        chips.appendChild(chip)
    }
    container.appendChild(chips)

    val grid = document.createElement("div") as HTMLElement
    grid.className = "dt-theme-manager-grid"

    data class Row(val scheme: ColorScheme, val isDefault: Boolean)
    val allRows = buildList {
        for ((_, c) in state.customSchemes) add(Row(c.toColorScheme(), isDefault = false))
        for (t in recommendedColorSchemes) add(Row(t, isDefault = true))
    }

    val filtered = allRows.filter { row ->
        when (filter) {
            SchemeFilter.All -> true
            SchemeFilter.Favorites -> row.scheme.name in state.favoriteSchemes
            SchemeFilter.Default -> row.isDefault
            SchemeFilter.Custom -> !row.isDefault
        }
    }

    // Favourites float to the top within whatever filter view is active, so
    // starred schemes are always surfaced first even in the All / Default /
    // Custom pills (stable sort keeps the internal order otherwise).
    val sorted = filtered.sortedByDescending { it.scheme.name in state.favoriteSchemes }

    for (row in sorted) {
        grid.appendChild(buildSchemeCard(row.scheme, row.isDefault, row.scheme.name == selected, onSelect))
    }

    if (filter != SchemeFilter.Default) {
        val newTile = document.createElement("div") as HTMLElement
        newTile.className = "dt-new-tile"
        newTile.innerHTML =
            """<span class="dt-new-tile-plus">+</span><span class="dt-new-tile-label">New colour scheme</span>"""
        newTile.addEventListener("click", {
            promptNewScheme { name -> onSelect(name) }
        })
        grid.appendChild(newTile)
    }

    container.appendChild(grid)

    if (filtered.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "dt-theme-manager-empty"
        empty.textContent = when (filter) {
            SchemeFilter.Favorites -> "No favorites yet — star a scheme to add it here."
            SchemeFilter.Custom -> "No custom schemes yet — click + New colour scheme to create one."
            else -> "No schemes match this filter."
        }
        container.appendChild(empty)
    }
}

/** Build a single scheme card: swatch + name + favourite star + kebab menu. */
private fun buildSchemeCard(
    scheme: ColorScheme,
    isDefault: Boolean,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
): HTMLElement {
    val card = document.createElement("div") as HTMLElement
    // Schemes have no "active" state — a theme's section assignments drive
    // which scheme renders where, so a persistent .dt-selected outline here
    // would imply selection state that doesn't exist.
    card.className = "dt-scheme-card dt-theme-card"
    card.setAttribute("data-scheme-name", scheme.name)

    applyCardPaletteVars(card, scheme)

    val state = themeManagerHost()
    val isFav = scheme.name in state.favoriteSchemes

    val star = document.createElement("button") as HTMLElement
    star.className = "dt-theme-card-star" + if (isFav) " dt-active" else ""
    star.innerHTML = if (isFav) "★" else "☆"
    star.title = if (isFav) "Unfavorite" else "Favorite"
    star.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().toggleFavoriteScheme(scheme.name)
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
        showSchemeCardMenu(kebab, scheme, isDefault)
    })
    card.appendChild(kebab)

    val swatch = document.createElement("span") as HTMLElement
    swatch.className = "dt-theme-card-silhouette"
    swatch.innerHTML = themeManagerHost().renderThemeSwatchHtml(scheme)
    card.appendChild(swatch)

    val nameRow = document.createElement("div") as HTMLElement
    nameRow.className = "dt-theme-card-name"
    val nameText = document.createElement("span") as HTMLElement
    nameText.textContent = scheme.name
    nameRow.appendChild(nameText)
    card.appendChild(nameRow)

    card.addEventListener("click", { onSelect(scheme.name) })
    return card
}

/** Per-card menu for schemes: clone, delete. */
private fun showSchemeCardMenu(
    anchor: HTMLElement,
    scheme: ColorScheme,
    isDefault: Boolean,
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

    addItem("Clone…") {
        showCloneSchemePrompt(scheme)
    }
    if (!isDefault) {
        addItem("Delete") {
            showConfirmDialog(
                title = "Delete scheme",
                message = "Delete colour scheme <b>${escapeHtml(scheme.name)}</b>?",
                confirmLabel = "Delete",
                messageIsHtml = true,
                onConfirm = {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        themeManagerHost().deleteCustomScheme(scheme.name)
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
 * Prompt the user for a name and create a clone of [source] as a custom
 * scheme. After save, the manager (if open) is switched to the editor view
 * for the new scheme so the user can start tweaking it immediately.
 */
internal fun showCloneSchemePrompt(source: ColorScheme) {
    val defaultName = "${source.name} Copy"
    val existing = buildSet {
        for (t in recommendedColorSchemes) add(t.name)
        for ((name, _) in themeManagerHost().customSchemes) add(name)
    }
    showNamePrompt(
        title = "Clone colour scheme",
        label = "New colour scheme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A scheme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val clone = CustomScheme(
            name = newName,
            darkFg = source.darkFg,
            lightFg = source.lightFg,
            darkBg = source.darkBg,
            lightBg = source.lightBg,
            overrides = source.overrides ?: emptyMap(),
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().saveCustomScheme(clone)
        }
        val focus = themeManagerFocusScheme
        if (focus != null) focus(newName) else pokeManager()
    }
}

/**
 * Prompt for a fresh custom colour scheme built from scratch (not cloned).
 * The new scheme uses neutral black/white fg+bg defaults for both dark and
 * light appearance, with no semantic overrides.
 *
 * @param onCreated called with the new scheme's name on success.
 */
private fun promptNewScheme(onCreated: (String) -> Unit) {
    val state = themeManagerHost()
    val existing = buildSet {
        for (t in recommendedColorSchemes) add(t.name)
        for ((name, _) in state.customSchemes) add(name)
    }
    val defaultName = run {
        var n = 1
        var candidate = "New colour scheme"
        while (candidate in existing) {
            n += 1
            candidate = "New colour scheme $n"
        }
        candidate
    }
    showNamePrompt(
        title = "New colour scheme",
        label = "Scheme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A scheme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val fresh = CustomScheme(
            name = newName,
            darkBg = "#000000",
            darkFg = "#ffffff",
            lightBg = "#ffffff",
            lightFg = "#000000",
            overrides = emptyMap(),
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            themeManagerHost().saveCustomScheme(fresh)
        }
        onCreated(newName)
        pokeManager()
    }
}
