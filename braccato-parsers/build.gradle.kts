import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}


kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions { optIn.add("dev.kiyoshi.braccato.types.internal.InternalBraccatoApi") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The same Android settings as Kodama NEXT: Compose 1.12 needs compileSdk 37.
    android {
        namespace = "dev.kiyoshi.braccato.parsers"
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
    // braccato's mark on this module's pages too, which carry their own stylesheets.
    pluginsConfiguration.html {
        customAssets.from(rootProject.file("docs/icon.png"))
        customStyleSheets.from(rootProject.file("docs/dokka-logo.css"))
    }
    dokkaSourceSets.configureEach {
        // Every public declaration is documented; the build says which one is not.
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/KiyoshiTheDevil/braccato-kotlin/tree/main/braccato-parsers/src")
        }
    }
}

// The coordinates and the POM come from gradle.properties; the javadoc jar is Dokka's HTML.
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
}
