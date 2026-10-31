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
| `braccato-compose` | `@braccato/core` renderer and styles | Compose renderer: scroll and stage layouts (desktop for now) |

The modules are Kotlin Multiplatform with a JVM target (Java 17 bytecode). `braccato-types`,
`braccato-parsers` and `braccato-core` carry no UI and work on desktop and Android as they are.
`braccato-compose` is written in `commonMain` against Compose Multiplatform 1.12; it builds for the
desktop JVM today, and an Android target is a build change rather than a code change.

`braccato-core` is everything upstream's renderer decides from numbers alone: how lines are split
into words and grouped, which lines are active at a time, when and where the view scrolls, how a seek
is told apart from playback, the stage layout, the letter swipe and wave, and the theme settings.
What upstream does with the DOM and Web Animations is the renderer's job, and in this port that is
`braccato-compose`. [UPSTREAM.md](UPSTREAM.md) says what was left out and why.

`braccato-compose` draws every effect from the lyric time on each frame, rather than starting
animations and keeping them in step: the word swipe and its glow, the word wobble and the letter
wave, line zoom, the instrumental note filling up, and the per-line stagger as the view scrolls. The
time is read only while drawing, so playing a song recomposes nothing. Its defaults are upstream's
stylesheet values, and a Better Lyrics theme's `--blyrics-*` properties and `blyrics-*` settings
apply on top (`BraccatoTheme.fromCss`).

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

With Compose:

```kotlin
import braccato.compose.BraccatoLyrics
import braccato.compose.PlaybackSnapshot

BraccatoLyrics(
    lyrics = remember(lyrics) { buildLyrics(lyrics, SetLyricsOptions(songwriters = songwriters)) },
    playback = { PlaybackSnapshot(timeS = player.positionS, isPlaying = player.isPlaying) },
    theme = BraccatoTheme(fontFamily = myFont, fontSize = 32.dp),
    onSeek = { player.seekTo(it) },
)
```

`BraccatoStage` is the subtitle layout. `rememberBraccatoLyricsState()` reports when the user has
scrolled the view away (`resumeAffordanceVisible`) and brings autoscroll back (`resumeAutoscroll()`).

Lyric times are milliseconds as `Double`, as in upstream; built lines and the timeline work in
seconds, as upstream's engine does. `parseTTMLContent`, `parseLRC` and `parseQRC` give access to the
extra options the format parsers take.

## Building

Requires JDK 21 (the build targets Java 17 bytecode).

```bash
./gradlew allTests
```

The demo plays a lyric file against a clock of its own, optionally under a Better Lyrics theme:

```bash
./gradlew :demo:run --args="<lyrics file> [theme.css]"
```

`RenderTest` renders the view off screen to `braccato-compose/build/snapshots`; with
`BRACCATO_RENDER_FILE` set it renders that file too and reports what a frame costs.

## License

MIT, as upstream. See [LICENSE](LICENSE).
