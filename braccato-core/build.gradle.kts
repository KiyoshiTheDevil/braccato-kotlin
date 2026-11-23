import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
}

// Mirrors `@braccato/core`, the part of it that is not the DOM. See UPSTREAM.md.
version = "1.17.1"

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions { optIn.add("braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The same Android settings as Kodama NEXT: Compose 1.12 needs compileSdk 37.
    android {
        namespace = "braccato.core"
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

dokka {
    dokkaSourceSets.configureEach {
        // Every public declaration is documented; the build says which one is not.
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/KiyoshiTheDevil/braccato-kotlin/tree/main/braccato-core/src")
        }
    }
}
