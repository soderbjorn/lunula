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
 * @see PersistKeys.APPEARANCE_SHAPE
 */
data class AppearanceShape(
    val cornerRadiusPx: Int? = null,
    val uiDensity: UiDensity? = null,
    val selectionStyle: SelectionStyle? = null,
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
    }

    /** The persisted form as a JSON string, for flat key/value backends. */
    fun toJson(): String = shapeJson.encodeToString(JsonObject.serializer(), encode())

    /** True when the user has expressed no preference at all. */
    val isEmpty: Boolean
        get() = cornerRadiusPx == null && uiDensity == null && selectionStyle == null

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
            val radius = (obj["cornerRadiusPx"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0..64 }
            val density = UiDensity.fromRaw((obj["uiDensity"] as? JsonPrimitive)?.contentOrNull)
            val selection = SelectionStyle.fromRaw(
                (obj["selectionStyle"] as? JsonPrimitive)?.contentOrNull
            )
            return AppearanceShape(
                cornerRadiusPx = radius,
                uiDensity = density,
                selectionStyle = selection,
            )
        }
    }
}
