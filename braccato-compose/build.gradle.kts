import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.dokka)
}

// The renderer `@braccato/core` builds as DOM, drawn with Compose instead.
version = "1.17.1"

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions { optIn.add("dev.kiyoshi.braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The same Android settings as Kodama NEXT: Compose 1.12 needs compileSdk 37.
    android {
        namespace = "dev.kiyoshi.braccato.compose"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":braccato-core"))
            // Composables, Modifier, Color and Dp are in this module's own API.
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(project(":braccato-parsers"))
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        // Every public declaration is documented; the build says which one is not.
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/KiyoshiTheDevil/braccato-kotlin/tree/main/braccato-compose/src")
        }
    }
}
