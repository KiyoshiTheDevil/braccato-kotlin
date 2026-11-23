plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish) apply false
}

subprojects {
    // The same as GROUP in gradle.properties, which is what the modules publish under.
    group = "dev.kiyoshi"
}

// The API reference for all four modules in one site: ./gradlew dokkaGenerate, then build/dokka/html.
dependencies {
    dokka(project(":braccato-types"))
    dokka(project(":braccato-parsers"))
    dokka(project(":braccato-core"))
    dokka(project(":braccato-compose"))
}

dokka {
    moduleName.set("braccato-kotlin")
}
