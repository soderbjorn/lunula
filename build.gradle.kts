plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

allprojects {
    group = "se.soderbjorn.darkness"
    version = "0.2.4"
}

// Default file-Maven-repo locations inside the two consumer worktrees. Each
// consumer commits its libs-repo so it can build without the toolkit checkout.
val termtasticLibsRepoDefault: String = "../../termtastic/main/libs-repo"
val notegrowLibsRepoDefault: String = "../../notegrow/main/libs-repo"

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
                    name = "TermtasticLibsRepo"
                    url = uri(resolveRepo("termtasticLibsRepo", termtasticLibsRepoDefault))
                }
                maven {
                    name = "NotegrowLibsRepo"
                    url = uri(resolveRepo("notegrowLibsRepo", notegrowLibsRepoDefault))
                }
            }
        }
    }
}

tasks.register("publishAllToLibsRepo") {
    group = "publishing"
    description = "Publishes every toolkit module to the libs-repo of both consumer repos (termtastic and notegrow)."
    // Filter to toolkit-* modules only — demo modules deliberately don't apply
    // maven-publish, so they have no publishAllPublicationsTo* tasks to depend
    // on. Filtering by name keeps the dependency list resolvable at config
    // time and prevents demo artifacts from ever being published.
    dependsOn(
        subprojects
            .filter { it.name.startsWith("toolkit-") }
            .flatMap { sub ->
                listOf(
                    "${sub.path}:publishAllPublicationsToTermtasticLibsRepoRepository",
                    "${sub.path}:publishAllPublicationsToNotegrowLibsRepoRepository",
                )
            }
    )
}
