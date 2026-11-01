import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Mirrors `@braccato/parsers`.
version = "0.4.0"

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The same Android settings as Kodama NEXT: Compose 1.12 needs compileSdk 37.
    android {
        namespace = "braccato.parsers"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":braccato-types"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
