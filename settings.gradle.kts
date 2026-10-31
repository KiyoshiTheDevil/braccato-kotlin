rootProject.name = "braccato-kotlin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":braccato-types")
include(":braccato-parsers")
include(":braccato-core")
include(":braccato-compose")
include(":demo")
