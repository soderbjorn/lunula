# Darkness Toolkit

A shared UI toolkit for the Darkness family of apps (Termtastic, Notegrow, …).
Provides the visual identity used across the family: theme model, color
schemes, drop-in DOM/Compose components, and a reusable theme/color-scheme
editor for web/Electron.

The toolkit is **library-style**: it ships helpers and drop-in components, not
a framework that takes over an app's architecture. Apps adopt as much or as
little as they want.

## Modules

- `toolkit-core` — pure data: `ColorScheme`, `Theme`, `ResolvedPalette`,
  `ThemeResolver`, color math, ~80 color schemes, ~30 designer themes,
  `UiSettings` data class with kotlinx.serialization round-trip helpers.
  Targets: android, jvm, ios (arm64+sim), js.
- `toolkit-store` — standalone filesystem helpers: `defaultSharedThemesPath()`,
  `readUiSettings(path)`, `writeUiSettings(path, settings)`. JVM/Android/iOS
  only — browser/Electron filesystem access is the host app's concern (e.g.
  via Electron IPC to a Node fs call).
- `toolkit-web` — Kotlin/JS DOM components: theme CSS-var helpers, modal
  dialogs, top bar, left/right sidebars, pane-tree windowing/layout
  framework, the parameterized theme/color-scheme editor.
- `toolkit-compose` — Compose Multiplatform drop-in widgets: `Modal`,
  `TopBar`, `Sidebar`, slide transitions, plus an *optional*
  `LocalDarknessPalette` composition local. No `DarknessTheme { }` wrapper.

## In-tree demo (`demo/`)

`demo/` holds a reference app — `:demo:client`, `:demo:web`,
`:demo:electron-main`, `:demo:electron` — that consumes the toolkit through
direct project deps. It's the boundary regression test: a new app should look
and feel like the demo with no app-side CSS. The demo modules deliberately
**do not** apply `maven-publish`, so they never appear in either consumer's
`libs-repo/`. To run it:

```sh
./gradlew :demo:web:jsBrowserDevelopmentRun   # web
./gradlew :demo:electron:run                  # Electron desktop
```

## Repository layout

This repo uses **git worktrees**. The default working directory is `main/`;
additional branches are checked out as sibling directories at the same level
(`extract-from-termtastic/`, etc.).

## Consumers

Apps consume the toolkit through a **committed file-Maven-repo**
(`<app>/libs-repo/`) populated from this checkout. A consumer can be cloned
and built with no `darkness-toolkit` checkout on disk.

When a sibling `darkness-toolkit` checkout *is* present, the consumer's
`settings.gradle.kts` auto-detects it and switches to a Gradle composite
build (`includeBuild`) so toolkit edits flow through with no extra steps.

### Refreshing the libs-repos

After changing toolkit code, publish to both consumer libs-repos with a
single Gradle command (run from this checkout):

```sh
./gradlew publishAllToLibsRepo
```

Default targets:
- `../../termtastic/adopt-darkness-toolkit/libs-repo`
- `../../treefacts/adopt-darkness-toolkit/libs-repo`

Override either with `-PtermtasticLibsRepo=…` or `-PtreefactsLibsRepo=…`
(absolute or relative to this `darkness-toolkit` checkout). Then commit the
updated `libs-repo/` tree in each consumer repo.

### Forcing artifact resolution in a consumer

Even when a sibling toolkit checkout is on disk, a consumer build can be
forced to ignore it and resolve from `libs-repo/` by passing
`-Pdarkness.toolkit.useArtifacts=true`. Useful for verifying that the
published artifacts actually work end-to-end.

### Repo layout

```
repo-root/
  darkness-toolkit/<worktree>/
  termtastic/<worktree>/
  notegrow/<worktree>/
```

## License

MIT — see `LICENSE`. Copyright © 2026 Robert Söderbjörn. Contributions must be
compatible (no GPL/LGPL).
