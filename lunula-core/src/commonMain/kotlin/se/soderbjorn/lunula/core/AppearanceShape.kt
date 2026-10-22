/* AppearanceShape.kt
 * The shell's shape and density preferences, and their persisted form.
 *
 * These are USER settings that sit beside the font preferences rather than on
 * a [Theme], and the separation is the whole point of the type existing: a
 * theme answers "what does this app look like", while roundness and spacing
 * answer "how do I like my windows". The second answer has to survive the
 * first one changing — a user who squares their corners keeps them square
 * through every palette they try — so the two are stored under different keys
 * and can never overwrite each other.
 *
 * Persisted per-app under [PersistKeys.APPEARANCE_SHAPE] by the web shell; the
 * codec lives here in core so a native shell can round-trip the same blob.
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Lenient codec — an unknown or malformed field must never lose the others. */
private val shapeJson = Json { ignoreUnknownKeys = true }

/**
 * The shell's shape and density preferences.
 *
 * Every field is nullable and `null` means "toolkit default", never "zero":
 * `cornerRadiusPx = 0` is a real choice (square corners) and has to be
 * distinguishable from "never picked". That is why this is not a data class of
 * primitives with defaults baked in — the difference between *unset* and *set
 * to the default value* is what lets a future change to the default reach
 * users who never expressed an opinion.
 *
 * @property cornerRadiusPx  radius for panes, tabs and sidebar pills; `null` →
 *   the stylesheet's 18px.
 * @property uiDensity       chrome spacing scale; `null` → [UiDensity.Compact].
 * @property selectionStyle  how selection is painted; `null` → [SelectionStyle.Default].
 * @property surfaceStyle    whether surfaces carry depth (shadows, sheen, glow,
 *   ambient wash) or stay flat; `null` → [SurfaceStyle.Default] ([SurfaceStyle.Depth]).
 * @property shellStyle      LEGACY look shorthand: a stored or brand-supplied
 *   `shellStyle` (Classic / Calm) stands for the whole matching [Looks]
 *   preset beneath this shape's own fields — see [lookLayer]. New code writes
 *   the individual fields instead; `null` → no shorthand.
 * @property navigationMode  whether tabs are reached through the sidebar, a tab
 *   strip, or both; `null` → [NavigationMode.Default] ([NavigationMode.Both]).
 *   Shared by both appearance modes, so it is ignored inside a [ModeStyles]
 *   entry.
 * @property chromeStyle     the chrome's colours and controls; `null` →
 *   [ChromeStyle.Default] ([ChromeStyle.Themed]).
 * @property windowLayout    cards or one sheet; `null` → [WindowLayout.Default]
 *   ([WindowLayout.Cards]).
 * @property focusStyle      how the active window is marked; `null` →
 *   [FocusStyle.Default] ([FocusStyle.Accent]).
 * @property labelCase       chrome label casing; `null` → [LabelCase.Default]
 *   ([LabelCase.Uppercase]).
 * @see PersistKeys.APPEARANCE_SHAPE
 * @see ModeStyles
 * @see resolveAppearance
 */
data class AppearanceShape(
    val cornerRadiusPx: Int? = null,
    val uiDensity: UiDensity? = null,
    val selectionStyle: SelectionStyle? = null,
    val surfaceStyle: SurfaceStyle? = null,
    val shellStyle: ShellStyle? = null,
    val navigationMode: NavigationMode? = null,
    val chromeStyle: ChromeStyle? = null,
    val windowLayout: WindowLayout? = null,
    val focusStyle: FocusStyle? = null,
    val labelCase: LabelCase? = null,
) {
    /**
     * Encodes to the persisted JSON object, omitting unset fields entirely.
     *
     * Omission rather than an explicit `null` keeps the blob meaning exactly
     * "here is what the user chose", so a value added in a later version isn't
     * shadowed by a stale explicit null written by an older one.
     *
     * @return the JSON object to store under [PersistKeys.APPEARANCE_SHAPE].
     */
    fun encode(): JsonObject = buildJsonObject {
        cornerRadiusPx?.let { put("cornerRadiusPx", JsonPrimitive(it)) }
        uiDensity?.let { put("uiDensity", JsonPrimitive(it.cssValue)) }
        selectionStyle?.let { put("selectionStyle", JsonPrimitive(it.cssValue)) }
        surfaceStyle?.let { put("surfaceStyle", JsonPrimitive(it.cssValue)) }
        shellStyle?.let { put("shellStyle", JsonPrimitive(it.cssValue)) }
        navigationMode?.let { put("navigationMode", JsonPrimitive(it.cssValue)) }
        chromeStyle?.let { put("chromeStyle", JsonPrimitive(it.cssValue)) }
        windowLayout?.let { put("windowLayout", JsonPrimitive(it.cssValue)) }
        focusStyle?.let { put("focusStyle", JsonPrimitive(it.cssValue)) }
        labelCase?.let { put("labelCase", JsonPrimitive(it.cssValue)) }
    }

    /** The persisted form as a JSON string, for flat key/value backends. */
    fun toJson(): String = shapeJson.encodeToString(JsonObject.serializer(), encode())

    /** True when the user has expressed no preference at all. */
    val isEmpty: Boolean
        get() = cornerRadiusPx == null && uiDensity == null && selectionStyle == null &&
            surfaceStyle == null && shellStyle == null && navigationMode == null &&
            chromeStyle == null && windowLayout == null && focusStyle == null && labelCase == null

    /**
     * The legacy look shorthand as a layer of its own: the [Looks] preset named
     * by [shellStyle] (Classic or Calm), or an empty shape when there is none.
     *
     * Placed directly beneath this shape in [resolveAppearance]'s ladder, so a
     * brand's `defaultStyle: clean`, a page's `?style=clean` or a user who
     * picked Calm before the individual settings existed all still get the
     * whole Calm bundle, while any individual field set beside the shorthand
     * still wins over it.
     *
     * @return the preset's shape, or `AppearanceShape()` when [shellStyle] is null.
     * @see Looks.forShellStyle
     */
    fun lookLayer(): AppearanceShape = shellStyle?.let { Looks.forShellStyle(it).shape } ?: AppearanceShape()

    /**
     * This shape with the legacy [shellStyle] shorthand folded into its
     * fields: each field this shape leaves unset takes the named look's value,
     * and the shorthand itself is cleared.
     *
     * How a user's pre-split pick ("Calm", once saved as "clean") becomes ordinary per-mode values
     * the first time they edit a style setting, so changing one setting keeps
     * the rest of the look they had.
     *
     * @return the expanded shape, without [shellStyle].
     * @see lookLayer
     */
    fun withLookExpanded(): AppearanceShape {
        val look = lookLayer()
        return AppearanceShape(
            cornerRadiusPx = cornerRadiusPx ?: look.cornerRadiusPx,
            uiDensity = uiDensity ?: look.uiDensity,
            selectionStyle = selectionStyle ?: look.selectionStyle,
            surfaceStyle = surfaceStyle ?: look.surfaceStyle,
            navigationMode = navigationMode,
            chromeStyle = chromeStyle ?: look.chromeStyle,
            windowLayout = windowLayout ?: look.windowLayout,
            focusStyle = focusStyle ?: look.focusStyle,
            labelCase = labelCase ?: look.labelCase,
        )
    }

    /**
     * This shape with every per-mode style field (and the legacy [shellStyle]
     * shorthand) cleared, keeping only what is shared by both appearance modes:
     * [navigationMode].
     *
     * Used when writing the persisted blob, whose top level holds the shared
     * fields while [ModeStyles] holds the per-mode ones.
     *
     * @return the shared part of this shape.
     */
    fun sharedPart(): AppearanceShape = AppearanceShape(navigationMode = navigationMode)

    /**
     * This shape with [navigationMode] and the legacy [shellStyle] cleared,
     * keeping only the fields remembered per appearance mode.
     *
     * Used to seed a [ModeStyles] entry from a legacy single-mode shape.
     *
     * @return the per-mode part of this shape.
     */
    fun styleOnly(): AppearanceShape = copy(navigationMode = null, shellStyle = null)

    companion object {
        /**
         * Parses the persisted blob, tolerating null, blank, malformed JSON,
         * and individually unrecognised values.
         *
         * Every failure mode degrades to "unset" for the affected field only.
         * This blob is written by whichever app version the user last ran and
         * may be hand-edited, so a density naming a scale this build doesn't
         * have must not also cost the user their corner radius.
         *
         * @param raw the stored JSON string, or null.
         * @return the decoded preferences; all-null when nothing is readable.
         */
        fun fromJson(raw: String?): AppearanceShape {
            val text = raw?.takeIf { it.isNotBlank() } ?: return AppearanceShape()
            val obj = runCatching { shapeJson.parseToJsonElement(text) as? JsonObject }
                .getOrNull() ?: return AppearanceShape()
            return fromJsonObject(obj)
        }

        /**
         * Decodes an already-parsed shape object, with the same per-field
         * leniency as [fromJson].
         *
         * Called by [fromJson] and by [ModeStyles.fromJson] for each mode's
         * entry.
         *
         * @param obj the JSON object holding the shape's fields.
         * @return the decoded preferences.
         */
        fun fromJsonObject(obj: JsonObject): AppearanceShape {
            val radius = (obj["cornerRadiusPx"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0..64 }
            val density = UiDensity.fromRaw((obj["uiDensity"] as? JsonPrimitive)?.contentOrNull)
            val selection = SelectionStyle.fromRaw(
                (obj["selectionStyle"] as? JsonPrimitive)?.contentOrNull
            )
            val surface = SurfaceStyle.fromRaw(
                (obj["surfaceStyle"] as? JsonPrimitive)?.contentOrNull
            )
            val shell = ShellStyle.fromRaw(
                (obj["shellStyle"] as? JsonPrimitive)?.contentOrNull
            )
            val navigation = NavigationMode.fromRaw(
                (obj["navigationMode"] as? JsonPrimitive)?.contentOrNull
            )
            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
            return AppearanceShape(
                cornerRadiusPx = radius,
                uiDensity = density,
                selectionStyle = selection,
                surfaceStyle = surface,
                shellStyle = shell,
                navigationMode = navigation,
                chromeStyle = ChromeStyle.fromRaw(str("chromeStyle")),
                windowLayout = WindowLayout.fromRaw(str("windowLayout")),
                focusStyle = FocusStyle.fromRaw(str("focusStyle")),
                labelCase = LabelCase.fromRaw(str("labelCase")),
            )
        }
    }
}
