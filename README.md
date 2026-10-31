# braccato-kotlin

A Kotlin port of [Braccato](https://github.com/better-lyrics/braccato), the synchronized lyrics engine
from [Better Lyrics](https://better-lyrics.boidu.dev), for apps built with Kotlin and Compose
Multiplatform, Kodama NEXT among them.

It is a port rather than a binding: no WebView, no JavaScript runtime. The code follows upstream file
for file so changes there can be carried over. [UPSTREAM.md](UPSTREAM.md) records which upstream
version this is, maps each file, and lists every difference made on purpose.

## Modules

| Module | Upstream | State |
|---|---|---|
| `braccato-types` | `@braccato/types` 1.0.0 | ported |
| `braccato-parsers` | `@braccato/parsers` 0.4.0 | ported: TTML, LRC, SRT, QRC, Plain |
| `braccato-core` | `@braccato/core` 1.17.1 | not yet: engine logic |
| `braccato-compose` | `@braccato/core` renderer | not yet: Compose renderer |

Both modules are Kotlin Multiplatform with a JVM target (Java 17 bytecode), so desktop and Android
apps can depend on them as they are. All code is in `commonMain`.

## Usage

```kotlin
import braccato.parsers.detectParser

val parser = detectParser(text)
val lyrics = parser.parse(text, songDurationMs)
val songwriters = parser.metadata(text).songwriters
```

Times are milliseconds as `Double`, as in upstream. `parseTTMLContent`, `parseLRC` and `parseQRC`
give access to the extra options the format parsers take.

## Building

Requires JDK 21 (the build targets Java 17 bytecode).

```bash
./gradlew allTests
```

## License

MIT, as upstream. See [LICENSE](LICENSE).
