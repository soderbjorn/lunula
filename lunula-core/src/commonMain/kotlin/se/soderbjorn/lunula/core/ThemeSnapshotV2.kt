/* ThemeSnapshotV2.kt
 * The persisted, cross-launch theme state for one user under the new theme
 * system. Replaces the old ThemeSnapshot (slots + custom themes/schemes +
 * favorites + fonts). Holds only: the two slot selections, the user's custom
 * themes, and the appearance preference. Fonts/titlebar prefs are persisted
 * separately by the app and are no longer part of this snapshot.
 *
 * Persistence splits across two keys so custom themes can be shared between
 * Lunula apps while slot selections stay per-app:
 *   - PersistKeys.THEME_V2_CUSTOM    (shared)  -> the customThemes array
 *   - PersistKeys.THEME_V2_SELECTION (per-app) -> dark/light slot + appearance
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** Lenient JSON codec for the snapshot (tolerates unknown / missing keys). */
private val themeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Persisted theme state for one user under the v2 system.
 *
 * ### Callers
 * The web shell's view-model reads/writes this; [resolve] is called by every
 * platform (web, Android, iOS) to turn the selection into a [ResolvedTheme].
 *
 * @property darkThemeName  name of the theme bound to the dark slot.
 * @property lightThemeName name of the theme bound to the light slot.
 * @property customThemes   the user's cloned/edited themes (shared across apps).
 * @property appearance     follow-OS, or force the dark/light slot.
 * @property favorites      names of the themes the user has starred; the theme
 *   picker hoists these to the top of its single list. Persisted per-app under
 *   [PersistKeys.THEME_V2_FAVORITES].
 * @see Theme
 * @see ResolvedTheme
 */
data class ThemeSnapshotV2(
    val darkThemeName: String = DEFAULT_DARK_THEME,
    val lightThemeName: String = DEFAULT_LIGHT_THEME,
    val customThemes: List<Theme> = emptyList(),
    val appearance: Appearance = Appearance.Auto,
    val favorites: List<String> = emptyList(),
) {
    /**
     * Resolves the active slot to a [ResolvedTheme].
     *
     * The slot is chosen by [appearance] (or by [systemIsDark] when Auto); the
     * named theme is looked up across built-ins ∪ [customThemes], falling back
     * to the slot default if the name is unknown.
     *
     * @param systemIsDark whether the OS is currently in dark mode.
     * @return the resolved palette for the active slot.
     */
    fun resolve(systemIsDark: Boolean): ResolvedTheme {
        val useDark = when (appearance) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.Auto -> systemIsDark
        }
        val name = if (useDark) darkThemeName else lightThemeName
        val fallback = if (useDark) DEFAULT_DARK_THEME else DEFAULT_LIGHT_THEME
        val all = allThemes(customThemes)
        val theme = all.firstOrNull { it.name == name }
            ?: all.first { it.name == fallback }
        return theme.resolve()
    }

    /** Encodes the per-app selection part: `{darkThemeName, lightThemeName, appearance}`. */
    fun encodeSelection(): JsonObject = buildJsonObject {
        put("darkThemeName", JsonPrimitive(darkThemeName))
        put("lightThemeName", JsonPrimitive(lightThemeName))
        put("appearance", JsonPrimitive(appearance.name))
    }

    /** Encodes the shared custom-themes part as a JSON array of [Theme]. */
    fun encodeCustomThemes(): JsonArray =
        themeJson.encodeToJsonElement(ListSerializer(Theme.serializer()), customThemes) as JsonArray

    /** The selection part as a JSON string (for flat key/value backends). */
    fun selectionJson(): String = themeJson.encodeToString(encodeSelection())

    /** The custom-themes part as a JSON string (for flat key/value backends). */
    fun customThemesJson(): String =
        themeJson.encodeToString(ListSerializer(Theme.serializer()), customThemes)

    /** Encodes the starred-theme names as a JSON array of strings. */
    fun encodeFavorites(): JsonArray =
        JsonArray(favorites.map { JsonPrimitive(it) })

    /** The favorites part as a JSON string (for flat key/value backends). */
    fun favoritesJson(): String = themeJson.encodeToString(encodeFavorites())

    companion object {
        /**
         * Parses a snapshot from its persisted parts. Any part may be null /
         * blank / malformed; missing data falls back to defaults.
         *
         * @param selection      the per-app selection JSON object, or null.
         * @param customArray    the shared custom-themes JSON array, or null.
         * @param favoritesArray the per-app starred-theme-names JSON array, or null.
         * @return the decoded snapshot.
         */
        fun fromParts(
            selection: JsonElement?,
            customArray: JsonElement?,
            favoritesArray: JsonElement? = null,
        ): ThemeSnapshotV2 {
            val sel = selection as? JsonObject
            val dark = (sel?.get("darkThemeName") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_DARK_THEME
            val light = (sel?.get("lightThemeName") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_LIGHT_THEME
            val appearance = (sel?.get("appearance") as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: Appearance.Auto
            val custom = parseCustomThemes(customArray)
            val favorites = parseFavorites(favoritesArray)
            return ThemeSnapshotV2(dark, light, custom, appearance, favorites)
        }

        /**
         * Convenience for flat key/value stores: parse from raw JSON strings.
         *
         * @param selectionJson    the selection JSON string, or null/blank.
         * @param customThemesJson the custom-themes JSON string, or null/blank.
         * @param favoritesJson    the starred-theme-names JSON string, or null/blank.
         */
        fun fromStrings(
            selectionJson: String?,
            customThemesJson: String?,
            favoritesJson: String? = null,
        ): ThemeSnapshotV2 {
            val sel = selectionJson?.takeIf { it.isNotBlank() }
                ?.let { runCatching { themeJson.parseToJsonElement(it) }.getOrNull() }
            val custom = customThemesJson?.takeIf { it.isNotBlank() }
                ?.let { runCatching { themeJson.parseToJsonElement(it) }.getOrNull() }
            val favorites = favoritesJson?.takeIf { it.isNotBlank() }
                ?.let { runCatching { themeJson.parseToJsonElement(it) }.getOrNull() }
            return fromParts(sel, custom, favorites)
        }

        /** Parses the shared custom-themes array, skipping malformed entries. */
        fun parseCustomThemes(el: JsonElement?): List<Theme> {
            val arr = el as? JsonArray ?: return emptyList()
            return arr.mapNotNull { item ->
                runCatching { themeJson.decodeFromJsonElement(Theme.serializer(), item) }.getOrNull()
            }
        }

        /**
         * Parses the starred-theme-names array, keeping only non-blank string
         * entries and de-duplicating while preserving order.
         *
         * @param el the JSON array element (or null / a JSON-string form is not
         *   accepted here — callers pre-parse strings via [fromStrings]).
         * @return the ordered, de-duplicated list of starred theme names.
         */
        fun parseFavorites(el: JsonElement?): List<String> {
            val arr = el as? JsonArray ?: return emptyList()
            return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                .distinct()
        }
    }
}
