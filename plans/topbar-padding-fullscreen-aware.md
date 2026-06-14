# Topbar leading padding — make traffic-light reservation fullscreen-aware

## Background

The toolkit reserves ~80 px of left padding on `.dt-topbar` so the macOS
traffic-light cluster (red / yellow / green window-control buttons) does
not overlap the topbar's leading content (sidebar toggle, first tab) when
the host window is using `titleBarStyle: "hiddenInset"` (a "custom
titlebar"). The reservation is gated on two body classes:

- `dt-electron-mac` — auto-applied by the toolkit when running inside an
  Electron renderer on macOS
  (`toolkit-web/.../DarknessToolkitStyles.kt:autoApplyElectronMacBodyClass`).
- `dt-custom-titlebar` — host opts in via
  `setDtCustomTitleBarBodyClass(enabled)`
  (`toolkit-web/.../DarknessToolkitStyles.kt:79`) whenever the current
  `BrowserWindow` is using `hiddenInset`.

CSS rule today (`toolkit-web/src/jsMain/resources/darkness-toolkit.css:206`):

```css
body.dt-electron-mac.dt-custom-titlebar .dt-topbar {
    padding-left: 80px;
}
```

Both apps already wire this:

- Notegrow (`web/.../Main.kt:53` `tagBodyForElectronMac`) sets both
  classes unconditionally — its main process always opens with
  `hiddenInset`.
- Termtastic (`web/.../main.kt:425`) toggles `dt-custom-titlebar` from
  the settings flow as the user flips the "themed titlebar" preference;
  its main process recreates the window when the value flips
  (`electron-main/.../ElectronMain.kt:551`).

## The hole

When the macOS window enters **native fullscreen** (the green-button /
Cmd-Ctrl-F / `View → Toggle Full Screen` action — what Electron calls
`enter-full-screen`), macOS hides the traffic-light cluster entirely.
The 80 px reservation then renders as **dead whitespace** on the left of
the topbar. Looks wrong (off-center first tab, sidebar toggle pushed
right) and there's nothing left to collide with, so the padding should
disappear for the duration of the fullscreen state.

Note: this is independent from HTML5 fullscreen
(`document.fullscreenElement`) and from "kiosk" / `setSimpleFullScreen`.
We're concerned with macOS native fullscreen — the only mode the OS
hides traffic lights in — which on Electron corresponds to
`BrowserWindow.isFullScreen()` being `true` after the
`enter-full-screen` event.

## Goal

Add fullscreen-awareness to the existing reservation rule with minimal
new surface area. The padding should be present only when **all** of:

1. running in Electron on macOS, AND
2. the window currently uses `titleBarStyle: "hiddenInset"`, AND
3. the window is **not** in native fullscreen.

Conditions (1) and (2) are already represented as body classes. We add a
third: `dt-mac-fullscreen`, owned by the toolkit, toggled by a single
host-facing helper (mirroring the `setDtCustomTitleBarBodyClass`
pattern). The CSS rule becomes negative on that class.

## Detection — chosen approach

Source of truth is the **Electron main process**, which receives
`enter-full-screen` and `leave-full-screen` events from
`BrowserWindow`. Renderer-side detection (`window.matchMedia`,
`screen.availHeight`, resize heuristics) is unreliable for macOS native
fullscreen across Electron versions and is rejected.

Flow:

```
BrowserWindow ──"enter-full-screen"──┐
BrowserWindow ──"leave-full-screen"──┤
                                     ▼
                       main process IPC: "fullscreen-changed"
                                     │
                       preload.js → contextBridge: onFullscreenChange(handler)
                                     │
                       renderer: setDtMacFullscreenBodyClass(value)
                                     │
                       toolkit: body.classList.toggle("dt-mac-fullscreen")
                                     │
                                  CSS gates
```

Initial state (window construction / recreation): the main process
reads `BrowserWindow.isFullScreen()` once and emits the same IPC so the
renderer doesn't have to wait for the next user action to converge.

## Toolkit changes — `darkness-toolkit/develop`

### 1. CSS — `toolkit-web/src/jsMain/resources/darkness-toolkit.css`

Replace the rule at line 206:

```css
body.dt-electron-mac.dt-custom-titlebar:not(.dt-mac-fullscreen) .dt-topbar {
    padding-left: 80px;
}
```

Update the documentation block immediately above (lines 190–205) to
list the third gate and explain why fullscreen drops the reservation
(macOS hides traffic lights in native fullscreen).

### 2. Helper — `toolkit-web/.../DarknessToolkitStyles.kt`

Add a new exported function next to `setDtCustomTitleBarBodyClass`:

```kotlin
/**
 * Toggle the `dt-mac-fullscreen` body class.
 *
 * Apps call this whenever their Electron BrowserWindow's macOS native
 * fullscreen state changes — i.e. on `enter-full-screen`
 * (`enabled = true`) and `leave-full-screen` (`enabled = false`)
 * BrowserWindow events, plus once at window construction so the initial
 * state is correct.
 *
 * Combined with `dt-electron-mac` and `dt-custom-titlebar`, the toolkit's
 * stylesheet drops the ~80 px traffic-light reservation on `.dt-topbar`
 * while the window is in fullscreen — macOS hides the traffic-light
 * cluster in that state, so the reservation is dead whitespace.
 *
 * Idempotent — safe to call repeatedly with the same value.
 *
 * @param enabled `true` when the host window is currently in macOS
 *   native fullscreen; `false` otherwise.
 */
fun setDtMacFullscreenBodyClass(enabled: Boolean) { ... }
```

KDoc additions to keep the surface coherent:

- Update `setDtCustomTitleBarBodyClass`'s KDoc to cross-reference
  `setDtMacFullscreenBodyClass` via `@see`.
- Update the file-level header comment to mention the new class.

### 3. Demo — `demo/electron-main/.../ElectronMain.kt` and `demo/electron/preload.js` (if separate)

Wire the same IPC path as the apps so the demo exercises the new code
end-to-end. This catches accidental breakage when the toolkit ships a
new minor version.

## Termtastic changes — `termtastic/develop`

Termtastic recreates its `BrowserWindow` whenever
`titleBarStyle` flips, so listeners must be attached on **every**
window construction, not once globally.

### 1. Main process — `electron-main/src/jsMain/kotlin/se/soderbjorn/termtastic/electron/ElectronMain.kt`

Wherever the `BrowserWindow` is created (the same path that sets
`opts.titleBarStyle` at line 551):

- After the window is constructed, attach listeners:
  - `win.on("enter-full-screen") { send IPC "fullscreen-changed" with true }`
  - `win.on("leave-full-screen") { send IPC "fullscreen-changed" with false }`
- Once `did-finish-load` fires (or equivalent existing hook used for
  the title-bar IPC), send the same IPC with the current
  `win.isFullScreen()` value so the renderer reflects the boot state.
- On window destroy, allow the listeners to be GC'd with the window
  (no explicit removal needed — they live on the same `BrowserWindow`).

### 2. Preload — `electron/preload.js`

Add to the `electronApi` bridge alongside the existing chrome-theming
methods:

```js
/**
 * Subscribes to native macOS fullscreen state changes on the current
 * BrowserWindow. The handler receives `true` on enter-full-screen and
 * `false` on leave-full-screen, plus once at boot reflecting the window's
 * initial fullscreen state.
 *
 * @param {(enabled: boolean) => void} handler
 * @returns {() => void} Unsubscribe function.
 */
onFullscreenChange: (handler) => {
  const wrapped = (_event, enabled) => handler(enabled === true);
  ipcRenderer.on("fullscreen-changed", wrapped);
  return () => ipcRenderer.removeListener("fullscreen-changed", wrapped);
},
```

### 3. Renderer — `web/src/jsMain/kotlin/se/soderbjorn/termtastic/main.kt`

In the same `if (isElectronClient) { ... }` block that sets up the
custom-titlebar reconciliation (around line 411), add a one-shot wiring:

```kotlin
val electronApi = window.asDynamic().electronApi
if (electronApi?.onFullscreenChange != null) {
    electronApi.onFullscreenChange({ enabled: Boolean ->
        setDtMacFullscreenBodyClass(enabled)
    })
}
```

No persistence. Fullscreen state is OS-volatile; we don't try to
restore it across restarts.

## Notegrow changes — `notegrow/develop`

Notegrow always uses `hiddenInset`, so the win is even more visible
(the dead padding shows in fullscreen on **every** session).

### 1. Main process — `electron-main/src/jsMain/kotlin/se/soderbjorn/notegrow/electron/ElectronMain.kt` (or equivalent)

Same listeners as termtastic: `enter-full-screen`,
`leave-full-screen`, plus an initial `isFullScreen()` emit after
`did-finish-load`.

### 2. Preload — `electron/preload.js`

Add the same `onFullscreenChange` bridge as termtastic. (If notegrow's
preload doesn't already expose `electronApi`, add the contextBridge
object with this single method.)

### 3. Renderer — `web/src/jsMain/kotlin/se/soderbjorn/notegrow/Main.kt`

Where `tagBodyForElectronMac()` runs (line 53), add a follow-up that
wires the toolkit helper to the IPC bridge — same shape as termtastic.
Keep `tagBodyForElectronMac()` unconditionally setting
`dt-custom-titlebar` (notegrow's behavior is unchanged); only the
fullscreen class is event-driven.

## Manual verification

For each app (termtastic and notegrow), package the Electron build and
verify on macOS:

1. **Windowed, custom titlebar on, traffic lights visible.**
   Topbar leading content sits ~80 px from the left edge — first tab /
   sidebar toggle clear of the traffic lights. `body` carries
   `dt-electron-mac` + `dt-custom-titlebar`, no `dt-mac-fullscreen`.
2. **Enter fullscreen** (green button or `Cmd-Ctrl-F`). Traffic lights
   disappear; topbar leading content slides flush to the left edge.
   `body` gains `dt-mac-fullscreen`. No layout jump beyond the
   intentional 80 px shift.
3. **Leave fullscreen.** Padding returns; topbar matches state (1).
   `dt-mac-fullscreen` is removed.
4. **Boot directly into fullscreen** (Cmd-Ctrl-F, quit, relaunch — macOS
   restores the fullscreen space). On first paint, no padding;
   `dt-mac-fullscreen` is present from the initial-state emit.
5. **Termtastic only — toggle "themed titlebar" off** while fullscreen
   and again while windowed. The new class behaves correctly under
   `BrowserWindow` recreation: listeners are reattached to the new
   window and the IPC fires once after `did-finish-load`.
6. **Non-mac Electron** (Linux build, smoke check): no
   `dt-electron-mac` present, the CSS rule never matches, no behavioral
   change. `dt-mac-fullscreen` may or may not be set (harmless).
7. **Plain browser** (web build, no Electron): `electronApi` undefined,
   no wiring runs, rule never matches.

DOM inspector check during each step: the three relevant body classes
should match the table.

| Scenario                          | electron-mac | custom-titlebar | mac-fullscreen | padding |
|-----------------------------------|:-:|:-:|:-:|:-:|
| macOS, native frame               | ✓ | ✗ | ✗/✓ | none   |
| macOS, hiddenInset, windowed      | ✓ | ✓ | ✗ | 80 px  |
| macOS, hiddenInset, fullscreen    | ✓ | ✓ | ✓ | none   |
| Linux/Windows Electron            | ✗ | any | any | none |
| Plain browser                     | ✗ | ✗ | ✗ | none |

## Out of scope / explicit non-goals

- **HTML5 fullscreen** (`element.requestFullscreen()`). Not relevant —
  macOS only hides traffic lights for native fullscreen, and HTML5
  fullscreen does not toggle `BrowserWindow.isFullScreen()`. We don't
  listen for `fullscreenchange` on `document`.
- **Auto-detection without an IPC bridge.** Considered using
  `window.matchMedia('(display-mode: fullscreen)')` to remove the need
  for app wiring, but its behavior across Electron + macOS native
  fullscreen is not consistent enough to rely on. The IPC handshake
  matches the existing `setDtCustomTitleBarBodyClass` pattern and is
  cheap to wire per-app (one IPC channel, one preload method, one
  renderer subscription).
- **Per-app variants of the chrome class.** Per the
  one-toolkit-look guidance, no `.dt-chrome-classic` or app-side
  override; the rule lives in toolkit CSS only.
- **Persisting fullscreen state.** macOS already restores fullscreen
  spaces across launches; the renderer just reflects whatever the OS
  has put the window into.
- **No backwards-compat shim.** Existing sites embedding the toolkit
  that don't call `setDtMacFullscreenBodyClass` simply never set the
  new class — the `:not(.dt-mac-fullscreen)` clause matches and the
  current behavior is preserved exactly.

## Execution order

The toolkit change is independent and ships first; the app changes are
no-ops against the old toolkit (the helper just doesn't exist) and
become live once they pick up the new toolkit version.

1. **darkness-toolkit:** CSS update + `setDtMacFullscreenBodyClass` +
   demo wiring + bump toolkit version. Single commit.
2. **termtastic:** bump toolkit dep → main-process listeners →
   preload bridge → renderer wiring. Single commit.
3. **notegrow:** bump toolkit dep → main-process listeners →
   preload bridge → renderer wiring. Single commit.
4. Manual verification per the table above on each app.

No follow-ups expected; the change is self-contained per app and
fully reversible by removing the new class wiring (the CSS rule
collapses back to its current behaviour when `dt-mac-fullscreen` is
never set).
