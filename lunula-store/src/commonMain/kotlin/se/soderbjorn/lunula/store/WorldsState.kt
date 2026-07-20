/**
 * Generalized "worlds" container — one level above [LayoutState].
 *
 * A **world** is a named workspace that owns its own tab/pane [LayoutState]
 * and (optionally) its own theme selection. [WorldsState] is the ordered
 * collection of worlds plus the id of the active one. It sits above
 * [LayoutState] the same way [LayoutState] sits above a single tab: same
 * conventions — a versioned [WorldsState.schemaVersion], a lenient
 * never-throws parse ([WorldsState.fromJsonString]), and a canonical
 * [WorldsState.toJsonString] for atomic disk / IPC round-trips.
 *
 * ### Why a container above tabs
 * The toolkit shell renders exactly one world's tabs at a time; switching
 * worlds swaps the entire tab strip (and, if the world carries one, its
 * theme). This lets a single app hold several unrelated workspaces —
 * e.g. Lunamux's per-project "worlds" — without cross-contaminating tab
 * lists or theme picks.
 *
 * ### Persistence
 * Local-mode consumers (the toolkit's own demo, single-process apps)
 * persist this under [se.soderbjorn.lunula.core.PersistKeys.WORLDS].
 * Source-mode apps (Lunamux) receive the world model from their server
 * over the wire and do **not** persist it here — their server owns it.
 *
 * ### Per-world theme
 * [WorldThemeSelection] mirrors the per-app selection part of
 * [ThemeSnapshotV2] (dark slot / light slot / appearance) and round-trips
 * through the very same [ThemeSnapshotV2.encodeSelection] /
 * [ThemeSnapshotV2.fromParts] seam, so a world's theme is resolved against
 * the shared custom-theme pool exactly like the global selection. Custom
 * theme *definitions* and favorites stay shared across worlds (and apps).
 *
 * @see LayoutState
 * @see ThemeSnapshotV2
 */
package se.soderbjorn.lunula.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.ThemeSnapshotV2

/** Conventional filename for the per-app worlds JSON blob (local mode). */
const val WORLDS_STATE_FILE_NAME = "worlds-state.json"

/**
 * Current worlds-schema version. Bump on a breaking change to the JSON
 * shape; readers seeing a higher version they don't understand fall back
 * to [WorldsState.defaults] rather than corrupt the file.
 */
const val WORLDS_SCHEMA_VERSION = 1

/** Default world name used when migrating a bare [LayoutState] into a world. */
const val DEFAULT_WORLD_NAME = "Home"

/**
 * A world's theme **pair** — the dark-slot and light-slot theme names
 * scoped to a single world.
 *
 * Deliberately holds only the two slot names, *not* the appearance
 * (Auto/Dark/Light). The light↔dark mode is a single **global** setting:
 * switching it flips the active slot for every world at once. So a world
 * owns "which dark theme and which light theme", while the global
 * appearance decides which of the two is live. Custom-theme *definitions*
 * and favorites likewise remain shared globally (never part of a world).
 *
 * Resolve a world's live theme by pairing this with the global appearance
 * via [toSnapshot]; round-trips through [ThemeSnapshotV2.encodeSelection] /
 * [ThemeSnapshotV2.fromParts] so it resolves against the shared
 * custom-theme pool identically to the global selection.
 *
 * @property darkThemeName  name of the theme bound to the dark slot.
 * @property lightThemeName name of the theme bound to the light slot.
 * @see WorldState.themeSelection
 */
data class WorldThemeSelection(
    val darkThemeName: String,
    val lightThemeName: String,
) {
    /**
     * Encodes this pair to a `{darkThemeName, lightThemeName}` object.
     * Appearance is intentionally omitted — it lives globally, not per
     * world.
     *
     * @return the selection JSON object (slots only).
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("darkThemeName", darkThemeName)
        put("lightThemeName", lightThemeName)
    }

    /**
     * Combines this per-world pair with the **global** [appearance] into a
     * full [ThemeSnapshotV2] the consumer can [ThemeSnapshotV2.resolve]
     * against the shared custom-theme pool.
     *
     * @param appearance the global appearance mode (Auto/Dark/Light).
     * @param customThemes the shared custom-theme definitions.
     * @return a snapshot carrying this world's pair + the global appearance.
     */
    fun toSnapshot(
        appearance: Appearance,
        customThemes: List<se.soderbjorn.lunula.core.Theme> = emptyList(),
    ): ThemeSnapshotV2 = ThemeSnapshotV2(
        darkThemeName = darkThemeName,
        lightThemeName = lightThemeName,
        customThemes = customThemes,
        appearance = appearance,
    )

    companion object {
        /**
         * Parses a selection object (or null) into a [WorldThemeSelection],
         * or `null` when the object is absent. Delegates to
         * [ThemeSnapshotV2.fromParts] so parsing tolerates missing / unknown
         * keys (including a legacy `appearance` field, which is ignored)
         * exactly like the global selection does.
         *
         * @param obj the selection JSON object, or null.
         * @return the parsed pair, or null when [obj] is null.
         */
        fun fromJson(obj: JsonObject?): WorldThemeSelection? {
            if (obj == null) return null
            val snap = ThemeSnapshotV2.fromParts(selection = obj, customArray = null)
            return WorldThemeSelection(
                darkThemeName = snap.darkThemeName,
                lightThemeName = snap.lightThemeName,
            )
        }
    }
}

/**
 * A single world: a named workspace owning its own tab/pane [LayoutState]
 * and, optionally, its own [WorldThemeSelection].
 *
 * @property id             stable world identifier; survives across launches.
 * @property name           human-readable world name (shown in the switcher).
 * @property layout         this world's tab/pane layout — the same model a
 *   single-world app persists directly.
 * @property themeSelection this world's theme, or `null` to follow the
 *   app/global selection.
 */
data class WorldState(
    val id: String,
    val name: String,
    val layout: LayoutState = LayoutState.defaults(),
    val themeSelection: WorldThemeSelection? = null,
) {
    /** Serialise this world to its canonical JSON object. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("layout", Json.parseToJsonElement(layout.toJsonString()))
        themeSelection?.let { put("themeSelection", it.toJson()) }
    }

    companion object {
        /** Parse a world JSON object; throws on a missing id (caller guards). */
        fun fromJson(obj: JsonObject): WorldState {
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("WorldState missing id")
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: id
            val layout = (obj["layout"] as? JsonObject)
                ?.let { LayoutState.fromJsonString(Json.encodeToString(JsonObject.serializer(), it)) }
                ?: LayoutState.defaults()
            val theme = WorldThemeSelection.fromJson(obj["themeSelection"] as? JsonObject)
            return WorldState(id = id, name = name, layout = layout, themeSelection = theme)
        }
    }
}

/**
 * Top-level persisted worlds snapshot — the ordered world list plus the
 * active world id.
 *
 * @property schemaVersion forward-migration gate; readers seeing a higher
 *   version they don't understand fall back to [defaults].
 * @property worlds        ordered world list. Order is preserved on
 *   round-trip; the first world is the conventional **default** world.
 * @property activeWorldId id of the active world, or `null` when [worlds]
 *   is empty (readers fall back to the first world).
 */
data class WorldsState(
    val schemaVersion: Int = WORLDS_SCHEMA_VERSION,
    val worlds: List<WorldState> = emptyList(),
    val activeWorldId: String? = null,
) {
    /** The default (first) world, or `null` when there are no worlds. */
    val defaultWorld: WorldState? get() = worlds.firstOrNull()

    /** The active world (by [activeWorldId]), falling back to [defaultWorld]. */
    val activeWorld: WorldState?
        get() = worlds.firstOrNull { it.id == activeWorldId } ?: defaultWorld

    /**
     * Serialise to the canonical JSON string used on disk and across IPC.
     *
     * @return a JSON object string suitable for persisting.
     */
    fun toJsonString(): String {
        val obj = buildJsonObject {
            put("schemaVersion", schemaVersion)
            activeWorldId?.let { put("activeWorldId", it) }
            put("worlds", buildJsonArray { worlds.forEach { add(it.toJson()) } })
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        /**
         * Default worlds snapshot — a single default world wrapping
         * [LayoutState.defaults]. Ships one world so consumers never handle
         * a "no worlds" empty state on first launch.
         *
         * @return a fresh [WorldsState] with one default world.
         */
        fun defaults(): WorldsState = fromLayout(LayoutState.defaults())

        /**
         * One-time migration: wrap a single [LayoutState] into one default
         * [WorldState] inside a [WorldsState]. This is the local-mode
         * counterpart to the server's V3→V4 window-config migration.
         *
         * @param layout the pre-worlds single layout to wrap.
         * @param id     the default world's id (default `"w1"`).
         * @param name   the default world's name (default [DEFAULT_WORLD_NAME]).
         * @return a worlds snapshot holding exactly one active world.
         */
        fun fromLayout(
            layout: LayoutState,
            id: String = "w1",
            name: String = DEFAULT_WORLD_NAME,
        ): WorldsState = WorldsState(
            worlds = listOf(WorldState(id = id, name = name, layout = layout)),
            activeWorldId = id,
        )

        /**
         * Parse a JSON string into a [WorldsState]. Returns [defaults] for
         * blank input, unparseable JSON, or a schema version this build does
         * not understand. Never throws — a typo in stored data must not
         * crash a client.
         *
         * @param json a JSON object string in the canonical worlds shape.
         * @return the parsed state, or [defaults] on any failure path.
         */
        fun fromJsonString(json: String): WorldsState {
            if (json.isBlank()) return defaults()
            val obj = runCatching {
                Json.parseToJsonElement(json) as? JsonObject
            }.getOrNull() ?: return defaults()
            val version = (obj["schemaVersion"] as? JsonPrimitive)
                ?.content?.toIntOrNull() ?: return defaults()
            if (version > WORLDS_SCHEMA_VERSION) return defaults()
            return runCatching { fromJson(obj) }.getOrNull()
                ?.takeIf { it.worlds.isNotEmpty() }
                ?: defaults()
        }

        /** Internal: structured JSON → [WorldsState]; throws on bad shape. */
        private fun fromJson(obj: JsonObject): WorldsState {
            val worldsJson = obj["worlds"] as? JsonArray ?: JsonArray(emptyList())
            val worlds = worldsJson.mapNotNull { it as? JsonObject }
                .mapNotNull { runCatching { WorldState.fromJson(it) }.getOrNull() }
            val schemaVersion = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: WORLDS_SCHEMA_VERSION
            val activeWorldId = (obj["activeWorldId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            return WorldsState(
                schemaVersion = schemaVersion,
                worlds = worlds,
                activeWorldId = activeWorldId ?: worlds.firstOrNull()?.id,
            )
        }
    }
}
