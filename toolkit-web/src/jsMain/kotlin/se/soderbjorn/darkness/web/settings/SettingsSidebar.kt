/**
 * Settings sidebar — slide-in right-side panel exposing every per-app
 * appearance / window control the toolkit owns.
 *
 * Sections rendered (in order):
 *  1. Theme & appearance — single button that opens the dedicated theme
 *     manager (the SettingsSidebar does not embed the manager itself; it
 *     just provides a jump link).
 *  2. Sidebar font face + size pill rows.
 *  3. Tab bar font face + size pill rows.
 *  4. Monospaced (main-content) font face + size pill rows.
 *  5. Proportional (main-content) font face + size pill rows.
 *  6. Desktop notifications On/Off.
 *  7. Custom title bar On/Off (Electron only).
 *
 * Mutual exclusion with the [ThemeManagerSidebar]: both occupy the
 * single right-sidebar slot owned by [mountAppShell]. The slot's
 * `rerender` path checks whichever of the two is open; toggling one open
 * while the other is open is implemented at the topbar-button level (see
 * the calls to [closeSettingsSidebar] / [closeThemeManagerSidebar] in
 * `AppShellMount.buildTopBar`).
 *
 * Public API mirrors `ThemeManagerSidebar` so apps wire it the same way:
 *   - [toggleSettingsSidebar] flips state and asks the host to rebuild.
 *   - [isSettingsSidebarOpen] is consulted by the host's rebuild path.
 *   - [buildSettingsSidebar] returns the freshly-mounted `<aside>` once
 *     the host's rebuild reaches the right-sidebar slot.
 *
 * @see SettingsSidebarSpec
 * @see ThemeManagerHost
 */
package se.soderbjorn.darkness.web.settings

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.darkness.web.applyMonoFontFamily
import se.soderbjorn.darkness.web.applyMonoFontSizePx
import se.soderbjorn.darkness.web.applyProportionalFontFamily
import se.soderbjorn.darkness.web.applyProportionalFontSizePx
import se.soderbjorn.darkness.web.applySidebarFontFamily
import se.soderbjorn.darkness.web.applySidebarFontSizePx
import se.soderbjorn.darkness.web.applyTabbarFontFamily
import se.soderbjorn.darkness.web.applyTabbarFontSizePx
import se.soderbjorn.darkness.web.applyPaneHeaderFontFamily
import se.soderbjorn.darkness.web.applyPaneHeaderFontSizePx
import se.soderbjorn.darkness.web.shell.SidebarSpec
import se.soderbjorn.darkness.web.shell.renderRightSidebar
import se.soderbjorn.darkness.web.themeeditor.FontKind
import se.soderbjorn.darkness.web.themeeditor.ThemeManagerHost
import se.soderbjorn.darkness.web.themeeditor.detectInstalledFonts
import se.soderbjorn.darkness.web.themeeditor.fontPresets

/**
 * Spec passed to [buildSettingsSidebar].
 *
 * @property host                  the toolkit-shared host that owns persisted
 *   per-app settings. Reads pull from [host]'s getters; writes call its
 *   setters.
 * @property isElectron            when `false`, the Custom title bar section
 *   is hidden (it requires Electron's `titleBarStyle: hiddenInset`).
 * @property onOpenThemeManager    invoked by the "Open theme manager" button
 *   inside section 1. Hosts typically call their existing theme-manager
 *   toggle; the SettingsSidebar deliberately does not own that flow.
 * @property mainSizePresets       size pill values for the Monospaced and
 *   Proportional sections.
 * @property sidebarSizePresets    size pill values for the Sidebar and
 *   Tab bar sections (chrome typically uses a tighter range).
 * @property sidebarSizeDefault    size to highlight in the Sidebar and Tab
 *   bar rows when the host getter returns null (i.e. the user has not
 *   explicitly picked one). 13 matches the toolkit chrome's CSS default
 *   (`.dt-app-frame { font-size: 13px }`).
 * @property mainSizeDefault       size to highlight in the Monospaced and
 *   Proportional rows when the host getter returns null. Apps whose main
 *   pane content uses a different intrinsic size may pass their own.
 */
data class SettingsSidebarSpec(
    val host: ThemeManagerHost,
    val isElectron: Boolean,
    val onOpenThemeManager: () -> Unit,
    val mainSizePresets: List<Int> = (10..24).toList(),
    val sidebarSizePresets: List<Int> = (10..18).toList(),
    val sidebarSizeDefault: Int = 13,
    val mainSizeDefault: Int = 14,
)

/** True while the Settings sidebar is considered open. */
private var sidebarOpen: Boolean = false

/** The currently-mounted sidebar element, or null when closed. */
private var currentSidebar: HTMLElement? = null

/** Last-known target width — used as the slide-in destination on next mount. */
private var lastWidthPx: Int = 420

/**
 * Latest `requestRebuild` callback handed to [toggleSettingsSidebar].
 *
 * Captured so the panel's own close affordances (X button, Escape) can
 * trigger the same animate-then-rebuild close path as the topbar toggle.
 */
private var lastRequestRebuild: (() -> Unit)? = null

/** Document-level Escape handler installed while the sidebar is open. */
private var escHandler: ((Event) -> Unit)? = null

/**
 * Whether the Settings sidebar is currently open. The host's rebuild
 * path consults this to decide whether to mount the sidebar.
 */
fun isSettingsSidebarOpen(): Boolean = sidebarOpen

/**
 * Toggle the Settings sidebar open/closed.
 *
 * On open: flips state to true, clears any stale element reference (so
 * the next mount runs the slide-in animation), and calls [requestRebuild]
 * so the host re-renders its AppFrame with the sidebar slot populated.
 *
 * On close: animates the existing sidebar element to width 0, waits for
 * `transitionend`, then flips state to false and calls [requestRebuild].
 */
fun toggleSettingsSidebar(requestRebuild: () -> Unit) {
    lastRequestRebuild = requestRebuild
    if (!sidebarOpen) {
        sidebarOpen = true
        currentSidebar = null
        requestRebuild()
        return
    }
    val sidebar = currentSidebar
    if (sidebar == null) {
        sidebarOpen = false
        requestRebuild()
        return
    }
    var done = false
    val handler: (Event) -> Unit = handler@{ ev ->
        if (done) return@handler
        if (ev.target !== sidebar) return@handler
        done = true
        sidebarOpen = false
        currentSidebar = null
        teardownEscHandler()
        requestRebuild()
    }
    sidebar.addEventListener("transitionend", handler)
    window.requestAnimationFrame { sidebar.style.width = "0px" }
}

/**
 * Force-close the Settings sidebar synchronously (no animation).
 *
 * Called by the topbar button that opens the Theme Manager so the two
 * right-side panels are mutually exclusive: the host calls this first,
 * then opens the manager. The settings element is detached on the next
 * `requestRebuild`.
 */
fun closeSettingsSidebar() {
    sidebarOpen = false
    currentSidebar = null
    teardownEscHandler()
}

private fun teardownEscHandler() {
    escHandler?.let { document.removeEventListener("keydown", it) }
    escHandler = null
}

/**
 * Build the right-sidebar `<aside>` element that hosts the Settings panel.
 *
 * Returned to the caller for them to pass into their
 * `AppFrameSpec(rightSidebar = …)` slot. Starts at `width: 0` and slides
 * to [SettingsSidebarSpec] (no public param yet for width since the
 * settings panel is fairly tall and a fixed 420 px reads well).
 *
 * @param spec the sidebar configuration.
 * @return the freshly-mounted sidebar element.
 */
fun buildSettingsSidebar(spec: SettingsSidebarSpec): HTMLElement {
    val resolvedWidthPx = lastWidthPx

    val mountTarget = document.createElement("div") as HTMLElement
    mountTarget.style.apply {
        width = "100%"
        flex = "1 1 auto"
        setProperty("min-height", "0")
        display = "flex"
        flexDirection = "column"
        // No `overflow-y: auto` here. The settings body manages its own
        // scroll on `.dt-settings-sidebar-sections` so the header stays
        // pinned. A second scroll container at the mount level would
        // absorb the overflow first and the inner section list would
        // never engage its own scrollbar — leaving the bottom rows
        // hidden below the panel.
    }

    val isReMount = currentSidebar != null

    val sidebar = renderRightSidebar(
        SidebarSpec(
            widthPx = resolvedWidthPx,
            content = mountTarget,
            visible = true,
            isResizable = false,
            minWidthPx = 360,
            maxWidthPx = 600,
        )
    )
    currentSidebar = sidebar

    if (isReMount) {
        sidebar.style.width = "${resolvedWidthPx}px"
        renderSettingsBody(mountTarget, spec)
    } else {
        // Double-rAF for the slide-in transition — see comment in
        // ThemeManagerSidebar.buildThemeManagerSidebar. Single rAF can
        // fire before the appended element ever paints at width:0, which
        // makes the browser skip the transition (visible when switching
        // between Theme Manager and Settings).
        sidebar.style.width = "0px"
        window.requestAnimationFrame {
            window.requestAnimationFrame {
                sidebar.style.width = "${resolvedWidthPx}px"
                renderSettingsBody(mountTarget, spec)
            }
        }
    }

    teardownEscHandler()
    val handler: (Event) -> Unit = { ev ->
        val key = (ev as? KeyboardEvent)?.key
        if (key == "Escape") {
            val rebuild = lastRequestRebuild
            if (rebuild != null) toggleSettingsSidebar(rebuild)
        }
    }
    document.addEventListener("keydown", handler)
    escHandler = handler

    return sidebar
}

/**
 * Mounts the Settings panel content into [target].
 *
 * Each section is built once per render; pill rows re-render in place
 * after a click so the selected state updates without rebuilding the
 * whole panel.
 */
private fun renderSettingsBody(target: HTMLElement, spec: SettingsSidebarSpec) {
    target.innerHTML = ""

    val panel = document.createElement("div") as HTMLElement
    panel.className = "dt-settings-sidebar-body"

    val header = document.createElement("div") as HTMLElement
    header.className = "dt-settings-sidebar-header"

    val title = document.createElement("h2") as HTMLElement
    title.className = "dt-settings-sidebar-title"
    title.textContent = "Appearance"
    header.appendChild(title)

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "dt-settings-sidebar-close"
    closeBtn.setAttribute("type", "button")
    closeBtn.innerHTML = "&times;"
    closeBtn.title = "Close"
    closeBtn.addEventListener("click", {
        val rebuild = lastRequestRebuild
        if (rebuild != null) toggleSettingsSidebar(rebuild)
    })
    header.appendChild(closeBtn)

    panel.appendChild(header)

    val body = document.createElement("div") as HTMLElement
    body.className = "dt-settings-sidebar-sections"
    panel.appendChild(body)

    // The Theme Manager already has a dedicated topbar entry (the
    // palette icon), so duplicating the same launcher here as a
    // "Theme" jump-link section produced two paths into the same
    // surface. Rely on the topbar icon — leave the settings sidebar
    // for genuinely distinct surfaces (font, notifications, titlebar).

    // ── Custom title bar (Electron only) ────────────────────────────
    // Pinned to the top of the panel: it's the most disruptive
    // appearance toggle (window chrome reflows on flip) and users
    // hunting for it shouldn't need to scroll past every font row.
    if (spec.isElectron) {
        body.appendChild(buildToggleSection(
            title = "Custom title bar",
            currentValue = { spec.host.useCustomTitleBar },
            onPick = { v ->
                spec.host.setUseCustomTitleBar(v)
            },
        ))
    }

    // ── Sidebar font ────────────────────────────────────────────────
    body.appendChild(buildFontFaceSection(
        title = "Sidebar font",
        hint = "Used by the topbar and sidebars.",
        kind = FontKind.Proportional,
        showKinds = setOf(FontKind.Proportional, FontKind.Mono),
        currentKey = { spec.host.sidebarFontFamily },
        onPick = { key ->
            spec.host.setSidebarFontFamily(key)
            applySidebarFontFamily(key)
        },
    ))
    body.appendChild(buildFontSizeSection(
        title = "Sidebar size",
        sizes = spec.sidebarSizePresets,
        defaultSize = spec.sidebarSizeDefault,
        currentSize = { spec.host.sidebarFontSizePx },
        onPick = { px ->
            spec.host.setSidebarFontSizePx(px)
            applySidebarFontSizePx(px)
        },
    ))

    // ── Tab bar font ────────────────────────────────────────────────
    body.appendChild(buildFontFaceSection(
        title = "Tab bar font",
        hint = "Used by the tab strip (falls back to Sidebar when unset).",
        kind = FontKind.Proportional,
        showKinds = setOf(FontKind.Proportional, FontKind.Mono),
        currentKey = { spec.host.tabbarFontFamily },
        onPick = { key ->
            spec.host.setTabbarFontFamily(key)
            applyTabbarFontFamily(key)
        },
    ))
    body.appendChild(buildFontSizeSection(
        title = "Tab bar size",
        sizes = spec.sidebarSizePresets,
        defaultSize = spec.sidebarSizeDefault,
        currentSize = { spec.host.tabbarFontSizePx },
        onPick = { px ->
            spec.host.setTabbarFontSizePx(px)
            applyTabbarFontSizePx(px)
        },
    ))

    // ── Window title font ───────────────────────────────────────────
    body.appendChild(buildFontFaceSection(
        title = "Window title font",
        hint = "Used by each window's title bar (falls back to Sidebar when unset).",
        kind = FontKind.Proportional,
        showKinds = setOf(FontKind.Proportional, FontKind.Mono),
        currentKey = { spec.host.paneHeaderFontFamily },
        onPick = { key ->
            spec.host.setPaneHeaderFontFamily(key)
            applyPaneHeaderFontFamily(key)
        },
    ))
    body.appendChild(buildFontSizeSection(
        title = "Window title size",
        sizes = spec.sidebarSizePresets,
        defaultSize = spec.sidebarSizeDefault,
        currentSize = { spec.host.paneHeaderFontSizePx },
        onPick = { px ->
            spec.host.setPaneHeaderFontSizePx(px)
            applyPaneHeaderFontSizePx(px)
        },
    ))

    // ── Monospaced font (main content — terminals, code) ────────────
    body.appendChild(buildFontFaceSection(
        title = "Monospaced font",
        hint = "Used by terminals and code panes.",
        kind = FontKind.Mono,
        currentKey = { spec.host.monoFontFamily },
        onPick = { key ->
            spec.host.setMonoFontFamily(key)
            applyMonoFontFamily(key)
        },
    ))
    body.appendChild(buildFontSizeSection(
        title = "Monospaced size",
        sizes = spec.mainSizePresets,
        defaultSize = spec.mainSizeDefault,
        currentSize = { spec.host.monoFontSizePx },
        onPick = { px ->
            spec.host.setMonoFontSizePx(px)
            applyMonoFontSizePx(px)
        },
    ))

    // ── Proportional font (main content — prose) ────────────────────
    body.appendChild(buildFontFaceSection(
        title = "Proportional font",
        hint = "Used by prose / note content.",
        kind = FontKind.Proportional,
        showKinds = setOf(FontKind.Proportional, FontKind.Mono),
        currentKey = { spec.host.proportionalFontFamily },
        onPick = { key ->
            spec.host.setProportionalFontFamily(key)
            applyProportionalFontFamily(key)
        },
    ))
    body.appendChild(buildFontSizeSection(
        title = "Proportional size",
        sizes = spec.mainSizePresets,
        defaultSize = spec.mainSizeDefault,
        currentSize = { spec.host.proportionalFontSizePx },
        onPick = { px ->
            spec.host.setProportionalFontSizePx(px)
            applyProportionalFontSizePx(px)
        },
    ))

    target.appendChild(panel)
}

/** Internal helper bundling a labelled section with its pill row container. */
private data class Section(val element: HTMLElement, val row: HTMLElement)

private fun makeSection(title: String, hint: String? = null): Section {
    val section = document.createElement("div") as HTMLElement
    section.className = "dt-settings-section"
    val label = document.createElement("div") as HTMLElement
    label.className = "dt-settings-label"
    label.textContent = title
    section.appendChild(label)
    if (hint != null) {
        val hintEl = document.createElement("div") as HTMLElement
        hintEl.className = "dt-settings-hint"
        hintEl.textContent = hint
        section.appendChild(hintEl)
    }
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-settings-button-row"
    section.appendChild(row)
    return Section(section, row)
}

/**
 * Builds one font-face pill row.
 *
 * @param kind the section's primary kind. Drives the system default
 *   ([FontKind.Mono] → `system`, [FontKind.Proportional] → `systemProp`)
 *   and floats presets of this kind to the front of the row.
 * @param showKinds the kinds whose presets are offered in the row.
 *   Defaults to just [kind]. Proportional chrome sections (Sidebar / Tab
 *   bar / Proportional) pass both kinds so users can also pick a
 *   monospaced face for chrome — the proportional presets stay first,
 *   monospaced ones follow.
 */
private fun buildFontFaceSection(
    title: String,
    hint: String,
    kind: FontKind,
    currentKey: () -> String?,
    onPick: (String?) -> Unit,
    showKinds: Set<FontKind> = setOf(kind),
): HTMLElement {
    val section = makeSection(title, hint)
    val row = section.row
    val installed = detectInstalledFonts()
    val current = currentKey()
    // System default first, regardless of order in [fontPresets], so
    // users without a strong opinion always see a familiar label at the
    // start of the row. When the row offers more than one kind (e.g.
    // chrome sections that also list monospaced faces), presets matching
    // the section's primary [kind] are floated ahead of the rest while
    // preserving each group's declared order (sortedWith is stable).
    val systemKey = if (kind == FontKind.Mono) "system" else "systemProp"
    val sortedPresets = fontPresets
        .filter { it.kind in showKinds }
        .sortedWith(compareBy(
            { if (it.key == systemKey) 0 else 1 },
            { if (it.kind == kind) 0 else 1 },
        ))
    for (preset in sortedPresets) {
        if (preset.key !in installed) continue
        val isSelected = preset.key == current ||
            (current.isNullOrEmpty() && preset.key == systemKey)
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.className = "dt-settings-choice-btn" + if (isSelected) " dt-selected" else ""
        btn.textContent = preset.displayName
        btn.style.fontFamily = preset.cssStack
        btn.addEventListener("click", {
            // Optimistic selection update. Hosts may persist the picked
            // value asynchronously (e.g. termtastic's `appVm.setX` runs
            // through `launch { … }`), in which case re-reading
            // `currentKey()` immediately after `onPick` returns the *old*
            // value — leaving the previous selection lit until the next
            // click. Updating the DOM directly here makes the click
            // visually take effect on the first click, regardless of how
            // the host backs the setter. */
            val rowChildren = row.children
            for (i in 0 until rowChildren.length) {
                (rowChildren.item(i) as? HTMLElement)?.classList?.remove("dt-selected")
            }
            btn.classList.add("dt-selected")
            onPick(preset.key)
        })
        row.appendChild(btn)
    }
    return section.element
}

/**
 * Builds one font-size pill row.
 *
 * Mirrors [buildFontFaceSection]'s null-fallback behaviour: when the
 * host has no stored size yet ([currentSize] returns null), the pill
 * matching [defaultSize] gets `.dt-selected` so the row always shows
 * exactly one ringed entry — even on a fresh install. The actual
 * rendered chrome size in that case comes from CSS defaults
 * (`.dt-app-frame { font-size: 13px }` for sidebar/tabbar), so
 * [defaultSize] should reflect that.
 *
 * @param title         section title shown above the row.
 * @param sizes         pill values, in render order.
 * @param defaultSize   value to highlight when [currentSize] is null.
 * @param currentSize   reader for the host's stored size; may be null
 *   when no explicit size has been picked.
 * @param onPick        called with the clicked size; the click handler
 *   also performs an optimistic DOM update so async hosts don't leave
 *   the previous selection lit.
 */
private fun buildFontSizeSection(
    title: String,
    sizes: List<Int>,
    defaultSize: Int,
    currentSize: () -> Int?,
    onPick: (Int) -> Unit,
): HTMLElement {
    val section = makeSection(title)
    val row = section.row
    row.classList.add("dt-settings-size-row")
    val current = currentSize()
    for (s in sizes) {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        val isSelected = s == current || (current == null && s == defaultSize)
        btn.className = "dt-settings-choice-btn" + if (isSelected) " dt-selected" else ""
        btn.textContent = "${s}px"
        btn.addEventListener("click", {
            // Optimistic selection update — see [buildFontFaceSection]
            // for the rationale. Async host setters can leave a stale
            // re-render reading the pre-click value otherwise.
            val rowChildren = row.children
            for (i in 0 until rowChildren.length) {
                (rowChildren.item(i) as? HTMLElement)?.classList?.remove("dt-selected")
            }
            btn.classList.add("dt-selected")
            onPick(s)
        })
        row.appendChild(btn)
    }
    return section.element
}

private fun buildToggleSection(
    title: String,
    currentValue: () -> Boolean,
    onPick: (Boolean) -> Unit,
): HTMLElement {
    val section = makeSection(title)
    val row = section.row
    val current = currentValue()
    for ((label, value) in listOf("On" to true, "Off" to false)) {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.className = "dt-settings-choice-btn" + if (value == current) " dt-selected" else ""
        btn.textContent = label
        btn.addEventListener("click", {
            // Optimistic selection update — see [buildFontFaceSection].
            val rowChildren = row.children
            for (i in 0 until rowChildren.length) {
                (rowChildren.item(i) as? HTMLElement)?.classList?.remove("dt-selected")
            }
            btn.classList.add("dt-selected")
            onPick(value)
        })
        row.appendChild(btn)
    }
    return section.element
}
