/* ElectronMain.kt — Electron main process, written in Kotlin/JS.
 *
 * UI settings split across the cross-app `<DarknessDir>/themes.json`
 * (theme/scheme definitions shared with every Darkness app) and the
 * per-app `<DarknessDir>/darkness-demo.json` (selections + UI prefs).
 * Layout state stays per-app under `<DarknessDir>/DarknessDemo/`. The
 * toolkit owns the JSON shapes; this host owns where the bytes
 * physically land. */
package se.soderbjorn.darknessdemo.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.darkness.core.SHARED_THEMES_KEYS
import se.soderbjorn.darkness.core.mergeSharedThemes

private const val APP_NAME = "DarknessDemo"

/**
 * App-name stem for the per-app UI-settings file. Lower-kebab-case to
 * match the toolkit's `defaultAppUiSettingsPath` convention.
 */
private const val APP_NAME_KEBAB = "darkness-demo"

private var mainWindow: BrowserWindow? = null

/**
 * Cached window-chrome preference (custom title bar on/off). Read once
 * from disk at startup so [createWindow] can pick the right
 * `titleBarStyle` synchronously, and updated by the
 * `darkness:setCustomTitleBar` IPC handler on toggle. Defaults to `false`
 * (native OS title bar) on first launch.
 *
 * Stored at `<userData>/electron-chrome.json` — distinct from the
 * cross-app `themes.json` / per-app `darkness-demo.json` because
 * `titleBarStyle` is fixed at BrowserWindow construction and must be
 * readable before the renderer comes up.
 */
private var chromePrefs: ChromePrefs = ChromePrefs(customTitleBar = false)

private data class ChromePrefs(val customTitleBar: Boolean)

private fun chromePrefsPath(): String =
    pathModule.join(app.getPath("userData"), "electron-chrome.json")

/**
 * Read `electron-chrome.json` synchronously. Returns a default
 * (`customTitleBar = false`) on any failure — missing file, parse error,
 * permissions — since a cosmetic preference should never block window
 * creation.
 */
private fun loadChromePrefs(): ChromePrefs = try {
    val raw = fsSync.readFileSync(chromePrefsPath(), "utf8")
    val parsed: dynamic = js("JSON.parse(raw)")
    ChromePrefs(customTitleBar = parsed.customTitleBar == true)
} catch (_: Throwable) {
    ChromePrefs(customTitleBar = false)
}

/**
 * Write [prefs] to `electron-chrome.json`. Silently swallows errors —
 * the value still drives the current session via the in-memory
 * [chromePrefs]; the next launch just forgets the preference.
 */
private fun saveChromePrefs(prefs: ChromePrefs) {
    try {
        val opts: dynamic = js("({})")
        opts.recursive = true
        fsSync.mkdirSync(pathModule.dirname(chromePrefsPath()), opts)
        val payload: dynamic = js("({})")
        payload.customTitleBar = prefs.customTitleBar
        fsSync.writeFileSync(chromePrefsPath(), js("JSON.stringify(payload)") as String)
    } catch (_: Throwable) {
        // Cosmetic; the next launch just forgets the preference.
    }
}

fun main() {
    app.setName(APP_NAME)

    if (!app.requestSingleInstanceLock()) {
        app.quit()
        return
    }

    registerIpcHandlers()

    app.on("second-instance") { _, _ ->
        val w = mainWindow
        if (w != null && !w.isDestroyed()) {
            if (w.isMinimized()) w.restore()
            w.focus()
        }
    }

    app.on("window-all-closed") { _, _ -> app.quit() }

    app.whenReady().then {
        // `app.getPath("userData")` is only valid after `whenReady`, so
        // the chrome cache load is deferred until here.
        chromePrefs = loadChromePrefs()
        buildAppMenu()
        createWindow()
    }
}

/* --- Path resolution -------------------------------------------------- */

/**
 * Cross-app shared darkness themes file path: holds custom themes,
 * custom schemes, and favorites — read/written by every Darkness app
 * on this machine.
 */
private fun sharedThemesPath(): String =
    sharedDarknessPath("themes.json")

/**
 * Per-app UI-settings file path: holds the demo's selected theme
 * slots, appearance, fonts, sizes, app-specific toggles. Sibling of
 * [sharedThemesPath].
 */
private fun appUiSettingsPath(): String =
    sharedDarknessPath("$APP_NAME_KEBAB.json")

/** Per-app darkness layout-state file path. Stays under the app's own subdir. */
private fun defaultAppLayoutStatePath(): String =
    perAppPath("layout-state.json")

private fun perAppPath(filename: String): String {
    val home = osModule.homedir()
    return when (process.platform) {
        "darwin" ->
            pathModule.join(home, "Library", "Application Support", "Darkness", APP_NAME, filename)
        "win32" -> {
            val appData = (process.env.APPDATA as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, "AppData", "Roaming")
            pathModule.join(appData, "Darkness", APP_NAME, filename)
        }
        else -> {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, ".config")
            pathModule.join(xdg, "darkness", APP_NAME.lowercase(), filename)
        }
    }
}

/**
 * Resolve a path relative to the OS-conventional Darkness data
 * directory (the same root every Darkness app on this machine uses).
 *
 * - macOS: `~/Library/Application Support/Darkness/<filename>`
 * - Windows: `%APPDATA%\Darkness\<filename>`
 * - Linux: `$XDG_CONFIG_HOME/darkness/<filename>` (defaults to
 *   `~/.config/darkness/`).
 */
private fun sharedDarknessPath(filename: String): String {
    val home = osModule.homedir()
    return when (process.platform) {
        "darwin" ->
            pathModule.join(home, "Library", "Application Support", "Darkness", filename)
        "win32" -> {
            val appData = (process.env.APPDATA as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, "AppData", "Roaming")
            pathModule.join(appData, "Darkness", filename)
        }
        else -> {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, ".config")
            pathModule.join(xdg, "darkness", filename)
        }
    }
}

/* --- Boot snapshot + window ------------------------------------------ */

private fun readSync(p: String): String? = try {
    fsSync.readFileSync(p, "utf8")
} catch (_: Throwable) {
    null
}

/**
 * Synchronously read both the cross-app shared themes file and the
 * per-app UI-settings file and return a merged JSON-object string. The
 * per-app file's keys win on collisions.
 */
private fun readMergedUiSettingsJsonSync(): String? {
    val sharedRaw = readSync(sharedThemesPath())
    val appRaw = readSync(appUiSettingsPath())
    if (sharedRaw == null && appRaw == null) return null
    val sharedObj: dynamic = parseJsonObjectOrEmpty(sharedRaw)
    val perAppObj: dynamic = parseJsonObjectOrEmpty(appRaw)
    val merged: dynamic = js("({})")
    val sharedKeys: Array<String> = js("Object.keys(sharedObj)") as Array<String>
    for (k in sharedKeys) merged[k] = sharedObj[k]
    val perAppKeys: Array<String> = js("Object.keys(perAppObj)") as Array<String>
    for (k in perAppKeys) merged[k] = perAppObj[k]
    return js("JSON.stringify(merged)") as String
}

private fun parseJsonObjectOrEmpty(raw: String?): dynamic {
    if (raw.isNullOrBlank()) return js("({})")
    return try {
        val parsed: dynamic = js("JSON.parse(raw)")
        if (parsed != null && (js("typeof parsed === 'object'") as Boolean) &&
            !(js("Array.isArray(parsed)") as Boolean)
        ) parsed else js("({})")
    } catch (_: Throwable) {
        js("({})")
    }
}

/**
 * Per-key merge the outgoing shared-themes JSON with whatever is on
 * disk, returning a JSON string ready for atomic write. Bridges
 * between the JS-side string at the IPC boundary and the toolkit's
 * [mergeSharedThemes] helper.
 */
private fun mergeSharedThemesAtomically(outgoing: String): String {
    val outgoingObj = parseKxJsonObject(outgoing) ?: return outgoing
    val onDiskRaw = readSync(sharedThemesPath())
    val onDiskObj = parseKxJsonObject(onDiskRaw) ?: JsonObject(emptyMap())
    val merged = mergeSharedThemes(outgoingObj, onDiskObj)
    return Json.encodeToString(JsonObject.serializer(), merged)
}

private fun parseKxJsonObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private fun partitionUiSettingsJson(json: String): Pair<String, String> {
    val parsed: dynamic = parseJsonObjectOrEmpty(json)
    val sharedOut: dynamic = js("({})")
    val perAppOut: dynamic = js("({})")
    val keys: Array<String> = js("Object.keys(parsed)") as Array<String>
    for (k in keys) {
        if (SHARED_THEMES_KEYS.contains(k)) {
            sharedOut[k] = parsed[k]
        } else {
            perAppOut[k] = parsed[k]
        }
    }
    return (js("JSON.stringify(sharedOut)") as String) to (js("JSON.stringify(perAppOut)") as String)
}

private fun createWindow() {
    val settingsJson = readMergedUiSettingsJsonSync()
    val layoutJson = readSync(defaultAppLayoutStatePath())
    val additionalArguments = mutableListOf<String>()
    if (settingsJson != null) {
        additionalArguments += "--darkness-settings=${js("encodeURIComponent")(settingsJson)}"
    }
    if (layoutJson != null) {
        additionalArguments += "--darkness-layout-state=${js("encodeURIComponent")(layoutJson)}"
    }
    // Authoritative window-chrome flag, sourced from `electron-chrome.json`
    // cached by this process. The renderer can't recover this from the
    // toolkit's `ThemeSnapshot` because the stock `ElectronIpcPersister`
    // doesn't round-trip THEME_SNAPSHOT — we pass it directly so
    // darkness-toolkit's `autoApplyCustomTitleBarBodyClass` (called from
    // `injectDarknessToolkitStyles`) can deterministically toggle
    // `dt-custom-titlebar` at boot, before the persister read completes.
    additionalArguments += "--darkness-custom-titlebar=${chromePrefs.customTitleBar}"

    val options: dynamic = js("({})")
    options.width = 1024
    options.height = 720
    options.title = APP_NAME
    // Honour the persisted window-chrome preference. `hiddenInset` lets
    // the themed top-bar bleed across the title bar on macOS (with the
    // OS traffic-light cluster floating over the corner); the default
    // style restores the native OS title bar. `titleBarStyle` is fixed
    // at BrowserWindow construction — runtime toggles destroy this
    // window and create a new one (see the `darkness:setCustomTitleBar`
    // IPC handler).
    options.titleBarStyle = if (chromePrefs.customTitleBar) "hiddenInset" else "default"
    val webPreferences: dynamic = js("({})")
    webPreferences.contextIsolation = true
    webPreferences.nodeIntegration = false
    // Resource layout (owned by electron/build.gradle.kts):
    //   electron/main.js                    — stub that loads the Kotlin bundle
    //   electron/preload.js                 — preload (still JS)
    //   electron/resources/main/*.js        — this Kotlin bundle + its deps
    //   electron/resources/web/index.html   — the renderer
    // __dirname here resolves to electron/resources/main/, so we go up
    // two levels to reach electron/ for preload, and up one to reach
    // resources/ for the renderer.
    val moduleDir = js("__dirname") as String
    webPreferences.preload = pathModule.join(moduleDir, "..", "..", "preload.js")
    webPreferences.additionalArguments = additionalArguments.toTypedArray()
    options.webPreferences = webPreferences

    val w = BrowserWindow(options)
    mainWindow = w

    val externalScheme = Regex("^(https?|mailto|tel|ftps?):", RegexOption.IGNORE_CASE)

    w.webContents.setWindowOpenHandler { details ->
        val url = details.url as String
        if (externalScheme.containsMatchIn(url)) shell.openExternal(url)
        js("({ action: 'deny' })")
    }

    w.webContents.on("will-navigate") { event, url ->
        val current = w.webContents.getURL()
        if (url != current && externalScheme.containsMatchIn(url)) {
            event.preventDefault()
            shell.openExternal(url)
        }
    }

    w.loadFile(pathModule.join(moduleDir, "..", "web", "index.html"))

    // Mirror macOS native fullscreen state to the renderer so the toolkit
    // can drop its 80 px traffic-light reservation on `.dt-topbar` while
    // the OS hides the traffic-light cluster. The renderer side is wired
    // automatically by darkness-toolkit's `autoWireMacFullscreenBodyClass`
    // (called from `injectDarknessToolkitStyles`), so the only host
    // responsibility is emitting the boolean over the `fullscreen-changed`
    // IPC. Listeners are attached on every window construction because
    // `darkness:setCustomTitleBar` recreates the BrowserWindow.
    w.on("enter-full-screen") { _ ->
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", true)
    }
    w.on("leave-full-screen") { _ ->
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", false)
    }
    // Initial-state emit: macOS may relaunch directly into a restored
    // fullscreen Space, so wait for the renderer to be ready and push
    // the current value once. Subsequent changes flow via the events above.
    w.webContents.asDynamic().on("did-finish-load") {
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", w.isFullScreen())
    }
}

/* --- Atomic write ----------------------------------------------------- */

private suspend fun atomicWrite(target: String, json: String) {
    val opts: dynamic = js("({})")
    opts.recursive = true
    fsPromises.mkdir(pathModule.dirname(target), opts).await()
    val tmp = "$target.tmp"
    val bufferModule: dynamic = js("require")("buffer")
    val bytes = bufferModule.Buffer.from(json, "utf8")
    fsPromises.writeFile(tmp, bytes).await()
    fsPromises.rename(tmp, target).await()
}

/* --- IPC handlers ----------------------------------------------------- */

private fun registerIpcHandlers() {
    // The renderer treats UI settings as a single blob, but on disk we
    // split it across the cross-app `themes.json` (definitions) and the
    // per-app `<appName>.json` (selections). Partition logic lives in
    // toolkit-core's [SHARED_THEMES_KEYS]; we mirror it here at the disk
    // boundary.
    ipcMain.handle("darkness:writeUiSettings") { _, json ->
        GlobalScope.promise {
            val (sharedJson, perAppJson) = partitionUiSettingsJson(json as String)
            // Read-merge-write on `themes.json` so peer-app additions
            // survive even when our file watcher misses an event.
            val sharedFinal = mergeSharedThemesAtomically(sharedJson)
            atomicWrite(sharedThemesPath(), sharedFinal)
            atomicWrite(appUiSettingsPath(), perAppJson)
        }
    }
    ipcMain.handle("darkness:readUiSettings") { _, _ ->
        GlobalScope.promise<String?> { readMergedUiSettingsJsonSync() }
    }
    ipcMain.handle("darkness:writeLayoutState") { _, json ->
        GlobalScope.promise { atomicWrite(defaultAppLayoutStatePath(), json as String) }
    }
    ipcMain.handle("darkness:readLayoutState") { _, _ ->
        readJsonOrNull(defaultAppLayoutStatePath())
    }

    // Toggle the custom (themed) title bar. `titleBarStyle` is immutable
    // post-creation, so we persist the new value and recreate the
    // BrowserWindow with the requested style. All in-renderer state is
    // reconstructed from disk (`themes.json`, `darkness-demo.json`,
    // layout-state) so the reload is purely visual. Idempotent — calls
    // with the unchanged value short-circuit.
    ipcMain.handle("darkness:setCustomTitleBar") { _, enabled ->
        val next = enabled == true
        if (next != chromePrefs.customTitleBar) {
            chromePrefs = ChromePrefs(customTitleBar = next)
            saveChromePrefs(chromePrefs)
            val old = mainWindow
            createWindow()
            if (old != null && !old.isDestroyed()) old.destroy()
        }
        Unit
    }
}

private fun readJsonOrNull(path: String): Promise<String?> = GlobalScope.promise {
    try {
        fsPromises.readFile(path, "utf8").await()
    } catch (err: Throwable) {
        val code = (err.asDynamic().code as String?)
        if (code == "ENOENT") null else throw err
    }
}

/* --- App menu --------------------------------------------------------- */

private fun buildAppMenu() {
    val isMac = process.platform == "darwin"
    val template = ArrayList<dynamic>()

    if (isMac) {
        template.add(menuItem(APP_NAME) {
            arrayOf(
                role("about"),
                separator(),
                role("services"),
                separator(),
                role("hide"),
                role("hideOthers"),
                role("unhide"),
                separator(),
                role("quit"),
            )
        })
    }

    template.add(menuItem("Edit") {
        arrayOf(
            role("undo"),
            role("redo"),
            separator(),
            role("cut"),
            role("copy"),
            role("paste"),
            role("selectAll"),
        )
    })

    template.add(menuItem("View") {
        arrayOf(
            role("reload"),
            role("forceReload"),
            role("toggleDevTools"),
            separator(),
            role("resetZoom"),
            role("zoomIn"),
            role("zoomOut"),
            separator(),
            role("togglefullscreen"),
        )
    })

    val windowMenu: dynamic = js("({})")
    windowMenu.role = "window"
    windowMenu.submenu = if (isMac) {
        arrayOf(role("minimize"), role("zoom"), separator(), role("front"))
    } else {
        arrayOf(role("minimize"), role("close"))
    }
    template.add(windowMenu)

    Menu.setApplicationMenu(Menu.buildFromTemplate(template.toTypedArray()))
}

private inline fun menuItem(label: String, submenu: () -> Array<dynamic>): dynamic {
    val item: dynamic = js("({})")
    item.label = label
    item.submenu = submenu()
    return item
}

private fun role(role: String): dynamic {
    val item: dynamic = js("({})")
    item.role = role
    return item
}

private fun separator(): dynamic = js("({ type: 'separator' })")
