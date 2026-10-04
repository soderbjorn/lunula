/**
 * Font-family presets surfaced by the toolkit's Settings sidebar.
 *
 * Apps consume the [fontPresets] list to render the Settings sidebar's
 * font picker pill rows. Persisted hosts store the preset
 * [FontPreset.key] (a stable short id) via the relevant
 * [ThemeManagerHost] setter (e.g. `setMonoFontFamily`), not the raw CSS
 * stack. [resolveFontFamilyCss] turns a key into the CSS font-family
 * stack at paint time, and [detectInstalledFonts] hides presets whose
 * primary family isn't available — neither installed on the machine nor
 * declared by the app through `@font-face` — so the user doesn't see
 * options that would silently fall back to a generic.
 *
 * Each preset declares a [FontPreset.kind]: `Mono` for fixed-width
 * presets (terminals, code panes) and `Proportional` for prose presets
 * (notegrow's editor, sidebar/topbar/tabbar chrome). The Settings
 * sidebar uses [FontPreset.kind] to order each font row: the Monospaced
 * section offers only [FontKind.Mono] presets, while the chrome sections
 * (Proportional / Sidebar / Tab bar) list the proportional presets first
 * and then the monospaced ones, so a user can pick a fixed-width face for
 * chrome if they want. `Display` presets (faces too wide for body text,
 * such as Unbounded) are offered only in the Display font row.
 *
 * @see ThemeManagerHost.setMonoFontFamily
 * @see ThemeManagerHost.setProportionalFontFamily
 * @see ThemeManagerHost.setSidebarFontFamily
 * @see ThemeManagerHost.setTabbarFontFamily
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

/**
 * Whether a [FontPreset] is intended for fixed-width content (terminals,
 * code), proportional content (prose, chrome) or only display text
 * (headings).
 *
 * The Settings sidebar uses this to partition presets between the
 * Monospaced section and the proportional sections (Proportional /
 * Sidebar / Tab bar / Window title). [Display] presets — faces too wide
 * or loud for body text, such as Unbounded — are offered only in the
 * Display font row.
 */
enum class FontKind { Mono, Proportional, Display }

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
 * @property detectFamily primary family name [detectInstalledFonts] looks
 *   for — declared by the app through `@font-face`, or installed on the
 *   machine — or `null` for presets that are always considered available
 *   (`system*` stacks and any [bundled] families).
 * @property bundled      `true` when every host is expected to ship the
 *   `.woff2` for this family (e.g. via `@font-face` rules). Bundled families
 *   skip the availability check and are always offered to the user. A
 *   family only some hosts ship is better left unbundled with a
 *   [detectFamily]: it is then offered exactly where its files (or an
 *   installed copy) exist.
 * @property kind         monospaced, proportional or display-only, see [FontKind].
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
 * Proportional / Sidebar / Tab bar sections use [FontKind.Proportional];
 * [FontKind.Display] presets appear in the Display font row only.
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
    // Shipped by Lunarbor (`@font-face`); offered in any app that declares
    // the family or where it is installed — see [detectInstalledFonts].
    FontPreset("instrumentSans", "Instrument Sans",
        "'Instrument Sans', system-ui, sans-serif", "Instrument Sans",
        kind = FontKind.Proportional),

    // ── Display (headings and titles only) ──────────────────────────
    FontPreset("unbounded", "Unbounded",
        "'Unbounded', system-ui, sans-serif", "Unbounded",
        kind = FontKind.Display),
)

/** The `system` mono stack — used when a host returns `null`/empty for mono. */
private val systemMonoStack: String =
    fontPresets.first { it.key == "system" }.cssStack

/** The `systemProp` proportional stack — used when a host returns `null`/empty for proportional. */
private val systemPropStack: String =
    fontPresets.first { it.key == "systemProp" }.cssStack

// ── App-injected presets ────────────────────────────────────────────
//
// The built-in [fontPresets] above are the toolkit's own; the list below
// is the seam the *consuming app* fills at runtime — parallel to how a host
// injects its `customThemes` rather than the toolkit shipping them. A deploy
// that wants a company font reaching the chrome registers it here (family +
// `cssStack`), and every resolver walks [allFontPresets] so an injected key
// resolves exactly like a built-in. The toolkit stays free of any specific
// family: the value arrives from the app.

/** App-injected font presets, keyed by [FontPreset.key]. */
private val injectedFontPresets: MutableList<FontPreset> = mutableListOf()

/**
 * Register [presets] as app-provided font presets, resolvable alongside the
 * built-in [fontPresets]. Re-registering an existing [FontPreset.key] replaces
 * it, so a host can refresh a family without accumulating duplicates.
 *
 * Injected presets are treated as always-available by [detectInstalledFonts]
 * (the app ships the `@font-face`, exactly like a [FontPreset.bundled] built-in),
 * so they are never hidden by the installed-fonts probe.
 */
fun registerFontPresets(presets: List<FontPreset>) {
    for (preset in presets) {
        val idx = injectedFontPresets.indexOfFirst { it.key == preset.key }
        if (idx >= 0) injectedFontPresets[idx] = preset else injectedFontPresets.add(preset)
    }
}

/**
 * The built-in [fontPresets] followed by any [registerFontPresets]-injected
 * ones. Every family-resolving path walks this so an app-injected preset
 * resolves identically to a built-in.
 */
fun allFontPresets(): List<FontPreset> = fontPresets + injectedFontPresets

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
    return allFontPresets().firstOrNull { it.key == key }?.cssStack ?: systemMonoStack
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
    return allFontPresets().firstOrNull { it.key == key }?.cssStack ?: systemPropStack
}

/**
 * Per-preset result of the canvas probe in [detectInstalledFonts], keyed by
 * [FontPreset.key]. Installed fonts don't change mid-session, so a key is
 * probed once; presets registered later are probed on the next call.
 */
private val installedProbeCache: MutableMap<String, Boolean> = mutableMapOf()

/**
 * The font families the page declares through `@font-face` (stylesheets or
 * `FontFace` objects in `document.fonts`), lower-cased and unquoted. A
 * declared face counts whether or not it has been loaded yet: the browser
 * fetches it on first use, so declaring it is what makes it available.
 *
 * Called by [detectInstalledFonts] on every call (cheap; not cached), so a
 * stylesheet that finishes loading after the first call is still seen.
 */
private fun declaredFontFamilies(): Set<String> {
    val families = mutableSetOf<String>()
    val fonts = document.asDynamic().fonts ?: return families
    fonts.forEach { face: dynamic ->
        val family = (face.family as? String) ?: return@forEach
        families.add(normalizeFamilyName(family))
    }
    return families
}

/** Strips surrounding quotes and whitespace from a family name and lower-cases it. */
private fun normalizeFamilyName(family: String): String =
    family.trim().trim('"', '\'').trim().lowercase()

/**
 * Detects which presets in [allFontPresets] are available on this page.
 * A preset is available when any of these holds:
 *
 * - its [FontPreset.detectFamily] is `null` (`system*` stacks, [FontPreset.bundled]
 *   families, and app-injected presets that ship their own `@font-face`);
 * - the page declares its [FontPreset.detectFamily] through `@font-face` — the
 *   app ships the files (e.g. Lunarbor's Instrument Sans and Unbounded), so
 *   the preset shows in that app and stays hidden in apps that don't;
 * - the family is installed on the machine, found with the canvas
 *   text-width-measurement technique against three generic fallbacks.
 *
 * The canvas probe is cached per preset key; the `@font-face` check runs on
 * every call. Called by the Settings sidebar each time it builds a font row.
 *
 * @return the set of preset keys ([FontPreset.key]) available on this page.
 */
fun detectInstalledFonts(): Set<String> {
    val declared = declaredFontFamilies()
    val available = mutableSetOf<String>()
    var ctx: dynamic = null
    var baselineWidths: Map<String, Double>? = null
    val sample = "mwiIWMOQabcdefghijklmnopqrstuvwxyz0123456789"
    val baselines = listOf("monospace", "serif", "sans-serif")
    val fontSize = 72

    fun widthOf(family: String): Double {
        ctx.font = "${fontSize}px $family"
        val metrics = ctx.measureText(sample)
        return (metrics.width as Number).toDouble()
    }

    for (preset in allFontPresets()) {
        val detect = preset.detectFamily
        if (detect == null || normalizeFamilyName(detect) in declared) {
            available.add(preset.key)
            continue
        }
        val installed = installedProbeCache.getOrPut(preset.key) {
            if (ctx == null) {
                val canvas = document.createElement("canvas") as HTMLCanvasElement
                ctx = canvas.getContext("2d").asDynamic()
                if (ctx == null) return@getOrPut false
                baselineWidths = baselines.associateWith { widthOf(it) }
            }
            val quoted = if (detect.contains(' ')) "'$detect'" else detect
            baselines.any { baseline ->
                val w = widthOf("$quoted, $baseline")
                val b = baselineWidths!!.getValue(baseline)
                kotlin.math.abs(w - b) > 0.5
            }
        }
        if (installed) available.add(preset.key)
    }
    return available
}
