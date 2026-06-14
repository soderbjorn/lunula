/**
 * Font-family presets surfaced by the toolkit's Settings sidebar.
 *
 * Apps consume the [fontPresets] list to render the Settings sidebar's
 * font picker pill rows. Persisted hosts store the preset
 * [FontPreset.key] (a stable short id) via the relevant
 * [ThemeManagerHost] setter (e.g. `setMonoFontFamily`), not the raw CSS
 * stack. [resolveFontFamilyCss] turns a key into the CSS font-family
 * stack at paint time, and [detectInstalledFonts] hides presets whose
 * primary family isn't available locally so the user doesn't see options
 * that would silently fall back to a generic.
 *
 * Each preset declares a [FontPreset.kind]: `Mono` for fixed-width
 * presets (terminals, code panes) and `Proportional` for prose presets
 * (notegrow's editor, sidebar/topbar/tabbar chrome). The Settings
 * sidebar partitions the list at render time so the Monospaced section
 * shows only [FontKind.Mono] presets and the Proportional / Sidebar /
 * Tab bar sections show only [FontKind.Proportional] presets.
 *
 * @see ThemeManagerHost.setMonoFontFamily
 * @see ThemeManagerHost.setProportionalFontFamily
 * @see ThemeManagerHost.setSidebarFontFamily
 * @see ThemeManagerHost.setTabbarFontFamily
 */
package se.soderbjorn.darkness.web.themeeditor

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

/**
 * Whether a [FontPreset] is intended for fixed-width content (terminals,
 * code) or proportional content (prose, chrome).
 *
 * The Settings sidebar uses this to partition presets between the
 * Monospaced section and the proportional sections (Proportional /
 * Sidebar / Tab bar).
 */
enum class FontKind { Mono, Proportional }

/**
 * A single font preset selectable in the Settings sidebar.
 *
 * @property key          short stable identifier persisted by the host
 *   (e.g. `"menlo"`). Hosts persist this — never the raw CSS stack — so
 *   the rendered CSS can evolve without invalidating stored settings.
 * @property displayName  human-readable label shown on the Settings button.
 * @property cssStack     full CSS `font-family` stack to apply in the
 *   browser. Always ends with a generic family so unknown families fall
 *   back gracefully.
 * @property detectFamily primary family name [detectInstalledFonts] probes
 *   for, or `null` for presets that are always considered available
 *   (`system*` stacks and any [bundled] families).
 * @property bundled      `true` when the host ships the `.woff2` for this
 *   family (e.g. via `@font-face` rules). Bundled families skip the
 *   installed-fonts probe and are always offered to the user.
 * @property kind         monospaced or proportional, see [FontKind].
 */
data class FontPreset(
    val key: String,
    val displayName: String,
    val cssStack: String,
    val detectFamily: String?,
    val bundled: Boolean = false,
    val kind: FontKind = FontKind.Mono,
)

/**
 * Ordered list of font presets. The Settings sidebar partitions this by
 * [FontPreset.kind]: the Monospaced section uses [FontKind.Mono]; the
 * Proportional / Sidebar / Tab bar sections use [FontKind.Proportional].
 *
 * The `system` mono and `systemProp` proportional presets are the
 * defaults when no preset is persisted for the corresponding kind.
 */
val fontPresets: List<FontPreset> = listOf(
    // ── Monospaced (terminals, code panes) ──────────────────────────
    FontPreset("system", "System Mono",
        "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", null,
        kind = FontKind.Mono),
    FontPreset("menlo", "Menlo", "Menlo, monospace", "Menlo",
        kind = FontKind.Mono),
    FontPreset("monaco", "Monaco", "Monaco, monospace", "Monaco",
        kind = FontKind.Mono),
    FontPreset("sfMono", "SF Mono",
        "'SF Mono', ui-monospace, monospace", "SF Mono",
        kind = FontKind.Mono),
    FontPreset("courier", "Courier New",
        "'Courier New', Courier, monospace", "Courier New",
        kind = FontKind.Mono),
    FontPreset("jetbrainsMono", "JetBrains Mono",
        "'JetBrains Mono', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),
    FontPreset("firaCode", "Fira Code",
        "'Fira Code', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),
    FontPreset("cascadiaCode", "Cascadia Code",
        "'Cascadia Code', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),
    FontPreset("ibmPlexMono", "IBM Plex Mono",
        "'IBM Plex Mono', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),
    FontPreset("geistMono", "Geist Mono",
        "'Geist Mono', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),
    FontPreset("sourceCodePro", "Source Code Pro",
        "'Source Code Pro', ui-monospace, monospace", null,
        bundled = true, kind = FontKind.Mono),

    // ── Proportional (prose, chrome) ────────────────────────────────
    FontPreset("systemProp", "System Default",
        "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif", null,
        kind = FontKind.Proportional),
    FontPreset("inter", "Inter",
        "'Inter', system-ui, sans-serif", "Inter",
        kind = FontKind.Proportional),
    FontPreset("sfPro", "SF Pro",
        "'SF Pro Text', '-apple-system', system-ui, sans-serif", "SF Pro Text",
        kind = FontKind.Proportional),
    FontPreset("helveticaNeue", "Helvetica Neue",
        "'Helvetica Neue', Helvetica, Arial, sans-serif", "Helvetica Neue",
        kind = FontKind.Proportional),
    FontPreset("georgia", "Georgia",
        "Georgia, 'Times New Roman', Times, serif", "Georgia",
        kind = FontKind.Proportional),
    FontPreset("ibmPlexSans", "IBM Plex Sans",
        "'IBM Plex Sans', system-ui, sans-serif", "IBM Plex Sans",
        kind = FontKind.Proportional),
)

/** The `system` mono stack — used when a host returns `null`/empty for mono. */
private val systemMonoStack: String =
    fontPresets.first { it.key == "system" }.cssStack

/** The `systemProp` proportional stack — used when a host returns `null`/empty for proportional. */
private val systemPropStack: String =
    fontPresets.first { it.key == "systemProp" }.cssStack

/**
 * Resolves a persisted preset key to its CSS font-family stack.
 *
 * Unknown or null keys fall back to the `system` mono stack so rendering
 * is always sensible even if the host returns a stale or renamed key.
 * For proportional defaults, callers should pass `"systemProp"` explicitly
 * or use [resolveProportionalFontFamilyCss].
 *
 * @param key the persisted preset key, or `null`/empty for the system mono default.
 * @return the CSS font-family stack to apply.
 */
fun resolveFontFamilyCss(key: String?): String {
    if (key.isNullOrEmpty()) return systemMonoStack
    return fontPresets.firstOrNull { it.key == key }?.cssStack ?: systemMonoStack
}

/**
 * Resolves a persisted proportional preset key to its CSS font-family stack.
 *
 * Unknown or null keys fall back to the system proportional stack.
 *
 * @param key the persisted preset key, or `null`/empty for the system default.
 * @return the CSS font-family stack to apply.
 */
fun resolveProportionalFontFamilyCss(key: String?): String {
    if (key.isNullOrEmpty()) return systemPropStack
    return fontPresets.firstOrNull { it.key == key }?.cssStack ?: systemPropStack
}

/** Cached result of [detectInstalledFonts]; null until the first call. */
private var installedFontsCache: Set<String>? = null

/**
 * Detects which [fontPresets] are actually installed on the user's machine
 * using the canvas text-width-measurement technique. Bundled and `system*`
 * presets are always returned as available; system-detected families are
 * probed against three generic fallbacks.
 *
 * The result is cached after the first call — installed fonts don't change
 * mid-session.
 *
 * @return the set of preset keys ([FontPreset.key]) available locally.
 */
fun detectInstalledFonts(): Set<String> {
    installedFontsCache?.let { return it }

    val canvas = document.createElement("canvas") as HTMLCanvasElement
    val ctx = canvas.getContext("2d").asDynamic() ?: return emptySet()
    val sample = "mwiIWMOQabcdefghijklmnopqrstuvwxyz0123456789"
    val baselines = listOf("monospace", "serif", "sans-serif")
    val fontSize = 72

    fun widthOf(family: String): Double {
        ctx.font = "${fontSize}px $family"
        val metrics = ctx.measureText(sample)
        return (metrics.width as Number).toDouble()
    }

    val baselineWidths = baselines.associateWith { widthOf(it) }

    val available = mutableSetOf<String>()
    for (preset in fontPresets) {
        val detect = preset.detectFamily
        if (detect == null) {
            available.add(preset.key)
            continue
        }
        val quoted = if (detect.contains(' ')) "'$detect'" else detect
        val installed = baselines.any { baseline ->
            val w = widthOf("$quoted, $baseline")
            val b = baselineWidths.getValue(baseline)
            kotlin.math.abs(w - b) > 0.5
        }
        if (installed) available.add(preset.key)
    }

    installedFontsCache = available
    return available
}
