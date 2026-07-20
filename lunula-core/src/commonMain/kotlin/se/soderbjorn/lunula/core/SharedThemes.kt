/* SharedThemes.kt
 * Cross-app sharing of custom theme *definitions*. Custom themes live in a
 * shared `themes.json` file so a theme cloned in one Lunula app is visible
 * in another on the same machine; slot selections + appearance stay per-app.
 *
 * This file holds the partition/merge helpers persistence layers (e.g.
 * termtastic's server) use to route an incoming flat key/value blob across the
 * shared file and the per-app file, and to union custom-theme arrays so an
 * addition made by another app isn't lost. Adapted from the old ThemeSnapshot
 * machinery; the only shared key is now [PersistKeys.THEME_V2_CUSTOM].
 */
package se.soderbjorn.lunula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Keys whose values are **theme definitions** and therefore belong in the
 * shared cross-app `themes.json` file. Everything else (slot selections,
 * appearance, fonts, per-app toggles) is per-app.
 *
 * @see partitionUiSettingsKeys
 * @see mergeSharedThemes
 */
val SHARED_THEMES_KEYS: Set<String> = setOf(
    PersistKeys.THEME_V2_CUSTOM,
)

/**
 * Partitions an incoming flat string-keyed payload into (shared, perApp).
 * Keys in [SHARED_THEMES_KEYS] go into the first map; the rest into the second.
 *
 * @param incoming the flat key/value map (e.g. a server PATCH body).
 * @return a Pair of (shared-themes keys, per-app keys).
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
 * Merges an outgoing payload (the shared keys this app is about to write to
 * `themes.json`) with the current on-disk content of that file, returning the
 * canonical nested form (the custom-themes value as a [JsonArray]).
 *
 * Custom themes are unioned by `name` (outgoing wins on collision, disk-only
 * entries preserved) so an addition made by another Lunula app isn't dropped
 * if our file watcher missed the announcement. Tolerates each shared value
 * arriving as either a [JsonArray] or a [JsonPrimitive] string holding JSON.
 *
 * @param outgoing the JSON object this app would otherwise write.
 * @param onDisk   the JSON object freshly re-read from `themes.json` (or empty).
 * @return the merged object in canonical nested form, ready to write.
 */
fun mergeSharedThemes(outgoing: JsonObject, onDisk: JsonObject): JsonObject {
    val result = LinkedHashMap<String, JsonElement>(outgoing.size + onDisk.size)
    for ((k, v) in outgoing) result[k] = v
    for (key in SHARED_THEMES_KEYS) {
        val outRaw = outgoing[key]
        val diskRaw = onDisk[key]
        if (outRaw == null && diskRaw == null) continue
        result[key] = mergeThemeArray(outRaw, diskRaw)
    }
    // Preserve disk-only top-level keys we don't classify, so a future schema
    // addition still round-trips through every app.
    for ((k, v) in onDisk) if (k !in result) result[k] = v
    return JsonObject(result)
}

/** Union two custom-theme arrays by the `name` field; outgoing wins on collisions. */
private fun mergeThemeArray(outgoing: JsonElement?, disk: JsonElement?): JsonElement {
    val outArr = outgoing?.let { asJsonArray(it) }
    val diskArr = disk?.let { asJsonArray(it) }
    val byName = LinkedHashMap<String, JsonElement>()
    if (outArr != null) for (item in outArr) themeName(item)?.let { byName[it] = item }
    if (diskArr != null) for (item in diskArr) {
        val n = themeName(item) ?: continue
        if (n !in byName) byName[n] = item
    }
    return JsonArray(byName.values.toList())
}

/** Reads the `name` field of a serialized theme element, or null. */
private fun themeName(el: JsonElement): String? =
    (el as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }

/** Coerces a JSON element (array, or string holding an array) to a [JsonArray]. */
private fun asJsonArray(el: JsonElement): JsonArray? = when (el) {
    is JsonArray -> el
    is JsonPrimitive -> if (el.isString) {
        runCatching { Json.parseToJsonElement(el.content) as? JsonArray }.getOrNull()
    } else null
    else -> null
}
