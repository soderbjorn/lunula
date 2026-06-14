/* preload.js — exposes the darkness IPC bridge to the renderer.
 *
 * Same shape as notegrow's preload so any future toolkit-side code that
 * looks at `globalThis.darknessApi.{readUiSettings,writeUiSettings,readLayoutState,writeLayoutState}`
 * works identically across Darkness apps. */

const { contextBridge, ipcRenderer } = require("electron");

// Pre-loaded JSON snapshots packed into BrowserWindow.additionalArguments
// by main.js. Read by the toolkit / app at boot time as
// `globalThis.__darknessSettings` / `__darknessLayoutState`.
const settingsArg = (process.argv || []).find(a => a && a.startsWith("--darkness-settings="));
if (settingsArg) {
  contextBridge.exposeInMainWorld(
    "__darknessSettings",
    decodeURIComponent(settingsArg.substring("--darkness-settings=".length)),
  );
}
const layoutArg = (process.argv || []).find(a => a && a.startsWith("--darkness-layout-state="));
if (layoutArg) {
  contextBridge.exposeInMainWorld(
    "__darknessLayoutState",
    decodeURIComponent(layoutArg.substring("--darkness-layout-state=".length)),
  );
}

// Authoritative window-chrome flag passed by main.js. Parsed here so
// darkness-toolkit can synchronously toggle `dt-custom-titlebar` on
// boot — without it, the toolkit can only learn the state from the
// async ThemeSnapshot read, which the stock ElectronIpcPersister
// doesn't round-trip. Defaults to false when absent.
const customTitleBarArg = (process.argv || []).find(a => a && a.startsWith("--darkness-custom-titlebar="));
const customTitleBarBoot = customTitleBarArg
  ? customTitleBarArg.substring("--darkness-custom-titlebar=".length) === "true"
  : false;

contextBridge.exposeInMainWorld("darknessApi", {
  /**
   * Boot-time custom-titlebar flag from the main process's
   * `electron-chrome.json` cache. The toolkit's
   * `autoApplyCustomTitleBarBodyClass` reads this to toggle the
   * `dt-custom-titlebar` body class synchronously, so the 80 px
   * traffic-light reservation on `.dt-topbar` applies on the very
   * first frame (rather than after the async persister read).
   *
   * @type {boolean}
   */
  customTitleBar: customTitleBarBoot,
  /** Persist UI settings JSON to the shared darkness location. */
  writeUiSettings: (json) => ipcRenderer.invoke("darkness:writeUiSettings", json),
  /** Read UI settings JSON from the shared darkness location, or null. */
  readUiSettings: () => ipcRenderer.invoke("darkness:readUiSettings"),
  /** Persist this app's layout-state JSON atomically. */
  writeLayoutState: (json) => ipcRenderer.invoke("darkness:writeLayoutState", json),
  /** Read this app's layout-state JSON, or null on first launch. */
  readLayoutState: () => ipcRenderer.invoke("darkness:readLayoutState"),
  /**
   * Toggle the custom (themed) title bar on the Electron main window.
   *
   * `titleBarStyle` is a creation-time BrowserWindow option in Electron
   * and cannot be mutated on an existing window, so the main process
   * destroys the current window and creates a new one with the requested
   * style. All renderer state reloads from disk (themes, layout), so the
   * reload is purely visual.
   *
   * The value is cached in `<userData>/electron-chrome.json` so the next
   * cold start opens the window with the right chrome without a round
   * trip to the renderer.
   *
   * Called by the toolkit's renderer subscriber (`AppShellMount`) when
   * the user toggles the setting in the Settings sidebar.
   *
   * @param {boolean} enabled `true` for the themed window chrome, `false`
   *   for the native OS title bar.
   * @returns {Promise<void>}
   */
  setCustomTitleBar: (enabled) => ipcRenderer.invoke("darkness:setCustomTitleBar", enabled),
  /**
   * Subscribe to macOS native fullscreen state changes from the Electron
   * main process. Called by the toolkit's `autoWireMacFullscreenBodyClass`
   * helper, which mirrors the boolean onto the `dt-mac-fullscreen` body
   * class so the 80 px traffic-light reservation on `.dt-topbar` is
   * suppressed while the OS hides the traffic lights.
   *
   * The main process fires the `fullscreen-changed` IPC once on every
   * BrowserWindow `enter-full-screen` / `leave-full-screen` event, plus
   * once at window construction so the initial state is correct.
   *
   * @param {(enabled: boolean) => void} handler invoked with the new state.
   * @returns {() => void} Unsubscribe function.
   */
  onFullscreenChange: (handler) => {
    const wrapped = (_event, enabled) => handler(enabled === true);
    ipcRenderer.on("fullscreen-changed", wrapped);
    return () => ipcRenderer.removeListener("fullscreen-changed", wrapped);
  },
});
