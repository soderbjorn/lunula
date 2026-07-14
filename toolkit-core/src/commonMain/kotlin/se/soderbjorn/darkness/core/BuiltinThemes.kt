/* BuiltinThemes.kt
 * The 73 built-in themes (35 dark, 38 light): 71 transcribed verbatim from
 * the "Termtastic Theme Studio" design's RAW array, plus two hand-tuned
 * retro-computer palettes ("Workbench", "C64") appended at the
 * end of the list. Each theme
 * defines all 20 literal tokens (including 8 dedicated syntax slots); the
 * four the design computes by formula (accentSoft, glow, addBg,
 * chromeAccentSoft) are derived at render time by [Theme.resolve] applying the
 * design's alpha to the accent/add/chromeAccent colour, so nothing else is
 * computed.
 *
 * The nine "… Split" themes additionally set the 8 optional chrome/canvas
 * tokens, painting the title bar / tab bar / sidebar and the pane canvas
 * independently of the pane content. Every other theme leaves them unset and
 * so falls back to the base tokens exactly as before.
 */
package se.soderbjorn.darkness.core

/** Default theme bound to the dark slot. */
const val DEFAULT_DARK_THEME: String = "Lunamux Dark"

/** Default theme bound to the light slot. */
const val DEFAULT_LIGHT_THEME: String = "Lunamux Light"

/**
 * Constructs a [Theme] from its 20 literal design tokens. The four translucent
 * tokens (accentSoft / glow / addBg / chromeAccentSoft) are not stored —
 * [Theme.resolve] derives them by applying the design's alpha to the
 * accent/add/chromeAccent colour.
 *
 * The 8 chrome/canvas tokens are trailing and default to `null`, so a theme
 * that doesn't split its chrome from its content simply omits them and gets
 * the base-token fallback.
 */
private fun theme(
    name: String, group: ThemeGroup, tag: String, desc: String,
    bg: String, surface: String, surfaceAlt: String, border: String,
    text: String, textDim: String, textBright: String,
    accent: String, warn: String, danger: String, add: String, addText: String,
    synKeyword: String, synString: String, synNumber: String, synComment: String,
    synFunction: String, synType: String, synOperator: String, synConstant: String,
    canvas: String? = null,
    chromeBg: String? = null, chromeText: String? = null, chromeTextDim: String? = null,
    chromeTextBright: String? = null, chromeBorder: String? = null,
    chromeAccent: String? = null, chromeTrack: String? = null,
): Theme = Theme(
    name = name, group = group, tag = tag, desc = desc,
    bg = bg, surface = surface, surfaceAlt = surfaceAlt, border = border,
    text = text, textDim = textDim, textBright = textBright,
    accent = accent,
    warn = warn, danger = danger, add = add, addText = addText,
    synKeyword = synKeyword, synString = synString, synNumber = synNumber, synComment = synComment,
    synFunction = synFunction, synType = synType, synOperator = synOperator, synConstant = synConstant,
    canvas = canvas,
    chromeBg = chromeBg, chromeText = chromeText, chromeTextDim = chromeTextDim,
    chromeTextBright = chromeTextBright, chromeBorder = chromeBorder,
    chromeAccent = chromeAccent, chromeTrack = chromeTrack,
)

/**
 * The 73 built-in themes in display order: 33 dark, then 38 light, then the
 * two retro-computer palettes (dark) last. "Lunamux Dark" leads the dark
 * section and "Lunamux Light" leads the light section, so the first entry of
 * each is that slot's default — but the slot defaults are bound by name via
 * [DEFAULT_DARK_THEME] / [DEFAULT_LIGHT_THEME], not by list position.
 *
 * The nine "… Split" themes (tagged "Chrome") follow their section's default
 * theme. The design's RAW array orders the light splits *ahead* of "Lunamux
 * Light"; they are placed after it here so that the "default leads its
 * section" rule above holds for both sections, as it already does for the
 * dark splits.
 *
 * @see Theme
 * @see allThemes
 */
val builtinThemes: List<Theme> = listOf(
    // ---------------------------------- Dark ----------------------------------
    theme("Lunamux Dark", ThemeGroup.Dark, "Signature", "The house look — near-black navy with a cyan glow and a mint-white lift.",
        "#04090f", "#070f1a", "#0b1626", "#1b2b3f", "#a9c4dd", "#5f7590", "#eaf3fb",
        "#4dc8f5", "#f0b24b", "#ef5f6b", "#4dd6c0", "#a7f0e4",
        "#7ab8ff", "#6fd3c0", "#a5d8ff", "#52708a", "#4dc8f5", "#5fe0d8", "#b8d0e0", "#c0e0ff"),
    theme("Obsidian Split", ThemeGroup.Dark, "Chrome", "Graphite content panes wrapped in a pure-black chrome, lit by violet. Chrome darker than the content.",
        "#14171d", "#191d24", "#1f242c", "#2c3340", "#c2c8d2", "#6b7484", "#eef1f6",
        "#a06cf0", "#e0af68", "#f7768e", "#7fd6a0", "#b0ecd0",
        "#a06cf0", "#7fd6a0", "#d0a0ff", "#5a6374", "#8c9dff", "#5fd0d0", "#c2c8d2", "#c0a0ff",
        canvas = "#000000",
        chromeBg = "#000000", chromeText = "#b8bec8", chromeTextDim = "#5f6774",
        chromeTextBright = "#ffffff", chromeBorder = "#1c2029",
        chromeAccent = "#b98cff", chromeTrack = "#14171d"),
    theme("Graphite Split", ThemeGroup.Dark, "Chrome", "Dark editor content under a slightly lighter cool-gray shell. Chrome lighter than the content — the JetBrains case.",
        "#1a1d23", "#20242b", "#262b33", "#333945", "#c4cad4", "#6f7886", "#eef1f6",
        "#5b9bd5", "#e0af68", "#e06c75", "#7fc0a0", "#bfe8d4",
        "#6fa8dc", "#7fc0a0", "#9db4ff", "#616a78", "#5b9bd5", "#5fc9d0", "#c4cad4", "#9db4ff",
        canvas = "#242832",
        chromeBg = "#242832", chromeText = "#c8ced8", chromeTextDim = "#7a8494",
        chromeTextBright = "#ffffff", chromeBorder = "#363c48",
        chromeAccent = "#6fb0e8", chromeTrack = "#2e3440"),
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
    theme("Monokai Pro", ThemeGroup.Dark, "Vivid", "The classic vivid palette — warm gray with candy syntax.",
        "#2d2a2e", "#353236", "#3a373c", "#4a464d", "#fcfcfa", "#939293", "#ffffff",
        "#ffd866", "#fc9867", "#ff6188", "#a9dc76", "#cbe8a0",
        "#ff6188", "#ffd866", "#ab9df2", "#727072", "#a9dc76", "#78dce8", "#fcfcfa", "#ab9df2"),
    theme("Deep Sea", ThemeGroup.Dark, "Abyss", "Deep ocean blue-teal. Cool, quiet, submerged.",
        "#071a26", "#0c2734", "#103240", "#1c4a5a", "#9fc8d6", "#547585", "#dff2f7",
        "#35c9d6", "#f2b34b", "#ff5f7a", "#4fd6a0", "#a6f0d0",
        "#5ad1e6", "#4fd6a0", "#7fb3ff", "#4a6a76", "#35c9d6", "#6fe0c8", "#9fc8d6", "#a5c8ff"),
    theme("Ember", ThemeGroup.Dark, "Fire", "Near-black with a molten orange glow. Warm and intense.",
        "#140a06", "#1e0f08", "#26140a", "#452414", "#e8b48f", "#8a5a3e", "#ffd9b8",
        "#ff7a2e", "#ffb02e", "#ff3b30", "#d0a850", "#f0d090",
        "#ff7a2e", "#ffb877", "#ffd08a", "#7a4a30", "#ff9d4d", "#ffbe7a", "#e8b48f", "#ffd08a"),
    theme("Espresso", ThemeGroup.Dark, "Coffee", "Dark roast browns with caramel accents. Cozy and rich.",
        "#231a15", "#2b201a", "#33261f", "#47372d", "#d6c3b0", "#8a7563", "#f5e9dc",
        "#c98a4b", "#d9a441", "#cf6b5a", "#9caf6a", "#c4d29a",
        "#c98a4b", "#9caf6a", "#d4a05a", "#7a6552", "#d9a441", "#7ba98f", "#d6c3b0", "#d4a05a"),
    theme("Ultraviolet", ThemeGroup.Dark, "Violet", "Deep purple night lit by electric violet. Moody neon.",
        "#130a24", "#1c0f34", "#241340", "#3a1f63", "#cbb6f0", "#7a5fa8", "#ede4ff",
        "#a855f7", "#f0b24b", "#ff4d8d", "#56e0c0", "#a6f0e0",
        "#c084fc", "#56e0c0", "#ff8fd0", "#6a4f98", "#a855f7", "#7ad0ff", "#cbb6f0", "#ff8fd0"),
    theme("Rosé Pine", ThemeGroup.Dark, "Muted", "Muted rose, gold and foam on a soft plum base.",
        "#191724", "#1f1d2e", "#26233a", "#403d52", "#e0def4", "#6e6a86", "#f7f5ff",
        "#ebbcba", "#f6c177", "#eb6f92", "#9ccfd8", "#c4e5eb",
        "#c4a7e7", "#f6c177", "#ebbcba", "#6e6a86", "#9ccfd8", "#3e9dbf", "#e0def4", "#ebbcba"),
    theme("Crimson", ThemeGroup.Dark, "Wine", "Deep burgundy lit by hot crimson. Bold and vinous.",
        "#1a0a0e", "#240f14", "#2c131a", "#4a1f2a", "#e0b0bc", "#9a5f6e", "#ffd8e0",
        "#e34a63", "#e0902e", "#ff4d4d", "#cfa04f", "#ecd090",
        "#e34a63", "#d98a9a", "#e8a05a", "#7a4a54", "#f07a90", "#d98acf", "#e0b0bc", "#e8a05a"),
    theme("Forest", ThemeGroup.Dark, "Woodland", "Muted moss and pine. Earthy, calm, organic.",
        "#0e1710", "#142016", "#18271b", "#2a3f2d", "#b8ccae", "#6f8a68", "#dcecd0",
        "#7aa35a", "#cf9a3e", "#cf5a4a", "#8ab36a", "#c0d8a4",
        "#a8b85a", "#7fae7a", "#c9a45e", "#5f7a58", "#9ac47a", "#6aae9a", "#b8ccae", "#c9a45e"),
    theme("Oxide", ThemeGroup.Dark, "Rust", "Industrial rust and iron. Weathered and warm.",
        "#16100c", "#1f1610", "#261a12", "#46301f", "#d0b49a", "#8a6f56", "#f0d8bf",
        "#c8622e", "#d99a3e", "#d64432", "#a89a5a", "#d0c48a",
        "#c8622e", "#a89a5a", "#cf8a4a", "#7a6350", "#d9814a", "#7a9a8a", "#d0b49a", "#cf8a4a"),
    theme("Ice", ThemeGroup.Dark, "Glacier", "Icy pale blue on deep slate. Cold and crisp.",
        "#0a1016", "#0f1720", "#131e28", "#253645", "#b8ccd8", "#647585", "#e8f2f8",
        "#7ec8e0", "#d9b26a", "#e07a86", "#7ec8b0", "#c0e8dc",
        "#9ad0e8", "#a8cfd8", "#c8dced", "#566876", "#7ec8e0", "#8fd8cf", "#b8ccd8", "#c8dced"),
    theme("Toxic", ThemeGroup.Dark, "Acid", "Acid lime burning on pure black. Loud and radioactive.",
        "#0a0f02", "#101705", "#151f08", "#2a3f12", "#b8d64a", "#6f8a2e", "#e0ff8a",
        "#c4ff2e", "#ffd23f", "#ff5a3f", "#8aff5a", "#c8ffa4",
        "#c4ff2e", "#a8e05a", "#e0ff5a", "#5f7a2a", "#9aff3a", "#6affc4", "#b8d64a", "#e0ff5a"),
    theme("Royal", ThemeGroup.Dark, "Regal", "Deep navy dressed in gold. Stately and rich.",
        "#0a0f24", "#0f1633", "#141d42", "#26325e", "#c0c8e6", "#6a769e", "#eaf0ff",
        "#d9b45a", "#e0a83e", "#e05a6a", "#5ab08a", "#a6dcc4",
        "#d9b45a", "#8ab0e6", "#c99ae6", "#5a668e", "#e0c46a", "#6ac4d8", "#c0c8e6", "#c99ae6"),
    theme("Aurora", ThemeGroup.Dark, "Aurora", "Deep teal night streaked with green, cyan and violet.",
        "#0a1a1e", "#0f2428", "#132e32", "#1f4a4e", "#a8d8d0", "#5a8a84", "#dcf7f0",
        "#4fe0c0", "#f0c060", "#ff6f8a", "#7ae0a0", "#b6f0cc",
        "#b48aff", "#6ae0a0", "#5ad0ff", "#4a7a74", "#4fe0c0", "#8ad0ff", "#a8d8d0", "#f0a05a"),
    theme("Bubblegum", ThemeGroup.Dark, "Candy", "Dark plum with pink and mint candy pop. Playful.",
        "#1a1020", "#24162e", "#2c1c38", "#452a54", "#e8c8e8", "#9a6f9a", "#ffe4ff",
        "#ff8ad0", "#ffd26a", "#ff5f8f", "#6ae0d0", "#b0f0e8",
        "#ff8ad0", "#8ae0d0", "#b48aff", "#7a5a80", "#ffb0e0", "#6ac8ff", "#e8c8e8", "#ffcf6a"),
    theme("Cobalt Night", ThemeGroup.Dark, "Electric", "Electric blue with a jolt of yellow. High-voltage classic.",
        "#06263a", "#0a2f47", "#0d3a56", "#17557a", "#c0d4e6", "#5f88a5", "#eaf4ff",
        "#ffc600", "#ff9d00", "#ff628c", "#3ad900", "#a6f0a0",
        "#ff9d00", "#3ad900", "#ff628c", "#4a7a95", "#ffc600", "#80fcff", "#c0d4e6", "#ff628c"),
    theme("Umbra", ThemeGroup.Dark, "OLED", "True-black canvas with cool lilac accents. Pure and OLED-friendly.",
        "#000000", "#0b0b0d", "#131316", "#26262b", "#d6d6db", "#6a6a72", "#ffffff",
        "#b0a8ff", "#d9b26a", "#f0728a", "#8ad0a8", "#c0e8d0",
        "#b0a8ff", "#a8b0c8", "#c8c8d4", "#5a5a62", "#cfc8ff", "#a8c8d8", "#d6d6db", "#c8c8d4"),
    // ---------------------------------- Light ---------------------------------
    theme("Lunamux Light", ThemeGroup.Light, "Daybreak", "Crisp white and cyan. Bright, on-brand daylight.",
        "#eef5fb", "#ffffff", "#e2ecf6", "#ccdcec", "#29455c", "#6b869e", "#0d2436",
        "#0e97c8", "#c47a1e", "#d0453f", "#10998a", "#0c7266",
        "#0e7d9a", "#2a8a5a", "#b06a1e", "#8aa5b8", "#0e97c8", "#2f7aa8", "#29455c", "#b06a1e"),
    theme("Lunamux Split", ThemeGroup.Light, "Chrome", "Dark navy chrome with white text, over a clean white workspace. The split-shell look.",
        "#ffffff", "#ffffff", "#eef4fa", "#d7e2ec", "#28455c", "#6b869e", "#0d2436",
        "#0e97c8", "#c47a1e", "#d0453f", "#10998a", "#0c7266",
        "#0e7d9a", "#2a8a5a", "#b06a1e", "#8aa5b8", "#0e97c8", "#2f7aa8", "#28455c", "#b06a1e",
        canvas = "#0a1a2e",
        chromeBg = "#0a1a2e", chromeText = "#c6d6e6", chromeTextDim = "#6e88a2",
        chromeTextBright = "#ffffff", chromeBorder = "#1b3251",
        chromeAccent = "#4dc8f5", chromeTrack = "#16273e"),
    theme("Termtastic Split", ThemeGroup.Light, "Chrome", "Termtastic green on a clean white workspace with a deep forest chrome shell.",
        "#ffffff", "#ffffff", "#edf4ee", "#d5e6d9", "#2a4636", "#6b8a76", "#12291c",
        "#159a5b", "#c47a1e", "#d0453f", "#159a5b", "#0c6640",
        "#0e8a6a", "#3a7a2a", "#b06a1e", "#8aab97", "#159a5b", "#2f8a7a", "#2a4636", "#b06a1e",
        canvas = "#0c1f16",
        chromeBg = "#0c1f16", chromeText = "#c2dccb", chromeTextDim = "#6e9080",
        chromeTextBright = "#ffffff", chromeBorder = "#1c3b2b",
        chromeAccent = "#46e08a", chromeTrack = "#16301f"),
    theme("Crimson Split", ThemeGroup.Light, "Chrome", "Hot crimson on white, wrapped in a deep burgundy chrome shell.",
        "#ffffff", "#ffffff", "#fbedf0", "#f0d3da", "#47262d", "#9a6e78", "#2c1016",
        "#d23b52", "#c47a1e", "#d0453f", "#b0842e", "#8a5414",
        "#c0324a", "#b0642e", "#b06a1e", "#a5858c", "#d23b52", "#b0548a", "#47262d", "#b06a1e",
        canvas = "#1a0a0e",
        chromeBg = "#1a0a0e", chromeText = "#e0b0bc", chromeTextDim = "#9a5f6e",
        chromeTextBright = "#ffd8e0", chromeBorder = "#4a1f2a",
        chromeAccent = "#e34a63", chromeTrack = "#2c131a"),
    theme("Ember Split", ThemeGroup.Light, "Chrome", "Molten orange on white, wrapped in a near-black warm ember shell.",
        "#ffffff", "#ffffff", "#fbf0e8", "#f0dcc8", "#4a3324", "#9a7a60", "#2e1a0c",
        "#d9691e", "#c47a1e", "#d0453f", "#b0842e", "#8a5a14",
        "#c46a1e", "#a06a2e", "#b06a1e", "#a89078", "#d9691e", "#b0742e", "#4a3324", "#b06a1e",
        canvas = "#140a06",
        chromeBg = "#140a06", chromeText = "#e8b48f", chromeTextDim = "#8a5a3e",
        chromeTextBright = "#ffd9b8", chromeBorder = "#452414",
        chromeAccent = "#ff7a2e", chromeTrack = "#26140a"),
    theme("Nord Split", ThemeGroup.Light, "Chrome", "Frost-white workspace under a deep Polar-Night chrome shell. Calm and arctic.",
        "#ffffff", "#ffffff", "#eceff4", "#d8dee9", "#3b4252", "#7b8494", "#2e3440",
        "#5e81ac", "#b0782e", "#bf616a", "#5a8a5e", "#3f6b43",
        "#5e81ac", "#4c8a86", "#b0782e", "#9aa4b2", "#8a6daf", "#4c8a86", "#3b4252", "#b0782e",
        canvas = "#2e3440",
        chromeBg = "#2e3440", chromeText = "#d8dee9", chromeTextDim = "#7b8494",
        chromeTextBright = "#eceff4", chromeBorder = "#434c5e",
        chromeAccent = "#88c0d0", chromeTrack = "#3b4252"),
    theme("Solarized Split", ThemeGroup.Light, "Chrome", "Solarized Light on cream, wrapped in the classic Solarized Dark chrome. The canonical two-tone.",
        "#fdf6e3", "#fdf6e3", "#eee8d5", "#d9d2bf", "#586e75", "#93a1a1", "#073642",
        "#268bd2", "#b58900", "#dc322f", "#859900", "#5f6f00",
        "#859900", "#2aa198", "#d33682", "#93a1a1", "#268bd2", "#b58900", "#586e75", "#cb4b16",
        canvas = "#002b36",
        chromeBg = "#002b36", chromeText = "#93a1a1", chromeTextDim = "#586e75",
        chromeTextBright = "#eee8d5", chromeBorder = "#073642",
        chromeAccent = "#2aa198", chromeTrack = "#073642"),
    theme("Sandstone Split", ThemeGroup.Light, "Chrome", "Warm cream and tan workspace under a dark espresso chrome, lit by amber.",
        "#f7f1e6", "#fcf7ee", "#ede3d0", "#dbccb2", "#4e4230", "#8f7f64", "#2e2414",
        "#c07c2e", "#c48a2e", "#bf4a3a", "#7a8a3a", "#5a6820",
        "#b06a2e", "#7a8a3a", "#a06a2e", "#a8967a", "#c07c2e", "#3a8a7a", "#4e4230", "#a06a2e",
        canvas = "#241a12",
        chromeBg = "#241a12", chromeText = "#cbb79c", chromeTextDim = "#8a745c",
        chromeTextBright = "#f5e9d8", chromeBorder = "#45341f",
        chromeAccent = "#e0a24b", chromeTrack = "#33261a"),
    theme("Termtastic Light", ThemeGroup.Light, "Brand", "On-brand green, daylight edition.",
        "#edf3ee", "#fbfdfb", "#e1ebe3", "#c9dfce", "#29382e", "#6c8975", "#0f2b17",
        "#1f9d57", "#c47a1e", "#c0392b", "#1f9d57", "#147a40",
        "#0f7d6e", "#5a8a2e", "#b06a1e", "#8aa593", "#1f9d57", "#0f7d8e", "#29382e", "#b06a1e"),
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
    theme("Sepia", ThemeGroup.Light, "Vintage", "Warm aged-paper browns. Like ink on an old letter.",
        "#efe6d3", "#f6efe0", "#e6dcc4", "#d3c6a8", "#4a3d2a", "#8a7a5c", "#2a2013",
        "#9c5b2c", "#b07514", "#a63423", "#6a7a2e", "#4a5518",
        "#9c5b2c", "#6a7a2e", "#99632c", "#a3937a", "#7a5a2a", "#3a7a6a", "#4a3d2a", "#99632c"),
    theme("Sakura", ThemeGroup.Light, "Blush", "Saturated cherry-blossom pinks. Sweet and lively.",
        "#fce4ec", "#fff1f5", "#f7d3de", "#f0bcce", "#6a2b40", "#b06d84", "#40101f",
        "#e0407a", "#e07b2e", "#d6294f", "#3fa06a", "#2a7a4c",
        "#c23a8a", "#3fa06a", "#e0407a", "#c69aab", "#e0407a", "#2f8ab0", "#6a2b40", "#e0407a"),
    theme("Citrus", ThemeGroup.Light, "Zest", "Bright lemon-lime energy. Fresh and punchy.",
        "#f7fbe4", "#fdffee", "#ecf3ce", "#d5e0a8", "#3f4a1e", "#7c8a4e", "#202a08",
        "#7fae1f", "#d99a1e", "#d64b2e", "#4fa03f", "#327a2a",
        "#c47a0f", "#4fa03f", "#a08a1f", "#a3b078", "#7fae1f", "#2f9d8a", "#3f4a1e", "#a08a1f"),
    theme("Lavender Haze", ThemeGroup.Light, "Violet", "Soft violet daylight. Dreamy and calm.",
        "#f1ecfa", "#f9f5ff", "#e6dcf4", "#d3c6ea", "#3f3358", "#857aa0", "#241a3a",
        "#7c46d8", "#c47a2e", "#d63b6a", "#4f9d7a", "#327a56",
        "#8839ef", "#4f9d7a", "#d9591e", "#a396c0", "#6a5ad8", "#2f8aa8", "#3f3358", "#d9591e"),
    theme("Seafoam", ThemeGroup.Light, "Aqua", "Cool teal and mint. Breezy and clean.",
        "#e2f4f0", "#f2fbf9", "#d2ece6", "#b6dcd3", "#244842", "#5f8a82", "#0f2e28",
        "#10a08a", "#d9902e", "#d64b56", "#2f9d6a", "#1a7a4c",
        "#0e8a9a", "#3f9d5a", "#c47a2e", "#8db0aa", "#10a08a", "#2f7aa8", "#244842", "#c47a2e"),
    theme("Peach", ThemeGroup.Light, "Coral", "Warm coral and peach. Sunny and inviting.",
        "#fdece2", "#fff6f0", "#f7dccb", "#f0c6ad", "#5a3728", "#a5745c", "#3a1e12",
        "#e85d2e", "#e0902e", "#d63b3b", "#6a9d3a", "#4a7a24",
        "#d94a6a", "#6a9d3a", "#e07b2e", "#c6a48f", "#e85d2e", "#2f9a8a", "#5a3728", "#e07b2e"),
    theme("Marigold", ThemeGroup.Light, "Gold", "Golden honey warmth. Rich and glowing.",
        "#fdf3d6", "#fffbe8", "#f2e4bc", "#e0cd94", "#4d3f1e", "#8f7a44", "#2e2408",
        "#d99411", "#d9701e", "#c33328", "#7a8a2a", "#5a6818",
        "#c25a1e", "#7a8a2a", "#b5850f", "#b0a072", "#d99411", "#3a8a7a", "#4d3f1e", "#b5850f"),
    theme("Periwinkle", ThemeGroup.Light, "Sky", "Soft blue-violet skies. Airy and serene.",
        "#e8ecfa", "#f4f6ff", "#dbe1f4", "#c3ccec", "#2f3a5c", "#6f7ba5", "#16203f",
        "#4f5fd8", "#c47a2e", "#d63b56", "#2f9d6a", "#1a7a4c",
        "#6a4fd8", "#2f9d6a", "#d9591e", "#96a0c6", "#4f5fd8", "#0e8aa8", "#2f3a5c", "#d9591e"),
    theme("Newsprint", ThemeGroup.Light, "Editorial", "High-contrast ink with a single red byline. Bold and journalistic.",
        "#f4f2ec", "#fffef9", "#e6e3da", "#c8c3b6", "#1a1a17", "#63615a", "#000000",
        "#c0261f", "#b06a14", "#c0261f", "#2a6a3a", "#164a24",
        "#c0261f", "#2a6a3a", "#9c3a1e", "#8a8578", "#1a1a17", "#3a5a8a", "#1a1a17", "#9c3a1e"),
    theme("Matcha", ThemeGroup.Light, "Green", "Earthy green-tea tones. Grounded and mellow.",
        "#eaf0dd", "#f5f9ea", "#dde6c8", "#c4d0a4", "#37432a", "#71805c", "#1e2814",
        "#5a8a2e", "#c48a1e", "#bf4a3a", "#4f9d5a", "#327a3c",
        "#7a6a1e", "#5a8a2e", "#a06a2e", "#9aab7e", "#5a8a2e", "#2f8a7a", "#37432a", "#a06a2e"),
    theme("Terracotta", ThemeGroup.Light, "Clay", "Warm baked clay and umber. Earthy and grounded.",
        "#f6e8dd", "#fcf2ea", "#eddac9", "#dcc2ac", "#4e352a", "#937059", "#2e1a12",
        "#c05a34", "#c48a2e", "#b33a2a", "#6a8a3a", "#4a6820",
        "#c05a34", "#6a8a3a", "#b06a2e", "#a89078", "#a84a2e", "#3a7a72", "#4e352a", "#b06a2e"),
    theme("Sky", ThemeGroup.Light, "Azure", "Bright azure on clean air. Open and breezy.",
        "#e3f2fb", "#f2faff", "#d3e8f6", "#b6d6ec", "#1f3a4e", "#5f8299", "#0d2434",
        "#1691d6", "#d9902e", "#e0556a", "#1f9d8a", "#147a68",
        "#1479c0", "#1f9d8a", "#d9591e", "#8db0c6", "#1691d6", "#7a5ad8", "#1f3a4e", "#d9591e"),
    theme("Lemonade", ThemeGroup.Light, "Sunny", "Pale sunlit yellow. Cheerful and bright.",
        "#fdf8d8", "#fffdea", "#f4ecbc", "#e2d68e", "#4a4318", "#8f8340", "#2c2606",
        "#e0a80f", "#e07a1e", "#d63b2e", "#7a9a2a", "#5a7018",
        "#c46a0f", "#7a9a2a", "#b58a0f", "#b0a86a", "#e0a80f", "#2f9a8a", "#4a4318", "#b58a0f"),
    theme("Sage", ThemeGroup.Light, "Herb", "Muted gray-green herb garden. Quiet and cool.",
        "#e6ece2", "#f2f6ee", "#d8e2d0", "#bccbb2", "#38443a", "#6f8070", "#1f2a20",
        "#5f8a6a", "#c48a3e", "#c0564e", "#5a8a5a", "#3a6a3c",
        "#6a7a4a", "#5a8a6a", "#a07a3e", "#96a892", "#5f8a6a", "#4a8a8a", "#38443a", "#a07a3e"),
    theme("Flamingo", ThemeGroup.Light, "Hot", "Vivid coral-pink heat. Energetic and loud.",
        "#fde6ea", "#fff2f4", "#f9d3da", "#f2b8c2", "#5a2434", "#ab6577", "#3a0f1c",
        "#f0447a", "#f0902e", "#e02e4a", "#2fa07a", "#1f7a5a",
        "#d63a8a", "#2fa07a", "#f0447a", "#cb99a6", "#f0447a", "#2f8ab0", "#5a2434", "#f0447a"),
    theme("Ink & Gold", ThemeGroup.Light, "Luxe", "Warm cream, navy ink and a gilt edge. Understated luxury.",
        "#f4efe2", "#fbf7ec", "#e8e0cd", "#d3c8ac", "#2a2f45", "#7a7560", "#14182e",
        "#b08a2e", "#c48a2e", "#a83a3a", "#3a7a6a", "#256050",
        "#3a4a8a", "#7a6a2e", "#b08a2e", "#a89880", "#b08a2e", "#3a7a6a", "#2a2f45", "#b08a2e"),
    theme("Cotton Candy", ThemeGroup.Light, "Pastel", "Pastel pink and blue swirl. Soft and playful.",
        "#f3ecfb", "#fbf5ff", "#e6e2f8", "#d6cbe8", "#4a3f5c", "#8f84a0", "#2a2040",
        "#e884c0", "#e0a04e", "#e05a8a", "#4fb0c0", "#2f7a8a",
        "#a06ad8", "#4fb0c0", "#e884c0", "#a89ab8", "#6a8ae0", "#4fb0a0", "#4a3f5c", "#e884c0"),
    theme("Meadow", ThemeGroup.Light, "Spring", "Fresh grass and wildflower. Bright and alive.",
        "#eaf4de", "#f4fbea", "#dcecc8", "#c2d8a4", "#33422a", "#6f8558", "#1e2c12",
        "#5aa02e", "#d99a2e", "#d64b4a", "#3fa03a", "#2a7a2a",
        "#c46a2e", "#5aa02e", "#b08a1e", "#9ab078", "#5aa02e", "#d6708a", "#33422a", "#b08a1e"),
    theme("Slate", ThemeGroup.Light, "Pro", "Cool neutral blue-gray. Composed and professional.",
        "#eceff3", "#f7f9fb", "#dfe4ea", "#c6cdd7", "#2f3742", "#6c7683", "#171d26",
        "#4a6a90", "#b0782e", "#c04a52", "#3a8a6a", "#256a4c",
        "#5a6a9a", "#3a8a6a", "#a06a3e", "#96a0ab", "#4a6a90", "#3a7a9a", "#2f3742", "#a06a3e"),
    theme("Heather", ThemeGroup.Light, "Mauve", "Dusty muted mauve. Gentle and understated.",
        "#efe8f0", "#f7f2f8", "#e2d6e5", "#ccbccf", "#423a4a", "#857a8c", "#281f30",
        "#8a5f9a", "#c48a3e", "#bf5670", "#5a8a7a", "#3a6a5a",
        "#8a5f9a", "#5a8a7a", "#a06a5e", "#a094a5", "#9a6aaa", "#5a7a9a", "#423a4a", "#a06a5e"),
    // ------------------------------ Retro (dark) ------------------------------
    theme("Workbench", ThemeGroup.Dark, "Amiga", "Workbench blue, white and that orange. Kickstart included.",
        "#0055aa", "#004c99", "#00458c", "#3d85c9", "#eaf4ff", "#8fc0e8", "#ffffff",
        "#ff8800", "#ffaa33", "#ff4433", "#55cc66", "#c2f0c9",
        "#ffaa33", "#cfe8ff", "#ffd28a", "#6fa8d6", "#ffffff", "#ffcc66", "#e6f2ff", "#ffd28a"),
    theme("C64", ThemeGroup.Dark, "C64", "38911 BASIC bytes free on VIC-II blue. READY.",
        "#352879", "#3b2d85", "#443596", "#6c5eb5", "#a8a0e0", "#7568c0", "#ffffff",
        "#8a7fd6", "#b8c76f", "#d0605a", "#9ad284", "#d6f0c8",
        "#9ad284", "#b8c76f", "#70a4b2", "#6c5eb5", "#ffffff", "#70a4b2", "#a8a0e0", "#b8c76f"),
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

/**
 * Orders a theme catalog for the single-list theme picker (post issue #107,
 * which dropped the separate "Dark" / "Light" section headings). The result is
 * one flat list bucketed in this order:
 *
 *   1. starred dark themes
 *   2. starred light themes
 *   3. unstarred dark themes
 *   4. unstarred light themes
 *
 * Within each bucket the input order is preserved (the sort is stable), so the
 * built-in display order and any custom-theme append order carry through.
 *
 * ### Callers
 * Shared by every platform's picker so they agree on ordering: the web/Mac
 * Theme Manager (`renderThemeList`), the Android `AppearanceSheet`, and the iOS
 * `AppearanceViewModel`.
 *
 * @param themes    the full pickable catalog (typically [allThemes]).
 * @param favorites the set of starred theme names (see [ThemeSnapshotV2.favorites]).
 * @return the themes in single-list picker order.
 * @see allThemes
 */
fun orderThemesForPicker(themes: List<Theme>, favorites: Set<String>): List<Theme> {
    fun bucket(t: Theme): Int {
        val starred = t.name in favorites
        val dark = t.group == ThemeGroup.Dark
        return when {
            starred && dark -> 0
            starred && !dark -> 1
            !starred && dark -> 2
            else -> 3
        }
    }
    return themes.sortedBy { bucket(it) }
}
