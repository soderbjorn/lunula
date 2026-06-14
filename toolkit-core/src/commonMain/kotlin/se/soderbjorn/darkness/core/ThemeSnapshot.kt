/**
 * Persisted snapshot of the toolkit-managed theme state for one user.
 *
 * [ThemeSnapshot] holds every field a Darkness app needs to round-trip
 * across launches that the per-paint [UiSettings] does not — slot
 * selections, custom themes/schemes, favorites, font preferences.
 *
 * Apps consume the snapshot as either a [JsonObject] (single-blob storage,
 * e.g. notegrow's localStorage) or a flat `Map<String, String>` (key/value
 * stores, e.g. termtastic's server `SettingsPersister`). Both encodings
 * use the same key names so the schema is one cross-app contract.
 *
 * Wire shape (the [JsonObject] form; the flat-string-map form
 * JSON-stringifies nested objects/arrays into the value):
 *
 * ```json
 * {
 *   "theme.light":       "Paper & Ink",
 *   "theme.dark":        "Neon Circuit",
 *   "favorites.themes":  ["Neon Green", "Verdant"],
 *   "favorites.schemes": ["Cyber teal"],
 *   "themeConfigs":      { "<name>": { "mode": "...", "colorScheme": "...",
 *                                       "sections": {...} } },
 *   "customSchemes":     { "<name>": { "darkFg": "...", "lightFg": "...",
 *                                       "darkBg": "...", "lightBg": "...",
 *                                       "overrides": {...} } },
 *   "monoFontFamily":           "JetBrainsMono",
 *   "monoFontSizePx":           14,
 *   "proportionalFontFamily":   "Inter",
 *   "proportionalFontSizePx":   15,
 *   "sidebarFontFamily":        "system",
 *   "sidebarFontSizePx":        13,
 *   "tabbarFontFamily":         null,
 *   "tabbarFontSizePx":         null,
 *   "desktopNotifications":     true,
 *   "electronCustomTitleBar":   false
 * }
 * ```
 *
 * No backwards-compatibility reader: data that doesn't match the current
 * shape parses as "no value present" and the corresponding field stays at
 * the toolkit default.
 *
 * @property lightThemeName            theme name bound to the light slot, or null.
 * @property darkThemeName             theme name bound to the dark slot, or null.
 * @property customThemes              user-saved custom themes, keyed by name.
 * @property customSchemes             user-saved custom colour schemes, keyed by name.
 * @property favoriteThemes            theme names the user has starred.
 * @property favoriteSchemes           scheme names the user has starred.
 * @property monoFontFamily            monospaced main-content font key.
 * @property monoFontSizePx            monospaced main-content font size (px).
 * @property proportionalFontFamily    proportional main-content font key.
 * @property proportionalFontSizePx    proportional main-content font size (px).
 * @property sidebarFontFamily         chrome (sidebar / topbar) font key.
 * @property sidebarFontSizePx         chrome (sidebar / topbar) font size (px).
 * @property tabbarFontFamily          tab-strip font key (null falls back to sidebar).
 * @property tabbarFontSizePx          tab-strip font size (px) (null falls back to sidebar).
 * @property desktopNotifications      desktop-notifications opt-in.
 * @property useCustomTitleBar         per-app custom-titlebar opt-in (Electron `hiddenInset`).
 */
package se.soderbjorn.darkness.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json

/** Wire-format key constants used by the snapshot codec. */
internal object SnapshotKeys {
    const val ThemeLight = "theme.light"
    const val ThemeDark = "theme.dark"
    const val FavoritesThemes = "favorites.themes"
    const val FavoritesSchemes = "favorites.schemes"
    const val ThemeConfigs = "themeConfigs"
    const val CustomSchemes = "customSchemes"
    const val MonoFontFamily = "monoFontFamily"
    const val MonoFontSizePx = "monoFontSizePx"
    const val ProportionalFontFamily = "proportionalFontFamily"
    const val ProportionalFontSizePx = "proportionalFontSizePx"
    const val SidebarFontFamily = "sidebarFontFamily"
    const val SidebarFontSizePx = "sidebarFontSizePx"
    const val TabbarFontFamily = "tabbarFontFamily"
    const val TabbarFontSizePx = "tabbarFontSizePx"
    const val DesktopNotifications = "desktopNotifications"
    const val ElectronCustomTitleBar = "electronCustomTitleBar"
}

/**
 * Keys whose values describe **theme/scheme definitions** and therefore
 * belong in the shared cross-app `themes.json` file. Everything else
 * (slot selections, fonts, per-app toggles, plus [UiSettings] keys like
 * `theme`/`appearance`) is per-app.
 *
 * Used by persistence layers (e.g. termtastic's server) to partition an
 * incoming flat key/value blob across the shared file and the per-app
 * file without each consumer having to re-encode the classification.
 */
val SHARED_THEMES_KEYS: Set<String> = setOf(
    SnapshotKeys.ThemeConfigs,
    SnapshotKeys.CustomSchemes,
    SnapshotKeys.FavoritesThemes,
    SnapshotKeys.FavoritesSchemes,
)

/**
 * Partition an incoming flat string-keyed payload into (shared, perApp).
 * Keys that match [SHARED_THEMES_KEYS] go into the first map; everything
 * else goes into the second.
 *
 * @param incoming the flat key/value map (e.g. termtastic's server
 *   `Map<String, String>` arriving via PATCH).
 * @return a Pair where `first` holds shared-themes-bound keys and
 *   `second` holds per-app keys.
 */
fun partitionUiSettingsKeys(
    incoming: Map<String, String>,
): Pair<Map<String, String>, Map<String, String>> {
    val shared = LinkedHashMap<String, String>()
    val perApp = LinkedHashMap<String, String>()
    for ((k, v) in incoming) {
        if (k in SHARED_THEMES_KEYS) shared[k] = v else perApp[k] = v
    }
    return shared to perApp
}

/**
 * Merge an outgoing payload (the keys this app is about to write to
 * `themes.json`) with the current on-disk content of that file. Always
 * returns the **canonical nested form**: shared-key values come back
 * as [JsonObject]/[JsonArray] regardless of how they arrived, so the
 * file written to disk is uniform across every Darkness app.
 *
 * Performs per-key union for [SnapshotKeys.ThemeConfigs] and
 * [SnapshotKeys.CustomSchemes] so additions made by another Darkness
 * app aren't dropped if our file watcher missed the announcement.
 * Favorites lists are set-unioned. Outgoing wins on map-key collisions
 * (the user just edited that theme/scheme; their value is fresher
 * than disk's).
 *
 * Trade-off: a theme/scheme deleted in another app is **resurrected**
 * if our watcher missed the deletion event and we still hold the
 * entry in our outgoing payload. This is the documented cost of
 * preserving cross-app additions without tombstones.
 *
 * Wire-shape tolerance on **input**: each shared-key value may arrive
 * as either a [JsonObject]/[JsonArray] or a [JsonPrimitive] string
 * holding JSON. On **output**, the canonical form is always nested.
 *
 * Pure JSON manipulation — safe to call from JVM and JS contexts.
 *
 * @param outgoing the JSON object this app would otherwise write
 * @param onDisk   the JSON object freshly re-read from `themes.json`,
 *                 or empty if the file is missing
 * @return the merged JSON object in canonical nested form, ready to
 *         atomically write to disk
 */
fun mergeSharedThemes(outgoing: JsonObject, onDisk: JsonObject): JsonObject {
    val result = LinkedHashMap<String, JsonElement>(outgoing.size + onDisk.size)
    for ((k, v) in outgoing) result[k] = v
    for (key in SHARED_THEMES_KEYS) {
        val outRaw = outgoing[key]
        val diskRaw = onDisk[key]
        if (outRaw == null && diskRaw == null) continue
        result[key] = when (key) {
            SnapshotKeys.ThemeConfigs, SnapshotKeys.CustomSchemes ->
                mergeMapValue(outRaw, diskRaw)
            SnapshotKeys.FavoritesThemes, SnapshotKeys.FavoritesSchemes ->
                mergeListValue(outRaw, diskRaw)
            else -> outRaw ?: diskRaw!!
        }
    }
    // Preserve any disk-only top-level keys we don't classify, so a
    // future schema addition still round-trips through every app.
    for ((k, v) in onDisk) {
        if (k !in result) result[k] = v
    }
    return JsonObject(result)
}

/** Per-key map merge: union of disk + outgoing keys, outgoing wins on collisions. */
private fun mergeMapValue(outgoing: JsonElement?, disk: JsonElement?): JsonElement {
    val outgoingObj = outgoing?.let { asJsonObject(it) }
    val diskObj = disk?.let { asJsonObject(it) }
    val merged = LinkedHashMap<String, JsonElement>()
    if (diskObj != null) for ((k, v) in diskObj) merged[k] = v
    if (outgoingObj != null) for ((k, v) in outgoingObj) merged[k] = v
    return JsonObject(merged)
}

/** Set-union of two lists, outgoing order first, disk-only entries appended. */
private fun mergeListValue(outgoing: JsonElement?, disk: JsonElement?): JsonElement {
    val outgoingArr = outgoing?.let { asJsonArray(it) }
    val diskArr = disk?.let { asJsonArray(it) }
    val seen = LinkedHashSet<String>()
    val merged = mutableListOf<JsonElement>()
    if (outgoingArr != null) for (item in outgoingArr) {
        val s = (item as? JsonPrimitive)?.contentOrNull ?: continue
        if (seen.add(s)) merged.add(item)
    }
    if (diskArr != null) for (item in diskArr) {
        val s = (item as? JsonPrimitive)?.contentOrNull ?: continue
        if (seen.add(s)) merged.add(item)
    }
    return JsonArray(merged)
}

private fun asJsonObject(el: JsonElement): JsonObject? = when (el) {
    is JsonObject -> el
    is JsonPrimitive -> if (el.isString) {
        runCatching { Json.parseToJsonElement(el.content) as? JsonObject }.getOrNull()
    } else null
    else -> null
}

private fun asJsonArray(el: JsonElement): JsonArray? = when (el) {
    is JsonArray -> el
    is JsonPrimitive -> if (el.isString) {
        runCatching { Json.parseToJsonElement(el.content) as? JsonArray }.getOrNull()
    } else null
    else -> null
}

/**
 * Persisted snapshot of toolkit-managed theme state. See file-level KDoc
 * for the wire format.
 */
data class ThemeSnapshot(
    val lightThemeName: String? = null,
    val darkThemeName: String? = null,
    val customThemes: Map<String, Theme> = emptyMap(),
    val customSchemes: Map<String, CustomScheme> = emptyMap(),
    val favoriteThemes: List<String> = emptyList(),
    val favoriteSchemes: List<String> = emptyList(),
    val monoFontFamily: String? = null,
    val monoFontSizePx: Int? = null,
    val proportionalFontFamily: String? = null,
    val proportionalFontSizePx: Int? = null,
    val sidebarFontFamily: String? = null,
    val sidebarFontSizePx: Int? = null,
    val tabbarFontFamily: String? = null,
    val tabbarFontSizePx: Int? = null,
    val desktopNotifications: Boolean = false,
    val useCustomTitleBar: Boolean = false,
) {
    /**
     * Encode this snapshot as a [JsonObject]. Used by apps that persist
     * the whole snapshot under a single key (e.g. notegrow's localStorage).
     */
    fun encodeAsJsonObject(): JsonObject = buildJsonObject {
        lightThemeName?.let { put(SnapshotKeys.ThemeLight, JsonPrimitive(it)) }
        darkThemeName?.let { put(SnapshotKeys.ThemeDark, JsonPrimitive(it)) }
        if (favoriteThemes.isNotEmpty()) {
            put(SnapshotKeys.FavoritesThemes, buildJsonArray {
                for (name in favoriteThemes) add(JsonPrimitive(name))
            })
        }
        if (favoriteSchemes.isNotEmpty()) {
            put(SnapshotKeys.FavoritesSchemes, buildJsonArray {
                for (name in favoriteSchemes) add(JsonPrimitive(name))
            })
        }
        if (customThemes.isNotEmpty()) {
            put(SnapshotKeys.ThemeConfigs, encodeCustomThemes(customThemes))
        }
        if (customSchemes.isNotEmpty()) {
            put(SnapshotKeys.CustomSchemes, encodeCustomSchemes(customSchemes))
        }
        monoFontFamily?.let { put(SnapshotKeys.MonoFontFamily, JsonPrimitive(it)) }
        monoFontSizePx?.let { put(SnapshotKeys.MonoFontSizePx, JsonPrimitive(it)) }
        proportionalFontFamily?.let { put(SnapshotKeys.ProportionalFontFamily, JsonPrimitive(it)) }
        proportionalFontSizePx?.let { put(SnapshotKeys.ProportionalFontSizePx, JsonPrimitive(it)) }
        sidebarFontFamily?.let { put(SnapshotKeys.SidebarFontFamily, JsonPrimitive(it)) }
        sidebarFontSizePx?.let { put(SnapshotKeys.SidebarFontSizePx, JsonPrimitive(it)) }
        tabbarFontFamily?.let { put(SnapshotKeys.TabbarFontFamily, JsonPrimitive(it)) }
        tabbarFontSizePx?.let { put(SnapshotKeys.TabbarFontSizePx, JsonPrimitive(it)) }
        if (desktopNotifications) {
            put(SnapshotKeys.DesktopNotifications, JsonPrimitive(true))
        }
        if (useCustomTitleBar) {
            put(SnapshotKeys.ElectronCustomTitleBar, JsonPrimitive(true))
        }
    }

    /**
     * Encode this snapshot as a flat `Map<String, String>` suitable for
     * key/value backends like termtastic's server `SettingsPersister`. Nested
     * structures (themes / schemes / favorites lists) are JSON-stringified
     * into the string value.
     *
     * Empty / null fields are omitted so a no-op snapshot writes nothing.
     */
    fun encodeAsStringMap(): Map<String, String> = buildMap {
        lightThemeName?.let { put(SnapshotKeys.ThemeLight, it) }
        darkThemeName?.let { put(SnapshotKeys.ThemeDark, it) }
        if (favoriteThemes.isNotEmpty()) {
            put(SnapshotKeys.FavoritesThemes, Json.encodeToString(
                JsonArray.serializer(),
                buildJsonArray { for (n in favoriteThemes) add(JsonPrimitive(n)) },
            ))
        }
        if (favoriteSchemes.isNotEmpty()) {
            put(SnapshotKeys.FavoritesSchemes, Json.encodeToString(
                JsonArray.serializer(),
                buildJsonArray { for (n in favoriteSchemes) add(JsonPrimitive(n)) },
            ))
        }
        if (customThemes.isNotEmpty()) {
            put(SnapshotKeys.ThemeConfigs, Json.encodeToString(
                JsonObject.serializer(),
                encodeCustomThemes(customThemes),
            ))
        }
        if (customSchemes.isNotEmpty()) {
            put(SnapshotKeys.CustomSchemes, Json.encodeToString(
                JsonObject.serializer(),
                encodeCustomSchemes(customSchemes),
            ))
        }
        monoFontFamily?.let { put(SnapshotKeys.MonoFontFamily, it) }
        monoFontSizePx?.let { put(SnapshotKeys.MonoFontSizePx, it.toString()) }
        proportionalFontFamily?.let { put(SnapshotKeys.ProportionalFontFamily, it) }
        proportionalFontSizePx?.let { put(SnapshotKeys.ProportionalFontSizePx, it.toString()) }
        sidebarFontFamily?.let { put(SnapshotKeys.SidebarFontFamily, it) }
        sidebarFontSizePx?.let { put(SnapshotKeys.SidebarFontSizePx, it.toString()) }
        tabbarFontFamily?.let { put(SnapshotKeys.TabbarFontFamily, it) }
        tabbarFontSizePx?.let { put(SnapshotKeys.TabbarFontSizePx, it.toString()) }
        if (desktopNotifications) put(SnapshotKeys.DesktopNotifications, "true")
        if (useCustomTitleBar) put(SnapshotKeys.ElectronCustomTitleBar, "true")
    }

    companion object {
        /**
         * Decode a [JsonObject] into a [ThemeSnapshot]. Missing keys leave
         * the corresponding field at its default (null / empty / false).
         * Malformed nested values are skipped silently.
         */
        fun fromJsonObject(obj: JsonObject): ThemeSnapshot {
            val customThemes = parseCustomThemes(obj[SnapshotKeys.ThemeConfigs])
            val customSchemes = parseCustomSchemes(obj[SnapshotKeys.CustomSchemes])
            return ThemeSnapshot(
                lightThemeName = (obj[SnapshotKeys.ThemeLight] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                darkThemeName = (obj[SnapshotKeys.ThemeDark] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                customThemes = customThemes,
                customSchemes = customSchemes,
                favoriteThemes = parseStringList(obj[SnapshotKeys.FavoritesThemes]),
                favoriteSchemes = parseStringList(obj[SnapshotKeys.FavoritesSchemes]),
                monoFontFamily = (obj[SnapshotKeys.MonoFontFamily] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                monoFontSizePx = (obj[SnapshotKeys.MonoFontSizePx] as? JsonPrimitive)?.intOrNull,
                proportionalFontFamily = (obj[SnapshotKeys.ProportionalFontFamily] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                proportionalFontSizePx = (obj[SnapshotKeys.ProportionalFontSizePx] as? JsonPrimitive)?.intOrNull,
                sidebarFontFamily = (obj[SnapshotKeys.SidebarFontFamily] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                sidebarFontSizePx = (obj[SnapshotKeys.SidebarFontSizePx] as? JsonPrimitive)?.intOrNull,
                tabbarFontFamily = (obj[SnapshotKeys.TabbarFontFamily] as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() },
                tabbarFontSizePx = (obj[SnapshotKeys.TabbarFontSizePx] as? JsonPrimitive)?.intOrNull,
                desktopNotifications = (obj[SnapshotKeys.DesktopNotifications] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
                useCustomTitleBar = (obj[SnapshotKeys.ElectronCustomTitleBar] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
            )
        }

        /**
         * Decode a JSON string into a [ThemeSnapshot]. Blank or malformed
         * input returns an empty snapshot.
         */
        fun fromJsonString(json: String): ThemeSnapshot {
            if (json.isBlank()) return ThemeSnapshot()
            val obj = runCatching {
                Json.parseToJsonElement(json) as? JsonObject
            }.getOrNull() ?: return ThemeSnapshot()
            return fromJsonObject(obj)
        }
    }
}

private fun encodeCustomThemes(themes: Map<String, Theme>): JsonObject = buildJsonObject {
    for ((name, t) in themes) {
        put(name, buildJsonObject {
            put("mode", JsonPrimitive(t.mode.name))
            put("colorScheme", JsonPrimitive(t.colorScheme))
            if (t.sections.isNotEmpty()) {
                put("sections", buildJsonObject {
                    for ((k, v) in t.sections) put(k, JsonPrimitive(v))
                })
            }
        })
    }
}

private fun encodeCustomSchemes(schemes: Map<String, CustomScheme>): JsonObject = buildJsonObject {
    for ((name, s) in schemes) {
        put(name, buildJsonObject {
            put("darkFg", JsonPrimitive(s.darkFg))
            put("lightFg", JsonPrimitive(s.lightFg))
            put("darkBg", JsonPrimitive(s.darkBg))
            put("lightBg", JsonPrimitive(s.lightBg))
            if (s.overrides.isNotEmpty()) {
                put("overrides", buildJsonObject {
                    for ((k, v) in s.overrides) put(k, JsonPrimitive(v))
                })
            }
        })
    }
}

private fun parseCustomThemes(el: JsonElement?): Map<String, Theme> {
    val obj = el as? JsonObject ?: return emptyMap()
    val out = linkedMapOf<String, Theme>()
    for ((name, value) in obj) {
        val o = value as? JsonObject ?: continue
        val colorScheme = o["colorScheme"]?.jsonPrimitive?.contentOrNull ?: continue
        val modeStr = o["mode"]?.jsonPrimitive?.contentOrNull
        val mode = modeStr?.let { runCatching { ConfigMode.valueOf(it) }.getOrNull() }
            ?: ConfigMode.Both
        val sections = (o["sections"] as? JsonObject)?.let { sec ->
            sec.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
        } ?: emptyMap()
        out[name] = Theme(
            name = name,
            mode = mode,
            colorScheme = colorScheme,
            sections = sections,
        )
    }
    return out
}

private fun parseCustomSchemes(el: JsonElement?): Map<String, CustomScheme> {
    val obj = el as? JsonObject ?: return emptyMap()
    val out = linkedMapOf<String, CustomScheme>()
    for ((name, value) in obj) {
        val o = value as? JsonObject ?: continue
        val darkFg = o["darkFg"]?.jsonPrimitive?.contentOrNull ?: continue
        val lightFg = o["lightFg"]?.jsonPrimitive?.contentOrNull ?: continue
        val darkBg = o["darkBg"]?.jsonPrimitive?.contentOrNull ?: continue
        val lightBg = o["lightBg"]?.jsonPrimitive?.contentOrNull ?: continue
        val ovr = (o["overrides"] as? JsonObject)?.let { om ->
            om.mapNotNull { (k, v) ->
                val p = v as? JsonPrimitive ?: return@mapNotNull null
                val l = p.longOrNull ?: p.contentOrNull?.toLongOrNull()
                l?.let { k to it }
            }.toMap()
        } ?: emptyMap()
        out[name] = CustomScheme(name, darkFg, lightFg, darkBg, lightBg, ovr)
    }
    return out
}

private fun parseStringList(el: JsonElement?): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
}
