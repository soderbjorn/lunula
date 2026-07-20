/*
 * HostThemePushSurvivesManagerTest.kt (jsTest)
 *
 * Regression test for a snapshot pushed through [AppShellHandle.setThemeSnapshot]
 * being silently reverted by the toolkit's own theme manager.
 *
 * The push path was written for apps that own theme resolution outside the
 * toolkit (termtastic), which supply an [AppShellSpec.settingsHost] and keep
 * their state there. An app that supplies *no* host — one that leaves the
 * toolkit's theme manager in charge and pushes only occasionally, e.g. because
 * the signed-in user changed under a shell that was mounted once — hit the
 * other half of that assumption: the push updated the painted snapshot but not
 * `themeState`, which for a host-less app *is* the theme manager's state and is
 * what `onThemeManagerChanged` rebuilds the snapshot from. So the push painted
 * correctly and then vanished the moment the user picked a theme, taking the
 * pushed appearance with it.
 *
 * The sequence below is exactly that, through real DOM: mount, push a snapshot,
 * open the theme manager, click a theme card. What the shell persists
 * afterwards must still carry the pushed appearance.
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
private class PushTestPersister : Persister {
    val store = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

private fun HTMLElement.clickIt() {
    dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
}

class HostThemePushSurvivesManagerTest {

    @Test
    fun pushedSnapshotSurvivesAThemeManagerPick() = GlobalScope.promise {
        val persister = PushTestPersister()
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        try {
            val handle = mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "theme-push-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = TabSource(subscribe = { }, onSelect = { }),
                    // No settingsHost: the toolkit's own theme manager is in
                    // charge, which is the configuration this is about.
                ),
            )

            // The mount reads the persister from an async init job; wait until
            // the topbar it builds afterwards exists.
            awaitCondition("topbar rendered") {
                root.querySelector("button[title=\"Theme manager\"]") != null
            }

            // The push an app makes when the person in front of the browser
            // changes. Light is deliberately not the default, so a revert is
            // visible rather than coincidentally correct.
            handle.setThemeSnapshot(
                ThemeSnapshotV2(
                    darkThemeName = "Dracula",
                    lightThemeName = "Lunamux Light",
                    appearance = Appearance.Light,
                ),
            )

            (root.querySelector("button[title=\"Theme manager\"]") as HTMLElement).clickIt()
            awaitCondition("theme manager open") {
                document.querySelector(".dt-theme-card") != null
            }

            // Pick a theme, which is what routes through the default host and
            // rebuilds the snapshot from `themeState` — the step that used to
            // throw the push away.
            val card = document.querySelector(".dt-theme-card") as? HTMLElement
            assertNotNull(card, "the theme manager rendered no theme cards")
            val picked = (card.querySelector(".dt-theme-card-name") as? HTMLElement)?.textContent?.trim()
            assertNotNull(picked, "the theme card carries no name to click for")
            card.clickIt()

            awaitCondition("selection persisted") {
                persister.store[PersistKeys.THEME_V2_SELECTION]
                    ?.contains("\"appearance\"") == true
            }

            val persisted = ThemeSnapshotV2.fromStrings(
                selectionJson = persister.store[PersistKeys.THEME_V2_SELECTION],
                customThemesJson = persister.store[PersistKeys.THEME_V2_CUSTOM],
            )
            assertEquals(
                Appearance.Light,
                persisted.appearance,
                "the theme manager rebuilt from stale state and reverted the pushed appearance",
            )
            // The pick itself must still have taken effect — otherwise this
            // would pass just as well against a manager that does nothing. The
            // *light* slot, because the card fills whichever slot the active
            // appearance names, and the push above made that Light.
            assertEquals(
                picked,
                persisted.lightThemeName,
                "the light slot does not hold the theme whose card was clicked",
            )
        } finally {
            root.remove()
        }
    }
}
