rootProject.name = "Lunula"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":lunula-core")
include(":lunula-store")
include(":lunula-web")
include(":lunula-compose")

// In-tree demo app — exercises the toolkit as a pure consumer (web + Electron).
// Lives under demo/ so module paths stay namespaced (`:demo:client`, etc.) and
// can never collide with toolkit modules. Demo modules deliberately skip the
// maven-publish plugin so they never leak into consumer libs-repos.
include(":demo:client")
include(":demo:web")
include(":demo:electron-main")
include(":demo:electron")
