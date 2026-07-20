plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

kotlin {
    js {
        browser()
    }

    sourceSets {
        jsMain.dependencies {
            api(project(":lunula-core"))
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.coroutines.core)
        }
        jsTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

val generatedCssKtDir = layout.buildDirectory.dir("generated/source/lunulaToolkitCss/jsMain/kotlin")

val generateLunulaCssKt by tasks.registering {
    val cssFile = layout.projectDirectory.file("src/jsMain/resources/lunula.css")
    val outDir = generatedCssKtDir
    inputs.file(cssFile)
    outputs.dir(outDir)
    doLast {
        val outFile = outDir.get()
            .dir("se/soderbjorn/lunula/web")
            .file("LunulaCssBundle.kt")
            .asFile
        outFile.parentFile.mkdirs()
        val raw = cssFile.asFile.readText()
        // Embed via Kotlin raw-string literal. The CSS file is required to not
        // contain ${'$'}{'"""'}; a guard below catches that at build time.
        check(!raw.contains("\"\"\"")) {
            "lunula.css must not contain triple-quote sequences"
        }
        // Escape interpolation markers so the raw string compiles.
        val safe = raw.replace("\$", "\${'\$'}")
        outFile.writeText(
            buildString {
                appendLine("package se.soderbjorn.lunula.web")
                appendLine()
                append("internal val LUNULA_CSS_BUNDLE: String = \"\"\"")
                append(safe)
                appendLine("\"\"\"")
            }
        )
    }
}

// Wire the generated CSS bundle as a source dir via the task provider so any
// downstream task that consumes jsMain sources (compileKotlinJs, jsSourcesJar,
// …) inherits the dependency automatically. A plain srcDir(file) only gets
// picked up by compileKotlinJs, leaving sourcesJar without a dependency edge.
kotlin.sourceSets.named("jsMain").configure {
    kotlin.srcDir(generateLunulaCssKt.map { generatedCssKtDir })
}
