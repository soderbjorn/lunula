@file:JsModule("electron")
@file:JsNonModule

package se.soderbjorn.darknessdemo.electron

import kotlin.js.Promise

external val app: ElectronApp
external val ipcMain: IpcMain
external val shell: Shell

external interface ElectronApp {
    fun setName(name: String)
    fun requestSingleInstanceLock(): Boolean
    fun quit()
    fun on(event: String, listener: (dynamic, dynamic) -> Unit): ElectronApp
    fun whenReady(): Promise<Unit>
    /**
     * Returns a path to a special directory or file (e.g. `"userData"`
     * for the per-app config dir). See Electron docs for the full list.
     */
    fun getPath(name: String): String
}

external interface IpcMain {
    fun handle(channel: String, listener: (event: dynamic, arg: dynamic) -> dynamic)
}

external interface Shell {
    fun openExternal(url: String): Promise<Unit>
}

@JsName("BrowserWindow")
external class BrowserWindow(options: dynamic = definedExternally) {
    val webContents: WebContents
    fun isDestroyed(): Boolean
    fun isMinimized(): Boolean
    fun restore()
    fun focus()
    fun loadFile(filePath: String): Promise<Unit>
    /** Force-closes the window without firing the `close` event. */
    fun destroy()
    /** `true` while the window is in macOS native fullscreen, `false` otherwise. */
    fun isFullScreen(): Boolean
    /**
     * Subscribe to BrowserWindow lifecycle events. Used by [ElectronMain] to
     * relay `enter-full-screen` / `leave-full-screen` to the renderer via
     * the `fullscreen-changed` IPC, which the darkness-toolkit's
     * `autoWireMacFullscreenBodyClass` consumes.
     */
    fun on(event: String, listener: (event: dynamic) -> Unit)
}

external interface WebContents {
    fun setWindowOpenHandler(handler: (details: dynamic) -> dynamic)
    fun on(event: String, listener: (event: dynamic, url: String) -> Unit)
    fun getURL(): String
    /**
     * Send an IPC message to the renderer. Used by [ElectronMain] to push
     * the `fullscreen-changed` boolean to the renderer so darkness-toolkit
     * can toggle the `dt-mac-fullscreen` body class.
     */
    fun send(channel: String, vararg args: dynamic)
}

@JsName("Menu")
external object Menu {
    fun setApplicationMenu(menu: dynamic)
    fun buildFromTemplate(template: Array<dynamic>): dynamic
}
