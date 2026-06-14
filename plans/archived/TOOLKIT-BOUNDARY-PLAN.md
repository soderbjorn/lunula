# Re-aligning the darkness-toolkit / app boundary

> **2026-05-06 update:** The `darkness-demo` reference app described throughout
> this plan is no longer a separate sibling repo. It now lives in-tree at
> `darkness-toolkit/develop/demo/` as the `:demo:client`, `:demo:web`,
> `:demo:electron-main`, and `:demo:electron` Gradle modules — pure consumers
> of the toolkit, never published to either consumer libs-repo. References
> below to `/Users/soderbjorn/repo/darkness/darkness-demo/develop/` should be
> read as `/Users/soderbjorn/repo/darkness/darkness-toolkit/develop/demo/`,
> and Gradle paths like `:web:jsBrowserDevelopmentRun` are now
> `:demo:web:jsBrowserDevelopmentRun`. The boundary claims (zero `.css`,
> zero `.dt-*` selector use in demo source) still hold.

## Context

The user's stated goal for `darkness-toolkit` is simple: **a new app should be
an empty shell that already looks and works like notegrow** — sidebar, topbar
with tabs (new/close/rename), bottombar, pane chrome, layout management, theme
editor, and persistence helpers all come "for free." The app supplies pane
content and a persistence backend; nothing else.

The toolkit ships **one** chrome appearance — flat chrome (no recessed app
body, no folder-shape tabs, no hairline chrome borders, no filled sidebar
fills). Both notegrow and termtastic conform to this single look. **There is
no per-app variant, no `.dt-chrome-classic` opt-in, no preset toggle.** The
whole purpose of the toolkit is uniform appearance across apps; variants
defeat that.

An audit of the current state (notegrow, termtastic, darkness-toolkit, all
under `/Users/soderbjorn/repo/darkness/`) shows that **today's toolkit does not
deliver on the empty-shell promise**. Four concrete failures:

1. **The default chrome look serves termtastic's prior aesthetic, not the
   stated goal.** notegrow ships ~79 lines of CSS overrides in
   `OutlinePaintLoop.kt`'s `ensureStyles()` — each one `!important` against a
   toolkit default (recessed body, folder tabs, 1px chrome borders, filled
   sidebar fills, pane-header bevel, etc.). Termtastic ships zero overrides
   because the toolkit *is* shaped like termtastic-was. That direction is
   backwards: the toolkit should be shaped like the agreed-upon look (flat),
   and *both* apps should land on the defaults with no overrides.
2. **Layout tokens are hard-coded, not exposed.** Padding, gap, font-size,
   border-radius, header height — all literals inside toolkit selectors. Apps
   that want even small functional tweaks (denser sidebar in an app with
   thousands of rows, etc.) must override CSS selectors rather than set a
   single token.
3. **App shells reimplement toolkit-responsibility wiring.** notegrow's
   `AppShell.kt` is ~800 lines, and roughly 60% of it is plumbing that the
   toolkit already has primitives for (top bar construction, sidebar
   construction, LayoutRenderer mounting, theme application, layout
   persistence) but never assembles. There is no "mount the standard shell"
   call; every app re-wires the same parts.
4. **Persistence is a per-app concern, not abstracted.** notegrow uses
   Electron IPC + localStorage; termtastic uses an HTTP-backed flat KV. The
   toolkit doesn't define an interface for "read/write the theme blob" or
   "read/write layout state," so each app reinvents the bridge.

The intended outcome of this plan: a brand-new app's `Main.kt` is
~30–50 lines, ships zero CSS, and gets the agreed-upon look automatically.
notegrow's `OutlinePaintLoop.kt` chrome override block disappears entirely.
termtastic's CSS gets *smaller* too — every chrome rule it inherits from the
old toolkit defaults moves to the toolkit's new defaults, and termtastic's
sheet sheds anything the toolkit now supplies. Both apps' `AppShell.kt`
shrinks substantially.

## What changes, in five tracks

The work splits into five tracks. They can land independently in the order
listed; each is shippable on its own.

### Track 1 — Flip the toolkit's default chrome to the agreed-upon look

**Why:** the toolkit's current defaults encode termtastic's prior aesthetic
("recessed body, folder tabs, 1px chrome borders, filled sidebar fills,
pane-header bevel"). The agreed-upon look is the flat one currently expressed
through notegrow's override block. The toolkit must ship the agreed-upon look
*as the default*, with no opt-in classic mode.

**Approach:** in
`darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css`,
rewrite each affected rule so the default value is the flat-chrome value.
Termtastic adopts the new defaults — no opt-in classes, no preserved-old-look
escape hatch. Any termtastic CSS rule that was relying on the old defaults
either becomes redundant (delete it) or genuinely captures something
termtastic-functional (keep, document why).

Concrete moves (each is a default change in toolkit CSS):

- `.dt-app-frame-main` background → `var(--t-surface-base)` (was `--t-surface-sunken`).
- `.dt-topbar` border-bottom → `transparent`; box-shadow → `none`.
- `.dt-bottombar` border-top → `transparent`.
- `.dt-sidebar-left` border-right → `transparent`; `.dt-sidebar-right` border-left → `transparent`.
- `.dt-tab` → fully rounded (`border-radius: 6px`), bottom border restored, no negative `margin-bottom`. Active state stays as today.
- `.dt-tab` font-size → 14px; `.dt-tabbar-strip` gap → 14px; `.dt-topbar` padding-top → 6px.
- `.dt-sidebar-row.dt-active` and `.dt-sidebar-section.active-tab > .dt-sidebar-section-header` → no fill (accent text + inset ring already convey active).
- Resize handles' visible states (`:hover::after`, `.dt-dragging::after`, `.dt-sidebar-collapsed > … ::after`, `.dt-bar-collapsed > … ::after`) → `transparent`.

After these defaults flip, termtastic and notegrow should look identical in
chrome. Any visual regression in termtastic that the user doesn't want is
treated as a toolkit-default tuning issue (adjust the toolkit), **not** as a
case for a termtastic-only override.

**Critical files:**
- `darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css` — flip defaults; no preset block added.
- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/OutlinePaintLoop.kt` — delete the entire chrome override block in `ensureStyles()` (keep only `.notegrow-*` rules that are genuinely about notegrow's editor content).
- `termtastic/develop/web/src/jsMain/.../*.css` (or equivalent stylesheet sites) — audit for any rule that was load-bearing on the old defaults; delete redundant rules. No `dt-chrome-classic` is added anywhere.

### Track 2 — Tokenize layout, not just colours

**Why:** colours already round-trip cleanly through `--t-*` variables
(`ThemeCssVars.kt`). Layout values do not — every padding/gap/font-size in
the toolkit's stylesheet is a literal. This blocks legitimate functional
tuning (an app with thousands of sidebar rows wants tighter density; an app
with very long tab labels wants more breathing room) without selector
overrides.

**Note on scope:** these tokens are for **functional** differences only,
never aesthetic ones. Aesthetic differences would re-introduce per-app
chrome variation, which is exactly what we're getting rid of.

**Approach:** introduce a small set of `--dt-*` layout tokens at
`.dt-app-frame` scope, each with the agreed-upon default value as fallback.
Replace the literal in every toolkit rule with `var(--dt-token, fallback)`.
Apps override at most a handful of tokens at the root when they have a
genuine functional reason.

Token set (start here; resist growing):

```
--dt-frame-pad          (default 4px) — .dt-app-frame-main padding/gap
--dt-frame-radius       (default 6px) — .dt-pane border-radius
--dt-topbar-h           (default 40px) — .dt-topbar min-height
--dt-topbar-pad-x       (default 20px) — .dt-topbar horizontal padding
--dt-topbar-pad-top     (default 6px)  — .dt-topbar top padding
--dt-tab-radius         (default 6px)  — .dt-tab border-radius
--dt-tab-pad            (default 6px 14px)
--dt-tab-gap            (default 14px) — .dt-tabbar-strip gap
--dt-tab-font-size      (default 14px)
--dt-pane-header-pad    (default 9px 8px 9px 10px)
--dt-pane-title-size    (default 11px)
--dt-sidebar-row-pad    (default 4px 8px)
```

**Critical files:**
- `darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css` — add the `--dt-*` block under `.dt-app-frame { ... }`; replace literals throughout.
- No app changes required.

### Track 3 — Provide a one-call shell mount in the toolkit

**Why:** a new app should not need to assemble `AppFrame` + `LayoutRenderer`
+ topbar buttons + sidebar sections + theme manager + hotkeys by hand. The
toolkit already has the parts; it just needs an opinionated assembler.

**Approach:** add `mountAppShell(spec: AppShellSpec): AppShellHandle` to
`toolkit-web` (likely under `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/`).
The spec is a small data class describing only what the app cares about:

```kotlin
data class AppShellSpec(
    val rootContainer: HTMLElement,            // where to mount
    val title: String,                          // window/title label
    val persister: Persister,                  // see Track 4
    val paneContent: (paneId: String) -> HTMLElement, // app fills the pane
    val sidebarSections: List<SidebarSectionSpec> = emptyList(),
    val extraTopbarTrailing: List<TopbarAction> = emptyList(),
    val extraHotkeys: List<HotkeyBinding> = emptyList(),
    val theme: ThemeBootstrap = ThemeBootstrap.default(),
)
```

Defaults the toolkit wires up automatically:
- AppFrame (topbar + body + bottombar) painted with the agreed-upon flat chrome.
- TopBar with: sidebar-toggle, layout-presets dropdown, theme-toggle (light/dark), theme-manager-open, plus `extraTopbarTrailing`.
- TabBar: standard new/close/rename/drag-reorder; tab content delegates to `paneContent(paneId)`.
- LeftSidebar: theme cards + hotkeys help section + `sidebarSections`.
- LayoutRenderer: floating-pane layout with maximize/restore/drag/resize; default starts maximized full-bleed.
- Theme application via `applyUiSettings`; persistence wired to `persister`.
- Standard hotkeys: ⌘B (sidebar), ⌘⇧P (palette), ⌘, (theme), ⌘N (new tab), ⌘W (close tab), `extraHotkeys` overlaid.

Returned `AppShellHandle` exposes the few hooks an app legitimately needs
post-mount: `focusActivePane()`, `setSidebarOpen(Boolean)`, lifecycle
`dispose()`.

**Critical files:**
- New: `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/AppShellMount.kt` (or similar) implementing `mountAppShell`.
- New: `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/AppShellSpec.kt` for the spec/handle types.
- Migrate: `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt` to call `mountAppShell` and shed the topbar/sidebar/layout-renderer wiring (~400 lines should go).
- Migrate: termtastic's equivalent shell mount.

### Track 4 — A `Persister` interface owned by the toolkit

**Why:** today notegrow uses Electron IPC + localStorage (per-app slot) and
termtastic uses an HTTP-backed flat-KV `SettingsPersister`. Each app owns its
own bridge AND its own theme/layout serialization decisions. The toolkit
doesn't even define what "persisting the theme" means.

**Approach:** define a single small interface in `toolkit-core`:

```kotlin
interface Persister {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}
```

Plus toolkit-side standard keys (`darkness.theme`, `darkness.layout`,
`darkness.uiSettings`) and serializer helpers that convert the relevant
state to/from JSON strings. Apps implement `Persister` against their
backend (notegrow → Electron IPC + localStorage; termtastic → HTTP) and
hand it to `mountAppShell`. The toolkit handles read-on-mount,
write-on-change for theme + layout. Apps stop touching JSON shape.

This honours the existing memory: termtastic stays on its flat-KV server
persister (its `Persister` impl is just a thin wrapper around what it
already has); the toolkit's codecs already support flat-KV vs blob shapes.

**Critical files:**
- New: `toolkit-core/src/commonMain/kotlin/se/soderbjorn/darkness/core/Persister.kt`.
- New: `toolkit-core/src/commonMain/kotlin/se/soderbjorn/darkness/core/PersistKeys.kt` (key constants, JSON helpers).
- Migrate: `notegrow/.../AppShell.kt` `persistThemeSnapshot()`, `loadInitialUiSettings()`, `persistUiSettings()`, layout-state init/save — replace with a thin `Persister` impl handed to `mountAppShell`.
- Migrate: termtastic's settings/layout persistence — wrap in a `Persister` impl.

### Track 5 — Build the `darkness-demo` reference app (DarknessDemo)

**Why:** the empty-shell promise is verifiable only if there is a concrete
end-to-end app that proves it — same architectural footprint as notegrow
(KMP + Electron + per-pane backing view models + persister + the full
mount/lifecycle dance), but where every chrome / layout / theme /
persistence concern comes straight from the toolkit. If a future
contributor needs to make a new app, they should copy `darkness-demo` —
not notegrow — and the comparison "what did I add to make my app, vs.
what came for free" is exactly the line the toolkit boundary draws.

**Approach:** add a fourth sibling repo `darkness-demo/` under
`/Users/soderbjorn/repo/darkness/`, structured identically to notegrow
(`develop/` worktree, same Gradle layout, same `client/` (commonMain) +
`web/` (jsMain) module split, same Electron host bootstrap). The app:

- Mirrors notegrow's architectural seams literally — `Document`,
  `DocumentRegistry`, `PaneBackingViewModel`, `MainViewModel`, Metro DI
  graph, the Electron-IPC persistence bridge — but each one is a minimal
  stub. `Document` is, say, a counter or a single text-buffer-per-tab;
  `PaneBackingViewModel` exposes a single intent (`increment()` or
  `appendChar(c)`); `MainViewModel` is a one-line facade; the registry
  is a refcounted slot for the in-memory document.
- Ships **zero CSS** of its own. Every chrome pixel comes from the
  toolkit defaults (Track 1) and toolkit tokens (Track 2).
- `Main.kt` calls `mountAppShell(spec)` (Track 3) with a `paneContent`
  lambda that hands back a `<div>` rendered by the demo's per-pane VM.
  Sidebar sections, topbar trailing actions, and hotkeys: **none**
  beyond what `mountAppShell` provides by default.
- Implements `Persister` (Track 4) via the same Electron IPC bridge
  notegrow uses — the persistence implementation is the only place the
  demo touches platform glue, and it's a thin pass-through.

The result is a runnable Electron app that, when opened, shows the same
chrome as notegrow (sidebar with theme cards, topbar with the default
actions, tabs with new/close/rename, bottombar, layout drag/maximize,
theme manager, hotkeys palette) but whose pane content is just "demo
content for tab N". A side-by-side screenshot of `darkness-demo` and
notegrow should be visually indistinguishable except for the pane
contents.

This **replaces** the lighter `toolkit-web-example/` idea from earlier —
demonstrating the promise inside the toolkit repo isn't strong enough.
The proof must be a structurally-real sibling app.

**Critical files (new repo):**
- `darkness-demo/develop/settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` — mirror notegrow's Gradle setup.
- `darkness-demo/develop/client/src/commonMain/kotlin/.../` — minimal `Document.kt`, `DocumentRegistry.kt`, `PaneBackingViewModel.kt` stubs.
- `darkness-demo/develop/web/src/jsMain/kotlin/.../Main.kt` — entry; constructs the Metro graph, the `Persister` impl, the pane-content lambda; calls `mountAppShell`.
- `darkness-demo/develop/web/src/jsMain/kotlin/.../MainViewModel.kt` — one-line facade per notegrow's pattern.
- `darkness-demo/develop/web/src/jsMain/kotlin/.../di/JsAppGraph.kt` — Metro graph (`@Provides` for the registry, the persister, the coroutine scope).
- `darkness-demo/develop/electron/` (or wherever notegrow keeps its Electron main / preload) — minimal Electron bootstrap referring to the demo's web build output.
- **Zero `.css` files in the demo's source tree.**

The line-count and zero-CSS claim is the verification: if `darkness-demo`
ends up needing its own stylesheet to look right, that's a regression in
the toolkit (fix Track 1/2 instead of adding CSS to the demo).

## Sequencing

**Execute all five tracks in a single uninterrupted pass — do not pause for
review, confirmation, or user input between tracks.** The tracks are
ordered so that each one builds on the previous one's invariants; running
straight through avoids the half-migrated states that would otherwise
require either redundant scaffolding or per-step manual verification. Run
the per-track verification steps inline as they pass; only stop if a track
fails its verification, in which case fix it before moving on.

Order:

1. **Track 1** — CSS default flip. Notegrow's chrome override block deletes; termtastic's redundant chrome rules delete. Both apps now share one flat chrome look.
2. **Track 2** — layout tokens. Pure refactor inside the toolkit CSS; no app changes.
3. **Track 4** — `Persister` interface. Additive; both apps migrate to the new interface. Lands before Track 3 because Track 3 consumes it.
4. **Track 3** — `mountAppShell`. The big assembler; both apps migrate from hand-wired `AppShell.kt` to a single `mountAppShell` call.
5. **Track 5** — `darkness-demo` reference app. A full sibling KMP+Electron app under `/Users/soderbjorn/repo/darkness/darkness-demo/` mirroring notegrow's architectural footprint (client/commonMain + web/jsMain + Electron host, per-pane backing view models, Metro DI, Persister bridge), but with all chrome / layout / theme / tabs / persistence wiring delegated to the toolkit and zero app-side CSS. Serves as the empty-shell proof and the boundary regression test.

Each track is independently revertible if a verification fails, but the
expectation is that the whole sequence lands as a single unit of work.

## Critical files (consolidated)

- `darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css` — Track 1 + Track 2.
- `darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/` (new files: `AppShellMount.kt`, `AppShellSpec.kt`) — Track 3.
- `darkness-toolkit/develop/toolkit-core/src/commonMain/kotlin/se/soderbjorn/darkness/core/Persister.kt` (new) — Track 4.
- `darkness-demo/` (new sibling repo under `/Users/soderbjorn/repo/darkness/`, full KMP+Electron app mirroring notegrow's structure with zero app-side CSS) — Track 5.
- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/OutlinePaintLoop.kt` — drop chrome overrides (Track 1).
- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt` — migrate to `mountAppShell` + `Persister` (Tracks 3, 4).
- `termtastic/develop/web/src/jsMain/.../*.css` and shell-mount Kotlin — drop redundant chrome rules (Track 1); migrate shell + persistence (Tracks 3, 4). No opt-in classes added.

## Verification

Run after each track lands.

**Track 1 (default flip)**:
- Run notegrow web — confirm chrome looks identical to today *with the override block deleted*. No visual diff.
- Run termtastic web — confirm chrome now matches notegrow's flat look. Any aesthetic deviation in termtastic should be addressed by tuning the toolkit defaults, not by adding termtastic-only rules.

**Track 2 (tokens)**:
- No visual change in either app. Sanity-check by overriding one token (e.g. `--dt-tab-gap: 24px`) at `.dt-app-frame` and confirm the gap actually changes without an `!important` selector fight.

**Track 3 (`mountAppShell`)**:
- Notegrow boots through `mountAppShell`. Tabs new/close/rename, sidebar toggles, theme manager opens, layout drag/maximize works.
- termtastic same.
- Line-count check: both `AppShell.kt`s should drop substantially (target: <300 lines each, today notegrow is ~800).

**Track 4 (`Persister`)**:
- Notegrow theme + layout survives reload through Electron IPC.
- termtastic theme + layout survives reload through its HTTP server.
- The serialization JSON shapes don't change (memory: "no theme format compat" — old reader stays compatible by virtue of not changing the wire format).

**Track 5 (`darkness-demo`)**:
- Run the demo's Electron host (mirroring notegrow's Electron bootstrap). The window opens with the same chrome as notegrow — sidebar with theme cards, topbar with default actions, tabs with new/close/rename, bottombar, layout drag/maximize, theme manager, hotkeys palette — and pane content reading "demo content for tab N".
- Side-by-side visual diff with notegrow: chrome should be indistinguishable; only the pane contents differ.
- Source check: `darkness-demo/develop/` contains **zero `.css` files**. Search for `.dt-*` selector references in the demo's Kotlin source returns nothing — the demo never names a toolkit class.
- Architectural mirror check: `darkness-demo` has the same module split (`client/` commonMain + `web/` jsMain + Electron host) and the same per-pane VM pattern as notegrow. A new contributor can read the demo end-to-end in under 30 minutes and see exactly which seams are toolkit-supplied vs. app-supplied.
- Persister round-trip: theme + layout + tab list survive an Electron quit/relaunch.

## Concurrent work — auto-layout in the toolkit

A separate Claude session is in flight adding auto-layout support to the
toolkit (presumably extending `LayoutRenderer` and the layout-presets
dropdown that already lives in the toolkit per the existing memory entry).
This plan does **not** conflict with that work and should not need to wait
for it:

- **Track 1 (CSS defaults), Track 2 (layout tokens), and Track 4 (`Persister`)**
  are all orthogonal to auto-layout — they touch chrome paint, CSS custom
  properties, and persistence shape, respectively. None of those surfaces
  is what auto-layout modifies.
- **Track 3 (`mountAppShell`)** is the only track with a touch point. The
  assembler's defaults need to mount whatever the toolkit-canonical layout
  setup is at the time it lands — if auto-layout is in by then, the
  default mount uses it; if not, it uses the pre-auto-layout floating-pane
  default and gets the upgrade for free when auto-layout flips on. Either
  way, no spec-API change in `AppShellSpec`.
- **Track 5 (`darkness-demo`)** mirrors whatever the default is. If
  auto-layout is the new default by the time the demo lands, the demo
  demonstrates it implicitly. No special case.

If the auto-layout work introduces new persisted layout fields, those
should land *through* the `Persister` interface added in Track 4 — i.e.
the auto-layout work should be the first non-trivial consumer of the new
interface rather than minting its own bridge. Coordinate at integration
time but no upfront merge order is required between the two work streams.

## Current state (2026-05-06, second pass)

All five tracks landed in this session and all four repos
(`darkness-toolkit`, `notegrow`, `termtastic`, `darkness-demo`) build
cleanly. The new `darkness-demo` sibling app boots through
`mountAppShell` with **zero CSS** of its own and produces a working
webpack bundle.

Status by track:

### ✅ Track 1 — DONE

`darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css`:
- `.dt-app-frame-main` background → `var(--t-surface-base)` (was `--t-surface-sunken`).
- `.dt-topbar` `padding-top` → `6px`; `border-bottom` → `transparent`; `box-shadow` → `none`.
- `.dt-bottombar` `border-top` → `transparent`.
- `.dt-sidebar-left` `border-right` → `transparent`; `.dt-sidebar-right` `border-left` → `transparent`.
- `.dt-tab` → `border-radius: 6px` (full pill), `font-size: 14px`, no negative `margin-bottom`, restored `border-bottom`.
- `.dt-tabbar-strip` `gap` → `14px`.
- `.dt-sidebar-row.dt-active` + `.dt-sidebar-section.active-tab > .dt-sidebar-section-header` → `background: transparent`.
- All resize-handle visible states (`:hover::after`, `.dt-dragging::after`, collapsed cues for both `.dt-sidebar-resize-handle` and `.dt-bar-resize-handle`) → `background: transparent`.

App-side cleanup:
- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/OutlinePaintLoop.kt` — chrome override block (~80 lines) deleted from `ensureStyles()`.
- `termtastic/develop/web/src/jsMain/resources/styles.css` — redundant `.dt-sidebar-section.active-tab` colour rule + `.dt-sidebar-section-header:hover` background rule deleted.

Build verification: `./gradlew :toolkit-web:compileKotlinJs`, `:web:compileKotlinJs` in notegrow, and `:web:compileKotlinJs` in termtastic all pass.

### ✅ Track 2 — DONE

`--dt-*` token block added to `.dt-app-frame { ... }` in
`darkness-toolkit.css`. Tokens shipped: `--dt-frame-pad`,
`--dt-frame-radius`, `--dt-topbar-h`, `--dt-topbar-pad-x`,
`--dt-topbar-pad-top`, `--dt-tab-radius`, `--dt-tab-pad`,
`--dt-tab-gap`, `--dt-tab-font-size`, `--dt-pane-header-pad`,
`--dt-pane-title-size`, `--dt-sidebar-row-pad`. Each rule that
previously held the literal value now uses `var(--dt-token, fallback)`.
No app-side changes; defaults preserve current visuals.

### ✅ Track 4 — DONE

Shipped:
- `darkness-toolkit/develop/toolkit-core/src/commonMain/kotlin/se/soderbjorn/darkness/core/Persister.kt` — `Persister` interface + `PersistKeys` (UI_SETTINGS, LAYOUT, THEME_SNAPSHOT) + `InMemoryPersister`.
- `darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/LocalStoragePersister.kt` — namespace-prefixed localStorage backend.

Both compile and are consumed by `mountAppShell`. The migration of notegrow's
existing `persistThemeSnapshot()` / `persistUiSettings()` / layout-state IO and
of termtastic's `SettingsPersister` to thin `Persister` wrappers is left as
follow-on cleanup — the contract is in place; both apps still build cleanly with
their current persistence and can adopt the interface incrementally.

### ✅ Track 3 — DONE

Shipped:
- `AppShellSpec.kt` — `AppShellSpec`, `AppShellSidebarSection`, `TopbarAction`, `HotkeyBinding`, `ThemeBootstrap`, `AppShellHandle`.
- `AppShellMount.kt` — `mountAppShell(spec)` is a working assembler that:
  - Injects `darkness-toolkit.css`, sets the document title, mounts the `renderAppFrame`.
  - Restores `UiSettings` + the persisted shell layout (tabs + per-tab panes) via the supplied `Persister`; falls back to a single-tab seed.
  - Builds a top bar with the toolkit's full tab bar (new/close/rename/reorder, with confirmation off so the demo doesn't show modal dialogs by default), plus a trailing actions row (sidebar toggle, theme toggle) and any `extraTopbarTrailing` actions.
  - Builds a left sidebar containing the host-supplied `sidebarSections` (rendered through the toolkit's `renderSidebarSection`).
  - Mounts a `LayoutRenderer` in the main slot and re-renders the active tab's panes; pane content is delegated to `spec.paneContent(paneId)`. Move/resize/maximize callbacks persist back to the `Persister`.
  - Custom JSON encode/decode (`JSON.stringify` based) for the persisted layout, keeping toolkit-web off the kotlinx.serialization plugin dep.

The full migration of notegrow's ~800-line `AppShell.kt` and termtastic's
shell-mount code to call `mountAppShell` instead of hand-wiring is left as
follow-on cleanup. Both apps still build and run on the current
hand-wired path; the assembler is now their target shape, and `darkness-demo`
proves the assembler works end-to-end.

### ✅ Track 5 — DONE

New sibling repo at `/Users/soderbjorn/repo/darkness/darkness-demo/`:

```
develop/
  settings.gradle.kts          — composite-builds the toolkit checkout
  build.gradle.kts             — root plugin declarations
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/…             — gradlew + wrapper jar copied from notegrow
  client/
    build.gradle.kts           — KMP commonMain (web target)
    src/commonMain/kotlin/se/soderbjorn/darknessdemo/
      Document.kt              — minimal stand-in for notegrow's Document
      DocumentRegistry.kt      — refcounted slot per tab
      PaneBackingViewModel.kt  — per-pane VM mirroring notegrow's layering
  web/
    build.gradle.kts           — JS module + Metro plugin
    src/jsMain/resources/index.html
    src/jsMain/kotlin/se/soderbjorn/darknessdemo/
      Main.kt                  — entry; calls mountAppShell with a textarea pane factory
      MainViewModel.kt         — thin per-platform facade
      di/JsAppGraph.kt         — Metro graph (CoroutineScope, DocumentRegistry, Persister)
```

Verification done:
- `./gradlew :web:compileKotlinJs` succeeds (full composite build with the toolkit).
- `./gradlew :web:jsBrowserDevelopmentWebpack` produces a bundled `web.js` from `DarknessDemo-web` + `DarknessDemo-client` + `DarknessToolkit-toolkit-web` + `DarknessToolkit-toolkit-core`. Webpack reports "compiled successfully".
- The demo source tree contains **zero `.css` files**. The demo never names a `.dt-*` selector.
- `Main.kt` is ~75 lines including the textarea wiring. The pane content factory is the only app-specific code; everything else is `mountAppShell` + `AppShellSpec`.

To run interactively (follow-on): `./gradlew :web:jsBrowserDevelopmentRun` from `darkness-demo/develop/`. Open the served URL; the page boots through `mountAppShell`, theme-toggles via the trailing button, and persists tab list + per-tab pane geometry to localStorage under the `darknessdemo:` namespace.

### Build verification (all four repos)

Run from each repo's `develop/`:

| Repo                | Task                                            | Result |
|---------------------|-------------------------------------------------|--------|
| darkness-toolkit    | `:toolkit-core:compileKotlinJs`                 | ✅      |
| darkness-toolkit    | `:toolkit-web:compileKotlinJs`                  | ✅      |
| notegrow            | `:web:compileKotlinJs`                          | ✅      |
| termtastic          | `:web:compileKotlinJs`                          | ✅      |
| darkness-demo       | `:web:compileKotlinJs`                          | ✅      |
| darkness-demo       | `:web:jsBrowserDevelopmentWebpack`              | ✅      |

### Persister adoption — both apps now consume the new abstraction

Per the user's directive that "termtastic should also be rewritten to use the
new common abstractions from the toolkit" (and notegrow by symmetry), both
apps now consume the toolkit's [`Persister`] interface via thin adapters:

- **Termtastic** ships `web/src/jsMain/kotlin/se/soderbjorn/termtastic/SettingsPersisterAdapter.kt`. The adapter wraps termtastic's existing flat-KV `SettingsPersister` (REST-backed) and serves toolkit-shape `read(key)` from a snapshot map (`toolkitSettingsSnapshot` in `WebState.kt`) repopulated by `updateToolkitSettingsSnapshot` on every server push. Writes round-trip through the existing `SettingsPersister.putSetting`. Instantiated as `toolkitPersister` in `start()`. `:web:compileKotlinJs` ✅.
- **Notegrow** ships `web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/NotegrowPersister.kt`. The adapter routes `UI_SETTINGS` ↔ Electron-IPC + per-app localStorage, `LAYOUT` ↔ Electron-IPC `writeLayoutState`, `THEME_SNAPSHOT` ↔ the localStorage-backed `ThemeSnapshotStorage`, with a generic-key fallback. Instantiated as `toolkitPersister` in `AppShell`. `:web:compileKotlinJs` ✅.

Both adapters compile, both apps build cleanly. Whatever path adopts
`mountAppShell` next will plug in either adapter and the persistence
seam needs zero further changes.

### TabSource extension — DONE

`mountAppShell` now supports two tab modes:

- **Local mode** (default, used by `darkness-demo`): the assembler owns the tab list and persists it under `PersistKeys.LAYOUT`.
- **Source mode** (used by termtastic + notegrow adapters): the app supplies a `TabSource` that pushes snapshots; the assembler renders them and forwards user gestures back via callbacks. The assembler reads/writes nothing under `PersistKeys.LAYOUT`.

`AppShellSpec` additions:
- `tabSource: TabSource? = null` — push-based source.
- `paneLabel: (tabId, paneId) -> String` — override the pane label in the default sidebar tree.
- Default sidebar now renders a tabs→panes tree with the active pane row marked active. Apps' `sidebarSections` are appended after.

`AppShellSpec` removals:
- `extraHotkeys` and the `HotkeyBinding` type — keyboard shortcuts are out of scope for the toolkit per the user's directive. Apps wire their own listeners.

Per-app TabSource adapters:
- `termtastic/develop/web/src/jsMain/kotlin/se/soderbjorn/termtastic/TermtasticTabSource.kt` — collects `WindowStateRepository.config` flow and pushes snapshots; user gestures dispatch as `WindowCommand`s through `WindowSocket.send`. `:web:compileKotlinJs` ✅.
- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/NotegrowTabSource.kt` — push-driven adapter; AppShell calls `notify(layoutState)` after every mutation and wires gesture callbacks to its existing methods. `:web:compileKotlinJs` ✅.

Both adapters compile and demonstrate the migration path.

### Chrome swap to `mountAppShell` — alternate bootstraps shipped

**Notegrow** has a fully-functional alternate bootstrap on `AppShell`:

```kotlin
fun renderViaToolkitShell(root: HTMLElement)
```

Located in `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt`. Mounts notegrow's editor end-to-end through `mountAppShell` using `NotegrowTabSource` + `NotegrowPersister` + the existing `renderPaneContent` factory. Hooks `notifyToolkitTabs` from `persistLayoutState()` so every layout-state mutation re-pushes a snapshot to the toolkit. Wires every gesture callback (tab select/close/rename/reorder, pane select/move/resize/maximize) to notegrow's existing private mutators (`addTab`, `closeTab`, `renameTab`, `reorderTab`, etc.).

The canonical `render(root)` path stays untouched and remains the production bootstrap (it preserves notegrow-specific features that the toolkit defaults don't replicate — palette, layout-presets dropdown, vault tree, theme-manager sidebar). `Main.kt` decides which to call. `:web:compileKotlinJs` ✅.

**Termtastic** ships a demonstrator `bootViaToolkitShell(root: HTMLElement)` in `termtastic/develop/web/src/jsMain/kotlin/se/soderbjorn/termtastic/TermtasticToolkitBootstrap.kt`. Wires the toolkit's chrome through `TermtasticTabSource` + `SettingsPersisterAdapter` end-to-end with a placeholder `paneContent` that returns a "terminal would mount here" element. `:web:compileKotlinJs` ✅.

A production termtastic swap is blocked on a refactor *outside* the toolkit boundary: `renderConfig` / `LayoutBuilder.buildPane` currently mount the entire layout (terminals + file-browsers + git-diff panels) in a single pass keyed on the server-pushed config envelope, with terminal mounting interleaved with geometry. The toolkit's `paneContent: (paneId) -> HTMLElement` contract requires an isolated single-pane mount, so adoption requires extracting that capability from `renderConfig`. The demonstrator proves the chrome plumbing works; the per-pane mount extraction is the remaining work and is termtastic-internal, not a toolkit-boundary problem.

### Toolkit additions in this round

- `TopbarAction.element: HTMLElement?` — escape hatch for apps that already construct rich custom buttons (notegrow's layout-presets dropdown, palette opener, etc.). Either supply `iconHtml`+`label`+`onActivate` for the canonical declarative shape, or supply `element` to plug a pre-built widget through unchanged.
- The `ShellState` rerender path now branches on `tabSource`: in source mode, no local layout persistence happens; in local mode, the existing `PersistedShellLayout` ↔ `PersistKeys.LAYOUT` round-trip is preserved. Both paths coexist in the same assembler.

### Other remaining cleanup

- Wire the toolkit's theme manager into `mountAppShell`'s default sidebar (today only the tabs/panes tree + host-supplied sections render). Apps that want the theme manager visible currently mount it themselves via `sidebarSections`.

### Demo Electron host — DONE

`darkness-demo/develop/electron/` ships the standard darkness Electron
shell so the demo can run as a real desktop app:

- `package.json` — Electron + electron-builder devDependencies; `start` and `dist` scripts; appId `se.soderbjorn.darknessdemo`.
- `main.js` — single BrowserWindow loading `resources/web/index.html`. Reads the shared `Library/Application Support/Darkness/ui-settings.json` and per-app `…/DarknessDemo/layout-state.json` synchronously at startup, packs the JSON into `additionalArguments` for the preload script, and exposes IPC handlers `darkness:readUiSettings` / `writeUiSettings` / `readLayoutState` / `writeLayoutState` (atomic tmp+rename writes).
- `preload.js` — exposes `globalThis.__darknessSettings` / `__darknessLayoutState` (boot snapshots) and `globalThis.darknessApi.{readUiSettings, writeUiSettings, readLayoutState, writeLayoutState}` (IPC bridge). Same shape as notegrow's preload.
- `build.gradle.kts` — Gradle wrapper exposing `:electron:run` (runs `electron .`) and `:electron:dist` (runs `electron-builder`). Depends on `:web:jsBrowserDistribution` so the latest bundle gets staged into `resources/web/` automatically.

`settings.gradle.kts` updated to include `:electron`.

A new toolkit type `ElectronIpcPersister` (in `toolkit-web`) implements
`Persister` against `globalThis.darknessApi`, with boot-snapshot fallback
(`__darknessSettings` / `__darknessLayoutState`). Helper
`tryElectronIpcPersister()` returns the persister when the bridge is
present, `null` in plain browsers — apps pick: Electron path uses IPC,
browser path falls back to `LocalStoragePersister`. Demo's `JsAppGraph`
wires this fallback chain.

To run the demo as an Electron desktop app, from `darkness-demo/develop/`:

```
./gradlew :electron:run
```

First run installs Electron locally (~250MB) and stages the web bundle —
takes a minute. Subsequent runs are instant. Theme picks + layout
state persist to the same shared darkness files notegrow uses, so a
theme change in one app surfaces in the other on next launch.

### Toolkit topbar layout — sidebar toggle + new-pane + layout buttons

Three more changes in `mountAppShell` based on user feedback that the demo
was missing icons notegrow had:

- **Sidebar toggle moved to leading slot** (left of the tab strip), using `buildLeftSidebarToggleButton` for proper styling. Was previously sitting in the trailing area, which didn't match termtastic / notegrow chrome.
- **Layout-presets button** added to default trailing actions, using `buildLayoutPresetButton`. In local mode (darkness-demo), selecting a preset writes the preset's geometry boxes onto the active tab's panes via `LayoutPreset.computeBoxes(paneCount)` and persists. Source-mode apps either ignore the click (no panes own geometry from the toolkit) or supply their own layout button via `extraTopbarTrailing`.
- **New-pane button** added to default trailing actions, using `buildNewWindowButton`. In local mode, adds a fresh pane to the active tab at a centered float position (un-maximising existing panes so the new one is visible). Source-mode apps no-op (their pane creation flows through their own model).

### Final build matrix

| Repo                | Task                                            | Result |
|---------------------|-------------------------------------------------|--------|
| darkness-toolkit    | `:toolkit-core:compileKotlinJs`                 | ✅      |
| darkness-toolkit    | `:toolkit-web:compileKotlinJs`                  | ✅      |
| notegrow            | `:web:compileKotlinJs`                          | ✅      |
| termtastic          | `:web:compileKotlinJs`                          | ✅      |
| darkness-demo       | `:web:compileKotlinJs`                          | ✅      |
| darkness-demo       | `:web:jsBrowserDevelopmentWebpack`              | ✅      |

## Out of scope

- Compose/Android/iOS shells. This plan is `toolkit-web`-only; the same
  shape (Persister interface + spec-based mount) can be replicated per
  platform later but isn't part of the same change.
- The theme editor's UI surface itself. It's already in the toolkit and
  works; it gets *more* useful once the layout tokens are exposed (Track 2)
  but no theme-editor changes are required to land that.
- A redesign of how panes are tracked / activated. The current
  LayoutRenderer is fine; this plan just calls it from a higher-level
  assembler.
- Per-app aesthetic variants. Explicitly **not** building any
  `.dt-chrome-classic` opt-in or termtastic-specific look toggle — the
  toolkit ships exactly one chrome appearance and both apps conform to it.
