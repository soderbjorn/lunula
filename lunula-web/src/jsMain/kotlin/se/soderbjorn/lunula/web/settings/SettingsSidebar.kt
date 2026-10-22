/**
 * Appearance settings — the panel exposing every per-app appearance / window
 * control the toolkit owns. Shown as the Appearance tab of the toolkit's
 * Appearance pane (beside the Themes tab; see `ThemeManagerSidebar.kt`), and
 * still mountable on its own as a right sidebar through [buildSettingsSidebar].
 *
 * Two groups, so it is clear which settings follow dark/light mode:
 *
 *  1. **Style for this mode** — remembered separately for dark and light mode
 *     and switched with the theme ([se.soderbjorn.lunula.core.ModeStyles]):
 *     Presets (Classic / Calm), then Chrome, Windows, Active window, Labels,
 *     Selection, Surfaces, Corner roundness and Spacing. The heading names the
 *     mode being edited, which is always the active one.
 *  2. **Shared by both modes** — Navigation, the Custom title bar toggle
 *     (Electron only), any section the app adds
 *     ([SettingsSidebarSpec.appAppearanceContent]), and Fonts: one line per
 *     surface (Sidebar, Tab bar, Window title, Text, Headings, Code), naming
 *     the font actually painted there with a − / + size stepper.
 *
 * Every row rings the RESOLVED value (the user's pick for the mode, then the
 * app/brand default, then the toolkit default), so a branded instance shows
 * what it actually paints.
 *
 * Note what is NOT here: colour. These are user preferences that must survive
 * a theme change — a user who likes square corners should not have to give up
 * a palette to keep them — so they are persisted per-app, not stored on a
 * [Theme].
 *
 * Public API mirrors `ThemeManagerSidebar` so apps can still wire it on its own:
 *   - [toggleSettingsSidebar] flips state and asks the host to rebuild.
 *   - [isSettingsSidebarOpen] is consulted by the host's rebuild path.
 *   - [buildSettingsSidebar] returns the freshly-mounted `<aside>` once
 *     the host's rebuild reaches the right-sidebar slot.
 *
 * @see SettingsSidebarSpec
 * @see ThemeManagerHost
 */
package se.soderbjorn.lunula.web.settings

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.applyMonoFontFamily
import se.soderbjorn.lunula.web.applyMonoFontSizePx
import se.soderbjorn.lunula.web.applyProportionalFontFamily
import se.soderbjorn.lunula.web.applyProportionalFontSizePx
import se.soderbjorn.lunula.web.applySidebarFontFamily
import se.soderbjorn.lunula.web.applySidebarFontSizePx
import se.soderbjorn.lunula.web.applyTabbarFontFamily
import se.soderbjorn.lunula.web.applyTabbarFontSizePx
import se.soderbjorn.lunula.web.applyPaneHeaderFontFamily
import se.soderbjorn.lunula.web.applyPaneHeaderFontSizePx
import se.soderbjorn.lunula.web.applyDisplayFontFamily
import se.soderbjorn.lunula.web.applyDisplayFontSizePx
import se.soderbjorn.lunula.web.applyAppearance
import se.soderbjorn.lunula.web.isDarkActive
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.DEFAULT_CORNER_RADIUS_PX
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.ResolvedAppearance
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.UiDensity
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.web.themeeditor.editableModeStyles
import se.soderbjorn.lunula.web.themeeditor.resolveStyle
import se.soderbjorn.lunula.web.shell.SidebarSpec
import se.soderbjorn.lunula.web.shell.renderRightSidebar
import se.soderbjorn.lunula.web.themeeditor.FontKind
import se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost
import se.soderbjorn.lunula.web.themeeditor.detectInstalledFonts
import se.soderbjorn.lunula.web.themeeditor.allFontPresets
import se.soderbjorn.lunula.web.themeeditor.FontChoice
import se.soderbjorn.lunula.web.themeeditor.fontLabelFor
import se.soderbjorn.lunula.web.themeeditor.fontRowChoices
import se.soderbjorn.lunula.web.themeeditor.loadLocalFontFamilies
import se.soderbjorn.lunula.web.themeeditor.localFontFamilies
import se.soderbjorn.lunula.web.themeeditor.resolveFontFamilyCss
import se.soderbjorn.lunula.web.themeeditor.resolveProportionalFontFamilyCss

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
 * @property mainSizePresets       sizes the Text, Headings and Code lines
 *   step through.
 * @property sidebarSizePresets    sizes the Sidebar, Tab bar and Window
 *   title lines step through (chrome typically uses a tighter range).
 * @property sidebarSizeDefault    size the Sidebar line shows when the host
 *   getter returns null (i.e. the user has not explicitly picked one); the
 *   Tab bar line follows the Sidebar's. 13 matches the toolkit chrome's CSS
 *   default (`.dt-app-frame { font-size: 13px }`).
 * @property mainSizeDefault       size the Text line shows when the host
 *   getter returns null. Apps whose main pane content uses a
 *   different intrinsic size may pass their own.
 */
data class SettingsSidebarSpec(
    val host: ThemeManagerHost,
    val isElectron: Boolean,
    val onOpenThemeManager: () -> Unit,
    val mainSizePresets: List<Int> = (10..24).toList(),
    val sidebarSizePresets: List<Int> = (10..18).toList(),
    val sidebarSizeDefault: Int = 13,
    val mainSizeDefault: Int = 14,
    /**
     * Size the Window title line shows when the host stores none. 11 matches
     * `lunula.css`'s `--dt-pane-title-size` fallback; an app with a chrome
     * size default passes that, since it reaches the window titles too.
     */
    val paneHeaderSizeDefault: Int = 11,
    /** Size the Code line shows when the host stores none (the app's mono default). */
    val monoSizeDefault: Int = mainSizeDefault,
    /** Size the Headings line shows when the host stores none (falls to prose's). */
    val displaySizeDefault: Int = mainSizeDefault,
    /**
     * Font lines that get no size control: surfaces whose size the app does
     * not take from the toolkit (Lunarbor sizes headings from its text size),
     * so a stepper there would change nothing. Empty by default — every line
     * keeps its stepper.
     */
    val fontSizeHidden: Set<FontSurfaceId> = emptySet(),
    /**
     * Effective font-preset key applied to the CHROME surfaces (sidebar / tab
     * bar / window title) when the user has picked none — i.e. a deployment
     * brand font, resolved the same way [se.soderbjorn.lunula.web.shell.AppShellSpec.defaultChromeFontFamily]
     * is. Returns null on an unbranded instance. The chrome font lines name
     * this font (falling back to `systemProp`) when there is no explicit user
     * pick, so the line names the font actually painted.
     */
    val chromeDefaultKey: () -> String? = { null },
    /** Effective proportional (prose) default key when the user picked none, or null. */
    val proseDefaultKey: () -> String? = { null },
    /** Effective display (heading) default key when the user picked none, or null. */
    val displayDefaultKey: () -> String? = { null },
    /**
     * Effective monospaced default key when the user picked none, or null.
     *
     * Unlike the three above, this one has no sibling to fall through to — a
     * proportional brand font must not letter a terminal — so it is null unless
     * the app names a mono face of its own. Null names the `system` preset (SF
     * Mono on a Mac), which is what `lunula.css`'s `var(--dt-font-mono,
     * ui-monospace, …)` chain paints.
     */
    val monoDefaultKey: () -> String? = { null },
    /**
     * Corner-radius pill values, in render order.
     *
     * A short, opinionated ladder rather than a range: 0 is square, 18 is the
     * toolkit default, 14 is the Calm look's, and the steps between are the
     * ones that read as distinct at a glance. Offering every integer would
     * present forty choices to answer a question with about five real answers.
     */
    val cornerRadiusPresets: List<Int> = listOf(0, 4, 8, 12, 14, 18, 24),
    /**
     * The shape settings the app applies when the user has picked none — a
     * deployment brand's defaults, resolved the same way [chromeDefaultKey] is.
     *
     * Each row highlights this value when the host's own is null, so the ringed
     * pill matches what is actually painted rather than claiming the toolkit
     * default while a branded instance shows something else. Fields left null
     * fall through to the toolkit's own defaults.
     */
    val appDefaultShape: () -> AppearanceShape = { AppearanceShape() },
    /**
     * Whether the dark slot is the active one, which decides whose style
     * settings the "Style for this mode" group shows and edits.
     * `AppShellMount` passes `ShellState.activeModeIsDark`; the default reads
     * the host's appearance (Auto answered by the system scheme).
     */
    val isDarkMode: () -> Boolean = { isDarkActive(host.appearance) },
    /**
     * Called with both modes' style settings after any pick in the "Style for
     * this mode" group. Must store them and repaint, so the next read of the
     * host resolves the new values. `AppShellMount` routes it through
     * `ShellState.setModeStyles` (host setter, re-apply, persist). The default
     * stores it on [host] and stamps the root directly, for a panel mounted
     * outside the shell.
     */
    val onStylesChanged: (ModeStyles) -> Unit = { styles ->
        host.setModeStyles(styles)
        applyAppearance(document.documentElement as HTMLElement, host.resolveStyle(isDarkMode(), appDefaultShape()))
    },
    /**
     * An app's own appearance controls, shown as an extra section in the
     * "Shared by both modes" group (above Fonts), or `null` for none. Built
     * afresh on each render of the panel; the app owns its contents and their
     * persistence. Use it for appearance options only one app has (a
     * terminal's cursor, an editor's line height), so they sit with the rest
     * of the appearance settings instead of in the app's own settings.
     */
    val appAppearanceContent: (() -> HTMLElement)? = null,
    /**
     * The navigation mode asked for (before the app's hard opt-outs), which the
     * Navigation row rings. `AppShellMount` passes `ShellState.resolvedNavigationMode`;
     * the default walks host → [appDefaultShape] → [NavigationMode.Default].
     */
    val resolvedNavigationMode: () -> NavigationMode = {
        host.navigationMode ?: appDefaultShape().navigationMode ?: NavigationMode.Default
    },
    /**
     * Called when the user picks a Navigation mode. `AppShellMount` routes it
     * through `ShellState.setNavigationMode`, which re-applies and redraws the
     * chrome. The default only stores it on [host].
     */
    val onPickNavigationMode: (NavigationMode) -> Unit = { mode -> host.setNavigationMode(mode) },
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
 * Mounts the Appearance panel content into [target]: a header and the two
 * groups described in the file header.
 *
 * Each style pick writes both modes' settings through
 * [SettingsSidebarSpec.onStylesChanged] and then re-renders the body in place
 * (keeping its scroll position), so a Look pick updates every row at once and
 * the Look row follows a single-setting change to "Custom".
 *
 * Called by [buildSettingsSidebar] and by the Appearance pane's Appearance tab.
 *
 * @param target the element to fill; emptied first.
 * @param spec   the panel configuration.
 */
internal fun renderSettingsBody(target: HTMLElement, spec: SettingsSidebarSpec) {
    // Keep the scroll position across an in-place re-render after a pick.
    val previousScroll = (target.querySelector(".dt-settings-sidebar-sections") as? HTMLElement)?.scrollTop
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

    // ── Group 1: style for the active mode ──────────────────────────
    val isDark = spec.isDarkMode()
    val resolved = spec.host.resolveStyle(isDark, spec.appDefaultShape())
    val rerender = { renderSettingsBody(target, spec) }
    // A pick edits the active mode's entry, starting from the user's own
    // values for it (not the resolved ones), so a field the user never set
    // keeps following the app/brand default.
    fun pickStyle(edit: (AppearanceShape) -> AppearanceShape) {
        val styles = spec.host.editableModeStyles()
        spec.onStylesChanged(styles.withMode(isDark, edit(styles.forMode(isDark))))
        rerender()
    }
    val styleGroup = makeGroup(
        title = if (isDark) "Style for dark mode" else "Style for light mode",
        hint = "Remembered separately for dark and light mode, like the theme. " +
            "Switch the appearance to set up the other mode.",
    )
    styleGroup.setAttribute("data-dt-group", "mode-style")
    body.appendChild(styleGroup)
    styleGroup.appendChild(buildPresetsSection(resolved) { look -> pickStyle { look.shape } })
    styleGroup.appendChild(buildChoiceSection(
        settingId = "chrome",
        title = "Chrome",
        hint = "Themed paints the bars in the theme's chrome colours; Blended puts them on the window background with quiet, borderless buttons.",
        options = listOf("Themed" to ChromeStyle.Themed, "Blended" to ChromeStyle.Blended),
        currentValue = { resolved.chromeStyle },
        onPick = { v -> pickStyle { it.copy(chromeStyle = v) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "windows",
        title = "Windows",
        hint = "Cards float apart on the canvas; Sheet puts every window on one rounded sheet, split by hairlines.",
        options = listOf("Cards" to WindowLayout.Cards, "Sheet" to WindowLayout.Sheet),
        currentValue = { resolved.windowLayout },
        onPick = { v -> pickStyle { it.copy(windowLayout = v) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "focus",
        title = "Active window",
        hint = "Accent marks the active window's title bar; Tone dims the other windows and lifts the active one.",
        options = listOf("Accent" to FocusStyle.Accent, "Tone" to FocusStyle.Tone),
        currentValue = { resolved.focusStyle },
        onPick = { v -> pickStyle { it.copy(focusStyle = v) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "labels",
        title = "Labels",
        hint = "How window titles and sidebar headings are lettered.",
        options = listOf("Uppercase" to LabelCase.Uppercase, "Sentence case" to LabelCase.Sentence),
        currentValue = { resolved.labelCase },
        onPick = { v -> pickStyle { it.copy(labelCase = v) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "selection",
        title = "Selection",
        hint = "How the active tab and sidebar row are marked — and, with an Accent active window, its title bar.",
        options = listOf(
            "Tinted" to SelectionStyle.Tint,
            "Filled" to SelectionStyle.Fill,
            "Soft" to SelectionStyle.Soft,
        ),
        currentValue = { resolved.selectionStyle },
        onPick = { v -> pickStyle { it.copy(selectionStyle = v) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "surfaces",
        title = "Surfaces",
        hint = "Depth lifts windows on soft shadows and gives accents a glow; Flat keeps everything on one plane; Soft is flat with calm, lightly shadowed dialogs and menus.",
        options = listOf(
            "Depth" to SurfaceStyle.Depth,
            "Flat" to SurfaceStyle.Flat,
            "Soft" to SurfaceStyle.Soft,
        ),
        currentValue = { resolved.surfaceStyle },
        onPick = { v -> pickStyle { it.copy(surfaceStyle = v) } },
    ))
    styleGroup.appendChild(buildPxChoiceSection(
        title = "Corner roundness",
        sizes = spec.cornerRadiusPresets,
        defaultSize = DEFAULT_CORNER_RADIUS_PX,
        currentSize = { resolved.cornerRadiusPx },
        onPick = { px -> pickStyle { it.copy(cornerRadiusPx = px) } },
    ))
    styleGroup.appendChild(buildChoiceSection(
        settingId = "spacing",
        title = "Spacing",
        hint = "How much air the chrome puts around windows, tabs and rows.",
        options = UiDensity.entries.map { it.name to it },
        currentValue = { resolved.uiDensity },
        onPick = { v -> pickStyle { it.copy(uiDensity = v) } },
    ))

    // ── Group 2: shared by both modes ───────────────────────────────
    val sharedGroup = makeGroup(
        title = "Shared by both modes",
        hint = "Kept the same in dark and light mode.",
    )
    sharedGroup.setAttribute("data-dt-group", "shared")
    body.appendChild(sharedGroup)
    sharedGroup.appendChild(buildChoiceSection(
        settingId = "navigation",
        title = "Navigation",
        hint = "How you move between tabs: the sidebar, a tab strip along the top, or both.",
        options = listOf(
            "Sidebar" to NavigationMode.Sidebar,
            "Tab strip" to NavigationMode.TabStrip,
            "Both" to NavigationMode.Both,
        ),
        currentValue = spec.resolvedNavigationMode,
        onPick = { mode -> spec.onPickNavigationMode(mode) },
    ))
    // Custom title bar (Electron only): the window chrome reflows on flip, and
    // it concerns the OS window, which has no dark or light mode of its own.
    if (spec.isElectron) {
        sharedGroup.appendChild(buildToggleSection(
            title = "Custom title bar",
            currentValue = { spec.host.useCustomTitleBar },
            onPick = { v ->
                spec.host.setUseCustomTitleBar(v)
            },
        ))
    }
    spec.appAppearanceContent?.let { build ->
        val appSection = build()
        appSection.classList.add("dt-settings-app-section")
        sharedGroup.appendChild(appSection)
    }
    // ── Fonts: one line per surface — its font and its size ─────────
    sharedGroup.appendChild(buildFontsSection(spec))

    target.appendChild(panel)
    if (previousScroll != null) body.scrollTop = previousScroll
}

/**
 * Builds one of the panel's two groups: a titled block with a hint, which the
 * caller fills with sections.
 *
 * @param title the group heading ("Style for dark mode", "Shared by both modes").
 * @param hint  the muted line under it, saying how the group behaves.
 * @return the group element (`.dt-settings-group`).
 */
private fun makeGroup(title: String, hint: String): HTMLElement {
    val group = document.createElement("div") as HTMLElement
    group.className = "dt-settings-group"
    val heading = document.createElement("div") as HTMLElement
    heading.className = "dt-settings-group-title"
    heading.textContent = title
    group.appendChild(heading)
    val hintEl = document.createElement("div") as HTMLElement
    hintEl.className = "dt-settings-group-hint"
    hintEl.textContent = hint
    group.appendChild(hintEl)
    return group
}

/**
 * Builds the Presets block at the head of the "Style for … mode" group: a
 * short explanation, one card per built-in look ([Looks.all]) and a status
 * line.
 *
 * A look is not a setting of its own but a preset of the settings below it,
 * and the block is drawn to say so: it sits in a framed box above the
 * settings, its hint says that picking a preset sets them all, each card is an
 * action naming what it applies (name and one-line description), and the
 * status line reports which preset — if any — the current settings match
 * ("Your own mix" otherwise). Nothing stores which preset was picked, so
 * changing any setting afterwards turns the status to "Your own mix" by
 * itself.
 *
 * The card of the matching preset carries `.dt-selected` and an "In use"
 * badge; the status line carries `data-dt-look` with the matching look's id,
 * or `custom`.
 *
 * @param resolved the values in effect for the active mode.
 * @param onPick   called with the clicked preset.
 * @return the section element (`data-dt-setting="look"`).
 */
private fun buildPresetsSection(resolved: ResolvedAppearance, onPick: (se.soderbjorn.lunula.core.Look) -> Unit): HTMLElement {
    val matched = Looks.all.firstOrNull { it.matches(resolved) }
    val section = makeSection(
        "Presets",
        "A preset sets all the style settings below in one go. You can still change any of them afterwards.",
    )
    section.element.classList.add("dt-settings-presets")
    section.element.setAttribute("data-dt-setting", "look")
    section.row.classList.add("dt-settings-preset-row")
    for (look in Looks.all) {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.setAttribute("data-dt-look", look.id)
        btn.className = "dt-settings-preset-btn" + if (look == matched) " dt-selected" else ""
        btn.title = "Apply the ${look.name} preset"
        val head = document.createElement("span") as HTMLElement
        head.className = "dt-settings-preset-head"
        val name = document.createElement("span") as HTMLElement
        name.className = "dt-settings-preset-name"
        name.textContent = look.name
        head.appendChild(name)
        if (look == matched) {
            val badge = document.createElement("span") as HTMLElement
            badge.className = "dt-settings-preset-badge"
            badge.textContent = "In use"
            head.appendChild(badge)
        }
        btn.appendChild(head)
        val desc = document.createElement("span") as HTMLElement
        desc.className = "dt-settings-preset-desc"
        desc.textContent = look.description
        btn.appendChild(desc)
        btn.addEventListener("click", { onPick(look) })
        section.row.appendChild(btn)
    }
    val status = document.createElement("div") as HTMLElement
    status.className = "dt-settings-preset-status"
    status.setAttribute("data-dt-look", matched?.id ?: "custom")
    status.textContent = if (matched != null) {
        "Your settings match the ${matched.name} preset."
    } else {
        "Your own mix: the settings below match no preset."
    }
    section.element.appendChild(status)
    return section.element
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
 * The surfaces the Appearance sidebar's Fonts section has a line for, in
 * line order. An app names them in [SettingsSidebarSpec.fontSizeHidden]
 * (via `AppShellSpec.fontSizeHidden`) to drop a line's size control.
 */
enum class FontSurfaceId { Sidebar, TabBar, WindowTitle, Text, Headings, Code }

/**
 * One surface's font settings, as the Fonts section lists them: what the host
 * stores for it and what is painted when it stores nothing.
 *
 * @property id      which surface this is.
 * @property label   the surface's name on its line ("Sidebar", "Code", …).
 * @property tooltip what the surface letters, shown on hover.
 * @property kind    [FontKind.Mono] for the code surface (monospaced fonts only).
 * @property family  the user's stored family key, `null` when unset.
 * @property setFamily the host setter plus the matching `apply…FontFamily`.
 * @property size    the user's stored size, `null` when unset.
 * @property setSize the host setter plus the matching `apply…FontSizePx`.
 * @property sizes   the sizes the stepper walks through, ascending.
 */
private class FontSurface(
    val id: FontSurfaceId,
    val label: String,
    val tooltip: String,
    val kind: FontKind,
    val family: () -> String?,
    val setFamily: (String) -> Unit,
    val size: () -> Int?,
    val setSize: (Int) -> Unit,
    val sizes: List<Int>,
) {
    /** The font painted when nothing is stored; set once every surface exists. */
    var paintedFamily: () -> String = { "systemProp" }
    /** The size painted when nothing is stored. */
    var paintedSize: () -> Int = { 13 }
}

/**
 * Builds the Fonts section: one line per surface — Sidebar, Tab bar, Window
 * title, Text, Headings, Code — each naming the font and size actually painted
 * there and changing either in place (no size control for a surface in
 * [SettingsSidebarSpec.fontSizeHidden]). The font button opens a searchable list
 * of every font ([buildFontPicker]); the size is a − / + stepper.
 *
 * What a line shows when the user has stored nothing follows the same ladder
 * `AppShellMount.applyHostFontVars` and `lunula.css` paint with: the app's
 * default for the surface (a brand font), else the surface it falls back to
 * (Tab bar and Window title → Sidebar, Headings → Text), else the system font
 * (`systemProp` / `system`, named for what they paint — [systemFontName]). So the
 * button always names a real font, never "Default". A pick updates every line
 * at once, since a line that falls back shows the font it falls back to.
 *
 * Called by [renderSettingsBody].
 */
private fun buildFontsSection(spec: SettingsSidebarSpec): HTMLElement {
    val host = spec.host
    val sidebar = FontSurface(FontSurfaceId.Sidebar, "Sidebar", "The topbar and the sidebars.", FontKind.Proportional,
        { host.sidebarFontFamily }, { host.setSidebarFontFamily(it); applySidebarFontFamily(it) },
        { host.sidebarFontSizePx }, { host.setSidebarFontSizePx(it); applySidebarFontSizePx(it) },
        spec.sidebarSizePresets)
    val tabbar = FontSurface(FontSurfaceId.TabBar, "Tab bar", "The tab strip.", FontKind.Proportional,
        { host.tabbarFontFamily }, { host.setTabbarFontFamily(it); applyTabbarFontFamily(it) },
        { host.tabbarFontSizePx }, { host.setTabbarFontSizePx(it); applyTabbarFontSizePx(it) },
        spec.sidebarSizePresets)
    val paneHeader = FontSurface(FontSurfaceId.WindowTitle, "Window title", "Each window's title bar.", FontKind.Proportional,
        { host.paneHeaderFontFamily }, { host.setPaneHeaderFontFamily(it); applyPaneHeaderFontFamily(it) },
        { host.paneHeaderFontSizePx }, { host.setPaneHeaderFontSizePx(it); applyPaneHeaderFontSizePx(it) },
        spec.sidebarSizePresets)
    val prose = FontSurface(FontSurfaceId.Text, "Text", "Prose and note content.", FontKind.Proportional,
        { host.proportionalFontFamily }, { host.setProportionalFontFamily(it); applyProportionalFontFamily(it) },
        { host.proportionalFontSizePx }, { host.setProportionalFontSizePx(it); applyProportionalFontSizePx(it) },
        spec.mainSizePresets)
    val display = FontSurface(FontSurfaceId.Headings, "Headings", "Titles and headings.", FontKind.Proportional,
        { host.displayFontFamily }, { host.setDisplayFontFamily(it); applyDisplayFontFamily(it) },
        { host.displayFontSizePx }, { host.setDisplayFontSizePx(it); applyDisplayFontSizePx(it) },
        spec.mainSizePresets)
    val mono = FontSurface(FontSurfaceId.Code, "Code", "Terminals, code panes and code in text.", FontKind.Mono,
        { host.monoFontFamily }, { host.setMonoFontFamily(it); applyMonoFontFamily(it) },
        { host.monoFontSizePx }, { host.setMonoFontSizePx(it); applyMonoFontSizePx(it) },
        spec.mainSizePresets)

    // A pick is shown before an async host setter has stored it (termtastic's
    // setters run through `launch { … }`), so every line reads these first.
    val pickedFamily = mutableMapOf<FontSurface, String>()
    val pickedSize = mutableMapOf<FontSurface, Int>()
    fun FontSurface.familyNow(): String? = pickedFamily[this] ?: family()?.ifEmpty { null }
    fun FontSurface.sizeNow(): Int? = pickedSize[this] ?: size()
    fun FontSurface.shownFamily(): String = familyNow() ?: paintedFamily()
    fun FontSurface.shownSize(): Int = sizeNow() ?: paintedSize()

    sidebar.paintedFamily = { spec.chromeDefaultKey() ?: "systemProp" }
    tabbar.paintedFamily = { spec.chromeDefaultKey() ?: sidebar.shownFamily() }
    paneHeader.paintedFamily = { spec.chromeDefaultKey() ?: sidebar.shownFamily() }
    prose.paintedFamily = { spec.proseDefaultKey() ?: "systemProp" }
    display.paintedFamily = { spec.displayDefaultKey() ?: prose.shownFamily() }
    mono.paintedFamily = { spec.monoDefaultKey() ?: "system" }
    sidebar.paintedSize = { spec.sidebarSizeDefault }
    tabbar.paintedSize = { sidebar.shownSize() }
    paneHeader.paintedSize = { spec.paneHeaderSizeDefault }
    prose.paintedSize = { spec.mainSizeDefault }
    display.paintedSize = { spec.displaySizeDefault }
    mono.paintedSize = { spec.monoSizeDefault }

    val section = document.createElement("div") as HTMLElement
    section.className = "dt-settings-section dt-fonts-section"
    val label = document.createElement("div") as HTMLElement
    label.className = "dt-settings-label"
    label.textContent = "Fonts"
    section.appendChild(label)

    val refreshers = mutableListOf<() -> Unit>()
    fun refreshAll() = refreshers.forEach { it() }
    for (surface in listOf(sidebar, tabbar, paneHeader, prose, display, mono)) {
        section.appendChild(buildFontLine(
            surface = surface,
            showSize = surface.id !in spec.fontSizeHidden,
            shownFamily = { surface.shownFamily() },
            shownSize = { surface.shownSize() },
            onPickFamily = { key ->
                pickedFamily[surface] = key
                surface.setFamily(key)
                refreshAll()
            },
            onPickSize = { px ->
                pickedSize[surface] = px
                surface.setSize(px)
                refreshAll()
            },
            registerRefresh = { refreshers.add(it) },
        ))
    }
    return section
}

/**
 * Builds one line of the Fonts section: the surface's name, a button naming
 * its font (drawn in it) and a − / + size stepper; the button opens a
 * searchable list of every font the surface takes, below the line. Opening
 * one line's list closes any other.
 *
 * The list holds presets and the machine's installed families alike,
 * alphabetically ([fontRowChoices]), each drawn in its own face; the
 * Code line lists monospaced faces only.
 *
 * Called by [buildFontsSection] for each surface.
 *
 * @param shownFamily the key the button names: the stored pick, or the font
 *   painted without one. A key no list offers (a font since uninstalled) is
 *   still named.
 * @param showSize whether the line has a size stepper; `false` for a surface
 *   in [SettingsSidebarSpec.fontSizeHidden], whose font button then takes the
 *   stepper's room too.
 * @param shownSize the size the stepper shows, likewise.
 * @param onPickFamily called with a picked entry's key.
 * @param onPickSize called with the stepped-to size.
 * @param registerRefresh hands over this line's repaint, run after any pick
 *   (a line that falls back to another shows that line's font).
 */
private fun buildFontLine(
    surface: FontSurface,
    showSize: Boolean,
    shownFamily: () -> String,
    shownSize: () -> Int,
    onPickFamily: (String) -> Unit,
    onPickSize: (Int) -> Unit,
    registerRefresh: (() -> Unit) -> Unit,
): HTMLElement {
    val kind = surface.kind
    // Start listing the installed families now, so the list is whole when opened.
    loadLocalFontFamilies {}

    val wrap = document.createElement("div") as HTMLElement
    wrap.className = "dt-font-picker"
    val line = document.createElement("div") as HTMLElement
    line.className = "dt-font-line"
    line.title = surface.tooltip
    wrap.appendChild(line)

    val name = document.createElement("div") as HTMLElement
    name.className = "dt-font-line-name"
    name.textContent = surface.label
    line.appendChild(name)

    val button = document.createElement("button") as HTMLElement
    button.setAttribute("type", "button")
    button.setAttribute("aria-label", "${surface.label} font")
    button.className = "dt-settings-choice-btn dt-font-picker-button"
    val buttonLabel = document.createElement("span") as HTMLElement
    buttonLabel.className = "dt-font-picker-label"
    val chevron = document.createElement("span") as HTMLElement
    chevron.className = "dt-font-picker-chevron"
    chevron.textContent = "▾"
    button.appendChild(buttonLabel)
    button.appendChild(chevron)
    line.appendChild(button)

    val stepper = document.createElement("div") as HTMLElement
    stepper.className = "dt-font-size"
    val minus = document.createElement("button") as HTMLButtonElement
    minus.type = "button"
    minus.className = "dt-font-size-step"
    minus.textContent = "−"
    minus.setAttribute("aria-label", "Smaller ${surface.label} font")
    val sizeLabel = document.createElement("span") as HTMLElement
    sizeLabel.className = "dt-font-size-value"
    val plus = document.createElement("button") as HTMLButtonElement
    plus.type = "button"
    plus.className = "dt-font-size-step"
    plus.textContent = "+"
    plus.setAttribute("aria-label", "Larger ${surface.label} font")
    stepper.appendChild(minus)
    stepper.appendChild(sizeLabel)
    stepper.appendChild(plus)
    if (showSize) line.appendChild(stepper) else line.classList.add("dt-font-line-nosize")

    val sizes = surface.sizes.sorted()
    fun step(direction: Int) {
        val now = shownSize()
        val next = if (direction > 0) sizes.firstOrNull { it > now } else sizes.lastOrNull { it < now }
        if (next != null) onPickSize(next)
    }
    minus.addEventListener("click", { step(-1) })
    plus.addEventListener("click", { step(+1) })

    fun stackOf(key: String): String =
        if (kind == FontKind.Mono) resolveFontFamilyCss(key) else resolveProportionalFontFamilyCss(key)

    fun repaint() {
        val key = shownFamily()
        buttonLabel.textContent = fontLabelFor(key)
        button.style.fontFamily = stackOf(key)
        val px = shownSize()
        sizeLabel.textContent = "$px"
        minus.disabled = sizes.none { it < px }
        plus.disabled = sizes.none { it > px }
    }
    registerRefresh(::repaint)
    repaint()

    val panel = document.createElement("div") as HTMLElement
    panel.className = "dt-font-picker-panel"
    panel.hidden = true
    val search = document.createElement("input") as HTMLInputElement
    search.type = "search"
    search.className = "dt-font-picker-search"
    search.placeholder = "Search fonts"
    search.setAttribute("aria-label", "Search ${surface.label} fonts")
    val list = document.createElement("div") as HTMLElement
    list.className = "dt-font-picker-list"
    list.setAttribute("role", "listbox")
    panel.appendChild(search)
    panel.appendChild(list)
    wrap.appendChild(panel)

    var choices: List<FontChoice> = emptyList()

    fun close() {
        panel.hidden = true
        wrap.classList.remove("dt-open")
    }

    fun pick(choice: FontChoice) {
        close()
        onPickFamily(choice.key)
    }

    fun renderList() {
        val query = search.value.trim().lowercase()
        val selected = shownFamily()
        list.innerHTML = ""
        val shown = choices.filter { query.isEmpty() || query in it.label.lowercase() }
        for (choice in shown) {
            val item = document.createElement("button") as HTMLElement
            item.setAttribute("type", "button")
            item.setAttribute("role", "option")
            item.className = "dt-font-picker-item" + if (choice.key == selected) " dt-selected" else ""
            item.textContent = choice.label
            item.style.fontFamily = choice.cssStack
            item.addEventListener("click", { pick(choice) })
            list.appendChild(item)
        }
        if (shown.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "dt-font-picker-empty"
            empty.textContent = "No fonts match"
            list.appendChild(empty)
        }
    }

    fun refreshChoices() {
        choices = fontRowChoices(kind, allFontPresets(), detectInstalledFonts(), localFontFamilies())
        renderList()
    }

    button.addEventListener("click", {
        if (!panel.hidden) { close(); return@addEventListener }
        val open = document.querySelectorAll(".dt-font-picker.dt-open")
        for (i in 0 until open.length) {
            val other = open.item(i) as? HTMLElement ?: continue
            other.classList.remove("dt-open")
            (other.querySelector(".dt-font-picker-panel") as? HTMLElement)?.hidden = true
        }
        panel.hidden = false
        wrap.classList.add("dt-open")
        search.value = ""
        refreshChoices()
        // The installed families arrive asynchronously the first time.
        loadLocalFontFamilies { if (!panel.hidden) refreshChoices() }
        (list.querySelector(".dt-selected") as? HTMLElement)?.scrollIntoView(js("({ block: 'center' })"))
        search.focus()
    })
    search.addEventListener("input", { renderList() })
    search.addEventListener("keydown", { e ->
        val key = (e as KeyboardEvent).key
        when (key) {
            "Escape" -> { e.preventDefault(); e.stopPropagation(); close(); button.focus() }
            "Enter" -> {
                e.preventDefault()
                val query = search.value.trim().lowercase()
                choices.firstOrNull { query.isEmpty() || query in it.label.lowercase() }?.let { pick(it) }
            }
        }
    })
    return wrap
}

/**
 * Builds one pixel-valued pill row — font sizes, and the corner-radius row.
 *
 * Mirrors [buildFontFaceSection]'s null-fallback behaviour: when the
 * host has no stored value yet ([currentSize] returns null), the pill
 * matching [defaultSize] gets `.dt-selected` so the row always shows
 * exactly one ringed entry — even on a fresh install. The actual
 * rendered value in that case comes from CSS defaults
 * (`.dt-app-frame { font-size: 13px }` for sidebar/tabbar,
 * `--dt-corner-radius`'s 18px fallback for roundness), so [defaultSize]
 * must be kept in step with those.
 *
 * @param title         section title shown above the row.
 * @param sizes         pill values, in render order.
 * @param defaultSize   value to highlight when [currentSize] is null.
 * @param currentSize   reader for the host's stored value; may be null
 *   when nothing explicit has been picked.
 * @param onPick        called with the clicked value; the click handler
 *   also performs an optimistic DOM update so async hosts don't leave
 *   the previous selection lit.
 */
private fun buildPxChoiceSection(
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

/**
 * Builds a labelled segmented-choice row: one `.dt-settings-choice-btn` per
 * option, the current one marked `.dt-selected`, picked optimistically with
 * the caller doing the store + apply. Used by [renderSettingsBody] for every named-choice row.
 *
 * @param settingId    stamped as `data-dt-setting` on the section, so the row
 *   can be found without matching on its (translatable) label.
 * @param title        the row's label.
 * @param hint         the muted line under the label.
 * @param options      label → value pairs, in render order.
 * @param currentValue reader for the value to ring (already resolved through
 *   user → app → toolkit default by the caller).
 * @param onPick       called with the clicked value.
 * @return the section element.
 */
private fun <T> buildChoiceSection(
    settingId: String,
    title: String,
    hint: String,
    options: List<Pair<String, T>>,
    currentValue: () -> T,
    onPick: (T) -> Unit,
): HTMLElement {
    val section = makeSection(title, hint)
    section.element.setAttribute("data-dt-setting", settingId)
    val row = section.row
    val current = currentValue()
    for ((label, value) in options) {
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
