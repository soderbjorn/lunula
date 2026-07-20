# Lunula theming system

How themes are modeled, resolved, persisted, and edited across the Lunula app family (`termtastic`, `notegrow`, future siblings).

The toolkit owns the data model, codec, resolver, and editor UI. Apps declare which concrete panes they render and plug in their own persistence backend.

---

## The shape of a theme

A theme is a small, app-agnostic data class living in `lunula-core`:

```kotlin
data class Theme(
    val name: String,
    val mode: ConfigMode = ConfigMode.Both,   // Dark / Light / Both
    val colorScheme: String,                  // baseline scheme name
    val sections: Map<String, String> = emptyMap(),       // universal section -> scheme name
    val paneOverrides: Map<String, String> = emptyMap(),  // concrete pane -> scheme name
)
```

Three layers, in resolution order from most-specific to most-generic:

```
   theme.paneOverrides[pane]                    // explicit per-pane override
       ↓ if absent
   theme.sections[paneToSection[pane]]          // universal section assignment
       ↓ if absent
   theme.colorScheme                            // theme baseline
```

A theme assigns colour schemes by *name* (looked up in `recommendedColorSchemes` ∪ the user's `customSchemes`), never by value, so the same theme renders identically wherever it is loaded.

---

## Universal sections

The toolkit defines a fixed vocabulary of "sections" — visual roles every app shares. Themes are written against this vocabulary so a theme designed in one app looks coherent in another.

```kotlin
object Sections {
    const val Main = "main"           // dominant content surface
    const val Sidebar = "sidebar"     // primary navigation column
    const val Tabs = "tabs"           // tab strip
    const val Chrome = "chrome"       // window chrome / titlebar
    const val Active = "active"       // focus rings / active-pane indicators
    const val Windows = "windows"     // pane frames / split borders
    const val Auxiliary = "auxiliary" // secondary content panels
    const val BottomBar = "bottomBar" // status / footer
}
```

The set is intentionally small and fixed. A free-form section namespace would re-fragment the cross-app contract — a theme that defines `myCustomTier = "Foo"` is dead weight in any other app. The fixed set is the contract; per-pane overrides give back any local flexibility users need.

Adding a new universal section is a toolkit version bump, deliberately.

### Section display labels

The Theme Editor renders these as user-facing rows:

| Section | UI label |
|---|---|
| `Main` | Main content |
| `Sidebar` | Sidebar |
| `Tabs` | Tab strip |
| `Chrome` | Window chrome |
| `Active` | Active indicators |
| `Windows` | Window frames |
| `Auxiliary` | Auxiliary panels |
| `BottomBar` | Bottom bar |

---

## App pane maps

Each app declares a `Map<String, String>` mapping its concrete pane names to universal sections. The toolkit doesn't ship this map — it's app code.

```kotlin
// termtastic/.../web/AppPanes.kt
val termtasticPanes: Map<String, String> = mapOf(
    "terminal"    to Sections.Main,
    "sidebar"     to Sections.Sidebar,
    "tabs"        to Sections.Tabs,
    "chrome"      to Sections.Chrome,
    "active"      to Sections.Active,
    "windows"     to Sections.Windows,
    "diff"        to Sections.Auxiliary,
    "fileBrowser" to Sections.Auxiliary,
    "git"         to Sections.Auxiliary,
    "bottomBar"   to Sections.BottomBar,
)

// notegrow/.../main/AppPanes.kt
val notegrowPanes: Map<String, String> = mapOf(
    "editor"    to Sections.Main,
    "sidebar"   to Sections.Sidebar,
    "tabs"      to Sections.Tabs,
    "chrome"    to Sections.Chrome,
    "active"    to Sections.Active,
    "windows"   to Sections.Windows,
    "starred"   to Sections.Auxiliary,
    "outline"   to Sections.Auxiliary,
    "bottomBar" to Sections.BottomBar,
)
```

The pane map is supplied to the toolkit two places:

1. To the resolver — `resolveActiveTheme(snapshot, appearance, systemIsDark, paneToSection)`.
2. To the Theme Manager host — `host.appPanes` controls which per-pane override rows the editor exposes for the *current* app.

---

## Resolving a paint

The toolkit ships `resolveActiveTheme` (commonMain) and `resolveActiveUiSettings` (jsMain).

`resolveActiveTheme(snapshot, appearance, systemIsDark, paneToSection): ResolvedThemeBundle`:

1. Pick slot: dark slot if `appearance == Dark` (or `Auto + systemIsDark`), else light slot. Fall back to `DEFAULT_DARK_THEME_NAME` / `DEFAULT_LIGHT_THEME_NAME`.
2. Look up the slot name in `snapshot.customThemes ∪ defaultThemes`. Fall back to a synthetic `Theme(name=slot, colorScheme=slot)` if neither matches (so a name that's only a scheme still applies).
3. Resolve the theme's `colorScheme` via `recommendedColorSchemes ∪ snapshot.customSchemes` → the main `ColorScheme`.
4. Walk `paneToSection` and resolve each pane through the layered ladder above → `Map<String, ColorScheme>` keyed by concrete pane name.

Returns `ResolvedThemeBundle(theme, paneSchemes)`. The web helper `resolveActiveUiSettings` wraps this to return a fresh `UiSettings(theme, appearance, paneSchemes)`.

---

## `UiSettings` — the per-paint wire

`UiSettings` is the resolved snapshot the painter consumes:

```kotlin
data class UiSettings(
    val theme: ColorScheme,                                  // baseline / fallback
    val appearance: Appearance,
    val paneSchemes: Map<String, ColorScheme> = emptyMap(),  // concrete pane -> scheme
) {
    fun schemeForPane(pane: String): ColorScheme = paneSchemes[pane] ?: theme
}
```

`paneSchemes` is open-ended — apps populate only the panes they render. A pane absent from the map paints with the main `theme`. The web painter (`applyUiSettings`) iterates this map and writes per-pane CSS variables to the matching DOM containers.

JSON wire is flat: `{"theme": "...", "appearance": "...", "theme.<pane>": "..."}`. Compatible with both single-blob and flat-KV storage backends.

---

## `ThemeSnapshot` — the persisted state

Everything *except* the per-paint `UiSettings` lives in the snapshot — slot selections, custom themes/schemes, favorites, font preferences:

```kotlin
data class ThemeSnapshot(
    val lightThemeName: String? = null,
    val darkThemeName: String? = null,
    val customThemes: Map<String, Theme> = emptyMap(),
    val customSchemes: Map<String, CustomScheme> = emptyMap(),
    val favoriteThemes: List<String> = emptyList(),
    val favoriteSchemes: List<String> = emptyList(),
    val fontFamily: String? = null,
    val fontSizePx: Int? = null,
    val useCustomTitleBar: Boolean = false,
)
```

Two encodings, same field set:

- `encodeAsJsonObject(): JsonObject` — single blob, e.g. one localStorage key (notegrow).
- `encodeAsStringMap(): Map<String, String>` — flat KV with nested objects JSON-stringified into the value, e.g. termtastic's server `SettingsPersister(persistSettings: Map<String,String>)`.

Reading is symmetric: `ThemeSnapshot.fromJsonObject(obj)` / `ThemeSnapshot.fromJsonString(json)`. No backwards-compat reader — data that doesn't match parses as "no value present" and the corresponding field stays at the toolkit default.

### Wire keys

```
theme.light            // string
theme.dark             // string
favorites.themes       // JSON array of strings
favorites.schemes      // JSON array of strings
themeConfigs           // JSON object: name -> { mode, colorScheme, sections, paneOverrides }
customSchemes          // JSON object: name -> { darkFg, lightFg, darkBg, lightBg, overrides }
paneFontFamily         // string
paneFontSize           // int
electronCustomTitleBar // bool
```

---

## The flow at runtime

```
            ┌───────────────────────────────┐
            │  Storage (app-supplied)       │
            │  notegrow: localStorage       │
            │  termtastic: server settings  │
            └───────────────┬───────────────┘
                            │ read / write JSON
                            ▼
            ┌───────────────────────────────┐
            │  ThemeSnapshot (lunula-core) │
            │  fromJsonObject / encodeAs*   │
            └───────────────┬───────────────┘
                            │ applySnapshot / toSnapshot
                            ▼
            ┌───────────────────────────────┐
            │  DefaultThemeManagerState     │
            │  (lunula-web — mutable bag)  │
            └───────────────┬───────────────┘
                            │ resolveActiveUiSettings(state, base, paneToSection)
                            ▼
            ┌───────────────────────────────┐
            │  UiSettings (lunula-core)    │
            │  theme + paneSchemes          │
            └───────────────┬───────────────┘
                            │ applyUiSettings(documentElement, settings, isDark)
                            ▼
                    DOM (CSS variables)
```

Every theme-state mutation (theme pick, appearance cycle, custom-theme save, favorite toggle) goes through one path:

1. Mutate `DefaultThemeManagerState` via `ThemeManagerHost` setters.
2. The host's `onChange` callback runs.
3. App calls `resolveActiveUiSettings(state, base, paneToSection)` to recompute `UiSettings`.
4. App calls `applyUiSettings(...)` to repaint.
5. App writes `state.toSnapshot().encodeAsJsonObject().toString()` (or `.encodeAsStringMap()`) to its storage.

---

## Wiring an app

Minimum integration:

```kotlin
// 1. Declare the pane map
val myAppPanes: Map<String, String> = mapOf(
    "myMainPane" to Sections.Main,
    "mySidebar"  to Sections.Sidebar,
    // …
)

// 2. Construct the theme state + host
val themeState = DefaultThemeManagerState()
val themeHost = DefaultThemeManagerHost(
    state = themeState,
    _appPanes = myAppPanes,
    onChange = { onThemeStateChange() },
)

// 3. Wire storage (web example)
val themeStorage = localStorageThemeSnapshotStorage("myapp.themeSnapshot.v1")
themeStorage.read()?.takeIf { it.isNotBlank() }?.let { json ->
    themeState.applySnapshot(ThemeSnapshot.fromJsonString(json))
}

// 4. Resolve + paint
fun onThemeStateChange() {
    uiSettings = resolveActiveUiSettings(themeState, uiSettings, myAppPanes)
    applyUiSettings(document.documentElement, uiSettings, isDarkActive(themeState.appearance))
    themeStorage.write(themeState.toSnapshot().encodeAsJsonObject().toString())
}
```

Termtastic differs only in the storage step — it calls `state.toSnapshot().encodeAsStringMap()` and feeds the result to its server `persistSettings(Map<String,String>)`. Same codec, different transport.

---

## The Theme Editor UI

Two areas, driven entirely by the toolkit:

1. **Sections** (always visible) — eight rows in `Sections.all` order. Editing the Main row writes to `Theme.colorScheme`; other rows write to `Theme.sections[<section>]`. Empty value = inherit from the baseline.

2. **Per-pane overrides (advanced)** — a `<details>`, default closed. Iterates `host.appPanes` and renders one row per pane. Each row is labelled with the pane name and `inherits <SectionLabel>` in muted type; the empty-value swatch tracks the live inheritance ladder so the user sees what removing the override would resolve to.

App-aware: termtastic's editor exposes `git`/`diff`/`fileBrowser`; notegrow's exposes `starred`/`outline`. Overrides for panes the *current* app doesn't render are preserved on save — a theme edited in notegrow keeps termtastic's `git` override intact, and vice versa.

---

## Slot independence

Light and dark slots are independent:

- `ThemeSnapshot.lightThemeName` and `darkThemeName` persist separately.
- The active slot is picked at resolve time based on `appearance × systemIsDark`.
- `DefaultThemeManagerHost.setLightThemeName` and `setDarkThemeName` mutate only the named slot.
- Cycling appearance triggers `resolveActiveUiSettings`, which re-picks the slot — flipping mode immediately repaints with the right slot's theme.

Slot defaults: `DEFAULT_LIGHT_THEME_NAME = "Paper & Ink"` / `DEFAULT_DARK_THEME_NAME = "Neon Circuit"`.

---

## Future: per-instance theming (terminals, bullet nodes, …)

The model already accommodates per-instance theming for things like "this specific terminal in termtastic uses Cyber Teal" or "this specific bullet subtree in notegrow uses Solarized." Two routes, depending on whether the override should be theme-scoped or session-scoped.

### Route A — theme-scoped (works *today*, no toolkit changes)

If each instance has a stable id (`"terminal:abc123"`, `"bullet:def456"`), the id can go straight into `Theme.paneOverrides`:

```kotlin
theme.copy(
    paneOverrides = theme.paneOverrides + ("terminal:abc123" to "Cyber teal"),
)
```

The resolver doesn't validate keys — it just does `paneOverrides[paneName] ?: …`. The painter picks it up if the app feeds the same id into its `paneToSection` map at paint time. The snapshot codec round-trips it untouched. The Theme Editor wouldn't render the override (it iterates the *static* `host.appPanes`), but the data is preserved.

Downside: the override belongs to a theme. Switching themes drops it. That's the right shape for "this theme has a special look for this terminal" but the wrong shape for "this terminal stays cyan no matter what theme I pick."

### Route B — session-scoped (small toolkit change)

If the override should outlive theme switches, it belongs in `ThemeSnapshot`, not `Theme`:

1. Add a field to `ThemeSnapshot`:
   ```kotlin
   val instanceOverrides: Map<String, String> = emptyMap(), // instance id -> scheme name
   ```
2. Extend the resolver ladder by one level (most-specific first):
   ```
   snapshot.instanceOverrides[id]
       ?: theme.paneOverrides[id]
       ?: theme.sections[paneToSection[id]]
       ?: theme.colorScheme
   ```
3. Apps assign stable ids to instances and feed them through `paneToSection` (or expose a separate `instanceToSection` if the indirection becomes unwieldy).
4. The override is set from the instance's own UI (e.g. terminal context menu, bullet kebab) — *not* the Theme Editor, because it isn't theme-scoped.

The snapshot codec gains one more key (`instanceOverrides`) on the same shape it already supports for `paneOverrides`. Persistence backends and the wire format are otherwise unchanged.

### Why nothing prevents either route

- `Theme.paneOverrides` is `Map<String, String>` — open-ended.
- `UiSettings.paneSchemes` is `Map<String, ColorScheme>` — open-ended.
- The snapshot codec doesn't constrain map keys for `themeConfigs[*].paneOverrides` or `customSchemes`.
- `host.appPanes` is *only* a UI filter for the editor; the data layers below it are key-agnostic.

The one upfront design call when implementing per-instance theming: pick Route A or B based on whether the override is conceptually a property of the theme (route A) or of the app session (route B). Per-instance overrides usually feel session-scoped — users expect "this terminal is always cyan" rather than "this terminal is cyan only while the Tron theme is active." Route B is the better default fit.

---

## File map

```
lunula/develop/
├── lunula-core/src/commonMain/kotlin/se/soderbjorn/lunula/core/
│   ├── Sections.kt              ← universal section vocabulary
│   ├── DefaultThemes.kt         ← Theme data class + ~40 default themes
│   ├── UiSettings.kt            ← per-paint wire
│   ├── ThemeSnapshot.kt         ← persisted state codec
│   ├── ResolvedThemeBundle.kt   ← resolveActiveTheme / resolvePaneSchemes
│   └── ColorSchemes.kt          ← scheme definitions + CustomScheme
└── lunula-web/src/jsMain/kotlin/se/soderbjorn/lunula/web/
    ├── ThemeCssVars.kt          ← applyUiSettings (DOM painter)
    └── themeeditor/
        ├── ThemeManagerHost.kt          ← host interface (incl. appPanes)
        ├── DefaultThemeManagerHost.kt   ← state-backed host impl
        ├── ThemeSnapshotBridge.kt       ← state ↔ snapshot extensions
        ├── ThemeSnapshotStorage.kt      ← localStorage adapter
        ├── ResolveActiveUiSettings.kt   ← jsMain wrapper around resolveActiveTheme
        ├── ThemeEditor.kt               ← Sections + per-pane overrides UI
        └── ThemeManager.kt              ← top-level manager sidebar
```

Apps:

```
termtastic/develop/
├── client/src/commonMain/.../viewmodel/
│   ├── SettingsViewModel.kt           ← consumes ThemeSnapshot.fromJsonObject
│   └── AppBackingViewModel.kt         ← State.paneSchemes, persists via encodeAsStringMap
└── web/src/jsMain/.../
    ├── AppPanes.kt                    ← termtasticPanes
    └── ThemeManager.kt                ← TermtasticThemeManagerHost.appPanes

notegrow/develop/web/src/jsMain/.../main/
├── AppPanes.kt                        ← notegrowPanes
└── AppShell.kt                        ← snapshot persistence + slot fixes
```
