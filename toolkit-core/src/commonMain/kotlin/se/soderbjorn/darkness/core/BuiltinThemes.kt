/* BuiltinThemes.kt
 * The 24 built-in themes (14 dark, 10 light), transcribed verbatim from the
 * "Termtastic Theme Studio" design's RAW array. Each theme defines all 20
 * literal tokens (including 8 dedicated syntax slots); the three the design
 * computes by formula (accentSoft, glow, addBg) are derived at render time by
 * [Theme.resolve] applying the design's alpha to the accent/add colour, so
 * nothing else is computed.
 */
package se.soderbjorn.darkness.core

/** Default theme bound to the dark slot. */
const val DEFAULT_DARK_THEME: String = "Termtastic Dark"

/** Default theme bound to the light slot. */
const val DEFAULT_LIGHT_THEME: String = "Termtastic Light"

/**
 * Constructs a [Theme] from its 20 literal design tokens. The three translucent
 * tokens (accentSoft / glow / addBg) are not stored — [Theme.resolve] derives
 * them by applying the design's alpha to the accent/add colour.
 */
private fun theme(
    name: String, group: ThemeGroup, tag: String, desc: String,
    bg: String, surface: String, surfaceAlt: String, border: String,
    text: String, textDim: String, textBright: String,
    accent: String, warn: String, danger: String, add: String, addText: String,
    synKeyword: String, synString: String, synNumber: String, synComment: String,
    synFunction: String, synType: String, synOperator: String, synConstant: String,
): Theme = Theme(
    name = name, group = group, tag = tag, desc = desc,
    bg = bg, surface = surface, surfaceAlt = surfaceAlt, border = border,
    text = text, textDim = textDim, textBright = textBright,
    accent = accent,
    warn = warn, danger = danger, add = add, addText = addText,
    synKeyword = synKeyword, synString = synString, synNumber = synNumber, synComment = synComment,
    synFunction = synFunction, synType = synType, synOperator = synOperator, synConstant = synConstant,
)

/**
 * The 24 built-in themes in display order: 14 dark, then 10 light.
 * `builtinThemes.first()` is the default dark theme ([DEFAULT_DARK_THEME]).
 *
 * @see Theme
 * @see allThemes
 */
val builtinThemes: List<Theme> = listOf(
    // ---------------------------------- Dark ----------------------------------
    theme("Termtastic Dark", ThemeGroup.Dark, "Signature", "The house look — green-dominant glow, a faint mint-white lift.",
        "#08110c", "#0c1611", "#112019", "#20392b", "#7fd0a0", "#557e63", "#dff7e8",
        "#6ee7a0", "#f0b24b", "#ef5f57", "#6ee7a0", "#a7f0c4",
        "#86efac", "#73c596", "#aef0b8", "#4f7a60", "#6ee7a0", "#5fe6c2", "#9fe0b8", "#d6f5b0"),
    theme("Phosphor", ThemeGroup.Dark, "CRT", "Maximum-contrast hacker green. The original glow.",
        "#03120a", "#081a0f", "#0c2114", "#184028", "#46e08a", "#3a7857", "#74ffb0",
        "#2fe57f", "#f0a64b", "#ef5350", "#2fe57f", "#8bf5b8",
        "#74ffb0", "#a9efc7", "#b8ffd0", "#3a7857", "#4fe89a", "#5fffd5", "#46e08a", "#c8ffb0"),
    theme("Tokyo Midnight", ThemeGroup.Dark, "Indigo", "Calm indigo night with violet & green syntax.",
        "#1a1b26", "#1f2230", "#16161e", "#2c3047", "#a9b1d6", "#565f89", "#d7dcff",
        "#7aa2f7", "#e0af68", "#f7768e", "#9ece6a", "#c7e8a0",
        "#bb9af7", "#9ece6a", "#ff9e64", "#565f89", "#7dcfff", "#2ac3de", "#a9b1d6", "#ff9e64"),
    theme("Amber CRT", ThemeGroup.Dark, "Mono", "Warm monochrome amber. Vintage VT terminal.",
        "#161005", "#1d1608", "#251c0a", "#3f2f13", "#e8a93f", "#8a6526", "#ffce73",
        "#ffb02e", "#ff7a33", "#ff5232", "#ffb02e", "#ffd98a",
        "#ffce73", "#c39a4c", "#e8c06a", "#8a6526", "#ffb02e", "#ffd98a", "#d9a23e", "#e8c06a"),
    theme("Synthwave", ThemeGroup.Dark, "Neon", "Neon magenta & cyan. Retro-futurist nights.",
        "#181029", "#201640", "#281a4c", "#3d2a63", "#e6d8ff", "#8a6fb0", "#ffffff",
        "#ff5fae", "#ffc857", "#ff4d6d", "#5fffd1", "#adfff0",
        "#ff5fae", "#5fffd1", "#ffc857", "#8a6fb0", "#c792ff", "#5ad4ff", "#9be8ff", "#ffc857"),
    theme("Nord Slate", ThemeGroup.Dark, "Arctic", "Cool desaturated slate. Low-contrast & easy.",
        "#2e3440", "#353c4a", "#3b4252", "#454d5e", "#d8dee9", "#6f7a8f", "#eceff4",
        "#88c0d0", "#ebcb8b", "#bf616a", "#a3be8c", "#cbe0b6",
        "#81a1c1", "#a3be8c", "#b48ead", "#6f7a8f", "#88c0d0", "#8fbcbb", "#d8dee9", "#b48ead"),
    theme("Solarized Dark", ThemeGroup.Dark, "Precise", "Solarized after dark. Engineered low-glare balance.",
        "#002b36", "#073642", "#08323d", "#14505e", "#839496", "#586e75", "#c4cfcf",
        "#268bd2", "#cb4b16", "#dc322f", "#859900", "#aeca3a",
        "#859900", "#2aa198", "#d33682", "#586e75", "#268bd2", "#b58900", "#839496", "#cb4b16"),
    theme("Dracula", ThemeGroup.Dark, "Vivid", "The famous vampire palette. Punchy and playful.",
        "#282a36", "#313442", "#21222c", "#44475a", "#f8f8f2", "#6272a4", "#ffffff",
        "#bd93f9", "#ffb86c", "#ff5555", "#50fa7b", "#b8fcc8",
        "#ff79c6", "#f1fa8c", "#bd93f9", "#6272a4", "#50fa7b", "#8be9fd", "#f8f8f2", "#bd93f9"),
    theme("Gruvbox Dark", ThemeGroup.Dark, "Retro", "Warm earthy retro groove. Cozy and bold.",
        "#282828", "#32302f", "#3c3836", "#504945", "#ebdbb2", "#928374", "#fbf1c7",
        "#fe8019", "#fabd2f", "#fb4934", "#b8bb26", "#d5d96a",
        "#fb4934", "#b8bb26", "#d3869b", "#928374", "#fabd2f", "#8ec07c", "#ebdbb2", "#d3869b"),
    theme("Catppuccin Mocha", ThemeGroup.Dark, "Pastel", "Soft pastel mocha. Gentle, modern, cozy.",
        "#1e1e2e", "#2a2b3c", "#181825", "#3a3c4e", "#cdd6f4", "#7f849c", "#f2f4ff",
        "#cba6f7", "#f9e2af", "#f38ba8", "#a6e3a1", "#cdf0c9",
        "#cba6f7", "#a6e3a1", "#fab387", "#7f849c", "#89b4fa", "#f9e2af", "#cdd6f4", "#fab387"),
    theme("Carbon", ThemeGroup.Dark, "Mono", "Graphite monochrome. Distraction-free minimalism.",
        "#0e0f11", "#16181b", "#1c1f23", "#2a2e33", "#c2c7cd", "#6b7178", "#f4f6f8",
        "#e8ebee", "#c9a26b", "#c96b6b", "#8fae8f", "#b8ccb8",
        "#f4f6f8", "#9aa0a6", "#b8bdc4", "#6b7178", "#cfd3d8", "#d8dce0", "#c2c7cd", "#b8bdc4"),
    theme("GitHub Dark", ThemeGroup.Dark, "Neutral", "GitHub after dark. Familiar, calm, professional.",
        "#0d1117", "#161b22", "#0b0f15", "#30363d", "#c9d1d9", "#8b949e", "#f0f6fc",
        "#2f81f7", "#d29922", "#f85149", "#3fb950", "#aff5b4",
        "#ff7b72", "#a5d6ff", "#79c0ff", "#8b949e", "#d2a8ff", "#7ee787", "#c9d1d9", "#79c0ff"),
    theme("Neon Green", ThemeGroup.Dark, "Neon", "Electric neon green on pure black. Old-school overdrive.",
        "#00140a", "#021c0f", "#042814", "#0d4a28", "#3dff9b", "#1f9a55", "#b6ffd0",
        "#00ff88", "#ffd23f", "#ff3b6b", "#00ff88", "#9dffce",
        "#5dff9e", "#a6ffc9", "#d6ff7a", "#1f7a48", "#00ffaa", "#66ffd0", "#3dff9b", "#c9ff9e"),
    theme("Neon Cyan", ThemeGroup.Dark, "Neon", "Electric neon cyan on pure black. Cold and bright.",
        "#001318", "#02191f", "#04252e", "#0c454f", "#3df0ff", "#1f8a9a", "#c0fbff",
        "#00e5ff", "#ffd23f", "#ff4d8d", "#00e5ff", "#9df3ff",
        "#5deaff", "#aef3ff", "#8affd6", "#1f6f7a", "#00f0ff", "#66ffe0", "#3df0ff", "#9df0ff"),
    // ---------------------------------- Light ---------------------------------
    theme("Paper", ThemeGroup.Light, "Ink", "Warm paper & ink. Quiet, focused, minimal.",
        "#f3efe5", "#faf7ef", "#eae5d8", "#dbd3c2", "#44423a", "#8d877a", "#211f1a",
        "#2f7d6b", "#b9742a", "#b23f2e", "#2f7d6b", "#1c5a4c",
        "#9a5b2e", "#5d7a36", "#9a5b2e", "#a39c8c", "#2f7d6b", "#3a7d8a", "#44423a", "#9a5b2e"),
    theme("Solarized Light", ThemeGroup.Light, "Classic", "The cult classic. Precise cream & cyan.",
        "#fdf6e3", "#f6efd8", "#eee8d5", "#ddd6bf", "#586e75", "#93a1a1", "#073642",
        "#268bd2", "#cb4b16", "#dc322f", "#859900", "#5d6b00",
        "#859900", "#2aa198", "#d33682", "#93a1a1", "#268bd2", "#b58900", "#586e75", "#cb4b16"),
    theme("Blossom", ThemeGroup.Light, "Rose", "Soft rose & mauve. Cozy and gentle.",
        "#faf0f1", "#fef7f8", "#f2e2e6", "#e8d2d7", "#575279", "#9a90ac", "#2b2440",
        "#b4637a", "#ea9d34", "#b4506a", "#3a9d7a", "#2a7d60",
        "#907aa9", "#56949f", "#b4637a", "#9a90ac", "#d7827e", "#ea9d34", "#575279", "#b4637a"),
    theme("Cobalt Sky", ThemeGroup.Light, "Crisp", "Crisp white & cobalt. Bright, high-energy.",
        "#eef3fa", "#ffffff", "#e2ecf7", "#ccdcef", "#2a3b52", "#6f88a3", "#0f2338",
        "#2563d8", "#d98324", "#d63b4a", "#1f9d6b", "#157a52",
        "#8a3ad0", "#1f9d6b", "#d9591e", "#8da7bf", "#2563d8", "#0e8aa8", "#2a3b52", "#d9591e"),
    theme("Termtastic Light", ThemeGroup.Light, "Brand", "On-brand green, daylight edition.",
        "#edf3ee", "#fbfdfb", "#e1ebe3", "#c9dfce", "#29382e", "#6c8975", "#0f2b17",
        "#1f9d57", "#c47a1e", "#c0392b", "#1f9d57", "#147a40",
        "#0f7d6e", "#5a8a2e", "#b06a1e", "#8aa593", "#1f9d57", "#0f7d8e", "#29382e", "#b06a1e"),
    theme("Gruvbox Light", ThemeGroup.Light, "Retro", "Gruvbox by daylight. Warm vintage paper.",
        "#fbf1c7", "#f6eec3", "#ebdbb2", "#d5c4a1", "#3c3836", "#7c6f64", "#282828",
        "#af3a03", "#b57614", "#9d0006", "#79740e", "#5c5709",
        "#9d0006", "#79740e", "#8f3f71", "#a89984", "#b57614", "#427b58", "#3c3836", "#8f3f71"),
    theme("GitHub Light", ThemeGroup.Light, "Neutral", "Clean, familiar developer light. Pure & legible.",
        "#f6f8fa", "#ffffff", "#eef1f4", "#d0d7de", "#1f2328", "#656d76", "#010409",
        "#0969da", "#9a6700", "#cf222e", "#1a7f37", "#116329",
        "#cf222e", "#0a3069", "#0550ae", "#6e7781", "#8250df", "#116329", "#1f2328", "#0550ae"),
    theme("Catppuccin Latte", ThemeGroup.Light, "Pastel", "Latte — the soft pastel light companion.",
        "#eff1f5", "#f7f8fa", "#e6e9ef", "#ccd0da", "#4c4f69", "#8c8fa1", "#2a2c40",
        "#8839ef", "#df8e1d", "#d20f39", "#40a02b", "#2e7a20",
        "#8839ef", "#40a02b", "#fe640b", "#8c8fa1", "#1e66f5", "#df8e1d", "#4c4f69", "#fe640b"),
    theme("Frost", ThemeGroup.Light, "Arctic", "Cool muted daylight. Calm and glare-free.",
        "#eceff4", "#f7f9fb", "#e0e6ef", "#cdd5e0", "#3b4252", "#7b8694", "#2e3440",
        "#5e81ac", "#b07e2f", "#bf616a", "#5a8a5e", "#3f6b43",
        "#5e81ac", "#4c8a86", "#b07e2f", "#9aa4b2", "#8a6daf", "#4c8a86", "#3b4252", "#b07e2f"),
    theme("High Contrast", ThemeGroup.Light, "A11y", "Maximum legibility. Bold ink on white for clarity.",
        "#ffffff", "#ffffff", "#f0f0f0", "#2a2a2a", "#111111", "#444444", "#000000",
        "#0033cc", "#b35900", "#cc0000", "#0a7d1f", "#064712",
        "#aa00aa", "#006666", "#b35900", "#666666", "#0033cc", "#0a7d1f", "#111111", "#b35900"),
)

/** Built-in themes indexed by name, for O(1) lookup. */
private val builtinByName: Map<String, Theme> = builtinThemes.associateBy { it.name }

/**
 * Looks up a built-in theme by name.
 *
 * @param name the theme name.
 * @return the built-in [Theme], or null if no built-in has that name.
 */
fun builtinTheme(name: String): Theme? = builtinByName[name]

/**
 * The full theme catalog: built-ins followed by [custom], with custom themes
 * overriding a built-in of the same name (so a clone edited under a reused
 * name wins).
 *
 * @param custom the user's custom themes (from [ThemeSnapshotV2.customThemes]).
 * @return built-ins ∪ custom, custom-wins-by-name, in stable display order.
 */
fun allThemes(custom: List<Theme>): List<Theme> {
    if (custom.isEmpty()) return builtinThemes
    val customByName = custom.associateBy { it.name }
    val merged = builtinThemes.map { customByName[it.name] ?: it }.toMutableList()
    val builtinNames = builtinByName.keys
    for (c in custom) if (c.name !in builtinNames) merged.add(c)
    return merged
}
