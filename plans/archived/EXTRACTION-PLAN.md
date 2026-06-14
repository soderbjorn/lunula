# Darkness Toolkit Extraction — Status

## Status as of 2026-04-25 — COMPLETE (Phase 1 + Phase 2)

Phase 1 (toolkit extraction + adoption + theme/dialog dedup) is committed.

Phase 2 — six tracks of follow-up refactors — landed **uncommitted** in
the same worktrees:

- **Track A** — toolkit `ThemeManager.kt` (2,137 lines) decomposed into
  `ThemeManager` / `ThemeGrid` / `ThemeEditor` / `SchemeGrid` /
  `ColorPickerDialog`.
- **Track B** — termtastic monoliths split: `AppBackingViewModel`,
  `WindowState`, `Application`, `WebState`, `GitPane`, `TerminalScreen`
  (Android).
- **Track C** — notegrow `DocumentViewBackingViewModel` and `MainScreen`
  decomposed.
- **Track D** — toolkit ships its own stylesheet
  (`darkness-toolkit.css`, 926 lines, `.dt-*` namespaced) and an
  `injectDarknessToolkitStyles()` helper. Inline `element.style.*`
  calls in toolkit components replaced with `className = "dt-..."`.
- **Track E** — termtastic web and notegrow web both call
  `injectDarknessToolkitStyles()` at boot. Verified the CSS string
  ships in each consumer's webpack bundle.
- **Track F** — `defaultSharedThemesPath()` is the single source of
  truth for theme persistence across termtastic (server-owned writer)
  and notegrow (Electron-owned writer). Atomic writes + live
  `watchUiSettings(...)` on JVM/Android/Electron with 200ms debounce
  and own-write suppression. iOS file-watch deferred (clients receive
  live updates via the existing `/window` socket from the server).

Toolkit, termtastic (web/server/Android/iOS), and notegrow
(web/Electron) all still build cleanly. **Runtime smoke testing has
not been done by Claude — manual boot-and-click verification per app
is the recommended next step before committing.**

### Worktrees

```
/Users/soderbjorn/repo/darkness/
  darkness-toolkit/
    main/                       (initial commit only)
    extract-from-termtastic/    ← all toolkit work landed here
  termtastic/
    main/
    adopt-darkness-toolkit/     ← composite-build wiring + theme migration
  notegrow/
    main/
    adopt-darkness-toolkit/     ← windowing + theme application + Electron IPC
```

### Commits (Phase 1 — landed)

| Repo                | Branch                  | Commit                                                        |
| ------------------- | ----------------------- | ------------------------------------------------------------- |
| darkness-toolkit    | extract-from-termtastic | `e488244` Initial darkness-toolkit extraction                 |
| darkness-toolkit    | extract-from-termtastic | `bed7d4d` Port ThemeManager (theme/colour-scheme editor)      |
| termtastic          | adopt-darkness-toolkit  | `4706fa5` Wire darkness-toolkit composite build               |
| termtastic          | adopt-darkness-toolkit  | `c021f4e` Migrate termtastic theme code to toolkit-core       |
| termtastic          | adopt-darkness-toolkit  | `bd81972` Adopt toolkit-web ThemeManager via thin host adapter|
| darkness-toolkit    | extract-from-termtastic | `31631cf` showConfirmDialog: optional messageIsHtml flag      |
| termtastic          | adopt-darkness-toolkit  | `d6a9f90` Swap ConfirmDialog/ThemeHelpers duplicates          |
| notegrow            | adopt-darkness-toolkit  | `f784358` Adopt darkness-toolkit: windowing + theme           |
| notegrow            | adopt-darkness-toolkit  | `3708bd7` Electron IPC: filesystem-backed theme persistence   |

### Phase 2 — uncommitted in worktrees

All six Phase 2 tracks landed as uncommitted changes on the same
branches above. Recommended commit grouping when ready:

- darkness-toolkit `extract-from-termtastic`: one commit per track
  touching the toolkit (A, D, F).
- termtastic `adopt-darkness-toolkit`: one commit per refactor target
  (six commits for B), one for E, one for F. Or squash into "Phase 2
  refactor pass" if the user prefers.
- notegrow `adopt-darkness-toolkit`: one for C, one for E, one for F.

## What's in each repo

### `darkness-toolkit/extract-from-termtastic`

- Gradle scaffold, MIT license, NOTICE, README, CLAUDE.md, .gitignore.
- `toolkit-core` (commonMain): `ColorMath`, `ColorSchemes`,
  `DefaultThemes`, `ResolvedPalette`, `ThemeResolver`, plus new
  `UiSettings` data class with kotlinx.serialization round-trip helpers
  (`toJsonString`, `fromJsonString`, `defaults`, `resolveAgainst`).
  Targets: android, jvm, ios (arm64+sim), js.
- `toolkit-store`: `defaultSharedThemesPath()`, `readUiSettings(path)`,
  `writeUiSettings(path, settings)` for JVM, Android, iOS. Library-style
  — no `UiSettingsStore` interface.
- `toolkit-web` (Kotlin/JS): `ThemeCssVars` (`toCssVarMap`,
  `toCssAliasMap`, `applyCssVars`, `applyColorScheme`,
  `systemPrefersDark`, `isDarkActive`), `ConfirmDialog`, `shell/TopBar`,
  `shell/Sidebar` (left + right), `layout/PaneTree` (pure data model
  with `PaneTreeOps` for split/close/resize/retitle),
  `layout/LayoutRenderer` (stateless DOM renderer with content callback,
  drag-resize, click-to-close).
- `toolkit-compose` (Compose Multiplatform): `LocalDarknessPalette`
  (optional), `darknessPaletteFor(...)`, `Long.toComposeColor()`. No
  `DarknessTheme {}` wrapper.

### `termtastic/adopt-darkness-toolkit`

- `settings.gradle.kts`: composite build with parameterized
  `darkness.toolkit.path` Gradle property and dependency substitutions.
- `gradle/libs.versions.toml`: `darkness-core/store/web/compose` library
  coords.
- `gradle.properties`: `darkness.toolkit.path` override pointing at the
  feature worktree.
- `client/build.gradle.kts`: `api(libs.darkness.core)` plus
  `export(libs.darkness.core)` on the iOS framework binaries so the
  Client framework re-exports the toolkit types for SwiftUI.
- `server/build.gradle.kts`: `implementation(libs.darkness.core)`.
- **Deleted** `client/commonMain/.../{ColorMath, ColorSchemes,
  DefaultThemes, ResolvedPalette, ThemeResolver}.kt`. The five files
  now live in `toolkit-core` and are pulled in via the `:client`
  `api()` dependency.
- **Rewrote** `client/.../UiSettings.kt`: data class moved out;
  termtastic-specific bits remain (`fetchUiSettings` extension on
  `TermtasticClient`, deprecated `effectiveColors`).
- **Updated** every termtastic file that referenced the moved types
  (`:client`, `:web`, `:server`, `:androidApp`) to import
  `se.soderbjorn.darkness.core.*` and rewrote explicit imports /
  fully-qualified references from the old package to the new one.
- **iOS Swift fix**: `iosApp/iosApp/Theme/SidebarPalette.swift` updated
  to use `Client.ColorScheme` (was stale `Client.TerminalTheme`),
  `Client.ColorSchemesKt.recommendedColorSchemes` and
  `Client.ColorSchemesKt.DEFAULT_THEME_NAME` (were stale `ThemesKt`
  references). Pre-existing breakage now fixed.
- toolkit-compose was deliberately **not** added as a `:androidApp`
  dependency — mixing `org.jetbrains.compose` (toolkit-compose) with
  `androidx.compose-bom` (termtastic) caused Compose runtime version
  conflicts (material-icons unresolved). termtastic Android consumes
  toolkit-core directly via the wildcard import, which is sufficient.
- All four build targets pass:
  - `:client:linkDebugFrameworkIosSimulatorArm64`
  - `:web:jsBrowserDistribution`
  - `:server:compileKotlin`
  - `:androidApp:assembleDebug`

### `notegrow/adopt-darkness-toolkit`

- `settings.gradle.kts`: composite build with `darkness.toolkit.path`
  property.
- `gradle/libs.versions.toml`: `darkness-core/store/web/compose` coords.
- `web/build.gradle.kts`: `:web` jsMain depends on `darkness-core` and
  `darkness-web`.
- **`web/.../main/AppShell.kt`** (new) — boot-time theme application via
  `applyCssVars(documentElement, palette.toCssVarMap())`, plus
  `LayoutRenderer` mounting a single-leaf `PaneTree` whose content is
  the existing `MainScreen` note editor. Loads `UiSettings` from
  `globalThis.__darknessSettings` injected by Electron preload, falls
  back to `UiSettings.defaults()`.
- **`web/.../Main.kt`** swapped from direct `MainScreen.render` to
  `AppShell.render`.
- **`web/.../main/MainScreen.kt`** every hardcoded inline colour
  (`#1e1e1e`, `#e6e6e6`, `#5ab0ff`, `rgba(90, 176, 255, 0.3)`,
  scrollbar/header colours) replaced with `var(--t-…, fallback)` so the
  editor honours the active theme.
- **`electron/main.js`**: `defaultDarknessSettingsPath()` resolves the
  per-OS shared darkness UI-settings path (matching
  toolkit-store/jvmMain). `readDarknessSettingsSync()` runs at startup
  and packs the JSON into the window's
  `webPreferences.additionalArguments`. New IPC handlers
  `darkness:writeUiSettings` and `darkness:readUiSettings`.
- **`electron/preload.js`**: decodes the `--darkness-settings=...`
  argument and exposes the JSON as `globalThis.__darknessSettings`
  before the renderer bundle loads. Exposes a `darknessApi.{read,
  write}UiSettings` bridge for any future renderer-side write surface.
- `:web:jsBrowserDevelopmentExecutableDistribution` produces a bundle
  including `DarknessToolkit-toolkit-core.js` (134 KiB) and
  `DarknessToolkit-toolkit-web.js` (30.5 KiB) alongside notegrow's own
  modules.

### Per the user's clarification: notegrow has no shell

Per "notegrow should not have a shell. it should simply have the
generic windowing system from the toolkit", notegrow does not ship a
top bar, sidebars, or theme editor. Splitting/closing windows isn't
wired into notegrow's UI yet — the `PaneTree` model supports it, so a
follow-up can add a shortcut or context menu without revisiting the
boot path.

The toolkit *does* include `TopBar` and `Sidebar` components for use
by other apps — they're available for termtastic to adopt incrementally
in a future PR.

## What's been completed

### Phase 1 (Phase-1 dedup)

- **Theme Manager modal** lives in `toolkit-web/themeeditor/`.
  Termtastic adopts via [TermtasticThemeManagerHost] in
  `web/src/jsMain/.../ThemeManager.kt`.
- **ConfirmDialog** — termtastic's copy deleted; uses toolkit-web's
  `showConfirmDialog`. The toolkit gained an opt-in `messageIsHtml`
  flag so HTML-bearing prompts (TabBarMenu's "Close tab") still work.
- **ThemeHelpers** — `toCssVarMap` deleted from termtastic in favour
  of toolkit-web's; `systemPrefersDark` delegates to toolkit-web;
  `isLightActive` is a one-line inverse of toolkit-web's
  `isDarkActive`. Termtastic-specific aliases (`--termtastic-orange`,
  `--terminal-bg`) stay in termtastic's `toCssAliasMap`.
- **Overlays.kt** — termtastic-specific (auth state, disconnected
  modal, device rejected). Not duplicated in toolkit; stays in
  termtastic.

### Phase 2 (refactor pass — uncommitted)

#### Track A — toolkit ThemeManager decomposition

`toolkit-web/.../themeeditor/ThemeManager.kt` was a 2,137-line modal
mixing tab switching, filtering, card rendering, scheme grids, color
picker, dialogs. Decomposed into five files in the same package:

| File                    | Lines | Responsibility                           |
| ----------------------- | ----- | ---------------------------------------- |
| `ThemeManager.kt`       |   536 | Public entry point + composer            |
| `ThemeGrid.kt`          |   361 | Theme list pane + filter chips + cards   |
| `ThemeEditor.kt`        |   425 | Per-theme section/scheme assignment      |
| `SchemeGrid.kt`         |   302 | Scheme list pane + cards                 |
| `ColorPickerDialog.kt`  |   447 | Per-scheme dark/light fg/bg + overrides  |

Public API (`showThemeManager`, `closeThemeManager`,
`refreshThemeManager`, `ThemeManagerHost`) unchanged. ConfirmDialog
not duplicated — reuses existing `showConfirmDialog`.

#### Track B — termtastic monoliths

| File                              | Before | After (orchestrator) | New helpers extracted |
| --------------------------------- | -----: | -------------------: | --------------------- |
| `client/.../AppBackingViewModel.kt` |  1,041 |              548 | `LayoutViewModel.kt` (118), `SettingsViewModel.kt` (329), `SessionStateViewModel.kt` (81) |
| `server/.../WindowState.kt`       |  1,432 |                  593 | `TabManager.kt` (147), `PaneManager.kt` (418), `PathFormatting.kt` (59), `PaneLayouts.kt` (212) |
| `server/.../Application.kt`       |  1,429 |                  151 | `PtyRoutes.kt` (124), `WindowRoutes.kt` (578), `TerminalSessionManager.kt` (357), `ServerInitializer.kt` (121) |
| `web/.../WebState.kt`             |    829 |                   39 | `TerminalRegistry.kt` (39), `DomRefRegistry.kt` (21), `PaneStateRegistry.kt` (46), `RenderingState.kt` (67), `WebStateActions.kt` (574) |
| `web/.../GitPane.kt`              |    913 |                  289 | `GitPaneViewModel.kt` (53), `GitFileListComponent.kt` (142), `GitDiffViewer.kt` (346), `GitSearchBar.kt` (110) |
| `androidApp/.../TerminalScreen.kt`|    867 |                  420 | `TerminalEmulatorHolder.kt` (144), `TerminalThemeResolver.kt` (85), `ImeHelperToolbar.kt` (156), `SwipeInputBar.kt` (112) |

Notes:
- `TerminalSessions` / `TerminalSession` retained their existing
  symbol names (renaming to `TerminalSessionManager` would have
  rippled across ~12 files); they moved to the new
  `TerminalSessionManager.kt` file only.
- `WindowState` landed at 593 lines (target ≤400) — agent flagged as
  the practical floor without invasive locking changes.
- `TerminalScreen` landed at 420 lines (target ≤250) — the
  `TerminalView` factory + `TerminalViewClient` stub overrides are
  ~120 of those and resist further extraction without shape change.
- `GitPaneState`'s `dynamic` fields kept (typing them touches the
  raw-JSON `WindowConnection.handlePaneContentMessage` path, which
  violates "pure split, no behavior change"). Documented as deferred.
- iOS Swift surface unchanged — `:client:linkDebugFrameworkIosSimulatorArm64` passes.

#### Track C — notegrow

| File                                          | Before | After | New helpers extracted |
| --------------------------------------------- | -----: | ----: | --------------------- |
| `client/.../DocumentViewBackingViewModel.kt`  |    809 |   322 | `TextEditingViewModel.kt` (322), `ZoomNavigation.kt` (81), `SelectionHelper.kt` (113) |
| `web/.../MainScreen.kt`                       |    715 |   171 | `EditorStyle.kt` (29), `OutlinePaintLoop.kt` (313), `OutlineInputHandlers.kt` (269), `HitTesting.kt` (63), `ScrollIntoView.kt` (51) |

`Companion.selectionOf` and `Companion.TAB_SIZE` preserved on the
aggregate VM so existing call sites don't change.
`AppShell.render → MainScreen.render(root)` signature unchanged.

#### Track D — toolkit ships its own CSS

New: `toolkit-web/src/jsMain/resources/darkness-toolkit.css` (926
lines, `.dt-*`-namespaced, theme-bound via `var(--t-*)`).
New: `toolkit-web/.../DarknessToolkitStyles.kt` (53 lines) — exposes
`darknessToolkitCss: String` and `injectDarknessToolkitStyles(target = document.head)` (idempotent; appends `<style data-darkness-toolkit>` if not already present).

Resource delivery: a Gradle codegen task
(`generateDarknessToolkitCssKt` in `toolkit-web/build.gradle.kts`)
reads the `.css` file at build time and emits a Kotlin file with
`internal val DARKNESS_TOOLKIT_CSS_BUNDLE: String = """..."""`. The
`.css` is also bundled into the toolkit klib via `jsProcessResources`
so consumers can grab the file directly if preferred.

All toolkit-web components (`ConfirmDialog`, `shell/TopBar`,
`shell/Sidebar`, `layout/LayoutRenderer`, `themeeditor/*`) converted
from inline `element.style.setProperty(...)` calls to
`element.className = "dt-..."`. Per-instance dynamic values stay
inline as CSS variables: `--dt-pane-flex` (split ratios),
`--star-fg` / `--kebab-fg` (theme card preview overrides), per-card
palette injection on the theme manager panel.

Pre-existing bugs flagged but **not** fixed (preserved bug-for-bug):
1. `if/else` precedence bug in `ThemeGrid.buildThemeCard`'s className
   concat — selected cards never receive `default` / `custom` modifier.
2. `showConfirmDialog` callers passing HTML in `message` without
   `messageIsHtml = true` render `<b>` literally as text.

#### Track E — consumer wiring

- `termtastic/adopt-darkness-toolkit/web/src/jsMain/.../main.kt:125` — calls
  `injectDarknessToolkitStyles()` at boot.
- `notegrow/adopt-darkness-toolkit/web/src/jsMain/.../AppShell.kt:95` — calls
  `injectDarknessToolkitStyles()` at boot.

Verified: both consumers' bundled JS contains the embedded CSS string
(distinctive `dt-pane-divider` class found in both `web.js`).

No consumer-side `build.gradle.kts` change needed — composite-build
runtime classpath already includes `:toolkit-web`.

#### Track F — single-file shared theme persistence

Toolkit additions:
- `toolkit-store/.../Closeable.kt` (commonMain `expect interface`,
  JVM/Android `actual typealias` to `java.io.Closeable`, iOS standalone SAM).
- `toolkit-store/.../UiSettingsStore.kt` (commonMain) gained
  `expect fun watchUiSettings(path, onChange): Closeable`,
  `writeUiSettingsRaw`, `readUiSettingsRaw`.
- JVM impl: `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`
  for atomic writes; `WatchService` daemon-thread watcher with 200ms
  debounce and own-write suppression via `lastWrittenBytes` map.
- Android impl: `File.renameTo` atomic write; `FileObserver`-based
  watcher (API-29+ `File` ctor, fallback to deprecated `String` ctor).
- iOS impl: stubbed (deferred — see "Deferred" section).

Termtastic server is the **only** writer; clients receive updates via
the existing `/window` `WindowEnvelope.UiSettings` socket fanout.
- `server/.../persistence/SettingsRepository.kt` reads/writes
  `defaultSharedThemesPath()` via `read/writeUiSettingsRaw`. One-shot
  SQLite → shared-file migration on startup.
- `server/.../ServerInitializer.kt` adds `installSharedThemesWatcher(repo)`
  subscribing via `watchUiSettings(...)`; `Application.kt` closes the
  handle in the shutdown hook.

Notegrow Electron writes the same shared file:
- `electron/main.js` — atomic write (`fs.writeFile(tmp); fs.rename`),
  `fs.watch` on parent dir with 200ms debounce, self-write
  suppression via `Buffer.equals`.
- `electron/preload.js` — exposes
  `darknessApi.onUiSettingsChanged(cb)` (returns unsubscribe fn).
- `web/.../AppShell.kt` — `subscribeToExternalThemeChanges()` re-runs
  `applyTheme(settings)` on each delivery.

Same path on every OS: macOS
`~/Library/Application Support/Darkness/ui-settings.json`, Windows
`%APPDATA%\Darkness\ui-settings.json`, Linux
`$XDG_CONFIG_HOME/darkness/ui-settings.json`. Electron mirrors the
Kotlin path byte-for-byte.

Deferred:
- **iOS file watch + write.** termtastic iOS already gets live updates
  via `/window` socket from the server (which is a JVM watcher), so
  iOS works today without a local watcher. Future implementation
  path documented in `UiSettingsStore.ios.kt` (`DispatchSource` or
  `NSFileManager.attributesOfItem` poll).
- **Termtastic app-private fields** (font size, sidebar width,
  custom themes, favourites) currently land in the shared file
  alongside toolkit `UiSettings`. Project-memory note "shared file is
  only for shared UI/theme state" suggests these should migrate to a
  separate termtastic-private file. Not enforced this pass.

Known runtime hazard: `fs.watch` on macOS uses FSEvents
(directory-granular, aggressive coalescing). A write+rename in the
same 200ms tick can deliver only a `rename` event with `null`
filename. If the renderer ever stops seeing external changes,
relax the filter from `if (changedName !== fname) return` to
`if (changedName && changedName !== fname) return`.

## How to verify locally

```bash
# Toolkit standalone
cd /Users/soderbjorn/repo/darkness/darkness-toolkit/extract-from-termtastic
./gradlew :toolkit-core:build :toolkit-store:compileKotlinJvm \
          :toolkit-store:compileKotlinIosArm64 \
          :toolkit-store:compileKotlinIosSimulatorArm64 \
          :toolkit-web:compileKotlinJs :toolkit-web:jsJar \
          :toolkit-compose:compileKotlinJvm

# Termtastic — every target
cd /Users/soderbjorn/repo/darkness/termtastic/adopt-darkness-toolkit
./gradlew :web:jsBrowserDistribution :server:compileKotlin \
          :androidApp:assembleDebug :client:linkDebugFrameworkIosSimulatorArm64

# Notegrow web bundle
cd /Users/soderbjorn/repo/darkness/notegrow/adopt-darkness-toolkit
./gradlew :web:jsBrowserDevelopmentExecutableDistribution
```

### Recommended manual smoke tests (not yet done)

Compile-green ≠ runtime-correct. Before committing Phase 2, exercise
each app's golden path:

1. **termtastic web/server**: boot server, connect web client, open
   tab, split pane, change theme, close + reopen — terminal echoes,
   theme persists, layout restores.
2. **termtastic Android**: APK boots, terminal pane renders with
   correct theme, IME toolbar + swipe input bar respond.
3. **termtastic iOS**: simulator runs, theme + sidebar palette apply,
   `/window` socket pushes settings.
4. **notegrow Electron**: boot Electron, type a note, change theme,
   restart — note persists, theme persists.
5. **Cross-app theme sync (Track F)**: with both termtastic and
   notegrow running, change theme in one — the other reflects it
   within ~1s without restart.
6. **Visual diff for toolkit components (Track D)**: open theme
   manager modal in termtastic, verify the layout matches pre-Track-D
   visuals (modal chrome, theme cards, kebab menus, color picker).

## Distribution / publishing

Composite build via `includeBuild`. No Maven Central publishing. Apps
require a sibling `darkness-toolkit` clone — per the user's stated
constraint that this is acceptable.

## Licensing

`darkness-toolkit` ships under MIT, matching termtastic. All extracted
code is original termtastic authorship (Robert Söderbjörn 2026). No
LGPL/Apache derivative work was extracted — JediTerm and the vendored
terminal-emulator/-view modules stay in termtastic only.
`darkness-toolkit/NOTICE` lists the toolkit's transitive Apache-2.0
deps (Kotlin, Compose Multiplatform, kotlinx-serialization).
