/*
 * AppearanceCycleRebindsThemeCardsTest.kt (jsTest)
 *
 * Regression test for the topbar's Auto/Dark/Light cycle button leaving an open
 * theme manager bound to the *previous* appearance.
 *
 * A theme card fills the slot named by the appearance that was active when the
 * card was built — that binding is captured in the card's click closure. The
 * cycle button called `rerender()`, which rebuilds the right-sidebar slot, but
 * `showThemeManager` is idempotent: it re-appends the panel it already built
 * rather than rebuilding it (that is what keeps scroll position across the
 * unrelated rerenders a busy shell produces). So the cards survived the flip
 * unchanged, still writing the old slot.
 *
 * From the user's side that read as two separate faults, both of them this one:
 * the first click after a flip appeared to do nothing (it filled the slot for
 * the appearance they had just left, which is not the one on screen), and only
 * a second click "worked" — because the first click's own repaint is what
 * rebound the cards. Meanwhile the slot they thought they were setting kept its
 * old theme, so flipping back showed the wrong one.
 *
 * The sequence below is exactly that, through real DOM: mount, open the theme
 * manager, flip the appearance, click one card, once.
 *
 * @see se.soderbjorn.lunula.web.shell.AppShellMount
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** In-memory [Persister], as the other mount tests use. */
private class CycleTestPersister(seed: ThemeSnapshotV2) : Persister {
    val store = mutableMapOf(
        PersistKeys.THEME_V2_SELECTION to seed.selectionJson(),
        PersistKeys.THEME_V2_CUSTOM to seed.customThemesJson(),
    )
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

private suspend fun awaitThat(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

private fun HTMLElement.dispatchClick() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

private fun persistedSnapshot(p: CycleTestPersister) = ThemeSnapshotV2.fromStrings(
    selectionJson = p.store[PersistKeys.THEME_V2_SELECTION],
    customThemesJson = p.store[PersistKeys.THEME_V2_CUSTOM],
)

class AppearanceCycleRebindsThemeCardsTest {

    @Test
    fun aPickAfterTheAppearanceCycleFillsTheSlotNowOnScreen() = GlobalScope.promise {
        // Start in Dark so one press of the cycle button lands on Light — a
        // definite appearance either way, so nothing here depends on what the
        // machine running the test has its OS set to.
        val persister = CycleTestPersister(
            ThemeSnapshotV2(
                darkThemeName = "Dracula",
                lightThemeName = "Lunamux Light",
                appearance = Appearance.Dark,
            ),
        )
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        try {
            mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "appearance-cycle-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = TabSource(subscribe = { }, onSelect = { }),
                    // No settingsHost: the toolkit's own theme manager is in
                    // charge, which is the configuration this is about.
                ),
            )

            awaitThat("topbar rendered") {
                root.querySelector("button[title=\"Theme manager\"]") != null
            }
            awaitThat("seeded snapshot applied") {
                persistedSnapshot(persister).appearance == Appearance.Dark
            }

            // Open the manager first, so its cards are built while the
            // appearance is still Dark. Opening it afterwards would build them
            // against the flipped value and prove nothing.
            (root.querySelector("button[title=\"Theme manager\"]") as HTMLElement).dispatchClick()
            awaitThat("theme manager open") { document.querySelector(".dt-theme-card") != null }

            // Dark → Light. The button cycles Auto → Dark → Light → Auto.
            val cycle = root.querySelector("button[title=\"Appearance: Dark\"]") as? HTMLElement
            assertNotNull(cycle, "no appearance-cycle button reading Dark in the topbar")
            cycle.dispatchClick()
            awaitThat("appearance flipped to Light") {
                persistedSnapshot(persister).appearance == Appearance.Light
            }

            // One click, on a card that is not already in either slot, so the
            // assertion cannot pass by coincidence.
            val cards = document.querySelectorAll(".dt-theme-card")
            var card: HTMLElement? = null
            for (i in 0 until cards.length) {
                val el = cards.item(i) as HTMLElement
                val n = (el.querySelector(".dt-theme-card-name") as? HTMLElement)?.textContent?.trim()
                if (n != null && n != "Dracula" && n != "Lunamux Light") { card = el; break }
            }
            assertNotNull(card, "the theme manager rendered no unassigned theme cards")
            val picked = (card.querySelector(".dt-theme-card-name") as HTMLElement).textContent!!.trim()
            card.dispatchClick()

            awaitThat("the pick was persisted") {
                val s = persistedSnapshot(persister)
                s.lightThemeName == picked || s.darkThemeName == picked
            }

            val after = persistedSnapshot(persister)
            assertEquals(
                picked,
                after.lightThemeName,
                "a single click while in Light mode did not fill the light slot",
            )
            // And it must not have gone anywhere else on the way: the dark slot
            // is what the stale binding used to overwrite.
            assertEquals(
                "Dracula",
                after.darkThemeName,
                "the pick leaked into the dark slot, which is not the one on screen",
            )
            assertEquals(Appearance.Light, after.appearance, "the appearance did not survive the pick")
        } finally {
            root.remove()
        }
    }
}
