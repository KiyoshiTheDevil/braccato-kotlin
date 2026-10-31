import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Mirrors `@braccato/core`, the part of it that is not the DOM. See UPSTREAM.md.
version = "1.17.1"

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, so an Android module can take the library as it is.
    compilerOptions { optIn.add("braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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
