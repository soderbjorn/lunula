/* Main.kt (jsMain)
 * Entry point. The demo's *only* responsibility: build the DI graph,
 * pick a per-pane content factory, and call mountAppShell. Everything
 * else — chrome, tabs, sidebar, theme manager, layout drag/resize,
 * persistence — is toolkit-driven.
 *
 * The pane body is intentionally a static, non-interactive label: the
 * demo exists to prove the toolkit shell + persistence boundary, not
 * to be a notes app. Apps that want editable content layer their own
 * view models on top — see notegrow for the full pattern. */
package se.soderbjorn.lunula.demo

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.web.shell.AppShellHandle
import se.soderbjorn.lunula.web.shell.AppShellSpec
import se.soderbjorn.lunula.web.shell.PaneOverflowSpec
import se.soderbjorn.lunula.web.shell.mountAppShell
import se.soderbjorn.lunula.demo.di.createJsAppGraph

/**
 * Pane titles the user has typed, keyed by pane id.
 *
 * The demo has no model of its own — the toolkit owns the tabs and panes in
 * local mode — so the one thing a host must still own for the overflow
 * menu's **Rename window** row to mean anything is *where the committed
 * label goes*. This map is that, in its smallest honest form: written by
 * [AppShellSpec.paneRename], read by [AppShellSpec.paneLabel]. It is
 * deliberately not persisted; the demo exists to show the seam, and a
 * label that survives a reload is the consuming app's job (see Lunicle's
 * `WorkspaceTab.paneLabels`).
 */
private val paneTitles: MutableMap<String, String> = mutableMapOf()

fun main() {
    val graph = createJsAppGraph()
    val root = document.getElementById("app") as HTMLElement

    // Set once mountAppShell returns; nothing reads it before then.
    var handle: AppShellHandle? = null

    // `darknessApi` is the preload-injected IPC bridge. Its presence is
    // also the toolkit's signal to expose the "Custom title bar" toggle
    // in the Settings sidebar (gear icon); in a plain browser it resolves
    // to `undefined` and the toggle stays hidden.
    val isElectron = js("typeof globalThis !== 'undefined' && globalThis.darknessApi != null") as Boolean

    // mountAppShell calls injectLunulaStyles, which both tags the
    // body with `dt-electron-mac` (via autoApplyElectronMacBodyClass) and
    // subscribes to `darknessApi.onFullscreenChange` (via
    // autoWireMacFullscreenBodyClass) so the toolkit's 80 px traffic-light
    // reservation on `.dt-topbar` activates and relaxes for fullscreen
    // automatically — no per-app wiring required.
    handle = mountAppShell(
        AppShellSpec(
            rootContainer = root,
            title = "LunulaDemo",
            persister = graph.persister,
            paneContent = ::buildPaneContent,
            // Every pane gets the `⋮`, and gets nothing in it the toolkit
            // does not already own — which is the point: the demo is a pure
            // consumer, so a Rename window / Move to tab menu that appears
            // here with no menu-building code is the shortest proof that
            // both rows are the toolkit's rather than an app's.
            paneOverflowMenu = { _, _ -> PaneOverflowSpec() },
            paneLabel = { _, paneId -> paneTitles[paneId] ?: "Pane $paneId" },
            // Committing the label is the host's half of the rename, and the
            // only half. An empty commit clears the override back to the
            // derived title, which is what `allowEmptyPaneRename` exists
            // for — see the flag's own docs.
            paneRename = { _, paneId, newLabel ->
                if (newLabel.isBlank()) paneTitles.remove(paneId) else paneTitles[paneId] = newLabel
                handle?.refresh()
            },
            allowEmptyPaneRename = true,
            isElectron = isElectron,
        ),
        scope = graph.coroutineScope,
    )
}

/**
 * Per-pane content factory. Returns a non-interactive `<div>` with a
 * single placeholder label naming the pane. The toolkit drops the
 * returned element into the pane's `.dt-pane-content` slot; the pane
 * title bar (with its leading icon and label) is rendered by the
 * toolkit chrome separately, so the body shouldn't repeat them.
 *
 * The label uses `--t-text` over a `--t-bg` surface — the post-revamp
 * theme tokens for primary text and the app background — so the demo's
 * pane reads correctly across every theme.
 */
private fun buildPaneContent(paneId: String): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.style.height = "100%"
    container.style.display = "flex"
    container.style.alignItems = "center"
    container.style.justifyContent = "center"
    container.style.padding = "16px"
    container.style.boxSizing = "border-box"
    container.style.background = "var(--t-bg, #1e1e1e)"
    container.style.color = "var(--t-text, #e6e6e6)"
    // Demo's sample pane is the canonical "code/terminal-style" content,
    // so it follows the toolkit's monospaced font category. Picking a
    // family or size in the gear's Settings sidebar takes effect here
    // via the `--dt-font-mono*` CSS chain populated by `applyMonoFontFamily/SizePx`.
    container.style.fontFamily = "var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace)"
    container.style.fontSize = "var(--dt-font-mono-size, 13px)"
    container.style.setProperty("user-select", "none")

    val label = document.createElement("div") as HTMLElement
    label.textContent = "Demo pane · $paneId"
    container.appendChild(label)
    return container
}
