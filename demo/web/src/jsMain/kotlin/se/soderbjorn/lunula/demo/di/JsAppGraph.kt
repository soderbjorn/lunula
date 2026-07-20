/* JsAppGraph.kt (jsMain)
 * Metro DI graph for the demo's web target. Provides only what
 * `mountAppShell` needs: a long-lived coroutine scope and a Persister.
 * The demo's panes show a static label, so there's no document /
 * registry / per-pane VM to wire — see Main.kt's pane factory. */
package se.soderbjorn.lunula.demo.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.LocalStoragePersister
import se.soderbjorn.lunula.web.tryElectronIpcPersister

/**
 * App-scoped DI graph.
 *
 * Exposes:
 * - [coroutineScope] — long-lived scope for the toolkit's persister IO.
 * - [persister] — durable KV bridge to the toolkit's persistence
 *   helpers; backed by Electron IPC when present and namespaced
 *   `localStorage` in a plain browser.
 */
@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val coroutineScope: CoroutineScope
    val persister: Persister

    @SingleIn(AppScope::class) @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope

    @SingleIn(AppScope::class) @Provides
    fun providePersister(): Persister =
        // In Electron (preload script installed `globalThis.darknessApi`),
        // persist to the OS-conventional shared lunula files. In a plain
        // browser, fall back to namespaced localStorage so the dev-server
        // path still works without any host setup.
        tryElectronIpcPersister() ?: LocalStoragePersister(namespace = "lunulademo")
}

/** Constructs the singleton graph at app boot. */
fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()
