/**
 * UI settings model for the Darkness theme system.
 *
 * [UiSettings] is the resolved snapshot of the user's visual preferences:
 * the active main colour scheme, the appearance mode, and the per-pane
 * scheme map computed from the active theme. Apps build it from a
 * [ThemeSnapshot] via the toolkit resolver and hand it to the painter on
 * every state change.
 *
 * Persistence shape (per-app file): a flat object with just the active
 * choice and appearance — no `theme.<pane>` keys. The per-pane map is
 * recomputed on load from the theme + the app's pane→section mapping.
 *
 * Example:
 *
 * ```json
 * {
 *   "theme":      "Neon Green",
 *   "appearance": "Auto"
 * }
 * ```
 *
 * @see ColorScheme
 * @see Appearance
 * @see Theme
 */
package se.soderbjorn.darkness.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Snapshot of the user's resolved visual preferences for one paint pass.
 *
 * @property theme       the active main colour scheme; fallback for any
 *   pane not present in [paneSchemes].
 * @property appearance  the user's light/dark/auto preference.
 * @property paneSchemes per-pane resolved schemes, keyed by concrete pane
 *   name (e.g. `"main"`, `"sidebar"`, `"git"`, `"editor"`). Open-ended:
 *   apps populate only the panes they render.
 *
 * @see toJsonString
 * @see fromJsonString
 * @see schemeForPane
 */
data class UiSettings(
    val theme: ColorScheme,
    val appearance: Appearance,
    val paneSchemes: Map<String, ColorScheme> = emptyMap(),
) {
    /**
     * Resolve the colour scheme for a concrete pane, falling back to the
     * main [theme] when the pane has no override.
     *
     * @param pane concrete pane name as the app uses it (e.g. `"main"`,
     *   `"sidebar"`, `"git"`).
     * @return the [ColorScheme] for that pane.
     */
    fun schemeForPane(pane: String): ColorScheme = paneSchemes[pane] ?: theme

    /**
     * Serialize this [UiSettings] to the canonical JSON string format.
     * Only colour-scheme **names** are written. [paneSchemes] is *not*
     * persisted — it's deterministically derived on load from the active
     * theme + the app's pane→section map.
     *
     * @return JSON object string suitable for persisting.
     * @see fromJsonString
     */
    fun toJsonString(): String {
        val obj = buildJsonObject {
            put("theme", theme.name)
            put("appearance", appearance.name)
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        /**
         * Default [UiSettings] used when no settings exist yet — the
         * [DEFAULT_THEME_NAME] scheme with [Appearance.Auto] and no per-pane
         * overrides.
         */
        fun defaults(): UiSettings = UiSettings(
            theme = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME },
            appearance = Appearance.Auto,
        )

        /**
         * Parse a JSON string into a [UiSettings] resolved against
         * [recommendedColorSchemes]. Use [resolveAgainst] to merge a custom
         * scheme pool when persisted data references user-defined schemes.
         *
         * Unknown theme names fall back silently to [DEFAULT_THEME_NAME];
         * unknown appearance values fall back to [Appearance.Auto].
         */
        fun fromJsonString(json: String): UiSettings {
            if (json.isBlank()) return defaults()
            val obj = runCatching {
                Json.parseToJsonElement(json) as? JsonObject
            }.getOrNull() ?: return defaults()
            return resolveAgainst(obj, recommendedColorSchemes)
        }

        /**
         * Build a [UiSettings] by applying [theme]'s composition (main
         * scheme + per-section assignments) onto [base] using the app's
         * [paneToSection] map.
         *
         * Resolution per pane (see [Theme]):
         *
         * ```
         * theme.sections[paneToSection[pane]]
         *     ?: theme.colorScheme
         * ```
         *
         * @param base           the [UiSettings] to update; only [theme]
         *   and [paneSchemes] are replaced. [appearance] is preserved.
         * @param theme          the picked [Theme] (typically from
         *   [defaultThemes] or a custom theme pool).
         * @param paneToSection  the app's pane → universal section map
         *   (declared in app code; e.g. `notegrowPanes`).
         * @param customSchemes  user-defined schemes the toolkit doesn't
         *   know about; consulted after [recommendedColorSchemes].
         * @return a fresh [UiSettings] with the theme applied. Falls back
         *   to [base] unchanged when [theme]'s main scheme name doesn't
         *   resolve in either pool.
         */
        fun applyTheme(
            base: UiSettings,
            theme: Theme,
            paneToSection: Map<String, String>,
            customSchemes: Map<String, ColorScheme> = emptyMap(),
        ): UiSettings {
            val main = resolveScheme(theme.colorScheme, customSchemes) ?: return base
            val resolved = mutableMapOf<String, ColorScheme>()
            for ((pane, section) in paneToSection) {
                val name = theme.sections[section] ?: continue
                val scheme = resolveScheme(name, customSchemes) ?: continue
                resolved[pane] = scheme
            }
            return base.copy(theme = main, paneSchemes = resolved)
        }

        /**
         * Parse a JSON object using a custom pool of colour schemes when
         * looking up names. Useful when the host app has its own
         * user-defined schemes alongside the recommended ones.
         *
         * Per-pane keys use the `theme.<paneName>` namespace; everything
         * outside that namespace is ignored except `theme` and `appearance`.
         *
         * @param obj  the parsed JSON object.
         * @param pool the full list of [ColorScheme]s to look names up in.
         * @return the parsed [UiSettings], or [defaults] if required
         *   fields are missing.
         */
        fun resolveAgainst(obj: JsonObject, pool: List<ColorScheme>): UiSettings {
            fun string(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun scheme(name: String?): ColorScheme? =
                name?.let { n -> pool.firstOrNull { it.name == n } }

            val theme = scheme(string("theme"))
                ?: pool.firstOrNull { it.name == DEFAULT_THEME_NAME }
                ?: pool.first()
            val appearance = string("appearance")
                ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
                ?: Appearance.Auto

            return UiSettings(theme = theme, appearance = appearance, paneSchemes = emptyMap())
        }

        private fun resolveScheme(
            name: String,
            customSchemes: Map<String, ColorScheme>,
        ): ColorScheme? {
            if (name.isEmpty()) return null
            return recommendedColorSchemes.firstOrNull { it.name == name }
                ?: customSchemes[name]
        }
    }
}
