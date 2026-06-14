/**
 * Built-in (default) [Theme] definitions for Darkness apps.
 *
 * A [Theme] is a named composition of a main colour scheme plus per-section
 * scheme assignments (keyed by names from [Sections]). The themes defined
 * here ship with the app and cannot be deleted by the user, though they
 * can be reordered alongside user-created themes.
 *
 * Each theme references colour schemes by name from [recommendedColorSchemes].
 * A missing entry in [Theme.sections] means "fall back to [Theme.colorScheme]".
 *
 * @see Sections
 * @see recommendedColorSchemes
 * @see ColorScheme
 * @see UiSettings
 */
package se.soderbjorn.darkness.core

/**
 * Which appearance mode(s) a built-in [Theme] is designed for.
 *
 * @see Theme.mode
 */
enum class ConfigMode {
    /** Optimised for dark appearance. */
    Dark,
    /** Optimised for light appearance. */
    Light,
    /** Works well in both dark and light appearance. */
    Both,
}

/**
 * A theme: a named composition that maps a main colour scheme plus optional
 * per-section colour-scheme assignments.
 *
 * Resolution for a concrete pane named `pane` rendered by app `A`:
 *
 * ```
 * sections[A.paneToSection[pane]]    // universal section assignment
 *     ?: colorScheme                 // theme baseline
 * ```
 *
 * @property name        Human-readable theme name shown in the theme list.
 * @property mode        Which appearance mode(s) this theme is designed for.
 * @property colorScheme Name of the main colour scheme (must exist in
 *   [recommendedColorSchemes]). Used as the fallback for any pane whose
 *   section assignment is missing.
 * @property sections    Map from a [Sections] constant to a colour-scheme name.
 *   Missing keys inherit [colorScheme].
 *
 * @see Sections
 * @see ColorScheme
 * @see defaultThemes
 */
data class Theme(
    val name: String,
    val mode: ConfigMode = ConfigMode.Both,
    val colorScheme: String,
    val sections: Map<String, String> = emptyMap(),
)

/**
 * Helper to build a [Theme.sections] map without typing each null check.
 * Pass `null` for a section to inherit from [Theme.colorScheme].
 */
private fun sectionsOf(
    main: String? = null,
    sidebar: String? = null,
    tabs: String? = null,
    chrome: String? = null,
    activeChrome: String? = null,
    active: String? = null,
    windows: String? = null,
    auxiliary: String? = null,
    bottomBar: String? = null,
    accent: String? = null,
): Map<String, String> = buildMap {
    main?.let { put(Sections.Main, it) }
    sidebar?.let { put(Sections.Sidebar, it) }
    tabs?.let { put(Sections.Tabs, it) }
    chrome?.let { put(Sections.Chrome, it) }
    activeChrome?.let { put(Sections.ActiveChrome, it) }
    active?.let { put(Sections.Active, it) }
    windows?.let { put(Sections.Windows, it) }
    auxiliary?.let { put(Sections.Auxiliary, it) }
    bottomBar?.let { put(Sections.BottomBar, it) }
    accent?.let { put(Sections.Accent, it) }
}

/**
 * The list of built-in [Theme]s that ship with the Darkness app family.
 *
 * Each entry is a curated combination of colour schemes assigned to the
 * universal sections, designed to produce a cohesive and visually distinct
 * look in any Darkness app. Users can reorder these alongside their custom
 * themes, but cannot delete them.
 *
 * @see Theme
 */
val defaultThemes: List<Theme> = listOf(
    // ── Dual-mode configurations ──────────────────────────────────────

    Theme(
        name = "Verdant",
        mode = ConfigMode.Both,
        colorScheme = "Verdant",
        sections = sectionsOf(
            main = "Verdant",
            sidebar = "Verdant",
            auxiliary = "Verdant",
            tabs = "Verdant",
            chrome = "Verdant",
            windows = "Verdant",
            active = "Verdant",
            activeChrome = "Verdant",
            bottomBar = "Verdant",
        ),
    ),

    // ── Dark mode configurations ──────────────────────────────────────

    // Neon family — minimal monochrome themes that paint every section in
    // a single saturated hue over a near-black bg. Each points at a
    // same-named entry in `recommendedColorSchemes` carrying hand-tuned
    // syntax overrides so the code pane stays polished, not flat.
    Theme(
        name = "Neon Green",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Green",
    ),
    Theme(
        name = "Neon Red",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Red",
    ),
    Theme(
        name = "Neon Yellow",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Yellow",
    ),
    Theme(
        name = "Neon Orange",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Orange",
    ),
    Theme(
        name = "Neon Blue",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Blue",
    ),
    Theme(
        name = "Neon Cyan",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Cyan",
    ),
    Theme(
        name = "Neon Purple",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Purple",
    ),
    Theme(
        name = "Neon Circuit",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Green",
        sections = sectionsOf(
            sidebar = "Vapor pink",
            main = "Cyber teal",
            tabs = "Neon Green",
            active = "Plasma",
        ),
    ),
    Theme(
        name = "Science Fiction",
        mode = ConfigMode.Dark,
        colorScheme = "Neon Green",
        sections = sectionsOf(
            // Neon Green on everything — green chrome, green editor — with a
            // Sci-fi blue accent / focus-ring set so the command palette
            // border, primary-button glow, and active-pane outline pop in a
            // complementary blue that nods to classic sci-fi UIs.
            active = "Sci-fi blue",
            activeChrome = "Sci-fi blue",
            accent = "Sci-fi blue",
        ),
    ),
    Theme(
        name = "Sunset Drive",
        mode = ConfigMode.Dark,
        colorScheme = "Amber Glow",
        sections = sectionsOf(
            sidebar = "Ember",
            main = "Coral",
            tabs = "Sunset",
            active = "Sunset",
            activeChrome = "Sunset",
        ),
    ),
    Theme(
        name = "Purple Haze",
        mode = ConfigMode.Dark,
        colorScheme = "Dracula",
        sections = sectionsOf(
            sidebar = "Royal violet",
            main = "Ultraviolet",
            tabs = "Dracula",
            active = "Lavender",
            activeChrome = "Lavender",
        ),
    ),
    Theme(
        name = "Cyber Noir",
        mode = ConfigMode.Dark,
        colorScheme = "Night Owl",
        sections = sectionsOf(
            sidebar = "Neon Green",
            main = "Matrix",
            windows = "Ayu Mirage",
            tabs = "Night Owl",
            auxiliary = "Ayu",
            active = "Cyber teal",
            activeChrome = "Cyber teal",
        ),
    ),
    Theme(
        name = "Ember Dusk",
        mode = ConfigMode.Dark,
        colorScheme = "One Dark",
        sections = sectionsOf(
            sidebar = "Monokai",
            main = "Lava",
            windows = "One Dark",
            tabs = "Hopscotch",
            auxiliary = "Dracula",
            active = "Crimson",
            activeChrome = "Crimson",
        ),
    ),
    Theme(
        name = "Candy Pop",
        mode = ConfigMode.Dark,
        colorScheme = "Catppuccin",
        sections = sectionsOf(
            sidebar = "Dracula",
            main = "Vapor pink",
            windows = "Catppuccin",
            tabs = "Hopscotch",
            auxiliary = "Rose Pine",
            active = "Magenta",
            activeChrome = "Magenta",
        ),
    ),
    Theme(
        name = "Ubuntu Terminal",
        mode = ConfigMode.Dark,
        colorScheme = "Ubuntu",
        sections = sectionsOf(
            sidebar = "Dracula",
            main = "Cherry",
            windows = "Hopscotch",
            tabs = "Ubuntu",
            auxiliary = "One Dark",
            active = "Hot rose",
            activeChrome = "Hot rose",
        ),
    ),
    Theme(
        name = "Hacker",
        mode = ConfigMode.Dark,
        colorScheme = "Ayu",
        sections = sectionsOf(
            sidebar = "Night Owl",
            main = "Matrix",
            windows = "Ayu",
            tabs = "Spacegray",
            auxiliary = "Monokai",
            active = "Cyber lime",
            activeChrome = "Cyber lime",
        ),
    ),
    Theme(
        name = "Retro Terminal",
        mode = ConfigMode.Dark,
        colorScheme = "Pencil",
        sections = sectionsOf(
            sidebar = "Ayu",
            main = "Amber CRT",
            windows = "Pencil",
            tabs = "Spacegray",
            auxiliary = "Monokai",
            active = "Cyber lime",
            activeChrome = "Cyber lime",
        ),
    ),

    Theme(
        name = "Magma",
        mode = ConfigMode.Dark,
        colorScheme = "Monokai",
        sections = sectionsOf(
            sidebar = "Hopscotch",
            main = "Crimson",
            windows = "Monokai",
            tabs = "Ubuntu",
            auxiliary = "Dracula",
            active = "Lava",
            activeChrome = "Lava",
        ),
    ),
    Theme(
        name = "Midnight Oil",
        mode = ConfigMode.Dark,
        colorScheme = "Ayu Mirage",
        sections = sectionsOf(
            sidebar = "Cobalt2",
            main = "Indigo",
            windows = "Ayu Mirage",
            tabs = "Night Owl",
            auxiliary = "Tokyo Night",
            active = "Cobalt",
            activeChrome = "Cobalt",
        ),
    ),
    Theme(
        name = "Toxic",
        mode = ConfigMode.Dark,
        colorScheme = "Matrix",
        sections = sectionsOf(
            sidebar = "Ayu",
            main = "Cyber lime",
            windows = "Spacegray",
            tabs = "Pencil",
            auxiliary = "Monokai",
            active = "Aqua glow",
            activeChrome = "Aqua glow",
        ),
    ),
    Theme(
        name = "Outrun",
        mode = ConfigMode.Dark,
        colorScheme = "Synthwave",
        sections = sectionsOf(
            sidebar = "Dracula",
            main = "Hot rose",
            windows = "Catppuccin",
            tabs = "Hopscotch",
            auxiliary = "Rose Pine",
            active = "Vapor pink",
            activeChrome = "Vapor pink",
        ),
    ),
    Theme(
        name = "Deep Space",
        mode = ConfigMode.Dark,
        colorScheme = "Night Owl",
        sections = sectionsOf(
            sidebar = "Tokyo Night",
            main = "Cobalt",
            windows = "Night Owl",
            tabs = "Ayu Mirage",
            auxiliary = "Nord",
            active = "Periwinkle",
            activeChrome = "Periwinkle",
        ),
    ),
    Theme(
        name = "Molten Gold",
        mode = ConfigMode.Dark,
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Monokai",
            main = "Sunflower",
            windows = "Sepia",
            tabs = "Gruvbox",
            auxiliary = "One Dark",
            active = "Honey",
            activeChrome = "Honey",
        ),
    ),
    Theme(
        name = "Blood Moon",
        mode = ConfigMode.Dark,
        colorScheme = "Dracula",
        sections = sectionsOf(
            sidebar = "Ubuntu",
            main = "Cherry",
            windows = "Hopscotch",
            tabs = "Dracula",
            auxiliary = "One Dark",
            active = "Crimson",
            activeChrome = "Crimson",
        ),
    ),

    // ── Light mode configurations ─────────────────────────────────────

    Theme(
        name = "Paper & Ink",
        mode = ConfigMode.Light,
        colorScheme = "Paper White",
        sections = sectionsOf(
            sidebar = "GitHub",
            main = "Pencil",
            windows = "Paper White",
            tabs = "Spacegray",
            auxiliary = "GitHub",
            active = "Mono Black",
            activeChrome = "Mono Black",
        ),
    ),
    Theme(
        name = "Solarized Warm",
        mode = ConfigMode.Light,
        colorScheme = "Solarized",
        sections = sectionsOf(
            sidebar = "Sepia",
            main = "Solarized",
            windows = "Gruvbox",
            tabs = "Sepia",
            auxiliary = "Solarized",
            active = "Honey",
            activeChrome = "Honey",
        ),
    ),
    Theme(
        name = "Morning Fog",
        mode = ConfigMode.Light,
        colorScheme = "Nord",
        sections = sectionsOf(
            sidebar = "Spacegray",
            main = "GitHub",
            windows = "Nord",
            tabs = "Material",
            auxiliary = "Nord",
            active = "Sky",
            activeChrome = "Sky",
        ),
    ),
    Theme(
        name = "Lavender Fields",
        mode = ConfigMode.Light,
        colorScheme = "Catppuccin",
        sections = sectionsOf(
            sidebar = "Rose Pine",
            main = "Lavender",
            windows = "Catppuccin",
            tabs = "Hopscotch",
            auxiliary = "Rose Pine",
            active = "Royal violet",
            activeChrome = "Royal violet",
        ),
    ),
    Theme(
        name = "Cream & Coffee",
        mode = ConfigMode.Light,
        colorScheme = "Gruvbox",
        sections = sectionsOf(
            sidebar = "Sepia",
            main = "Sand",
            windows = "Gruvbox",
            tabs = "Monokai",
            auxiliary = "Gruvbox",
            active = "Amber Glow",
            activeChrome = "Amber Glow",
        ),
    ),
    Theme(
        name = "Ocean Breeze",
        mode = ConfigMode.Light,
        colorScheme = "GitHub",
        sections = sectionsOf(
            sidebar = "Cobalt2",
            main = "Sky",
            windows = "GitHub",
            tabs = "Nord",
            auxiliary = "Material",
            active = "Ocean",
            activeChrome = "Ocean",
        ),
    ),
    Theme(
        name = "Spring Garden",
        mode = ConfigMode.Light,
        colorScheme = "One Dark",
        sections = sectionsOf(
            sidebar = "Gruvbox",
            main = "Mint Cream",
            windows = "One Dark",
            tabs = "Material",
            auxiliary = "GitHub",
            active = "Sea Foam",
            activeChrome = "Sea Foam",
        ),
    ),
    Theme(
        name = "Peach Blossom",
        mode = ConfigMode.Light,
        colorScheme = "Rose Pine",
        sections = sectionsOf(
            sidebar = "Catppuccin",
            main = "Peach",
            windows = "Rose Pine",
            tabs = "Hopscotch",
            auxiliary = "Catppuccin",
            active = "Coral",
            activeChrome = "Coral",
        ),
    ),
    Theme(
        name = "Clean Slate",
        mode = ConfigMode.Light,
        colorScheme = "Mono Black",
        sections = sectionsOf(
            sidebar = "GitHub",
            main = "Spacegray",
            windows = "Pencil",
            tabs = "Mono Black",
            auxiliary = "GitHub",
            active = "Cobalt",
            activeChrome = "Cobalt",
        ),
    ),
    Theme(
        name = "Ubuntu Rose",
        mode = ConfigMode.Light,
        colorScheme = "Ubuntu",
        sections = sectionsOf(
            sidebar = "Hopscotch",
            main = "Pastel Pink",
            windows = "Ubuntu",
            tabs = "Rose Pine",
            auxiliary = "Catppuccin",
            active = "Cherry",
            activeChrome = "Cherry",
        ),
    ),

    Theme(
        name = "Ivory Tower",
        mode = ConfigMode.Light,
        colorScheme = "GitHub",
        sections = sectionsOf(
            sidebar = "One Dark",
            main = "Paper White",
            windows = "GitHub",
            tabs = "Spacegray",
            auxiliary = "Nord",
            active = "Indigo",
            activeChrome = "Indigo",
        ),
    ),
    Theme(
        name = "Mint Tea",
        mode = ConfigMode.Light,
        colorScheme = "Material",
        sections = sectionsOf(
            sidebar = "Nord",
            main = "Mint Cream",
            windows = "Material",
            tabs = "GitHub",
            auxiliary = "One Dark",
            active = "Sea Foam",
            activeChrome = "Sea Foam",
        ),
    ),
    Theme(
        name = "Sunset Terrace",
        mode = ConfigMode.Light,
        colorScheme = "Gruvbox",
        sections = sectionsOf(
            sidebar = "Monokai",
            main = "Peach",
            windows = "Gruvbox",
            tabs = "Sepia",
            auxiliary = "One Dark",
            active = "Coral",
            activeChrome = "Coral",
        ),
    ),
    Theme(
        name = "Nordic Frost",
        mode = ConfigMode.Light,
        colorScheme = "Nord",
        sections = sectionsOf(
            sidebar = "Cobalt2",
            main = "Ice",
            windows = "Nord",
            tabs = "GitHub",
            auxiliary = "Material",
            active = "Periwinkle",
            activeChrome = "Periwinkle",
        ),
    ),
    Theme(
        name = "Daybreak",
        mode = ConfigMode.Light,
        colorScheme = "One Dark",
        sections = sectionsOf(
            sidebar = "Catppuccin",
            main = "Sky",
            windows = "One Dark",
            tabs = "Nord",
            auxiliary = "GitHub",
            active = "Ocean",
            activeChrome = "Ocean",
        ),
    ),
    Theme(
        name = "Honey Bee",
        mode = ConfigMode.Light,
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Gruvbox",
            main = "Sunflower",
            windows = "Sepia",
            tabs = "Monokai",
            auxiliary = "Gruvbox",
            active = "Amber Glow",
            activeChrome = "Amber Glow",
        ),
    ),

    // ── Both modes ────────────────────────────────────────────────────

    Theme(
        name = "Deep Ocean",
        colorScheme = "Nord",
        sections = sectionsOf(
            sidebar = "Cobalt",
            main = "Ocean",
            tabs = "Nord",
            active = "Ice",
            activeChrome = "Ice",
        ),
    ),
    Theme(
        name = "Forest Floor",
        colorScheme = "Gruvbox",
        sections = sectionsOf(
            sidebar = "Forest",
            main = "Sage",
            tabs = "Gruvbox",
            active = "Olive",
            activeChrome = "Olive",
        ),
    ),
    Theme(
        name = "Tokyo Midnight",
        colorScheme = "Tokyo Night",
        sections = sectionsOf(
            sidebar = "Dracula",
            main = "Catppuccin",
            tabs = "Tokyo Night",
            active = "Rose Pine",
            activeChrome = "Rose Pine",
        ),
    ),
    Theme(
        name = "Solarized Classic",
        colorScheme = "Solarized",
        sections = sectionsOf(
            // Solarized's canonical highlight is its blue (#268bd2). Cobalt
            // gives the focus accent + focused-pane titlebar a saturated
            // blue band that reads against both the dark (#002b36) and
            // light (#fdf6e3) Solarized surfaces without competing with
            // Solarized's own amber/cyan body palette.
            active = "Cobalt",
            activeChrome = "Cobalt",
        ),
    ),
    Theme(
        name = "Warm Hearth",
        colorScheme = "Monokai",
        sections = sectionsOf(
            sidebar = "Sepia",
            main = "Amber CRT",
            tabs = "Monokai",
            active = "Honey",
            activeChrome = "Honey",
        ),
    ),
    Theme(
        name = "Rose Garden",
        colorScheme = "Rose Pine",
        sections = sectionsOf(
            sidebar = "Catppuccin",
            main = "Pastel Pink",
            tabs = "Rose Pine",
            active = "Hopscotch",
            activeChrome = "Hopscotch",
        ),
    ),
    Theme(
        name = "Monochrome",
        colorScheme = "Mono Black",
        sections = sectionsOf(
            sidebar = "Pencil",
            main = "Spacegray",
            tabs = "Mono Black",
            active = "GitHub",
            activeChrome = "GitHub",
        ),
    ),
    Theme(
        name = "Arctic Aurora",
        colorScheme = "Nord",
        sections = sectionsOf(
            sidebar = "Material",
            main = "Ice",
            windows = "Nord",
            tabs = "Cobalt2",
            auxiliary = "GitHub",
            active = "Aqua glow",
            activeChrome = "Aqua glow",
        ),
    ),
    Theme(
        name = "Sakura",
        colorScheme = "Rose Pine",
        sections = sectionsOf(
            sidebar = "Hopscotch",
            main = "Pastel Pink",
            windows = "Catppuccin",
            tabs = "Rose Pine",
            auxiliary = "Catppuccin",
            active = "Hot rose",
            activeChrome = "Hot rose",
        ),
    ),
    Theme(
        name = "Copper Patina",
        colorScheme = "Material",
        sections = sectionsOf(
            sidebar = "Gruvbox",
            main = "Sea Foam",
            windows = "Material",
            tabs = "Sepia",
            auxiliary = "Ayu Mirage",
            active = "Honey",
            activeChrome = "Honey",
        ),
    ),
    Theme(
        name = "Midnight Violet",
        colorScheme = "Tokyo Night",
        sections = sectionsOf(
            sidebar = "Catppuccin",
            main = "Indigo",
            windows = "Tokyo Night",
            tabs = "Dracula",
            auxiliary = "Rose Pine",
            active = "Royal violet",
            activeChrome = "Royal violet",
        ),
    ),
    Theme(
        name = "Sandstorm",
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Gruvbox",
            main = "Sand",
            windows = "Sepia",
            tabs = "Monokai",
            auxiliary = "Gruvbox",
            active = "Apricot",
            activeChrome = "Apricot",
        ),
    ),
    Theme(
        name = "Electric Blue",
        colorScheme = "Cobalt2",
        sections = sectionsOf(
            sidebar = "Nord",
            main = "Cobalt",
            windows = "Cobalt2",
            tabs = "Night Owl",
            auxiliary = "Material",
            active = "Periwinkle",
            activeChrome = "Periwinkle",
        ),
    ),
    Theme(
        name = "Jungle",
        colorScheme = "Ayu",
        sections = sectionsOf(
            sidebar = "Material",
            main = "Forest",
            windows = "Ayu",
            tabs = "Nord",
            auxiliary = "Gruvbox",
            active = "Teal Storm",
            activeChrome = "Teal Storm",
        ),
    ),
    Theme(
        name = "Campfire",
        colorScheme = "Gruvbox",
        sections = sectionsOf(
            sidebar = "Sepia",
            main = "Amber CRT",
            windows = "Monokai",
            tabs = "Gruvbox",
            auxiliary = "One Dark",
            active = "Sunset",
            activeChrome = "Sunset",
        ),
    ),
    Theme(
        name = "Frost",
        colorScheme = "GitHub",
        sections = sectionsOf(
            sidebar = "Nord",
            main = "Sky",
            windows = "GitHub",
            tabs = "Spacegray",
            auxiliary = "Nord",
            active = "Periwinkle",
            activeChrome = "Periwinkle",
        ),
    ),
    Theme(
        name = "Twilight",
        colorScheme = "Ayu Mirage",
        sections = sectionsOf(
            sidebar = "Tokyo Night",
            main = "Lavender",
            windows = "Ayu Mirage",
            tabs = "Catppuccin",
            auxiliary = "Dracula",
            active = "Synthwave",
            activeChrome = "Synthwave",
        ),
    ),
    Theme(
        name = "Autumn Leaves",
        colorScheme = "Monokai",
        sections = sectionsOf(
            sidebar = "Gruvbox",
            main = "Amber Glow",
            windows = "Sepia",
            tabs = "Monokai",
            auxiliary = "Gruvbox",
            active = "Olive",
            activeChrome = "Olive",
        ),
    ),
    Theme(
        name = "Coral Reef",
        colorScheme = "Night Owl",
        sections = sectionsOf(
            sidebar = "Material",
            main = "Sea Foam",
            windows = "Night Owl",
            tabs = "Cobalt2",
            auxiliary = "Ayu Mirage",
            active = "Aqua glow",
            activeChrome = "Aqua glow",
        ),
    ),
    Theme(
        name = "Starlight",
        colorScheme = "One Dark",
        sections = sectionsOf(
            sidebar = "Catppuccin",
            main = "Nord",
            windows = "Spacegray",
            tabs = "One Dark",
            auxiliary = "Tokyo Night",
            active = "Periwinkle",
            activeChrome = "Periwinkle",
        ),
    ),
    Theme(
        name = "Driftwood",
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Material",
            main = "Peach",
            windows = "Sepia",
            tabs = "Gruvbox",
            auxiliary = "Monokai",
            active = "Sand",
            activeChrome = "Sand",
        ),
    ),
    Theme(
        name = "Nebula",
        colorScheme = "Catppuccin",
        sections = sectionsOf(
            sidebar = "Tokyo Night",
            main = "Royal violet",
            windows = "Catppuccin",
            tabs = "Dracula",
            auxiliary = "Rose Pine",
            active = "Synthwave",
            activeChrome = "Synthwave",
        ),
    ),
    Theme(
        name = "Evergreen",
        colorScheme = "Material",
        sections = sectionsOf(
            sidebar = "Nord",
            main = "Forest",
            windows = "Material",
            tabs = "Gruvbox",
            auxiliary = "Ayu Mirage",
            active = "Sea Foam",
            activeChrome = "Sea Foam",
        ),
    ),

    // ── Bold light-mode configurations ────────────────────────────────
    // Each pairs a pastel content theme with a medium-saturated "Panel"
    // sidebar/chrome and a vivid "Bar" tab strip so the UI never collapses
    // to white/yellow/grey. The panel tone is deliberately mid-weight —
    // clearly coloured, but not so dark it turns the light mode into a
    // near-dark mode. The bottomBar is pulled to the panel hue so the
    // chrome reads as a single coloured frame around the soft content.

    Theme(
        name = "Midnight Library",
        mode = ConfigMode.Light,
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Midnight Panel",
            main = "Sepia",
            windows = "Sepia",
            tabs = "Tangerine Bar",
            chrome = "Midnight Panel",
            auxiliary = "Sepia",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Midnight Panel",
        ),
    ),
    // Body panes use the hand-tuned "Emerald Forest" scheme rather than
    // the pastel "Mint Chip" the early revision used. Two fixes that
    // composition alone couldn't deliver:
    //   • Saturated mid-emerald body bg + `chrome.titlebar` override a
    //     step lighter, so the titlebar visibly tops the pane body in
    //     both modes (the earlier pair collapsed into one surface,
    //     especially in light mode).
    //   • Focused-pane outline lands on warm amber — the chromatic
    //     complement of emerald — rather than the white the original
    //     Hot Magenta Bar `active` section produced (Bar schemes have
    //     `lightFg = #ffffff`, so their `accent.primary` defaulted to
    //     white and the ring was nearly invisible).
    Theme(
        name = "Emerald Garden",
        mode = ConfigMode.Light,
        colorScheme = "Emerald Forest",
        sections = sectionsOf(
            sidebar = "Forest Panel",
            main = "Emerald Forest",
            windows = "Emerald Forest",
            tabs = "Hot Magenta Bar",
            chrome = "Emerald Forest",
            auxiliary = "Emerald Forest",
            active = "Emerald Forest",
            activeChrome = "Emerald Forest",
            bottomBar = "Forest Panel",
        ),
    ),
    Theme(
        name = "Plum Velvet",
        mode = ConfigMode.Light,
        colorScheme = "Lavender Dream",
        sections = sectionsOf(
            sidebar = "Royal Plum Panel",
            main = "Lavender Dream",
            windows = "Lavender Dream",
            tabs = "Cyan Bar",
            chrome = "Royal Plum Panel",
            auxiliary = "Lavender Dream",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Royal Plum Panel",
        ),
    ),
    Theme(
        name = "Vintage Wine",
        mode = ConfigMode.Light,
        colorScheme = "Rose Quartz",
        sections = sectionsOf(
            sidebar = "Burgundy Panel",
            main = "Rose Quartz",
            windows = "Rose Quartz",
            tabs = "Tangerine Bar",
            chrome = "Burgundy Panel",
            auxiliary = "Rose Quartz",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Burgundy Panel",
        ),
    ),
    Theme(
        name = "Italian Espresso",
        mode = ConfigMode.Light,
        colorScheme = "Sepia",
        sections = sectionsOf(
            sidebar = "Espresso Panel",
            main = "Sepia",
            windows = "Sepia",
            tabs = "Tangerine Bar",
            chrome = "Espresso Panel",
            auxiliary = "Sepia",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Espresso Panel",
        ),
    ),
    Theme(
        name = "Tropical Lagoon",
        mode = ConfigMode.Light,
        colorScheme = "Tidepool",
        sections = sectionsOf(
            sidebar = "Deep Teal Panel",
            main = "Tidepool",
            windows = "Tidepool",
            tabs = "Hot Magenta Bar",
            chrome = "Deep Teal Panel",
            auxiliary = "Tidepool",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Deep Teal Panel",
        ),
    ),
    Theme(
        name = "Slate Office",
        mode = ConfigMode.Light,
        colorScheme = "Paper White",
        sections = sectionsOf(
            sidebar = "Slate Panel",
            main = "Paper White",
            windows = "Paper White",
            tabs = "Cyan Bar",
            chrome = "Slate Panel",
            auxiliary = "Paper White",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Slate Panel",
        ),
    ),
    Theme(
        name = "Indigo Bloom",
        mode = ConfigMode.Light,
        colorScheme = "Lavender Dream",
        sections = sectionsOf(
            sidebar = "Indigo Panel",
            main = "Lavender Dream",
            windows = "Lavender Dream",
            tabs = "Hot Magenta Bar",
            chrome = "Indigo Panel",
            auxiliary = "Lavender Dream",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Indigo Panel",
        ),
    ),
    Theme(
        name = "Terracotta Patio",
        mode = ConfigMode.Light,
        colorScheme = "Marigold",
        sections = sectionsOf(
            sidebar = "Terracotta Panel",
            main = "Marigold",
            windows = "Marigold",
            tabs = "Cyan Bar",
            chrome = "Terracotta Panel",
            auxiliary = "Marigold",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Terracotta Panel",
        ),
    ),
    Theme(
        name = "Olive Grove",
        mode = ConfigMode.Light,
        colorScheme = "Citrus Zest",
        sections = sectionsOf(
            sidebar = "Olive Panel",
            main = "Citrus Zest",
            windows = "Citrus Zest",
            tabs = "Tangerine Bar",
            chrome = "Olive Panel",
            auxiliary = "Citrus Zest",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Olive Panel",
        ),
    ),
    Theme(
        name = "Cocoa & Cream",
        mode = ConfigMode.Light,
        colorScheme = "Peach Sorbet",
        sections = sectionsOf(
            sidebar = "Mocha Panel",
            main = "Peach Sorbet",
            windows = "Peach Sorbet",
            tabs = "Tangerine Bar",
            chrome = "Mocha Panel",
            auxiliary = "Peach Sorbet",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Mocha Panel",
        ),
    ),
    Theme(
        name = "Charcoal Pearl",
        mode = ConfigMode.Light,
        colorScheme = "Paper White",
        sections = sectionsOf(
            sidebar = "Charcoal Panel",
            main = "Paper White",
            windows = "Paper White",
            tabs = "Cyan Bar",
            chrome = "Charcoal Panel",
            auxiliary = "Paper White",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Charcoal Panel",
        ),
    ),
    Theme(
        name = "Beach Cabana",
        mode = ConfigMode.Light,
        colorScheme = "Sky Breeze",
        sections = sectionsOf(
            sidebar = "Deep Teal Panel",
            main = "Sky Breeze",
            windows = "Sky Breeze",
            tabs = "Tangerine Bar",
            chrome = "Deep Teal Panel",
            auxiliary = "Sky Breeze",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Deep Teal Panel",
        ),
    ),
    Theme(
        name = "Berry Tart",
        mode = ConfigMode.Light,
        colorScheme = "Cotton Candy",
        sections = sectionsOf(
            sidebar = "Royal Plum Panel",
            main = "Cotton Candy",
            windows = "Cotton Candy",
            tabs = "Hot Magenta Bar",
            chrome = "Royal Plum Panel",
            auxiliary = "Cotton Candy",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Royal Plum Panel",
        ),
    ),
    Theme(
        name = "Lemon Forest",
        mode = ConfigMode.Light,
        colorScheme = "Citrus Zest",
        sections = sectionsOf(
            sidebar = "Forest Panel",
            main = "Citrus Zest",
            windows = "Citrus Zest",
            tabs = "Tangerine Bar",
            chrome = "Forest Panel",
            auxiliary = "Citrus Zest",
            active = "Lime Bar",
            activeChrome = "Lime Bar",
            bottomBar = "Forest Panel",
        ),
    ),
    Theme(
        name = "Watermelon Picnic",
        mode = ConfigMode.Light,
        colorScheme = "Rose Quartz",
        sections = sectionsOf(
            sidebar = "Forest Panel",
            main = "Rose Quartz",
            windows = "Rose Quartz",
            tabs = "Hot Magenta Bar",
            chrome = "Forest Panel",
            auxiliary = "Rose Quartz",
            active = "Lime Bar",
            activeChrome = "Lime Bar",
            bottomBar = "Forest Panel",
        ),
    ),
    Theme(
        name = "Aurora Mist",
        mode = ConfigMode.Light,
        colorScheme = "Mint Chip",
        sections = sectionsOf(
            sidebar = "Indigo Panel",
            main = "Mint Chip",
            windows = "Mint Chip",
            tabs = "Cyan Bar",
            chrome = "Indigo Panel",
            auxiliary = "Mint Chip",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Indigo Panel",
        ),
    ),
    Theme(
        name = "Sunset Diner",
        mode = ConfigMode.Light,
        colorScheme = "Coral Reef",
        sections = sectionsOf(
            sidebar = "Burgundy Panel",
            main = "Coral Reef",
            windows = "Coral Reef",
            tabs = "Tangerine Bar",
            chrome = "Burgundy Panel",
            auxiliary = "Coral Reef",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Burgundy Panel",
        ),
    ),
    Theme(
        name = "Pearl Diver",
        mode = ConfigMode.Light,
        colorScheme = "Tidepool",
        sections = sectionsOf(
            sidebar = "Midnight Panel",
            main = "Tidepool",
            windows = "Tidepool",
            tabs = "Cyan Bar",
            chrome = "Midnight Panel",
            auxiliary = "Tidepool",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Midnight Panel",
        ),
    ),
    // Slack-inspired: white content area, medium slate-blue sidebars,
    // deep navy tab/top bar — the signature Slack "navy" light look.
    // Marked Both so dark mode also gets a cohesive three-tier look
    // (darkest navy tabs → mid navy sidebar → dark navy content).
    Theme(
        name = "Slack",
        mode = ConfigMode.Both,
        colorScheme = "Slack Canvas",
        sections = sectionsOf(
            sidebar = "Slack Slate Panel",
            main = "Slack Canvas",
            windows = "Slack Slate Panel",
            tabs = "Slack Navy Panel",
            chrome = "Slack Navy Panel",
            auxiliary = "Slack Canvas",
            active = "Slack Navy Panel",
            activeChrome = "Slack Navy Panel",
            bottomBar = "Slack Navy Panel",
        ),
    ),
    Theme(
        name = "Confetti Party",
        mode = ConfigMode.Light,
        colorScheme = "Cotton Candy",
        sections = sectionsOf(
            sidebar = "Royal Plum Panel",
            main = "Cotton Candy",
            windows = "Cotton Candy",
            tabs = "Lime Bar",
            chrome = "Cyan Bar",
            auxiliary = "Cotton Candy",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Cyan Bar",
        ),
    ),

    // ── Bold dark-mode configurations ─────────────────────────────────

    Theme(
        name = "Neon Carnival",
        mode = ConfigMode.Dark,
        colorScheme = "Catppuccin",
        sections = sectionsOf(
            sidebar = "Hot Magenta Bar",
            main = "Catppuccin",
            windows = "Catppuccin",
            tabs = "Cyan Bar",
            chrome = "Royal Plum Panel",
            auxiliary = "Catppuccin",
            active = "Lime Bar",
            activeChrome = "Lime Bar",
            bottomBar = "Royal Plum Panel",
        ),
    ),
    Theme(
        name = "Deep Sea Trench",
        mode = ConfigMode.Dark,
        colorScheme = "Deep Sea",
        sections = sectionsOf(
            sidebar = "Midnight Panel",
            main = "Deep Sea",
            windows = "Deep Sea",
            tabs = "Cyan Bar",
            chrome = "Midnight Panel",
            auxiliary = "Deep Sea",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Midnight Panel",
        ),
    ),
    Theme(
        name = "Galactic Drift",
        mode = ConfigMode.Dark,
        colorScheme = "Nebula",
        sections = sectionsOf(
            sidebar = "Royal Plum Panel",
            main = "Nebula",
            windows = "Nebula",
            tabs = "Cyan Bar",
            chrome = "Royal Plum Panel",
            auxiliary = "Nebula",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Royal Plum Panel",
        ),
    ),
    Theme(
        name = "Phoenix Rise",
        mode = ConfigMode.Dark,
        colorScheme = "Volcanic",
        sections = sectionsOf(
            sidebar = "Burgundy Panel",
            main = "Volcanic",
            windows = "Volcanic",
            tabs = "Tangerine Bar",
            chrome = "Burgundy Panel",
            auxiliary = "Volcanic",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Burgundy Panel",
        ),
    ),
    Theme(
        name = "Forest Spirit",
        mode = ConfigMode.Dark,
        colorScheme = "Aurora",
        sections = sectionsOf(
            sidebar = "Forest Panel",
            main = "Aurora",
            windows = "Aurora",
            tabs = "Lime Bar",
            chrome = "Forest Panel",
            auxiliary = "Aurora",
            active = "Lime Bar",
            activeChrome = "Lime Bar",
            bottomBar = "Forest Panel",
        ),
    ),
    Theme(
        name = "Royal Court",
        mode = ConfigMode.Dark,
        colorScheme = "Tokyo Night",
        sections = sectionsOf(
            sidebar = "Royal Plum Panel",
            main = "Tokyo Night",
            windows = "Tokyo Night",
            tabs = "Tangerine Bar",
            chrome = "Royal Plum Panel",
            auxiliary = "Tokyo Night",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Royal Plum Panel",
        ),
    ),
    Theme(
        name = "Storm Front",
        mode = ConfigMode.Dark,
        colorScheme = "Night Owl",
        sections = sectionsOf(
            sidebar = "Slate Panel",
            main = "Night Owl",
            windows = "Night Owl",
            tabs = "Cyan Bar",
            chrome = "Slate Panel",
            auxiliary = "Night Owl",
            active = "Cyan Bar",
            activeChrome = "Cyan Bar",
            bottomBar = "Slate Panel",
        ),
    ),
    Theme(
        name = "Volcanic Lab",
        mode = ConfigMode.Dark,
        colorScheme = "Blood Moon",
        sections = sectionsOf(
            sidebar = "Burgundy Panel",
            main = "Blood Moon",
            windows = "Blood Moon",
            tabs = "Lime Bar",
            chrome = "Burgundy Panel",
            auxiliary = "Blood Moon",
            active = "Lime Bar",
            activeChrome = "Lime Bar",
            bottomBar = "Burgundy Panel",
        ),
    ),
    Theme(
        name = "Northern Lights",
        mode = ConfigMode.Dark,
        colorScheme = "Aurora",
        sections = sectionsOf(
            sidebar = "Indigo Panel",
            main = "Aurora",
            windows = "Aurora",
            tabs = "Hot Magenta Bar",
            chrome = "Indigo Panel",
            auxiliary = "Aurora",
            active = "Hot Magenta Bar",
            activeChrome = "Hot Magenta Bar",
            bottomBar = "Indigo Panel",
        ),
    ),
    Theme(
        name = "Burnished Copper",
        mode = ConfigMode.Dark,
        colorScheme = "Dragon's Hoard",
        sections = sectionsOf(
            sidebar = "Espresso Panel",
            main = "Dragon's Hoard",
            windows = "Dragon's Hoard",
            tabs = "Tangerine Bar",
            chrome = "Espresso Panel",
            auxiliary = "Dragon's Hoard",
            active = "Tangerine Bar",
            activeChrome = "Tangerine Bar",
            bottomBar = "Espresso Panel",
        ),
    ),
)

/**
 * Set of default theme configuration names for quick membership checks.
 *
 * Used to prevent users from creating custom configurations with names
 * that collide with built-in presets, and to identify default entries
 * in the merged configuration list.
 *
 * @see defaultThemes
 */
val defaultThemeConfigNames: Set<String> = defaultThemes.map { it.name }.toSet()
