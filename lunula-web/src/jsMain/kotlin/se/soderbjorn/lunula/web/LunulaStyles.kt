/**
 * Stylesheet exposure helper for the lunula web components.
 *
 * Toolkit components (modal, top bar, sidebar, pane divider, theme editor,
 * …) rely on the `.dt-*` class names defined in `lunula.css`. The
 * stylesheet is bundled both as a Kotlin string constant ([lunulaToolkitCss])
 * and as a `jsMain/resources/lunula.css` file. Apps that want the
 * default visual at boot call [injectLunulaStyles]; apps that have
 * their own stylesheet pipeline can read [lunulaToolkitCss] directly and
 * inject it however they like.
 *
 * Resource-delivery choice: the CSS source is embedded into the compiled
 * Kotlin/JS bundle via a Gradle codegen step (see `build.gradle.kts`), with
 * the `.css` file in `jsMain/resources/` serving as the single source of
 * truth. This sidesteps the awkwardness of reading bundled klib resources
 * at runtime in Kotlin/JS while still letting consumers pick the file off
 * disk if they prefer.
 */
package se.soderbjorn.lunula.web

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLStyleElement

/**
 * The toolkit stylesheet as a string. Source-of-truth lives in
 * `lunula-web/src/jsMain/resources/lunula.css`; the build embeds
 * it into the compiled Kotlin/JS bundle.
 */
val lunulaToolkitCss: String
    get() = LUNULA_CSS_BUNDLE

/** Marker attribute used to detect a previously-injected toolkit stylesheet. */
private const val MARKER_ATTR: String = "data-lunula"

/**
 * Inject the lunula stylesheet into the document so the `.dt-*`
 * class names used by toolkit components have visual styles. Idempotent —
 * subsequent calls are no-ops if the stylesheet has already been mounted
 * under [target].
 *
 * @param target the element to append the `<style>` tag to. Defaults to
 *   `document.head`. Pass a Shadow DOM root to scope toolkit styles to a
 *   subtree.
 */
fun injectLunulaStyles(target: HTMLElement = document.head as HTMLElement) {
    val existing = target.querySelector("style[$MARKER_ATTR]")
    if (existing != null) return
    val style = document.createElement("style") as HTMLStyleElement
    style.setAttribute(MARKER_ATTR, "")
    style.textContent = lunulaToolkitCss
    target.appendChild(style)
    // Tag the body with `dt-electron-mac` when running inside an Electron
    // renderer on macOS. Apps additionally toggle `dt-custom-titlebar`
    // (via [setDtCustomTitleBarBodyClass]) whenever their BrowserWindow is
    // currently using `titleBarStyle: "hiddenInset"`; the toolkit's
    // stylesheet pads `.dt-topbar` left by ~80 px when both classes are
    // present so the floating traffic-light buttons don't overlap the
    // sidebar toggle / first tab. Non-mac Electron and the default macOS
    // chrome leave the second class off and get no extra padding.
    //
    // Apps that want the padding to disappear while the window is in
    // macOS native fullscreen (traffic lights hidden) used to wire
    // [setDtMacFullscreenBodyClass] manually to their BrowserWindow's
    // `enter-full-screen` / `leave-full-screen` events; the toolkit now
    // does it automatically via [autoWireMacFullscreenBodyClass] as long
    // as the host exposes `globalThis.darknessApi.onFullscreenChange`.
    autoApplyElectronMacBodyClass()
    autoApplyCustomTitleBarBodyClass()
    autoWireMacFullscreenBodyClass()
}

/**
 * Toggle the `dt-custom-titlebar` body class.
 *
 * Apps call this whenever their Electron BrowserWindow's `titleBarStyle`
 * flips between `"hiddenInset"` (custom titlebar — class on) and the
 * default chrome (class off). Combined with `dt-electron-mac` (set by
 * [autoApplyElectronMacBodyClass]), the toolkit's stylesheet reserves
 * ~80 px on the left of `.dt-topbar` so the OS traffic-light cluster
 * doesn't overlap the topbar's leading content.
 *
 * Idempotent — safe to call repeatedly with the same value.
 *
 * @param enabled `true` when the host window currently uses
 *   `titleBarStyle: "hiddenInset"`; `false` otherwise.
 *
 * @see setDtMacFullscreenBodyClass — suppresses the reservation while
 *   the window is in macOS native fullscreen (traffic lights hidden).
 */
fun setDtCustomTitleBarBodyClass(enabled: Boolean) {
    val body = document.body ?: return
    if (enabled) body.classList.add("dt-custom-titlebar")
    else body.classList.remove("dt-custom-titlebar")
}

/**
 * Toggle the `dt-mac-fullscreen` body class.
 *
 * Apps call this whenever their Electron BrowserWindow's macOS native
 * fullscreen state changes — i.e. on `enter-full-screen` (`enabled = true`)
 * and `leave-full-screen` (`enabled = false`) BrowserWindow events,
 * plus once at window construction so the initial state is correct
 * (e.g. macOS may relaunch the app directly into a restored fullscreen
 * Space).
 *
 * Combined with `dt-electron-mac` and `dt-custom-titlebar`, the toolkit's
 * stylesheet drops the ~80 px traffic-light reservation on `.dt-topbar`
 * while the window is in native fullscreen — macOS hides the
 * traffic-light cluster entirely in that state, so the reservation
 * would render as dead whitespace.
 *
 * Idempotent — safe to call repeatedly with the same value. No-op on
 * non-mac Electron and in plain browsers (the reservation rule is
 * gated on `dt-electron-mac` regardless).
 *
 * @param enabled `true` when the host window is currently in macOS
 *   native fullscreen; `false` otherwise.
 *
 * @see setDtCustomTitleBarBodyClass — opts in to the reservation in the
 *   first place; this helper only suppresses it while fullscreen.
 */
fun setDtMacFullscreenBodyClass(enabled: Boolean) {
    val body = document.body ?: return
    if (enabled) body.classList.add("dt-mac-fullscreen")
    else body.classList.remove("dt-mac-fullscreen")
}

/**
 * Toggle `dt-custom-titlebar` on `<body>` from the host Electron app's
 * authoritative boot-time flag, exposed as `globalThis.darknessApi.customTitleBar`.
 *
 * Why this exists: `useCustomTitleBar` is a per-app preference that is no
 * longer carried in the persisted theme snapshot, and the stock
 * [ElectronIpcPersister] doesn't round-trip it, so apps using the default
 * persistence pipeline lose the flag across restarts.
 * The main process keeps its own `electron-chrome.json` cache and uses
 * that to pick `titleBarStyle` at BrowserWindow construction; passing the
 * same boolean into the renderer via preload (`darknessApi.customTitleBar`)
 * lets the toolkit's CSS rule fire synchronously on the first frame
 * instead of waiting for the async persister read — which never delivers
 * the value in the first place.
 *
 * In plain browsers (no `darknessApi`) or in apps that haven't exposed
 * the boolean yet (`darknessApi.customTitleBar === undefined`), this is a
 * no-op and the body class stays whatever the toolkit's later
 * [AppShellMount] subscriber sets it to.
 *
 * Idempotent — the underlying [setDtCustomTitleBarBodyClass] is too.
 *
 * @see setDtCustomTitleBarBodyClass — the body-class toggle this drives.
 * @see autoApplyElectronMacBodyClass — companion auto-apply for the
 *   `dt-electron-mac` gating class.
 */
fun autoApplyCustomTitleBarBodyClass() {
    document.body ?: return
    val raw: dynamic = js(
        """
        (function() {
            try {
                if (typeof globalThis !== 'undefined'
                    && globalThis.darknessApi
                    && typeof globalThis.darknessApi.customTitleBar === 'boolean') {
                    return globalThis.darknessApi.customTitleBar;
                }
            } catch (e) { }
            return null;
        })()
        """
    )
    if (raw == null) return
    setDtCustomTitleBarBodyClass(raw as Boolean)
}

/**
 * Subscribe to the host Electron app's macOS fullscreen state and mirror
 * it onto the `dt-mac-fullscreen` body class via [setDtMacFullscreenBodyClass].
 *
 * Detection contract: the host preload script must expose
 * `globalThis.darknessApi.onFullscreenChange(handler)` where `handler` is
 * invoked with a single boolean (`true` while in macOS native fullscreen,
 * `false` otherwise) once at boot and again on every
 * `enter-full-screen` / `leave-full-screen` transition. The handler is
 * expected to return an unsubscribe function but the toolkit doesn't
 * keep it — the subscription lives for the lifetime of the renderer.
 *
 * In plain browsers (no `darknessApi`) and in Electron apps that haven't
 * exposed the bridge yet, this is a no-op. Idempotent: subsequent calls
 * after the first successful subscription short-circuit.
 *
 * Called automatically from [injectLunulaStyles] so every app
 * that mounts the toolkit chrome gets fullscreen-aware traffic-light
 * padding for free.
 *
 * @see setDtMacFullscreenBodyClass — the body-class toggle this drives.
 * @see autoApplyElectronMacBodyClass — companion auto-wire for the
 *   `dt-electron-mac` gating class.
 */
fun autoWireMacFullscreenBodyClass() {
    val body = document.body ?: return
    if (body.getAttribute(MAC_FULLSCREEN_WIRED_ATTR) != null) return
    val darknessApi: dynamic = js("(typeof globalThis !== 'undefined' && globalThis.darknessApi) || null")
    if (darknessApi == null) return
    val onFullscreenChange: dynamic = darknessApi.onFullscreenChange
    val isFn = js("typeof onFullscreenChange === 'function'") as Boolean
    if (!isFn) return
    body.setAttribute(MAC_FULLSCREEN_WIRED_ATTR, "")
    onFullscreenChange({ enabled: Boolean -> setDtMacFullscreenBodyClass(enabled) })
}

/** Marker on `<body>` so [autoWireMacFullscreenBodyClass] only subscribes once. */
private const val MAC_FULLSCREEN_WIRED_ATTR: String = "data-dt-mac-fullscreen-wired"

/**
 * Add `dt-electron-mac` to `<body>` when running inside an Electron
 * renderer on macOS. Detection is intentionally permissive: we look at
 * `globalThis.darknessApi.isElectronMac`, the user-agent navigator
 * platform string, and `process?.platform === "darwin"`. Idempotent.
 *
 * Apps may use the class to apply their own platform-conditional
 * styling (e.g. reserving space for the floating traffic lights when
 * `titleBarStyle: "hiddenInset"` is in use). Non-mac Electron puts
 * window controls on the right and needs no such handling, hence the
 * platform-specific class.
 */
fun autoApplyElectronMacBodyClass() {
    val body = document.body ?: return
    if (body.classList.contains("dt-electron-mac")) return
    val isMacElectron = js(
        """
        (function() {
            try {
                if (typeof globalThis !== 'undefined' && globalThis.darknessApi
                    && globalThis.darknessApi.isElectronMac === true) return true;
                var nav = (typeof navigator !== 'undefined') ? navigator : null;
                var ua = nav && (nav.userAgent || '');
                var isElectron = ua && ua.indexOf('Electron') !== -1;
                var isMac = nav && (
                    /Mac/.test(nav.platform || '') ||
                    /Mac/.test(ua)
                );
                if (isElectron && isMac) return true;
                if (typeof process !== 'undefined' && process.versions
                    && process.versions.electron && process.platform === 'darwin') return true;
            } catch (e) { }
            return false;
        })()
        """
    ) as Boolean
    if (isMacElectron) body.classList.add("dt-electron-mac")
}
