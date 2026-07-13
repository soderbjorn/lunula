/**
 * Round-trip and migration tests for [WorldsState] / [WorldState] /
 * [WorldThemeSelection].
 *
 * Called by the toolkit-store test task; guards the two A1 "done when"
 * checks: a [WorldsState] survives a JSON round-trip, and a legacy single
 * [LayoutState] migrates into one default world with no data loss.
 */
package se.soderbjorn.darkness.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldsStateTest {

    @Test
    fun worldsState_roundTrips_throughJson() {
        val original = WorldsState(
            worlds = listOf(
                WorldState(
                    id = "w1",
                    name = "World 1",
                    layout = LayoutState.defaults(),
                ),
                WorldState(
                    id = "w2",
                    name = "DarknessIRC",
                    layout = LayoutState.defaults().copy(activeTabId = "channels"),
                    themeSelection = WorldThemeSelection(
                        darkThemeName = "Amber CRT",
                        lightThemeName = "Sepia",
                    ),
                ),
            ),
            activeWorldId = "w2",
        )

        val restored = WorldsState.fromJsonString(original.toJsonString())

        assertEquals(2, restored.worlds.size)
        assertEquals("w2", restored.activeWorldId)
        assertEquals("DarknessIRC", restored.worlds[1].name)
        assertEquals("channels", restored.worlds[1].layout.activeTabId)
        val theme = assertNotNull(restored.worlds[1].themeSelection)
        assertEquals("Amber CRT", theme.darkThemeName)
        assertEquals("Sepia", theme.lightThemeName)
        assertNull(restored.worlds[0].themeSelection)
    }

    @Test
    fun singleLayout_migratesInto_oneDefaultWorld() {
        val layout = LayoutState.defaults()

        val worlds = WorldsState.fromLayout(layout)

        assertEquals(1, worlds.worlds.size)
        val world = worlds.worlds.single()
        assertEquals(worlds.activeWorldId, world.id)
        // No data loss: the wrapped layout matches the original exactly.
        assertEquals(layout.tabs.map { it.id }, world.layout.tabs.map { it.id })
        assertEquals(layout.activeTabId, world.layout.activeTabId)
        assertEquals(DEFAULT_WORLD_NAME, world.name)
    }

    @Test
    fun blankOrGarbage_fallsBackToDefaults() {
        assertTrue(WorldsState.fromJsonString("").worlds.isNotEmpty())
        assertTrue(WorldsState.fromJsonString("not json").worlds.isNotEmpty())
        assertTrue(WorldsState.fromJsonString("{}").worlds.isNotEmpty())
    }

    @Test
    fun higherSchemaVersion_fallsBackToDefaults() {
        val future = """{"schemaVersion":999,"worlds":[]}"""
        val restored = WorldsState.fromJsonString(future)
        // Unknown future version → defaults (one world), not an empty list.
        assertEquals(1, restored.worlds.size)
    }
}
