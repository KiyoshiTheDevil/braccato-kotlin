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
| `braccato-core` | `@braccato/core` 1.17.1 | ported: the engine's logic, without the DOM |
| `braccato-compose` | `@braccato/core` renderer | not yet: Compose renderer |

The modules are Kotlin Multiplatform with a JVM target (Java 17 bytecode), so desktop and Android
apps can depend on them as they are. All code but one grapheme helper is in `commonMain`.

`braccato-core` is everything upstream's renderer decides from numbers alone: how lines are split
into words and grouped, which lines are active at a time, when and where the view scrolls, how a seek
is told apart from playback, the stage layout, the letter swipe and wave, and the theme settings.
What upstream does with the DOM and Web Animations is the renderer's job, and in this port that is
`braccato-compose`. [UPSTREAM.md](UPSTREAM.md) says what was left out and why.

## Usage

```kotlin
import braccato.core.LyricsTimeline
import braccato.core.TickOptions
import braccato.core.buildLyrics
import braccato.parsers.detectParser

val parser = detectParser(text)
val lyrics = parser.parse(text, songDurationMs)
val songwriters = parser.metadata(text).songwriters

// Once per song:
val timeline = LyricsTimeline(buildLyrics(lyrics))

// Once per frame, with the playback time in seconds and where the lines are laid out:
val frame = timeline.tick(playerTimeS, TickOptions(isPlaying = true), now = wallClockMs, viewport)
```

Lyric times are milliseconds as `Double`, as in upstream; built lines and the timeline work in
seconds, as upstream's engine does. `parseTTMLContent`, `parseLRC` and `parseQRC` give access to the
extra options the format parsers take.

## Building

Requires JDK 21 (the build targets Java 17 bytecode).

```bash
./gradlew allTests
```

## License

MIT, as upstream. See [LICENSE](LICENSE).
