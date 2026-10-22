/* ModeStyles.kt
 * The style settings, remembered separately for dark and light mode.
 *
 * A user can run one combination of style settings at night and another by
 * day, and switching the appearance switches the settings with it, the same
 * way it switches the theme between the dark and light slots. What is
 * remembered per mode is the style: corner roundness, spacing, selection,
 * surfaces, chrome, windows, active window and labels. Navigation and the font
 * settings are shared by both modes and live elsewhere.
 *
 * Persisted inside the shape blob under [PersistKeys.APPEARANCE_SHAPE] by the
 * web shell's own host, as `{"dark": {...}, "light": {...}}` beside the shared
 * fields; an app with its own settings host stores the string from [toJson].
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Lenient codec — an unreadable mode must never lose the other one. */
private val modeJson = Json { ignoreUnknownKeys = true }

/**
 * The user's style settings for each appearance mode.
 *
 * Each entry is an [AppearanceShape] whose per-mode fields are the user's own
 * picks (null = not picked, fall through to the app default); its
 * `navigationMode` and `shellStyle` are never set here.
 *
 * @property dark  the style used while the dark slot is active.
 * @property light the style used while the light slot is active.
 * @see AppearanceShape.styleOnly
 */
data class ModeStyles(
    val dark: AppearanceShape = AppearanceShape(),
    val light: AppearanceShape = AppearanceShape(),
) {
    /**
     * The entry for one mode.
     *
     * @param isDark whether the dark slot is the active one.
     * @return [dark] or [light].
     */
    fun forMode(isDark: Boolean): AppearanceShape = if (isDark) dark else light

    /**
     * A copy with one mode's entry replaced, the other untouched.
     *
     * @param isDark whether to replace [dark] (else [light]).
     * @param shape  the new entry; reduced to its per-mode fields.
     * @return the updated pair.
     */
    fun withMode(isDark: Boolean, shape: AppearanceShape): ModeStyles =
        if (isDark) copy(dark = shape.styleOnly()) else copy(light = shape.styleOnly())

    /**
     * Encodes as `{"dark": {...}, "light": {...}}`, each entry as
     * [AppearanceShape.encode] writes it.
     *
     * @return the JSON object.
     */
    fun encode(): JsonObject = buildJsonObject {
        put("dark", dark.styleOnly().encode())
        put("light", light.styleOnly().encode())
    }

    /** The persisted form as a JSON string, for an app's own storage. */
    fun toJson(): String = modeJson.encodeToString(JsonObject.serializer(), encode())

    companion object {
        /**
         * Both modes seeded with the same legacy single-mode values: how the
         * settings saved before they were remembered per mode carry over.
         *
         * @param legacy the single shape the user had.
         * @return a pair with that shape in both modes.
         */
        fun bothFrom(legacy: AppearanceShape): ModeStyles =
            ModeStyles(dark = legacy.styleOnly(), light = legacy.styleOnly())

        /**
         * Parses [raw], accepting either the `{"dark":…, "light":…}` form or a
         * blob that also carries other keys (the shell's shape blob keeps the
         * shared fields beside them).
         *
         * @param raw the stored JSON, or null.
         * @return the pair, or `null` when [raw] holds neither mode (so the
         *   caller can fall back to the legacy single-mode values).
         */
        fun fromJson(raw: String?): ModeStyles? {
            val text = raw?.takeIf { it.isNotBlank() } ?: return null
            val obj = runCatching { modeJson.parseToJsonElement(text) as? JsonObject }
                .getOrNull() ?: return null
            val dark = obj["dark"] as? JsonObject
            val light = obj["light"] as? JsonObject
            if (dark == null && light == null) return null
            return ModeStyles(
                dark = dark?.let { AppearanceShape.fromJsonObject(it).styleOnly() } ?: AppearanceShape(),
                light = light?.let { AppearanceShape.fromJsonObject(it).styleOnly() } ?: AppearanceShape(),
            )
        }

        /**
         * The shell's shape blob: [legacy]'s fields at the top level (the
         * shared `navigationMode` and the single-mode values from before the
         * settings were per mode, written back as they were read) and, when
         * [modes] is set, the two per-mode entries beside them.
         *
         * Written by the web shell's own host under
         * [PersistKeys.APPEARANCE_SHAPE]. An older build reading it sees only
         * the top-level fields (and ignores the unknown `dark` / `light`),
         * which is the safe degradation.
         *
         * @param legacy the top-level fields, as [AppearanceShape.encode] writes them.
         * @param modes  the per-mode settings, or `null` when never edited.
         * @return the blob as a JSON string.
         */
        fun shapeBlob(legacy: AppearanceShape, modes: ModeStyles?): String {
            val obj = buildJsonObject {
                for ((k, v) in legacy.encode()) put(k, v)
                if (modes != null) {
                    put("dark", modes.dark.styleOnly().encode())
                    put("light", modes.light.styleOnly().encode())
                }
            }
            return modeJson.encodeToString(JsonObject.serializer(), obj)
        }
    }
}
