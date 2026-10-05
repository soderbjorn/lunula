/**
 * Settings sidebar — slide-in right-side panel exposing every per-app
 * appearance / window control the toolkit owns.
 *
 * Sections rendered (in order):
 *  1. Custom title bar On/Off (Electron only).
 *  2. Corner roundness + Selection + Surfaces + Spacing — the shell's
 *     shape, its selection language, Depth vs Flat surfaces, and its density.
 *  3. Fonts — one line per surface (Sidebar, Tab bar, Window title, Text,
 *     Headings, Code): the font actually painted there, opening a searchable
 *     list of every usable font, and a − / + size stepper.
 *
 * Shape before type, deliberately: roundness and spacing change what the
 * shell IS shaped like, where every font line changes what it is lettered in.
 *
 * Note what is NOT here: colour. Corner roundness and spacing are user
 * preferences that must survive a theme change — a user who likes square
 * corners should not have to give up a palette to keep them — so they live
 * beside the fonts and are persisted per-app, not stored on a [Theme].
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
import se.soderbjorn.lunula.web.applyCornerRadiusPx
import se.soderbjorn.lunula.web.applyUiDensity
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.UiDensity
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
     * toolkit default, and the steps between are the ones that read as
     * distinct at a glance. Offering every integer would present forty
     * choices to answer a question with about five real answers.
     */
    val cornerRadiusPresets: List<Int> = listOf(0, 4, 8, 12, 18, 24),
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
)

/**
 * The radius the corner row highlights when the host has stored none.
 *
 * Must match `--dt-corner-radius`'s fallback in `lunula.css`, or the ringed
 * pill claims a value the shell is not actually painting.
 */
private const val DEFAULT_CORNER_RADIUS_PX: Int = 18

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
 * Each section is built once per render; pill rows and font lines
 * re-render in place after a click so the selected state updates without rebuilding the
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

    // ── Shape and spacing ───────────────────────────────────────────
    // Above the font rows because they are the two coarsest controls in the
    // panel: they change what the shell *is* shaped like, where every row
    // below changes what it is lettered in. Both are user settings rather
    // than theme properties — see ThemeManagerHost.cornerRadiusPx.
    body.appendChild(buildPxChoiceSection(
        title = "Corner roundness",
        sizes = spec.cornerRadiusPresets,
        defaultSize = spec.appDefaultShape().cornerRadiusPx ?: DEFAULT_CORNER_RADIUS_PX,
        currentSize = { spec.host.cornerRadiusPx },
        onPick = { px ->
            spec.host.setCornerRadiusPx(px)
            applyCornerRadiusPx(px)
        },
    ))
    body.appendChild(buildSelectionStyleSection(
        currentValue = {
            spec.host.selectionStyle
                ?: spec.appDefaultShape().selectionStyle
                ?: SelectionStyle.Default
        },
        onPick = { st ->
            spec.host.setSelectionStyle(st)
            applySelectionStyle(document.documentElement as HTMLElement, st)
        },
    ))
    body.appendChild(buildSurfaceStyleSection(
        currentValue = {
            spec.host.surfaceStyle
                ?: spec.appDefaultShape().surfaceStyle
                ?: SurfaceStyle.Default
        },
        onPick = { st ->
            spec.host.setSurfaceStyle(st)
            applySurfaceStyle(st)
        },
    ))
    body.appendChild(buildDensitySection(
        currentValue = {
            spec.host.uiDensity ?: spec.appDefaultShape().uiDensity ?: UiDensity.Compact
        },
        onPick = { d ->
            spec.host.setUiDensity(d)
            applyUiDensity(d)
        },
    ))

    // ── Fonts: one line per surface — its font and its size ─────────
    body.appendChild(buildFontsSection(spec))

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

/**
 * Builds the chrome-density pill row.
 *
 * Its own builder rather than a reuse of [buildToggleSection] because the
 * choices are named states, not On/Off, and rather than of [buildPxChoiceSection]
 * because the underlying values are not numbers the user should be reasoning
 * about — "Comfortable" is one decision, not the six paddings it sets.
 *
 * @param currentValue reader for the host's stored density (never null; the
 *   caller substitutes [UiDensity.Compact]).
 * @param onPick       called with the clicked density.
 */
private fun buildDensitySection(
    currentValue: () -> UiDensity,
    onPick: (UiDensity) -> Unit,
): HTMLElement {
    val section = makeSection("Spacing", "How much air the chrome puts around windows, tabs and rows.")
    val row = section.row
    val current = currentValue()
    for (density in UiDensity.entries) {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.className = "dt-settings-choice-btn" + if (density == current) " dt-selected" else ""
        btn.textContent = density.name
        btn.addEventListener("click", {
            // Optimistic selection update — see [buildFontFaceSection].
            val rowChildren = row.children
            for (i in 0 until rowChildren.length) {
                (rowChildren.item(i) as? HTMLElement)?.classList?.remove("dt-selected")
            }
            btn.classList.add("dt-selected")
            onPick(density)
        })
        row.appendChild(btn)
    }
    return section.element
}

/**
 * Builds the selection-style pill row.
 *
 * Labelled by what the user sees rather than by the enum: "Tinted" and
 * "Filled" describe the result, where "Tint"/"Fill" describe the mechanism.
 *
 * @param currentValue reader for the host's stored style (never null; the
 *   caller walks the user → app → [SelectionStyle.Default] ladder).
 * @param onPick       called with the clicked style.
 */
private fun buildSelectionStyleSection(
    currentValue: () -> SelectionStyle,
    onPick: (SelectionStyle) -> Unit,
): HTMLElement {
    val section = makeSection(
        "Selection",
        "How the focused window, active tab and active sidebar row are marked.",
    )
    val row = section.row
    val current = currentValue()
    for ((label, value) in listOf(
        "Tinted" to SelectionStyle.Tint,
        "Filled" to SelectionStyle.Fill,
    )) {
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
 * Builds the surface-style pill row ("Surfaces: Depth · Flat").
 *
 * Same shape as [buildSelectionStyleSection]: two named states, picked
 * optimistically, with the caller doing the persist + apply.
 *
 * @param currentValue reader for the host's stored style (never null; the
 *   caller walks the user → app → [SurfaceStyle.Default] ladder).
 * @param onPick       called with the clicked style.
 */
private fun buildSurfaceStyleSection(
    currentValue: () -> SurfaceStyle,
    onPick: (SurfaceStyle) -> Unit,
): HTMLElement {
    val section = makeSection(
        "Surfaces",
        "Depth lifts windows on soft shadows and gives accents a glow; Flat keeps everything on one plane.",
    )
    val row = section.row
    val current = currentValue()
    for ((label, value) in listOf(
        "Depth" to SurfaceStyle.Depth,
        "Flat" to SurfaceStyle.Flat,
    )) {
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
