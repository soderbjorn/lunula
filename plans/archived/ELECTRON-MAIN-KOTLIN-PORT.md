# Port darkness-demo's Electron main.js to Kotlin/JS (Node target)

## Context

`darkness-demo/develop/electron/main.js` is ~215 lines of JavaScript that runs in Electron's Node.js main process. Most of what it does — per-OS path resolution, atomic tmp+rename writes, JSON I/O for `ui-settings.json` and `layout-state.json` — is already implemented in Kotlin (JVM) inside `darkness-toolkit` (`UiSettingsStore.jvm.kt`). The same logic also exists, with minor variations, in notegrow's and termtastic's `main.js` files. Two parallel implementations of the same persistence code in two languages is fragile: when the JSON shape, path layout, or write semantics change, the JS copies drift.

The ask: have the Electron main process logic written in Kotlin instead of JavaScript so it can share types and helpers with the rest of the codebase, and (eventually) be reused by notegrow/termtastic. Scope for this plan: **darkness-demo only**, as a proof-of-concept; the resulting pattern can be lifted to the toolkit and adopted by the other apps in a follow-up. The user picked the "tiny stub" option for `main.js` — all logic moves to Kotlin.

## Hard constraint: web-only consumers must still work

The toolkit must remain usable by consumers that ship a plain website with no Electron, no Node main process, no desktop wrapper. This plan is **not** about making Electron mandatory — it's about giving Electron consumers a Kotlin path that lives next to, not in place of, the browser path.

Concretely, the boundary holds because:

- **The renderer side already supports it.** `toolkit-web` (browser-only) exposes `Persister` as an interface. `ElectronIpcPersister` is one impl; a website consumer plugs in `LocalStoragePersister` (or any other). Nothing in `toolkit-core` or `toolkit-web` requires `darknessApi` to exist — `ElectronIpcPersister` is only constructed when the consumer chose Electron. The browser bundle has no Node imports today, and this plan does not add any.
- **The new Node-target code is opt-in and isolated.** The `jsNodeMain` source set lives in the demo app's own module (or, in the follow-up, a separate `toolkit-electron-main` module with its own `js { nodejs() }` target). Web-only consumers depend only on `toolkit-core` + `toolkit-web` and never see the Node code, its `external` Electron declarations, or its npm dependencies.
- **No Node leakage into shared code.** Path resolution, `fs/promises`, atomic writes, IPC handler registration — all stay inside `jsNodeMain`. `jsCommonMain` is restricted to plain data: channel-name strings, argv-prefix strings, JSON keys. A website consumer that pulls in only `jsBrowserMain` should not transitively pick up anything from `jsNodeMain` — verify this by inspecting the browser bundle after the change.
- **Renderer behavior degrades gracefully.** When `globalThis.darknessApi` is absent (website mode), the toolkit's existing fallback handling already kicks in — the renderer doesn't crash, it just reads/writes via whichever `Persister` the consumer wired in. This plan does not change that behavior.

If a follow-up lifts the Node code into `toolkit-electron-main`, that module is published as a separate Gradle artifact; nothing forces a website consumer to add it to their `dependencies { }`.

## On merging main.js with the existing Main.kt

The existing `web/src/jsMain/kotlin/.../Main.kt` is the **renderer** entry point — it runs inside the BrowserWindow under Chromium (browser globals, no `require`, no fs). `main.js` is the **main process** entry point — it runs in Node.js (no `document`, no DOM, but has `require`, `fs`, Electron APIs). Electron splits these into two OS processes by design; they cannot share a runtime, a global, or a function call.

**One file is not viable.** A single `.kt` file cannot target both `js { browser() }` and `js { nodejs() }` simultaneously — Kotlin/JS compiles per-target, and the file would either pull in Node APIs (breaking the browser bundle) or browser APIs (breaking Node). They must be separate source files compiled to separate bundles loaded by separate processes.

**Same module, separate files is viable and is the recommended landing.** Both can live in `darkness-demo/web` under sibling source sets — `jsMain` (renderer) and a new `jsNodeMain` (main process) — sharing a `jsCommonMain` for IPC channel name constants and JSON shapes. This gives the unification benefit (one Gradle module, one language, shared types for IPC messages) without conflating the runtimes. Pros: shared IPC contract types, a single place to evolve the demo, no JS duplication. Cons: slightly more Gradle plumbing (a second JS target with its own webpack config), `node_modules` resolution for Electron API typings (or a tiny `external` shim), and the file count goes up not down.

Recommendation: separate `ElectronMain.kt` and `Main.kt`, same module, with shared constants in `jsCommonMain`. Don't try to make it one file.

## Approach

1. **Add a Kotlin/JS Node target to `darkness-demo/web`** alongside the existing browser target, and reorganize source sets:
   - `jsCommonMain` — shared constants: IPC channel names (`darkness:readUiSettings`, …), JSON keys, the `--darkness-settings=` argv prefix.
   - `jsMain` (or rename to `jsBrowserMain`) — the existing renderer code, unchanged.
   - `jsNodeMain` — new source set, contains `ElectronMain.kt` and a small `preload.kt`.
   - Configure the Node target with `binaries.executable()` so webpack emits a runnable `electron-main.js`.

2. **Port `main.js` to `ElectronMain.kt`** in `jsNodeMain`:
   - Use `kotlin.js.require("electron")` / `kotlin.js.require("fs/promises")` / `path` / `os` via thin `external` declarations (a single `Electron.kt` declarations file with `external interface BrowserWindow`, `external object ipcMain { fun handle(...) }`, etc.). Keep the surface minimal — only what main.js actually uses.
   - Reuse the path-resolution code shape from `UiSettingsStore.jvm.kt` (mac/windows/linux branches, `Library/Application Support/Darkness/<APP_NAME>/`).
   - Reuse the atomic-write semantics (write to `<target>.tmp`, then rename) — reimplemented for Node's `fs/promises`, but with the same invariants.
   - Register the four IPC handlers (`darkness:readUiSettings`, `writeUiSettings`, `readLayoutState`, `writeLayoutState`).
   - BrowserWindow setup, single-instance lock, external-link handling, app menu — all in Kotlin using the `external` declarations.

3. **Port `preload.js` to a tiny Kotlin/JS file** in `jsNodeMain` (or its own `jsPreloadMain` source set if preload needs to be a separate webpack entry — Electron loads preload via a path, and it must be a self-contained JS file. Preload is small enough that staying in JS is also reasonable; flag this as a judgment call during implementation.)

4. **Shrink `electron/main.js` to a stub:**
   ```js
   require("./resources/main/electron-main.js");
   ```
   The Gradle `copyWebBundle` task gains a sibling `copyMainBundle` that copies the Kotlin/JS Node webpack output into `electron/resources/main/`.

5. **Update `electron/build.gradle.kts`:**
   - `copyMainBundle` task copies `:web:jsNodeProductionRun` (or equivalent — exact task name depends on KGP version) into `resources/main/`.
   - The existing `run` and `dist` tasks gain `dependsOn(copyMainBundle)`.
   - `package.json`'s `main` field still points at `main.js` (the stub).

## Files to modify or add

- **Modify** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/web/build.gradle.kts` — add Node target, source set wiring.
- **Add** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/web/src/jsCommonMain/kotlin/se/soderbjorn/darknessdemo/IpcChannels.kt` — channel-name constants, argv-prefix constants, `APP_NAME`.
- **Add** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/web/src/jsNodeMain/kotlin/se/soderbjorn/darknessdemo/electron/ElectronMain.kt` — the port of `main.js`.
- **Add** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/web/src/jsNodeMain/kotlin/se/soderbjorn/darknessdemo/electron/ElectronExternals.kt` — `external` declarations for the Electron / Node APIs actually used.
- **Modify** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/electron/main.js` — shrink to a single `require(...)` line.
- **Modify** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/electron/build.gradle.kts` — add `copyMainBundle` task; wire as a dependency of `run` and `dist`.
- **Optionally modify** `/Users/soderbjorn/repo/darkness/darkness-demo/develop/electron/preload.js` — port to Kotlin/JS as a second Node webpack entry, or leave as JS (small, rarely changes).

## Reuse from existing code

- **Path-resolution logic shape**: mirror `UiSettingsStore.jvm.kt` in `darkness-toolkit/develop/toolkit-store/src/jvmMain/kotlin/se/soderbjorn/darkness/store/UiSettingsStore.jvm.kt` — same OS branches, same directory layout (`Library/Application Support/Darkness/<APP_NAME>/`), same filenames.
- **IPC contract**: the renderer side already consumes `globalThis.darknessApi.{readUiSettings,writeUiSettings,readLayoutState,writeLayoutState}` via `ElectronIpcPersister.kt` in `darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/ElectronIpcPersister.kt`. The Kotlin port of `main.js` must register exactly these channels — don't change names or shapes.
- **Atomic-write invariants**: same as JVM (`Files.move(... ATOMIC_MOVE)` becomes `fs.rename` on Node, which is atomic on POSIX and best-effort on Windows — same trade-off as the current JS).

## Verification

0. **Web-only path still works.** Build the browser bundle alone (`./gradlew :web:jsBrowserDistribution`) and confirm it has no Node-only imports (`require("electron")`, `require("fs")`, `require("path")`, `require("os")`). A simple grep over the emitted JS is sufficient. Open `index.html` in a plain browser (no Electron) — the app should load, fall back to whichever non-Electron `Persister` is wired in, and not throw on missing `darknessApi`.
1. `./gradlew :electron:run` from `darkness-demo/develop/` — app launches, BrowserWindow opens with the demo pane.
2. Pick a theme in the renderer; close the app; reopen — theme persists. Confirms `writeUiSettings` → file → `--darkness-settings=` boot snapshot → `readUiSettings` round-trip.
3. Change tab layout (split, resize); close; reopen — layout persists. Confirms `writeLayoutState` / `readLayoutState`.
4. `ls ~/Library/Application\ Support/Darkness/DarknessDemo/` shows `ui-settings.json` and `layout-state.json` — same path as before the port.
5. Click an external link in the renderer (if any) — opens in default browser, not in the BrowserWindow. Confirms `setWindowOpenHandler` / `will-navigate` ported correctly.
6. Launch the app a second time while one is already running — second instance exits, first instance focuses. Confirms `requestSingleInstanceLock` ported correctly.
7. App menu shows the same items (Edit/View/Window, plus app-name menu on macOS). Confirms `buildAppMenu` ported correctly.

## Follow-up (out of scope here)

If this lands cleanly, lift `ElectronMain` into a new `darkness-toolkit` module (`toolkit-electron-main`) so notegrow and termtastic can reuse it. notegrow needs additional `notegrow:*` IPC handlers for file ops; termtastic embeds a JVM server jar — both can extend the toolkit base.
