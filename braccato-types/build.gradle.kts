import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Mirrors `@braccato/types`.
version = "1.0.0"

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, so an Android module can take the library as it is.
    compilerOptions { optIn.add("braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
