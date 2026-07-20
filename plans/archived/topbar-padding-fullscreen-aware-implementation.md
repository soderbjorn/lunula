# Topbar leading padding — fullscreen-aware reservation (as-built)

Reference doc for the change made on 2026-05-09 to make the toolkit's
macOS traffic-light reservation on `.dt-topbar` disappear while the
window is in macOS native fullscreen. Companion to
[`topbar-padding-fullscreen-aware.md`](./topbar-padding-fullscreen-aware.md)
(the plan); this file records what actually shipped.

## Symptom

When a host Electron BrowserWindow on macOS uses
`titleBarStyle: "hiddenInset"` (a "custom titlebar"), the OS floats the
red/yellow/green window-control buttons over the upper-left corner of
the web content. The toolkit reserves ~80 px of leading padding on
`.dt-topbar` so the topbar's first interactive item (sidebar toggle,
first tab) doesn't sit underneath the traffic lights.

In macOS **native fullscreen** (the green-button / `Cmd-Ctrl-F` /
`View → Toggle Full Screen` action) the OS hides the traffic-light
cluster entirely. The 80 px reservation then renders as dead
whitespace and the topbar's leading content is visibly off-center.

This is independent from HTML5 fullscreen
(`document.fullscreenElement`) and from `setSimpleFullScreen` —
only macOS native fullscreen hides traffic lights, and that
corresponds to `BrowserWindow.isFullScreen()` being `true` after
the `enter-full-screen` event.

## Mechanism

A third body class — `dt-mac-fullscreen` — joins the existing two
gates. The CSS rule becomes negative on it:

```css
body.dt-electron-mac.dt-custom-titlebar:not(.dt-mac-fullscreen) .dt-topbar {
    padding-left: 80px;
}
```

The new class is owned by the toolkit and toggled by a single
host-facing helper, mirroring the existing `setDtCustomTitleBarBodyClass`
pattern. Source of truth is the **Electron main process**, which is
the only place that reliably observes `BrowserWindow`'s
`enter-full-screen` and `leave-full-screen` events (renderer-side
heuristics like `window.matchMedia` and `screen.availHeight` are not
consistent enough across Electron versions for macOS native fullscreen).

```
BrowserWindow ──"enter-full-screen"──┐
BrowserWindow ──"leave-full-screen"──┤
BrowserWindow ──did-finish-load (initial emit)─┐
                                     ▼         ▼
                       webContents.send("fullscreen-changed", boolean)
                                     │
                       preload.js → contextBridge: onFullscreenChange(handler)
                                     │
                       renderer: setDtMacFullscreenBodyClass(value)
                                     │
                       toolkit: body.classList.toggle("dt-mac-fullscreen")
                                     │
                                  CSS gates
```

The initial-state emit on `did-finish-load` covers macOS relaunching
directly into a restored fullscreen Space — the renderer reflects the
correct state at first paint without waiting for the next user action.

## Files changed

### lunula (`develop/`)

- **`lunula-web/src/jsMain/resources/lunula.css`** — added
  `:not(.dt-mac-fullscreen)` to the rule at line 206 and extended the
  comment block above it to describe the third gate.
- **`lunula-web/src/jsMain/kotlin/se/soderbjorn/lunula/web/LunulaStyles.kt`**
  — new exported function `setDtMacFullscreenBodyClass(enabled: Boolean)`
  alongside the existing `setDtCustomTitleBarBodyClass`. KDoc on the
  existing setter updated with `@see` cross-reference; the comment in
  `injectLunulaStyles` now mentions the new helper as the
  fullscreen-suppression hook.

### termtastic (`develop/`)

- **`electron-main/.../ElectronExternals.kt`** — added `fun isFullScreen(): Boolean`
  to the `BrowserWindow` external.
- **`electron-main/.../ElectronMain.kt`** — `createWindow` now attaches
  `enter-full-screen` and `leave-full-screen` listeners that call
  `w.webContents.send("fullscreen-changed", <bool>)`, plus a
  `did-finish-load` listener for the initial emit. Listeners are
  attached on every `createWindow()` call because the
  `set-custom-title-bar` IPC handler tears down and recreates the
  BrowserWindow.
- **`electron/preload.js`** — added `electronApi.onFullscreenChange(handler)`
  exposing the IPC channel; returns an unsubscribe function.
- **`web/src/jsMain/kotlin/se/soderbjorn/termtastic/main.kt`** —
  imports `setDtMacFullscreenBodyClass`; subscribes inside the
  existing `if (isElectronClient) { ... }` block right after the
  custom-titlebar reconciliation.

### notegrow (`develop/`)

- **`electron-main/.../ElectronExternals.kt`** — added `fun isFullScreen(): Boolean`
  to the `BrowserWindow` external.
- **`electron-main/.../ElectronMain.kt`** — `createWindow` attaches the
  same listener trio as termtastic, sending on the same
  `"fullscreen-changed"` IPC channel.
- **`electron/preload.js`** — added `noteApi.onFullscreenChange(cb)`.
- **`web/src/jsMain/kotlin/se/soderbjorn/notegrow/Main.kt`** — new
  `wireMacFullscreenBodyClass()` private function called from `main()`
  immediately after `tagBodyForElectronMac()`. Subscribes via
  `window.noteApi.onFullscreenChange` and forwards each value to
  `setDtMacFullscreenBodyClass`.

## Truth table

| Scenario                          | dt-electron-mac | dt-custom-titlebar | dt-mac-fullscreen | padding |
|-----------------------------------|:-:|:-:|:-:|:-:|
| Plain browser                     | ✗ | ✗ | ✗ | none |
| Linux/Windows Electron            | ✗ | any | any | none |
| macOS, native frame               | ✓ | ✗ | ✗/✓ | none |
| macOS, hiddenInset, windowed      | ✓ | ✓ | ✗ | 80 px |
| macOS, hiddenInset, fullscreen    | ✓ | ✓ | ✓ | none |

## Backwards compatibility

None required. Hosts that never call `setDtMacFullscreenBodyClass` —
including any third-party site embedding the toolkit CSS today — never
set `dt-mac-fullscreen`, so `:not(.dt-mac-fullscreen)` always matches
and the rule keeps its prior behaviour exactly. The change is additive:
new helper, new optional class, no removed surface, no shape change to
existing exports.

## Deferred / out of scope

- **Demo Electron wiring.** The toolkit's `demo/` Electron module does
  not currently set `titleBarStyle = "hiddenInset"` and does not call
  `setDtCustomTitleBarBodyClass`, so wiring fullscreen alone there
  wouldn't exercise the padding rule end-to-end. Termtastic and
  notegrow cover the full code path.
- **HTML5 fullscreen** (`element.requestFullscreen()`) is not relevant —
  macOS only hides traffic lights for native fullscreen, and HTML5
  fullscreen does not toggle `BrowserWindow.isFullScreen()`. We do
  not listen for `fullscreenchange` on `document`.
- **Auto-detection without an IPC bridge.** Considered using
  `window.matchMedia('(display-mode: fullscreen)')` so apps wouldn't
  need to wire IPC, but its behaviour across Electron + macOS native
  fullscreen is not consistent enough to rely on. The IPC handshake
  matches the existing `setDtCustomTitleBarBodyClass` pattern.
- **Persisting fullscreen state.** macOS already restores fullscreen
  Spaces across launches; the `did-finish-load` initial emit reflects
  whatever the OS has already put the window into.
- **Per-app variants of the chrome class.** No `.dt-chrome-classic` or
  app-side override; the rule lives in toolkit CSS only, per the
  one-toolkit-look guidance.

## Verification

Compilation: `./gradlew :web:compileKotlinJs :electron-main:compileKotlinJs`
clean in all three projects (`lunula/develop`,
`termtastic/develop`, `notegrow/develop`).

**Manual verification** — required on macOS, not done from the
implementation environment. For each of termtastic and notegrow,
package the Electron build and verify:

1. Windowed, custom titlebar on, traffic lights visible — topbar
   leading content sits ~80 px from the left edge; body has
   `dt-electron-mac` + `dt-custom-titlebar`, no `dt-mac-fullscreen`.
2. Enter fullscreen (green button or `Cmd-Ctrl-F`) — traffic lights
   disappear; topbar slides flush left; body gains `dt-mac-fullscreen`.
3. Leave fullscreen — padding returns; `dt-mac-fullscreen` removed.
4. Boot directly into fullscreen (Cmd-Ctrl-F, quit, relaunch) — first
   paint has no padding; `dt-mac-fullscreen` present from the
   `did-finish-load` initial emit.
5. **Termtastic only** — toggle "themed titlebar" off and on while
   fullscreen and while windowed; the BrowserWindow recreate path
   re-attaches the listeners and the IPC fires correctly on the new
   `webContents`.
6. Non-mac Electron (Linux build): `dt-electron-mac` absent, rule
   never matches, no behavioural change.
7. Plain browser: `electronApi` / `noteApi` undefined, wiring no-ops.
