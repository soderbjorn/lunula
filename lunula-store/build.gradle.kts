import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "LunulaStore"
            isStatic = true
        }
    }

    jvm {
        // Pin desktop-JVM bytecode to Java 11 (matching androidTarget) so
        // consumers bundling a Java 17 runtime — e.g. lunamux's Electron
        // app — can load these classes. Without this the jvm() target inherits
        // whatever JDK Gradle runs on (currently 21), emitting class file
        // version 65.0 that a 17 JRE rejects with UnsupportedClassVersionError.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js(IR) {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":lunula-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "se.soderbjorn.lunula.store"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
