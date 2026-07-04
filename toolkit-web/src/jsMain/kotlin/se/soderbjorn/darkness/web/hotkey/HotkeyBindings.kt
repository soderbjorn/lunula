/*
 * HotkeyBindings.kt (jsMain)
 *
 * Action-level hotkey binding store: the layer between the raw
 * chord→callback [HotkeyRegistry] and user-facing configurability.
 *
 * The registry binds *chords*; this file binds *actions*. Toolkit
 * components (LayoutRenderer's pane navigation, TabBar's tab cycling)
 * register a [HotkeyActionSpec] — a stable action id, a human label and
 * the default chords — together with a handler. [HotkeyBindings] then
 * resolves the *effective* chords for each action: the user's custom
 * chords when present (loaded from the host's persisted
 * `PersistKeys.HOTKEY_BINDINGS` JSON), otherwise the defaults — and keeps
 * [HotkeyRegistry] in sync as either side changes.
 *
 * Custom bindings are all-or-nothing per action: when an action id is
 * present in the custom map, its chord list fully replaces the defaults
 * (so removing a default chord is expressible); when absent, the defaults
 * apply. "Reset to defaults" simply removes the action's entry.
 *
 * Persistence is host-owned: the web shell (`mountAppShell`) reads the
 * JSON blob at mount via the app's `Persister` and installs [onPersist]
 * so edits round-trip back through the same store. Apps that receive
 * live settings pushes (e.g. termtastic's server-managed UI settings)
 * call [applyCustomBindingsJson] again when a push lands.
 *
 * commonMain rules don't apply here — this is jsMain only.
 *
 * @see HotkeyRegistry
 * @see openHotkeyConfigDialog
 */
package se.soderbjorn.darkness.web.hotkey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Serialize this chord to the compact textual spec used in the persisted
 * bindings JSON: lowercase modifier tokens joined by `+`, key last —
 * e.g. `"ctrl+alt+ArrowLeft"`, `"meta+k"`. The key keeps its
 * `KeyboardEvent.key` form verbatim (single letters lowercase, named
 * keys like `ArrowLeft` / `Enter` as-is, space as the literal `" "`).
 *
 * Inverse of [parseHotkeySpec]; used by [HotkeyBindings] when writing
 * the custom-bindings blob.
 *
 * @return the chord spec string.
 * @see parseHotkeySpec
 */
fun Hotkey.toSpec(): String {
    val parts = mutableListOf<String>()
    if (ctrl) parts += "ctrl"
    if (alt) parts += "alt"
    if (shift) parts += "shift"
    if (meta) parts += "meta"
    parts += key
    return parts.joinToString("+")
}

/**
 * Parse a chord spec produced by [Hotkey.toSpec] back into a [Hotkey].
 *
 * Tolerant of unknown input: returns `null` for blank strings or specs
 * with no key part, so a corrupt persisted entry degrades to "binding
 * dropped" rather than a crash. A trailing `+` (i.e. the key *is* `+`)
 * is handled: the final `+`-separated token is always the key, and
 * every earlier token must be a known modifier name.
 *
 * @param spec the chord spec string (e.g. `"ctrl+alt+ArrowLeft"`).
 * @return the parsed [Hotkey], or `null` when the spec is invalid.
 * @see Hotkey.toSpec
 */
fun parseHotkeySpec(spec: String): Hotkey? {
    if (spec.isBlank()) return null
    // The key is the text after the *last* '+' separator — unless the
    // spec ends with '+', in which case the key itself is "+" (e.g.
    // "ctrl++" is Ctrl + the "+" key, and "+" alone is the bare "+" key).
    val key: String
    val modifierPart: String
    if (spec.endsWith("+")) {
        key = "+"
        // Drop the trailing "+" key and its separator (when present).
        modifierPart = spec.dropLast(1).removeSuffix("+")
    } else {
        val idx = spec.lastIndexOf('+')
        key = if (idx == -1) spec else spec.substring(idx + 1)
        modifierPart = if (idx == -1) "" else spec.substring(0, idx)
    }
    if (key.isEmpty()) return null
    val tokens = if (modifierPart.isEmpty()) emptyList() else modifierPart.split('+')
    var ctrl = false; var alt = false; var shift = false; var meta = false
    for (tok in tokens) {
        when (tok.lowercase()) {
            "ctrl", "control" -> ctrl = true
            "alt", "option", "opt" -> alt = true
            "shift" -> shift = true
            "meta", "cmd", "command", "win", "super" -> meta = true
            else -> return null
        }
    }
    return Hotkey(key = key, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
}

/**
 * Declaration of one user-configurable hotkey action.
 *
 * Registered by toolkit components (and, in principle, host apps) via
 * [HotkeyBindings.registerAction]; consumed by the hotkey-config dialog
 * ([openHotkeyConfigDialog]) and by app-built shortcut reference panes.
 *
 * @property id stable, namespaced action id (e.g.
 *   `"toolkit.pane.focusLeft"`). This is the persistence key — renaming
 *   it orphans users' saved custom bindings for the action.
 * @property label human-readable action name shown in the config dialog
 *   (apps can override per-call — see [openHotkeyConfigDialog]).
 * @property defaults the chords bound when the user has no custom
 *   bindings for this action.
 */
data class HotkeyActionSpec(
    val id: String,
    val label: String,
    val defaults: List<Hotkey>,
)

/** Canonical ids for the toolkit's built-in configurable actions. */
object ToolkitHotkeyIds {
    /** Spatially focus the pane to the left ([StandardHotkeys.PreviousPane]). */
    const val PANE_FOCUS_LEFT: String = "toolkit.pane.focusLeft"
    /** Spatially focus the pane to the right ([StandardHotkeys.NextPane]). */
    const val PANE_FOCUS_RIGHT: String = "toolkit.pane.focusRight"
    /** Spatially focus the pane above ([StandardHotkeys.FocusPaneUp]). */
    const val PANE_FOCUS_UP: String = "toolkit.pane.focusUp"
    /** Spatially focus the pane below ([StandardHotkeys.FocusPaneDown]). */
    const val PANE_FOCUS_DOWN: String = "toolkit.pane.focusDown"
    /**
     * Expand the focused pane one state step — minimized → normal →
     * maximized ([StandardHotkeys.ExpandPane]).
     */
    const val PANE_EXPAND: String = "toolkit.pane.expand"
    /**
     * Collapse the focused pane one state step — maximized → normal →
     * minimized ([StandardHotkeys.CollapsePane]).
     */
    const val PANE_COLLAPSE: String = "toolkit.pane.collapse"
    /** Cycle to the previous tab ([StandardHotkeys.PreviousTab]). */
    const val TAB_PREVIOUS: String = "toolkit.tab.previous"
    /** Cycle to the next tab ([StandardHotkeys.NextTab]). */
    const val TAB_NEXT: String = "toolkit.tab.next"
}

/**
 * Central store of configurable hotkey actions and the user's custom
 * chord overrides. Singleton, mirroring [HotkeyRegistry].
 *
 * Lifecycle: components call [registerAction] (possibly repeatedly —
 * e.g. TabBar re-registers on every render so handlers close over the
 * latest spec); the shell calls [applyCustomBindingsJson] once the
 * persisted blob is available (and again on live settings pushes); the
 * config dialog calls [setCustomChords] on save, which rebinds and
 * fires [onPersist] so the host can write the new blob.
 */
object HotkeyBindings {

    /** Everything known about one registered action. */
    private class ActionRecord(
        var spec: HotkeyActionSpec,
        var handler: () -> Unit,
        /** Chords currently registered in [HotkeyRegistry] for this action. */
        var boundChords: List<Hotkey> = emptyList(),
    )

    /** Registered actions, keyed by action id (insertion-ordered). */
    private val actions = LinkedHashMap<String, ActionRecord>()

    /** The user's custom chord lists, keyed by action id. */
    private var custom: MutableMap<String, List<Hotkey>> = mutableMapOf()

    /**
     * Chords the toolkit binds directly (not via an action) that the
     * config dialog must still treat as taken — e.g. the nine positional
     * tab-switch chords. Maps chord → human label for conflict messages.
     */
    private val fixedChords = LinkedHashMap<Hotkey, String>()

    /**
     * Host-installed persistence hook: called with the serialized
     * custom-bindings JSON after every user edit ([setCustomChords]).
     * Installed by `mountAppShell` to write
     * `PersistKeys.HOTKEY_BINDINGS` through the app's `Persister`.
     */
    var onPersist: ((String) -> Unit)? = null

    /**
     * Register (or re-register) a configurable action and bind its
     * effective chords in [HotkeyRegistry].
     *
     * Called by toolkit components at their existing hotkey-install
     * points (LayoutRenderer init, TabBar render). Re-registering the
     * same id replaces the handler and rebinds — [HotkeyRegistry]'s
     * replace-on-register semantics make this cheap and idempotent.
     *
     * @param spec the action declaration (id, label, default chords).
     * @param handler invoked when any of the action's effective chords fires.
     */
    fun registerAction(spec: HotkeyActionSpec, handler: () -> Unit) {
        val record = actions.getOrPut(spec.id) { ActionRecord(spec, handler) }
        record.spec = spec
        record.handler = handler
        rebind(record)
    }

    /**
     * Record a chord the toolkit binds directly (outside the action
     * system) so the config dialog can flag it as a conflict. No-op on
     * dispatch — the actual binding stays wherever it is today.
     *
     * @param chord the reserved chord.
     * @param label human label used in the dialog's conflict message.
     */
    fun noteFixedChord(chord: Hotkey, label: String) {
        fixedChords[chord] = label
    }

    /**
     * The chords currently in effect for [actionId]: the custom list if
     * the user has one, otherwise the registered defaults.
     *
     * @param actionId the action to resolve.
     * @return the effective chord list (empty when the action is unknown
     *   or the user removed every chord).
     */
    fun effectiveChords(actionId: String): List<Hotkey> =
        custom[actionId] ?: actions[actionId]?.spec?.defaults ?: emptyList()

    /**
     * The registered default chords for [actionId] (empty when unknown).
     *
     * @param actionId the action to resolve.
     * @return the default chord list.
     */
    fun defaultChords(actionId: String): List<Hotkey> =
        actions[actionId]?.spec?.defaults ?: emptyList()

    /**
     * Whether the user has custom bindings for [actionId] (i.e. an entry
     * in the custom map — even an empty list counts, since that means
     * "explicitly unbound").
     *
     * @param actionId the action to test.
     * @return `true` when custom bindings exist.
     */
    fun isCustomized(actionId: String): Boolean = custom.containsKey(actionId)

    /**
     * The registered label for [actionId], or `null` when unknown.
     * Used as the config dialog's default title.
     *
     * @param actionId the action to look up.
     * @return the label, or `null`.
     */
    fun actionLabel(actionId: String): String? = actions[actionId]?.spec?.label

    /**
     * Find what [chord] is already bound to, other than [excludeActionId].
     * Consulted by the config dialog's capture validation so the user
     * can't record a chord that would silently steal another action's key.
     *
     * @param chord the candidate chord.
     * @param excludeActionId the action being edited (its own chords are
     *   not conflicts).
     * @return the human label of the conflicting action / fixed chord,
     *   or `null` when the chord is free.
     */
    fun conflictLabel(chord: Hotkey, excludeActionId: String): String? {
        for ((id, record) in actions) {
            if (id == excludeActionId) continue
            if (chord in (custom[id] ?: record.spec.defaults)) return record.spec.label
        }
        return fixedChords[chord]
    }

    /**
     * Replace the entire custom-bindings map from its persisted JSON
     * form and rebind every registered action.
     *
     * Called by `mountAppShell` after reading
     * `PersistKeys.HOTKEY_BINDINGS`, and by hosts when a live settings
     * push carries a new value. Passing `null` (key absent) clears all
     * customizations. Unparseable JSON or entries are dropped silently —
     * a corrupt blob degrades to defaults instead of crashing the shell.
     *
     * Does **not** fire [onPersist] (this is the inbound direction).
     *
     * @param json the persisted blob (`{actionId: ["spec", …], …}`), or
     *   `null` to reset to defaults.
     */
    fun applyCustomBindingsJson(json: String?) {
        val parsed = mutableMapOf<String, List<Hotkey>>()
        if (json != null && json.isNotBlank()) {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            if (obj != null) {
                for ((actionId, element) in obj) {
                    val arr = element as? JsonArray ?: continue
                    parsed[actionId] = arr.mapNotNull { item ->
                        (item as? JsonPrimitive)?.takeIf { it.isString }
                            ?.let { parseHotkeySpec(it.content) }
                    }
                }
            }
        }
        if (parsed == custom) return
        custom = parsed
        rebindAll()
    }

    /**
     * Set (or clear) the custom chords for one action — the config
     * dialog's save path. Rebinds the affected registry entries and
     * fires [onPersist] with the updated blob so the host persists it.
     *
     * @param actionId the action being edited.
     * @param chords the new effective chord list, or `null` to reset the
     *   action to its registered defaults. An empty list is legal and
     *   means "explicitly unbound".
     */
    fun setCustomChords(actionId: String, chords: List<Hotkey>?) {
        if (chords == null) custom.remove(actionId) else custom[actionId] = chords
        rebindAll()
        onPersist?.invoke(customBindingsJson())
    }

    /**
     * Serialize the current custom-bindings map to its persisted JSON
     * form (the exact shape [applyCustomBindingsJson] accepts).
     *
     * @return the JSON blob (`"{}"` when nothing is customized).
     */
    fun customBindingsJson(): String {
        val obj = JsonObject(custom.mapValues { (_, chords) ->
            JsonArray(chords.map { JsonPrimitive(it.toSpec()) })
        })
        return obj.toString()
    }

    /** Test-only full reset (actions, customs, fixed chords, hook). */
    internal fun clearForTest() {
        for (record in actions.values) {
            for (chord in record.boundChords) HotkeyRegistry.unregister(chord)
        }
        actions.clear()
        custom.clear()
        fixedChords.clear()
        onPersist = null
    }

    /** Rebind a single action's effective chords into [HotkeyRegistry]. */
    private fun rebind(record: ActionRecord) {
        val effective = custom[record.spec.id] ?: record.spec.defaults
        for (chord in record.boundChords) {
            if (chord !in effective) HotkeyRegistry.unregister(chord)
        }
        for (chord in effective) {
            HotkeyRegistry.register(chord) { record.handler() }
        }
        record.boundChords = effective
    }

    /**
     * Rebind every registered action after a bulk custom-map change.
     * Two-phase (unregister everything, then register everything) so a
     * chord moving from one action to another within a single apply
     * can't be clobbered by iteration order.
     */
    private fun rebindAll() {
        for (record in actions.values) {
            for (chord in record.boundChords) HotkeyRegistry.unregister(chord)
            record.boundChords = emptyList()
        }
        for (record in actions.values) {
            val effective = custom[record.spec.id] ?: record.spec.defaults
            for (chord in effective) {
                HotkeyRegistry.register(chord) { record.handler() }
            }
            record.boundChords = effective
        }
    }
}

/**
 * Heuristic for chords a real browser reserves for itself (tab / window
 * management, address bar, …), which a page-level `keydown` handler
 * cannot reliably intercept. Such chords still work in the Electron
 * desktop shell — the config dialog and shortcut panes surface them as
 * **Mac-only** rather than rejecting them outright.
 *
 * The list is intentionally pragmatic, covering the chords Chromium /
 * Safari / Firefox won't deliver (or where `preventDefault` is ignored):
 * primary-modifier (Cmd on macOS, Ctrl elsewhere) + `t/n/w/q` (with or
 * without Shift), primary + digit (browser tab switching — the Alt
 * variant is deliverable, which is why the toolkit's own web tab-switch
 * chord adds Alt), and primary + `l` (address bar).
 *
 * @param chord the chord to classify.
 * @return `true` when the chord won't work in a plain browser tab.
 * @see isElectronPlatform
 */
fun isBrowserReservedChord(chord: Hotkey): Boolean {
    val primary = if (isMacPlatform()) chord.meta else chord.ctrl
    if (!primary) return false
    // The secondary modifier (Ctrl on mac / Meta on win) or Alt generally
    // frees the chord for the page — except we keep Cmd+Opt+digit off the
    // reserved list explicitly below.
    val key = chord.key.lowercase()
    val digit = key.length == 1 && key[0] in '0'..'9'
    if (digit && !chord.alt) return true
    if (chord.alt) return false
    return key in BROWSER_RESERVED_PRIMARY_KEYS
}

/**
 * Key values (lowercase) a browser reserves when combined with the
 * platform's primary modifier (± Shift). See [isBrowserReservedChord].
 */
private val BROWSER_RESERVED_PRIMARY_KEYS = setOf("t", "n", "w", "q", "l")
