plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.mavenPublish) apply false
}

allprojects {
    group = "se.soderbjorn.lunula"
    version = "0.2.74"
}

// The toolkit publishes to a single file-Maven-repo whose location is supplied
// by the caller via `-Plunula.publishTarget=…`. This keeps lunula agnostic of
// who consumes it: each consumer repo owns a `refreshLunula` task that invokes
// this build with its own libs-repo as the target (see each consumer's
// build.gradle.kts). When no target is given the artifacts land in a throwaway
// dir inside this build, so a bare `publishAllToLibsRepo` never writes into
// someone else's tree by accident.
val publishTargetDefault: String = layout.buildDirectory.dir("local-libs-repo").get().asFile.path

fun resolveRepo(propertyName: String, default: String): java.io.File {
    val configured = providers.gradleProperty(propertyName).orNull ?: default
    val asFile = file(configured)
    return if (asFile.isAbsolute) asFile else rootProject.projectDir.resolve(configured)
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "LibsRepo"
                    url = uri(resolveRepo("lunula.publishTarget", publishTargetDefault))
                }
            }
        }
    }
}

// Maven Central publishing. The vanniktech plugin generates what Central
// requires and a hand-rolled `maven-publish` setup does not: sources and
// javadoc jars for every Kotlin Multiplatform target, the root module metadata
// that ties the platform variants together, and PGP signatures on all of it.
// Credentials (`mavenCentralUsername`/`Password`) and the signing key
// (`signingInMemoryKey`/`Password`) come from ~/.gradle/gradle.properties and
// are deliberately absent from this repo.
//
// Applied only to `lunula-*` modules. The demo modules under demo/ must never
// be published, which today is enforced by them not applying `maven-publish`;
// the name filter below preserves that guarantee independently.
val toolkitPomDescriptions: Map<String, String> = mapOf(
    "lunula-core" to "Lunula UI toolkit — themes, appearance and persistence primitives shared across platforms.",
    "lunula-store" to "Lunula UI toolkit — observable layout, world and settings state.",
    "lunula-web" to "Lunula UI toolkit — the web implementation: layout, tabs, windows, theming and DOM helpers.",
    "lunula-compose" to "Lunula UI toolkit — Compose Multiplatform components and palette.",
)

subprojects {
    if (!name.startsWith("lunula-")) return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // `true` is automaticRelease: the deployment is released as soon as Central finishes
        // validating it, rather than waiting in the portal for somebody to press Publish. The
        // no-argument form defaults to false, and the resulting silence is what makes it a
        // trap — `publishToMavenCentral` reports BUILD SUCCESSFUL either way, so a release
        // looks finished while the artifacts are not actually resolvable. Consuming apps pin
        // a version at release time and then cannot build until the button is pressed.
        //
        // The cost is that there is no longer a point at which a deployment can be inspected
        // and abandoned, and Central is append-only: a version, once released, is permanent.
        // The gate this removes was never much of one — its whole content was a click.
        publishToMavenCentral(true)
        signAllPublications()
        pom {
            name.set(this@subprojects.name)
            description.set(
                toolkitPomDescriptions[this@subprojects.name]
                    ?: "Lunula UI toolkit — ${this@subprojects.name}.",
            )
            inceptionYear.set("2026")
            url.set("https://github.com/soderbjorn/lunula")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/soderbjorn/lunula/blob/main/LICENSE")
                }
            }
            developers {
                developer {
                    id.set("soderbjorn")
                    name.set("Robert Söderbjörn")
                    url.set("https://www.soderbjorn.se")
                }
            }
            scm {
                url.set("https://github.com/soderbjorn/lunula")
                connection.set("scm:git:git://github.com/soderbjorn/lunula.git")
                developerConnection.set("scm:git:ssh://git@github.com/soderbjorn/lunula.git")
            }
        }
    }
}

tasks.register("publishAllToLibsRepo") {
    group = "publishing"
    description = "Publishes every toolkit module to the file-Maven-repo given by -Plunula.publishTarget (default: build/local-libs-repo)."
    // Filter to lunula-* modules only — demo modules deliberately don't apply
    // maven-publish, so they have no publishAllPublicationsTo* tasks to depend
    // on. Filtering by name keeps the dependency list resolvable at config
    // time and prevents demo artifacts from ever being published.
    dependsOn(
        subprojects
            .filter { it.name.startsWith("lunula-") }
            .map { sub -> "${sub.path}:publishAllPublicationsToLibsRepoRepository" }
    )
}
