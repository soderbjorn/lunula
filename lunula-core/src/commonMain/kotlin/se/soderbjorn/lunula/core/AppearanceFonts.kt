/* AppearanceFonts.kt
 * The user's font picks for every surface the toolkit letters, and their
 * persisted form.
 *
 * The exact sibling of [AppearanceShape], and it exists for the same reason: a
 * theme answers "what does this app look like", while "which face, at what
 * size" answers "how do I like to read". The second answer has to survive the
 * first one changing — a user who picks a serif keeps it through every palette
 * they try — so fonts are stored under their own key and a theme write can
 * neither carry them along nor clear them.
 *
 * ── Why one blob and not twelve keys ────────────────────────────────────────
 *
 * There are six lettered surfaces (sidebar, tab bar, window title, monospaced
 * content, proportional content, display headings) and each has a family and a
 * size, so the alternative shape is twelve flat keys. One object under one key
 * instead, for three reasons:
 *
 *  - **It is one fact.** "What this user's app is lettered in" is a single
 *    answer with twelve parts, the way [AppearanceShape] is a single answer
 *    with three. Twelve keys can half-apply — a reader that sees the new
 *    sidebar family but not yet its size paints a combination nobody chose.
 *    One blob arrives whole or not at all.
 *  - **Each key has a cost outside this file.** Every server-backed consumer
 *    enforces its own allowlist of keys that may travel (LunaPin's
 *    `UiSettingKeys`, Lunicle's `ThemePersister`), so a key is a change in
 *    each app that adopts it, and twelve keys is twelve such entries against
 *    one user-visible feature.
 *  - **A thirteenth surface costs nothing.** A future lettered surface adds a
 *    field a reader that predates it simply ignores, rather than a new key
 *    every consuming app must allowlist again.
 *
 * The cost, stated rather than hidden: two browsers changing *different* font
 * rows at the same time resolve last-writer-wins across the whole set rather
 * than per row. That is the same cost [AppearanceShape] already carries, and
 * the losing edit is one pill click to redo.
 *
 * Persisted per-app under [PersistKeys.APPEARANCE_FONTS] by the web shell; the
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
private val fontsJson = Json { ignoreUnknownKeys = true }

/**
 * Smallest and largest font size this codec will accept, in px.
 *
 * A guard on the stored blob rather than on the picker: the value arrives from
 * whichever app version the user last ran and may be hand-edited, and a size of
 * 0 or 4000 is not a preference anyone can read the app at. The range is
 * deliberately wider than any pill row offers so a future row does not have to
 * remember to widen it.
 */
private val SIZE_RANGE = 4..200

/**
 * One user's font picks, as [se.soderbjorn.lunula.core.PersistKeys] stores them.
 *
 * Every field is nullable and `null` means "this user has picked nothing for
 * this surface", never "the default" — the two have to stay distinguishable,
 * because unset is what lets the app's own deploy-time default (and beneath it
 * the toolkit's) still apply, and what lets a change to either reach everyone
 * who never expressed an opinion. Storing the resolved default instead would
 * pin each account to whatever the default was on the day they first loaded.
 *
 * Family fields hold a [se.soderbjorn.lunula.web.themeeditor.FontPreset] *key*
 * (e.g. `"systemProp"`, `"jetbrainsMono"`), not a CSS stack: the stack is a
 * property of the build and can be improved, while the key is what the user
 * chose. A key this build has never heard of resolves to nothing rather than to
 * a wrong face, which is why the codec keeps unknown strings — a preset removed
 * in one version and restored in the next does not cost the pick.
 *
 * @property sidebarFontFamily      chrome (sidebar + topbar) face.
 * @property sidebarFontSizePx      chrome size.
 * @property tabbarFontFamily       tab-strip face; falls back to the sidebar's.
 * @property tabbarFontSizePx       tab-strip size.
 * @property paneHeaderFontFamily   window-title face; falls back to the sidebar's.
 * @property paneHeaderFontSizePx   window-title size.
 * @property monoFontFamily         monospaced content face (terminals, code).
 * @property monoFontSizePx         monospaced content size.
 * @property proportionalFontFamily proportional content ("prose") face.
 * @property proportionalFontSizePx proportional content size.
 * @property displayFontFamily      heading face; falls back to prose.
 * @property displayFontSizePx      heading size.
 * @see PersistKeys.APPEARANCE_FONTS
 * @see AppearanceShape
 */
data class AppearanceFonts(
    val sidebarFontFamily: String? = null,
    val sidebarFontSizePx: Int? = null,
    val tabbarFontFamily: String? = null,
    val tabbarFontSizePx: Int? = null,
    val paneHeaderFontFamily: String? = null,
    val paneHeaderFontSizePx: Int? = null,
    val monoFontFamily: String? = null,
    val monoFontSizePx: Int? = null,
    val proportionalFontFamily: String? = null,
    val proportionalFontSizePx: Int? = null,
    val displayFontFamily: String? = null,
    val displayFontSizePx: Int? = null,
) {
    /**
     * Encodes to the persisted JSON object, omitting unset fields entirely.
     *
     * Omission rather than an explicit `null` keeps the blob meaning exactly
     * "here is what the user chose", so a surface added in a later version is
     * not shadowed by a stale explicit null written by an older one.
     *
     * The field names are the [se.soderbjorn.lunula.web.themeeditor.ThemeManagerHost]
     * property names verbatim, so the blob reads as the host it hydrates.
     *
     * @return the JSON object to store under [PersistKeys.APPEARANCE_FONTS].
     */
    fun encode(): JsonObject = buildJsonObject {
        sidebarFontFamily?.let { put("sidebarFontFamily", JsonPrimitive(it)) }
        sidebarFontSizePx?.let { put("sidebarFontSizePx", JsonPrimitive(it)) }
        tabbarFontFamily?.let { put("tabbarFontFamily", JsonPrimitive(it)) }
        tabbarFontSizePx?.let { put("tabbarFontSizePx", JsonPrimitive(it)) }
        paneHeaderFontFamily?.let { put("paneHeaderFontFamily", JsonPrimitive(it)) }
        paneHeaderFontSizePx?.let { put("paneHeaderFontSizePx", JsonPrimitive(it)) }
        monoFontFamily?.let { put("monoFontFamily", JsonPrimitive(it)) }
        monoFontSizePx?.let { put("monoFontSizePx", JsonPrimitive(it)) }
        proportionalFontFamily?.let { put("proportionalFontFamily", JsonPrimitive(it)) }
        proportionalFontSizePx?.let { put("proportionalFontSizePx", JsonPrimitive(it)) }
        displayFontFamily?.let { put("displayFontFamily", JsonPrimitive(it)) }
        displayFontSizePx?.let { put("displayFontSizePx", JsonPrimitive(it)) }
    }

    /** The persisted form as a JSON string, for flat key/value backends. */
    fun toJson(): String = fontsJson.encodeToString(JsonObject.serializer(), encode())

    /**
     * True when the user has picked nothing at all.
     *
     * Read by the shell to decide whether the key is worth a write: an empty
     * blob says exactly what a missing key already says, and an app whose users
     * never open the font rows should not acquire a stored value — nor a
     * request to store one — for a feature it does not use.
     */
    val isEmpty: Boolean get() = encode().isEmpty()

    companion object {
        /**
         * Parses the persisted blob, tolerating null, blank, malformed JSON, and
         * individually unusable values.
         *
         * Every failure mode degrades to "unset" for the affected field only.
         * A size outside [SIZE_RANGE], or a family stored as something other
         * than a string, must not also cost the user the eleven picks beside it.
         *
         * @param raw the stored JSON string, or null.
         * @return the decoded picks; all-null when nothing is readable.
         */
        fun fromJson(raw: String?): AppearanceFonts {
            val text = raw?.takeIf { it.isNotBlank() } ?: return AppearanceFonts()
            val obj = runCatching { fontsJson.parseToJsonElement(text) as? JsonObject }
                .getOrNull() ?: return AppearanceFonts()
            fun family(name: String): String? =
                (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun size(name: String): Int? =
                (obj[name] as? JsonPrimitive)?.intOrNull?.takeIf { it in SIZE_RANGE }
            return AppearanceFonts(
                sidebarFontFamily = family("sidebarFontFamily"),
                sidebarFontSizePx = size("sidebarFontSizePx"),
                tabbarFontFamily = family("tabbarFontFamily"),
                tabbarFontSizePx = size("tabbarFontSizePx"),
                paneHeaderFontFamily = family("paneHeaderFontFamily"),
                paneHeaderFontSizePx = size("paneHeaderFontSizePx"),
                monoFontFamily = family("monoFontFamily"),
                monoFontSizePx = size("monoFontSizePx"),
                proportionalFontFamily = family("proportionalFontFamily"),
                proportionalFontSizePx = size("proportionalFontSizePx"),
                displayFontFamily = family("displayFontFamily"),
                displayFontSizePx = size("displayFontSizePx"),
            )
        }
    }
}
