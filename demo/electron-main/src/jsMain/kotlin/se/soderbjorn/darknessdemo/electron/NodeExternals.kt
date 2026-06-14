/* Minimal `external` surface for the Node modules main.js used:
 * fs (sync read), fs/promises (write/mkdir/rename/read), os (homedir),
 * path (join/dirname), and process (platform/env/argv).
 *
 * Node modules are loaded via `kotlin.js.require` rather than
 * `@JsModule` because Kotlin's CommonJS output already emits
 * `require("…")` for `external` decls and we want the plain runtime
 * `require("fs/promises")` lookup the Electron main process gets at
 * boot. */
package se.soderbjorn.darknessdemo.electron

import kotlin.js.Promise

private val nodeRequire: dynamic = js("require")

internal val fsPromises: FsPromises = nodeRequire("fs/promises").unsafeCast<FsPromises>()
internal val fsSync: FsSync = nodeRequire("fs").unsafeCast<FsSync>()
internal val osModule: OsModule = nodeRequire("os").unsafeCast<OsModule>()
internal val pathModule: PathModule = nodeRequire("path").unsafeCast<PathModule>()

external interface FsPromises {
    fun mkdir(path: String, options: dynamic = definedExternally): Promise<dynamic>
    fun writeFile(path: String, data: dynamic): Promise<Unit>
    fun rename(oldPath: String, newPath: String): Promise<Unit>
    fun readFile(path: String, encoding: String): Promise<String>
}

external interface FsSync {
    fun readFileSync(path: String, encoding: String): String
    fun writeFileSync(path: String, data: String)
    fun mkdirSync(path: String, options: dynamic = definedExternally)
}

external interface OsModule {
    fun homedir(): String
}

external interface PathModule {
    fun join(vararg parts: String): String
    fun dirname(p: String): String
}

internal external val process: NodeProcess

external interface NodeProcess {
    val platform: String
    val env: dynamic
    val argv: Array<String>
}
