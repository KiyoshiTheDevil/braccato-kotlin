---
title: Quickstart
description: From a lyrics file to lines that light up word by word, in three steps.
---

From a lyrics file to lines that light up word by word, in three steps.

## 1. Read the file

Hand the text to `detectParser`. It tells TTML, LRC, SRT and QRC apart, and reads anything else as plain
lines, one lyric per line of text.

```kotlin
import dev.kiyoshi.braccato.parsers.detectParser

val parser = detectParser(text)
val lyrics = parser.parse(text, songDurationMs)
val songwriters = parser.metadata(text).songwriters
```

The song's duration, in milliseconds, lets the parser add an instrumental line for the outro. Read the
file once when the lyrics arrive, not on every frame.

## 2. Build them once

Building splits each line into words, groups and background vocals, and works out which way it reads.
Do it when the song changes:

```kotlin
import dev.kiyoshi.braccato.core.SetLyricsOptions
import dev.kiyoshi.braccato.core.buildLyrics

val built = remember(lyrics) { buildLyrics(lyrics, SetLyricsOptions(songwriters = songwriters)) }
```

The songwriters, when there are any, are shown after the last line.

## 3. Show them

```kotlin
import dev.kiyoshi.braccato.compose.BraccatoLyrics
import dev.kiyoshi.braccato.compose.PlaybackSnapshot

BraccatoLyrics(
    lyrics = built,
    playback = { PlaybackSnapshot(timeS = player.positionS, isPlaying = player.isPlaying) },
    onSeek = { player.seekTo(it) },
)
```

`playback` is read once per frame, so a player's position can be handed over as it is. `onSeek` is
called with a time in seconds when a line is tapped; leave it out and the lines stay inert.

:::tip[A player that moves in steps?]
Media3's `ExoPlayer.currentPosition` changes about ten times a second. Hand it over as it is: while a
song plays, the view carries the last reading on by the wall clock, so the sweep still moves on every
frame. [Playback](/playback/) says more.
:::

## Next

- [Scrolling Lyrics](/scrolling-lyrics/): what it draws, and what the user's own scrolling does
- [Theming](/theming/): colours, sizes, fonts and which effects run
