/*
 * HotkeyRegistry.kt (jsMain)
 *
 * Window-level registry of keyboard chord → action bindings. Owns a
 * single `keydown` listener attached to the JS `window` (lazy on first
 * `register`) and dispatches matching events to the registered action.
 *
 * Replace semantics: registering the same [Hotkey] twice replaces the
 * prior action. This is the right invariant for the toolkit's two
 * built-in consumers — [LayoutRenderer]'s pane-nav binding (re-installed
 * each time a new renderer is constructed for the active tab) and
 * [renderTabBar]'s tab-nav binding (re-installed each render so the
 * action closes over the current spec). It also lets apps overwrite or
 * augment the toolkit's defaults from their own boot code.
 *
 * Scope: a [HotkeyScope] lets a binding opt out of firing while the
 * focused element is e.g. a text input. v1 ships with `Global` only —
 * the chosen toolkit chords don't conflict with any text-editing
 * shortcut on macOS or Windows/Linux, so scoping isn't needed yet. The
 * enum is in place so future bindings (e.g. plain-arrow tab cycling)
 * can opt into a stricter scope without an API break.
 *
 * Capture phase + `preventDefault`: the listener runs in capture phase
 * and only calls `preventDefault()` when an action actually fires. This
 * lets us intercept events before per-component listeners (so we beat
 * inline pane / dialog handlers to the punch on chords they didn't
 * know about) without swallowing unrelated events.
 *
 * commonMain rules don't apply here — this is jsMain only.
 */
package se.soderbjorn.lunula.web.hotkey

import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Where a hotkey is allowed to fire. Reserved for future expansion —
 * v1 only uses [Global].
 */
enum class HotkeyScope {
    /** Fire regardless of focus. */
    Global,
}

/** Internal binding record kept inside [HotkeyRegistry]'s map. */
private data class Binding(val scope: HotkeyScope, val action: () -> Unit)

/**
 * App-wide hotkey registry. Singleton because the underlying `window`
 * listener is also a singleton — multiple registries would just compete
 * for the same events.
 *
 * Typical wiring lives inside the toolkit ([LayoutRenderer.init] for
 * pane-nav, [renderTabBar] for tab-nav). Apps can also call [register]
 * directly to bind their own chords, or [unregister] to remove a
 * toolkit default.
 */
object HotkeyRegistry {
    private val bindings: MutableMap<Hotkey, Binding> = mutableMapOf()
    private var listenerAttached: Boolean = false

    /**
     * While `true`, the registry's window listener ignores every event —
     * no action fires and no default is prevented.
     *
     * Set by the hotkey-config dialog ([openHotkeyConfigDialog]) for its
     * whole lifetime so (a) recording a chord that happens to match an
     * existing binding doesn't *run* that binding underneath the modal,
     * and (b) navigation chords can't rearrange the app while a modal is
     * up. Always restore to `false` when done.
     */
    var suppressed: Boolean = false

    /**
     * Bind [action] to [hotkey]. If a prior binding exists for the same
     * chord, it's replaced; the previous action is dropped.
     *
     * @param hotkey the chord to bind.
     * @param scope  reserved; pass [HotkeyScope.Global] (the default).
     * @param action invoked when the chord fires. Runs synchronously in
     *   the keydown event handler.
     */
    fun register(
        hotkey: Hotkey,
        scope: HotkeyScope = HotkeyScope.Global,
        action: () -> Unit,
    ) {
        bindings[hotkey] = Binding(scope, action)
        ensureListener()
    }

    /**
     * Remove the binding for [hotkey], if any. No-op if no binding
     * exists. The window listener stays attached (cheap to leave in
     * place; an app rarely tears down the registry mid-session).
     */
    fun unregister(hotkey: Hotkey) {
        bindings.remove(hotkey)
    }

    /** Drop every binding. Test-only; no production caller. */
    internal fun clearForTest() {
        bindings.clear()
    }

    private fun ensureListener() {
        if (listenerAttached) return
        listenerAttached = true
        window.addEventListener("keydown", { ev: Event ->
            val ke = ev as? KeyboardEvent ?: return@addEventListener
            // Recording mode (hotkey-config dialog): stand down entirely.
            if (suppressed) return@addEventListener
            // Modifier-only events (the user pressed Ctrl alone, before
            // adding the rest of the chord) shouldn't even be looked up;
            // their `key` is "Control" / "Alt" / etc. and would never
            // match a real binding anyway, but skipping the map lookup
            // avoids burning a hash on every modifier press.
            if (ke.key in MODIFIER_KEYS) return@addEventListener
            for ((hotkey, binding) in bindings) {
                if (!hotkey.matches(ke)) continue
                // Capture-phase + preventDefault only when we actually
                // act on the event. Unrelated keys flow through to the
                // app's own listeners untouched.
                ke.preventDefault()
                ke.stopPropagation()
                binding.action()
                return@addEventListener
            }
        }, /* capture = */ true)
    }

    private val MODIFIER_KEYS = setOf("Control", "Alt", "Shift", "Meta", "OS")
}
