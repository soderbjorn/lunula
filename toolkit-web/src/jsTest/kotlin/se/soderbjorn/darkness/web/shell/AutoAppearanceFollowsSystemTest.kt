/*
 * AutoAppearanceFollowsSystemTest.kt (jsTest)
 *
 * Regression test for [Appearance.Auto] not actually following the system.
 *
 * Auto means "whatever the OS is doing", and `isDarkActive` answers that by
 * reading `prefers-color-scheme` — but it was only ever read, at paint time,
 * and nothing subscribed to the media query. A tab left open across the
 * switchover (macOS's scheduled Auto Appearance, or a user flipping it by hand)
 * kept painting the slot it had resolved at load until some unrelated rerender
 * happened to repaint it.
 *
 * The media query is faked here rather than driven for real: a headless browser
 * cannot be asked to change the OS colour scheme mid-test, and `window
 * .matchMedia` is the single seam every read goes through — `systemPrefersDark`
 * calls it fresh each time, so swapping it controls both the subscription and
 * every subsequent resolve.
 *
 * Two observables together, because neither is sufficient alone. `color-scheme`
 * is painted onto the shared documentElement, which any shell a sibling test
 * left mounted also writes to; a render pass of *this* shell says the reaction
 * was ours but not what it painted. The pair pins both.
 *
 * @see se.soderbjorn.darkness.web.shell.AppShellMount
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.Persister
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private class AutoTestPersister(seed: ThemeSnapshotV2) : Persister {
    val store = mutableMapOf(
        PersistKeys.THEME_V2_SELECTION to seed.selectionJson(),
        PersistKeys.THEME_V2_CUSTOM to seed.customThemesJson(),
    )
    override suspend fun read(key: String): String? = store[key]
    override suspend fun write(key: String, value: String) { store[key] = value }
}

/**
 * Replaces `window.matchMedia` for the dark-scheme query only, so every other
 * media query the toolkit or the test runner asks about still gets a real
 * answer. Each call hands back a fresh object over shared state, matching how
 * the real API behaves for the shell (which subscribes once) and for
 * `systemPrefersDark` (which re-queries on every paint).
 *
 * @return a control handle with `set(dark)` to flip and notify, and `restore()`.
 */
private fun fakeSystemDarkQuery(): dynamic = js(
    """
    (function () {
        var real = window.matchMedia;
        var state = { dark: false, listeners: [] };
        window.matchMedia = function (q) {
            if (String(q).indexOf('prefers-color-scheme: dark') === -1) {
                return real.call(window, q);
            }
            var mql = {
                media: q,
                addEventListener: function (t, cb) {
                    if (t === 'change') state.listeners.push(cb);
                },
                removeEventListener: function (t, cb) {
                    var i = state.listeners.indexOf(cb);
                    if (i >= 0) state.listeners.splice(i, 1);
                }
            };
            // Defined rather than assigned so `matches` stays live for any
            // holder that keeps the object across a flip, as the real one is.
            Object.defineProperty(mql, 'matches', {
                get: function () { return state.dark; }
            });
            return mql;
        };
        state.set = function (d) {
            state.dark = d;
            state.listeners.slice().forEach(function (cb) {
                cb({ matches: d, media: '(prefers-color-scheme: dark)' });
            });
        };
        state.restore = function () { window.matchMedia = real; };
        return state;
    })()
    """
)

private suspend fun awaitThis(what: String, condition: () -> Boolean) {
    repeat(150) {
        if (condition()) return
        delay(20)
    }
    fail("timed out waiting for: $what")
}

private fun paintedColorScheme(): String =
    (document.documentElement as HTMLElement).style.getPropertyValue("color-scheme")

class AutoAppearanceFollowsSystemTest {

    @Test
    fun theOsSwitchingToDarkRepaintsAnAutoShell() = GlobalScope.promise {
        val system = fakeSystemDarkQuery()
        val persister = AutoTestPersister(
            ThemeSnapshotV2(
                darkThemeName = "Dracula",
                lightThemeName = "Lunamux Light",
                appearance = Appearance.Auto,
            ),
        )
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        // Counts render passes of *this* shell. The painted `color-scheme` sits
        // on the shared documentElement, which any shell a sibling test left
        // mounted also writes to — so on its own it can go dark for reasons
        // that have nothing to do with the subscription under test. Pairing it
        // with a refresh of this shell is what makes the assertion specific.
        var refreshes = 0
        var handle: AppShellHandle? = null
        try {
            handle = mountAppShell(
                AppShellSpec(
                    rootContainer = root,
                    title = "auto-appearance-test",
                    persister = persister,
                    paneContent = { document.createElement("div") as HTMLElement },
                    tabSource = TabSource(subscribe = { }, onSelect = { }),
                    onAfterRefresh = { refreshes++ },
                    // No settingsHost: an app that owns theme resolution watches
                    // the query itself and is deliberately left alone.
                ),
            )

            awaitThis("topbar rendered") {
                root.querySelector("button[title=\"Theme manager\"]") != null
            }
            awaitThis("shell painted the light slot") { paintedColorScheme() == "light" }

            // The OS switches to dark. Nothing else happens — no click, no
            // rerender from any other source.
            val before = refreshes
            system.set(true)

            awaitThis("the shell reacted to the OS change") { refreshes > before }
            assertEquals("dark", paintedColorScheme(), "the shell repainted, but not for the dark slot")

            // Auto is a preference about *following* the system, so following
            // it must not rewrite what was stored — the appearance stays Auto
            // and the slots stay as they were.
            val stored = ThemeSnapshotV2.fromStrings(
                selectionJson = persister.store[PersistKeys.THEME_V2_SELECTION],
                customThemesJson = persister.store[PersistKeys.THEME_V2_CUSTOM],
            )
            assertEquals(Appearance.Auto, stored.appearance, "following the OS overwrote the preference")
            assertEquals("Dracula", stored.darkThemeName, "the dark slot was rewritten")
            assertEquals("Lunamux Light", stored.lightThemeName, "the light slot was rewritten")

            // And back again, so this is a subscription rather than a one-shot.
            val beforeBack = refreshes
            system.set(false)
            awaitThis("the shell reacted a second time") { refreshes > beforeBack }
            assertEquals("light", paintedColorScheme(), "the shell did not follow the OS back to light")
        } finally {
            handle?.dispose()
            system.restore()
            root.remove()
        }
    }
}
