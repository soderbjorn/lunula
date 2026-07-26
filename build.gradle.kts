plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

allprojects {
    group = "se.soderbjorn.lunula"
    version = "0.2.60"
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
