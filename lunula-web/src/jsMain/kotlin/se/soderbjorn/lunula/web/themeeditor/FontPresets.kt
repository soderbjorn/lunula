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
 * Besides the presets, the font lists offer every family installed on the
 * machine (Electron only; [loadLocalFontFamilies]), persisted as a
 * [localFontKey]. A row shows presets and installed families as one
 * alphabetical list ([fontRowChoices]); the `system` / `systemProp` presets
 * are listed under the name of the font they paint ([systemFontName]).
 *
 * Each preset declares a [FontPreset.kind]: `Mono` for fixed-width
 * presets (terminals, code panes) and `Proportional` for prose presets
 * (notegrow's editor, sidebar/topbar/tabbar chrome). The Settings
 * sidebar uses [FontPreset.kind] to filter each font row ([fontRowChoices]):
 * the Monospaced section offers only [FontKind.Mono] presets (and monospaced
 * installed families), while every other row (Proportional / Sidebar / Tab
 * bar / Window title / Display) offers every font — so a user can pick any
 * face for any surface but code.
 *
 * @see ThemeManagerHost.setMonoFontFamily
 * @see ThemeManagerHost.setProportionalFontFamily
 * @see ThemeManagerHost.setSidebarFontFamily
 * @see ThemeManagerHost.setTabbarFontFamily
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/**
 * Whether a [FontPreset] is intended for fixed-width content (terminals,
 * code), proportional content (prose, chrome) or only display text
 * (headings).
 *
 * The Settings sidebar uses this to keep the Monospaced row to [Mono]
 * presets; every other row offers every kind (see [fontRowChoices]).
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
 * @property bundled      `true` for a family the toolkit's apps ship as
 *   `.woff2` files (via `@font-face` rules) rather than expect installed. It
 *   is offered where the page declares it or the machine has it — the
 *   family being the first of [cssStack] — see [detectInstalledFonts].
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
 * Ordered list of font presets. The Settings sidebar filters this by
 * [FontPreset.kind] only for the Monospaced section ([FontKind.Mono]); every
 * other font row lists all of them (see [fontRowChoices]).
 *
 * The `system` mono and `systemProp` proportional stacks are the fallbacks
 * when no key is persisted for the corresponding kind; the lists name them
 * for the font they paint ([systemFontName]).
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

/**
 * The preset kinds a Settings sidebar font row offers: [FontKind.Mono] only
 * for the Monospaced row (`kind == Mono`), every kind for any other row — so
 * a kind added later is offered there without touching the rows.
 *
 * Called by [fontRowChoices].
 *
 * @param kind the row's primary kind ([FontKind.Mono] for the Monospaced row,
 *   [FontKind.Proportional] for every other row).
 */
fun offeredFontKinds(kind: FontKind): Set<FontKind> =
    if (kind == FontKind.Mono) setOf(FontKind.Mono) else FontKind.entries.toSet()

/**
 * The keys of the two system-font presets (`system`, `systemProp`). Their
 * stacks start with the CSS keywords for the platform's own UI and monospaced
 * fonts (`system-ui`, `ui-monospace`) — the only way a page reaches them, since
 * macOS hides its system fonts from the installed-font list. The lists name
 * them for what they paint ([systemFontName]): "SF Pro" / "SF Mono" on a Mac.
 */
val systemFontKeys: Set<String> = setOf("system", "systemProp")

/**
 * The name of the font a system preset paints on this platform: SF Pro / SF
 * Mono on macOS, Segoe UI / Consolas on Windows, else a plain description.
 *
 * @param key `system` (monospaced) or `systemProp` (proportional).
 * @param platform `navigator.platform`, passed for tests.
 */
fun systemFontName(key: String, platform: String = currentPlatform()): String {
    val mono = key == "system"
    return when {
        platform.startsWith("Mac") || platform.startsWith("iP") -> if (mono) "SF Mono" else "SF Pro"
        platform.startsWith("Win") -> if (mono) "Consolas" else "Segoe UI"
        else -> if (mono) "System monospace" else "System UI font"
    }
}

/** `navigator.platform`, or `""` where there is none. */
private fun currentPlatform(): String =
    (window.navigator.asDynamic().platform as? String).orEmpty()

/**
 * One entry of a Settings sidebar font list: a preset or an installed family.
 *
 * @property key      what the host persists — a [FontPreset.key], or a
 *   [localFontKey] for an installed family no preset covers.
 * @property label    the name shown in the list (and on the picker button).
 * @property cssStack the `font-family` stack the entry is drawn in.
 */
data class FontChoice(val key: String, val label: String, val cssStack: String)

/**
 * The fonts one Settings sidebar font row lists: one alphabetical list,
 * whatever their source. It holds the [presets] that are [available] and of
 * an offered kind ([offeredFontKinds]) — the system presets named for what
 * they paint ([systemFontName]) — plus every installed family in [localFamilies] no listed preset already
 * names (the Monospaced row only takes [LocalFontFamily.isMono] ones). A
 * preset wins over the same installed family, so a stored preset key keeps
 * matching its entry.
 *
 * Called by the Settings sidebar's font picker each time it fills its list.
 *
 * @param kind the row's primary kind ([FontKind.Mono] for the Monospaced row,
 *   [FontKind.Proportional] for every other row).
 * @param presets the candidate presets, usually [allFontPresets].
 * @param available preset keys usable on this page ([detectInstalledFonts]).
 * @param localFamilies the machine's installed families ([localFontFamilies]),
 *   empty where they can't be listed.
 * @param platform `navigator.platform`, for the system presets' names.
 * @return the entries, sorted case-insensitively by [FontChoice.label].
 */
fun fontRowChoices(
    kind: FontKind,
    presets: List<FontPreset>,
    available: Set<String>,
    localFamilies: List<LocalFontFamily>,
    platform: String = currentPlatform(),
): List<FontChoice> {
    val kinds = offeredFontKinds(kind)
    val offered = presets.filter { it.kind in kinds && it.key in available }
    val systemNames = offered.filter { it.key in systemFontKeys }
        .map { normalizeFamilyName(systemFontName(it.key, platform)) }.toSet()
    // A system preset named "SF Pro" stands in for the installed-only SF Pro
    // preset, and one named "Segoe UI" for an installed Segoe UI.
    val kept = offered.filter {
        it.key in systemFontKeys || normalizeFamilyName(it.displayName) !in systemNames
    }
    val presetFamilies = kept.flatMap {
        if (it.key in systemFontKeys) listOf(systemFontName(it.key, platform))
        else listOfNotNull(it.detectFamily, primaryFamilyOf(it.cssStack), it.displayName)
    }.map(::normalizeFamilyName).toSet()
    val locals = localFamilies
        .filter { kind != FontKind.Mono || it.isMono }
        .filter { normalizeFamilyName(it.family) !in presetFamilies }
        .map { FontChoice(localFontKey(it.family), it.family, localFontStack(it.family, it.isMono)) }
    val presetChoices = kept.map {
        FontChoice(it.key, if (it.key in systemFontKeys) systemFontName(it.key, platform) else it.displayName, it.cssStack)
    }
    return (presetChoices + locals)
        .distinctBy { it.key }
        .sortedBy { it.label.lowercase() }
}

/**
 * The name to show for a persisted font key: the preset's
 * [FontPreset.displayName] (a system preset's [systemFontName]), an installed
 * family's name, or the key itself
 * for one this build doesn't know.
 *
 * Called by the Settings sidebar's font picker for its button label.
 */
fun fontLabelFor(key: String): String =
    localFamilyOf(key)
        ?: (if (key in systemFontKeys) systemFontName(key) else null)
        ?: allFontPresets().firstOrNull { it.key == key }?.displayName
        ?: key

/** The first family of a CSS `font-family` stack, unquoted. */
private fun primaryFamilyOf(cssStack: String): String =
    cssStack.substringBefore(',').trim().trim('"', '\'').trim()

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
 * is always sensible even if the host returns a stale or renamed key. A
 * [localFontKey] resolves to that family with a monospaced fallback.
 * For proportional defaults, callers should pass `"systemProp"` explicitly
 * or use [resolveProportionalFontFamilyCss].
 *
 * @param key the persisted preset key, or `null`/empty for the system mono default.
 * @return the CSS font-family stack to apply.
 */
fun resolveFontFamilyCss(key: String?): String {
    if (key.isNullOrEmpty()) return systemMonoStack
    localFamilyOf(key)?.let { return localFontStack(it, isMono = true) }
    return allFontPresets().firstOrNull { it.key == key }?.cssStack ?: systemMonoStack
}

/**
 * Resolves a persisted proportional preset key to its CSS font-family stack.
 *
 * Unknown or null keys fall back to the system proportional stack. A
 * [localFontKey] resolves to that family, falling back to a monospaced
 * generic when it is a known monospaced family and a sans-serif one otherwise.
 *
 * @param key the persisted preset key, or `null`/empty for the system default.
 * @return the CSS font-family stack to apply.
 */
fun resolveProportionalFontFamilyCss(key: String?): String {
    if (key.isNullOrEmpty()) return systemPropStack
    localFamilyOf(key)?.let { return localFontStack(it, isMono = isLocalFamilyMono(it)) }
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
 * - its [FontPreset.detectFamily] is `null` and it is not [FontPreset.bundled]
 *   (`system*` stacks, and app-injected presets that ship their own `@font-face`);
 * - it is [FontPreset.bundled] and the page declares or the machine has its
 *   family (the stack's first) — an app that ships none of its files never
 *   offers it;
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
    for (preset in allFontPresets()) {
        // A bundled family is there only where the app ships it: Lunamux
        // declares all of them, Lunarbor JetBrains Mono alone.
        val detect = preset.detectFamily
            ?: if (preset.bundled) primaryFamilyOf(preset.cssStack) else null
        if (detect == null || normalizeFamilyName(detect) in declared) {
            available.add(preset.key)
            continue
        }
        val installed = installedProbeCache.getOrPut(preset.key) {
            FontProbe.instance?.rendersLatin(detect) ?: false
        }
        if (installed) available.add(preset.key)
    }
    return available
}

/**
 * Canvas text measuring behind [detectInstalledFonts] and the installed-font
 * list: a family is drawn with each of three generic fallbacks, and a width
 * that differs from the bare fallback's means the family drew the text.
 */
private class FontProbe(private val ctx: dynamic) {
    private val sample = "mwiIWMOQabcdefghijklmnopqrstuvwxyz"
    private val baselines = listOf("monospace", "serif", "sans-serif")
    private val baselineWidths = baselines.associateWith { widthOf(sample, it) }

    private fun widthOf(text: String, family: String): Double {
        ctx.font = "72px $family"
        return (ctx.measureText(text).width as Number).toDouble()
    }

    /** Whether [family] is available and draws Latin text itself. */
    fun rendersLatin(family: String): Boolean {
        val quoted = cssFamilyName(family)
        return baselines.any { kotlin.math.abs(widthOf(sample, "$quoted, $it") - baselineWidths.getValue(it)) > 0.5 }
    }

    /** Whether [family] gives narrow and wide letters the same advance. */
    fun isMono(family: String): Boolean {
        val quoted = "${cssFamilyName(family)}, sans-serif"
        val narrow = widthOf("iiiiiiiiii", quoted)
        return kotlin.math.abs(narrow - widthOf("WWWWWWWWWW", quoted)) < 0.5 &&
            kotlin.math.abs(narrow - widthOf("mmmmmmmmmm", quoted)) < 0.5
    }

    companion object {
        /** The page's probe, made on first use; `null` without a 2D canvas. */
        val instance: FontProbe? by lazy {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.getContext("2d")?.let { FontProbe(it.asDynamic()) }
        }
    }
}

// ── Installed fonts ─────────────────────────────────────────────────
//
// Besides the presets, the font lists offer every family installed on the
// machine, read with the Local Font Access API (`window.queryLocalFonts()`).
// Only in Electron: there the call needs neither a permission prompt nor a
// user gesture, while a browser would prompt — so a web page (Lunarbor's
// browser demo) keeps the presets alone. An installed family is persisted as
// [localFontKey] (`local:<family>`), resolved by [resolveFontFamilyCss] /
// [resolveProportionalFontFamilyCss] to that family plus a generic fallback,
// so a family uninstalled later falls back instead of failing.

/** Prefix of a persisted key naming an installed family, see [localFontKey]. */
const val LOCAL_FONT_KEY_PREFIX: String = "local:"

/** The persisted key for the installed family [family]. */
fun localFontKey(family: String): String = LOCAL_FONT_KEY_PREFIX + family

/** The family a [localFontKey] names, or `null` for any other key. */
fun localFamilyOf(key: String): String? =
    if (key.startsWith(LOCAL_FONT_KEY_PREFIX)) key.removePrefix(LOCAL_FONT_KEY_PREFIX).ifEmpty { null } else null

/**
 * The CSS stack for the installed family [family]: the family, quoted, then a
 * monospaced generic when [isMono], else a sans-serif one.
 */
fun localFontStack(family: String, isMono: Boolean): String =
    cssFamilyName(family) + if (isMono) ", ui-monospace, monospace" else ", system-ui, sans-serif"

/** [family] as a quoted CSS family name, quotes and backslashes escaped. */
private fun cssFamilyName(family: String): String =
    "'" + family.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * One family installed on the machine.
 *
 * @property family the family name, as the system reports it.
 * @property isMono whether it is fixed-width (offered in the Monospaced row).
 */
data class LocalFontFamily(val family: String, val isMono: Boolean)

/**
 * Installed families that map letters to symbols, emoji or braille. They pass
 * the Latin probe — they do draw something for `a` — but text set in them is
 * unreadable, so the font lists leave them out. Lower-case, as
 * [normalizeFamilyName] gives.
 */
private val symbolFontFamilies: Set<String> = setOf(
    "apple braille", "apple color emoji", "apple symbols", "bodoni ornaments",
    "lastresort", "noto color emoji", "segoe ui emoji", "segoe ui symbol",
    "symbol", "webdings", "wingdings", "wingdings 2", "wingdings 3", "zapf dingbats",
)

/** The installed families once listed ([loadLocalFontFamilies]); `null` before. */
private var localFamiliesCache: List<LocalFontFamily>? = null

/** Whether a listing is under way, so concurrent callers share it. */
private val localFamiliesWaiters: MutableList<() -> Unit> = mutableListOf()

/**
 * The installed families listed so far: empty until [loadLocalFontFamilies]
 * has finished, and always empty outside Electron.
 */
fun localFontFamilies(): List<LocalFontFamily> = localFamiliesCache.orEmpty()

/** Whether [family] is a listed installed family that is fixed-width. */
private fun isLocalFamilyMono(family: String): Boolean =
    localFamiliesCache?.firstOrNull { it.family == family }?.isMono ?: false

/**
 * Lists the machine's installed families once (`queryLocalFonts()`), keeping
 * those that draw Latin letters — other-script faces fall back to a generic
 * for the app's text, and [symbolFontFamilies] draw symbols, so either would
 * only look broken — and skipping hidden system families (names starting
 * with `.`). Calls [onLoaded] when the
 * list is ready (at once if it already is); outside Electron, or when the call
 * fails, the list stays empty and [onLoaded] still runs.
 *
 * Called by the Settings sidebar when it builds its font rows.
 */
fun loadLocalFontFamilies(onLoaded: () -> Unit) {
    if (localFamiliesCache != null) { onLoaded(); return }
    localFamiliesWaiters.add(onLoaded)
    if (localFamiliesWaiters.size > 1) return
    fun finish(families: List<LocalFontFamily>) {
        localFamiliesCache = families
        val waiters = localFamiliesWaiters.toList()
        localFamiliesWaiters.clear()
        waiters.forEach { it() }
    }
    val win = window.asDynamic()
    val isElectron = (window.navigator.userAgent).contains("Electron")
    if (!isElectron || win.queryLocalFonts == undefined) { finish(emptyList()); return }
    try {
        (win.queryLocalFonts() as Promise<dynamic>).then({ faces: dynamic ->
            val probe = FontProbe.instance
            val names = mutableSetOf<String>()
            for (i in 0 until (faces.length as Int)) {
                val name = faces[i].family as? String ?: continue
                if (name.isNotBlank() && !name.startsWith(".")) names.add(name)
            }
            finish(names.filter { normalizeFamilyName(it) !in symbolFontFamilies }
                .filter { probe?.rendersLatin(it) ?: true }
                .map { LocalFontFamily(it, probe?.isMono(it) ?: false) })
        }, { _: Throwable -> finish(emptyList()) })
    } catch (_: Throwable) {
        finish(emptyList())
    }
}
