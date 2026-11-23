---
title: Installation
description: Add braccato-kotlin's modules from Maven Central.
---

braccato-kotlin is four Kotlin Multiplatform modules on Maven Central, for the desktop JVM and Android.

## Add the modules

Most apps need the Compose views and the parsers. The other two modules come along with them.

```kotlin title="build.gradle.kts"
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.kiyoshi:braccato-compose:0.1.0")
            implementation("dev.kiyoshi:braccato-parsers:0.1.0")
        }
    }
}
```

In a plain JVM or Android module, the same two lines go into `dependencies { }`.

An app that draws lyrics itself, without Compose, takes `braccato-core` instead of `braccato-compose`.
See [Custom Renderers](/custom-renderers/).

## Requirements

- Kotlin 2.4 and Compose Multiplatform 1.12
- The desktop JVM: Java 17 or newer at run time
- Android: `minSdk 26`, and `compileSdk 37` as Compose 1.12 asks for

:::note[Which Braccato is inside?]
The versions are this port's own. Each one names the upstream release it carries: 0.1.0 carries
`@braccato/core` 1.22.0, `@braccato/parsers` 0.4.3 and `@braccato/types` 1.1.0. The
[changelog](https://github.com/KiyoshiTheDevil/braccato-kotlin/blob/main/CHANGELOG.md) lists what changed.
:::

## Working on a copy

To work on braccato-kotlin from another Gradle build, include it there. Gradle then builds it along and
takes it in place of the published modules:

```kotlin title="settings.gradle.kts"
includeBuild("../braccato-kotlin")
```

```kotlin title="build.gradle.kts"
implementation("dev.kiyoshi:braccato-compose")
implementation("dev.kiyoshi:braccato-parsers")
```
