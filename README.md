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
| `braccato-types` | `@braccato/types` 1.1.0 | ported |
| `braccato-parsers` | `@braccato/parsers` 0.4.3 | ported: TTML, LRC, SRT, QRC, Plain |
| `braccato-core` | `@braccato/core` 1.22.0 | ported: the engine's logic, without the DOM |
| `braccato-compose` | `@braccato/core` renderer and styles | Compose renderer: scroll and stage layouts |

Not ported: `@braccato/provider-blyrics`, the chain that fetches lyrics from providers, and
`@braccato/rics`, which compiles RICS themes to CSS. An app brings its own lyrics sources, and a RICS
theme is compiled first and then handed over as CSS (`BraccatoTheme.fromCss`).

Every module is Kotlin Multiplatform with two targets: the desktop JVM (Java 17 bytecode) and
Android (compileSdk 37, which Compose 1.12 requires, and minSdk 26), built with AGP's
`com.android.kotlin.multiplatform.library` plugin. All code is in `commonMain` but the grapheme
splitting, which uses `java.text.BreakIterator` on desktop and ICU's on Android.

Each module passes on what its API is made of: `braccato-compose` brings `braccato-core` (for
`buildLyrics` and `SetLyricsOptions`) and Compose's runtime and UI; `braccato-core` brings
`braccato-types`. A consumer needs `braccato-compose`, and `braccato-parsers` to read files.

To use the working copy from another Gradle build, include it there; Gradle then builds it along:

```kotlin
// settings.gradle.kts
includeBuild("../braccato-kotlin")

// build.gradle.kts, in commonMain
implementation("braccato:braccato-compose")
implementation("braccato:braccato-parsers")
```

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
import dev.kiyoshi.braccato.core.LyricsTimeline
import dev.kiyoshi.braccato.core.TickOptions
import dev.kiyoshi.braccato.core.buildLyrics
import dev.kiyoshi.braccato.parsers.detectParser

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
import dev.kiyoshi.braccato.compose.BraccatoLyrics
import dev.kiyoshi.braccato.compose.PlaybackSnapshot

BraccatoLyrics(
    lyrics = remember(lyrics) { buildLyrics(lyrics, SetLyricsOptions(songwriters = songwriters)) },
    playback = { PlaybackSnapshot(timeS = player.positionS, isPlaying = player.isPlaying) },
    theme = BraccatoTheme(fontFamily = myFont, fontSize = 32.dp),
    onSeek = { player.seekTo(it) },
)
```

`playback` can hand over a player's position as it is, even one that moves in coarse steps: Media3's
`ExoPlayer.currentPosition` changes about ten times a second, and while a song plays the view carries
a reading the player keeps repeating forward on the wall clock (at most 250 ms, never backwards), so
the swipe still moves every frame. A host that knows when its reading was taken can pass
`sampledAtMs` instead, and the view extrapolates from that.

Translations and romanizations can arrive after the song is built. `decorations` is asked again for a
line whenever the host passes another function or a state the function reads changes, and a
decoration that comes or goes fades in or out under its line while the lines below slide to make room,
as upstream's do:

```kotlin
val romaji = remember { mutableStateMapOf<Int, String>() }   // filled in as it is generated

BraccatoLyrics(
    lyrics = built,
    playback = { ... },
    decorations = { line ->
        lyricDecorations(line).copy(
            romanization = romaji[line.index]?.let { buildRomanization(line.content!!.main.text, line.language, it) },
        )
    },
)
```

Lyrics built again for the same song, with nothing but other translations or romanizations on their
lines, are taken as the same song too: the view keeps its scroll and place and only the decorations
change, as upstream's `songKey` swap does. `decorations` is still the lighter way, since nothing is
built twice.

`BraccatoStage` is the subtitle layout. `rememberBraccatoLyricsState()` reports when the user has
scrolled the view away (`resumeAffordanceVisible`) and brings autoscroll back (`resumeAutoscroll()`).

Lyric times are milliseconds as `Double`, as in upstream; built lines and the timeline work in
seconds, as upstream's engine does. `parseTTMLContent`, `parseLRC` and `parseQRC` give access to the
extra options the format parsers take.

## Building

Requires JDK 21 (the build targets Java 17 bytecode) and the Android SDK with platform 37, found
through `sdk.dir` in `local.properties` or `ANDROID_HOME`.

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
