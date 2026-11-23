plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("dev.kiyoshi.braccato.types.internal.InternalBraccatoApi") }
}

dependencies {
    implementation(project(":braccato-compose"))
    implementation(project(":braccato-parsers"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "braccato.demo.MainKt"
    }
}
