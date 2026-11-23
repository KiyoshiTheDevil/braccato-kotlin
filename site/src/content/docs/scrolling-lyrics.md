---
title: "Scrolling Lyrics"
description: BraccatoLyrics, the view that scrolls with the song.
---

`BraccatoLyrics` shows every line and scrolls to keep the ones being sung in place. Words light up with a
sweep and a glow as they are sung, word groups nudge as they start, long words wave letter by letter, and
the line being sung grows a little.

```kotlin
BraccatoLyrics(
    lyrics = built,
    playback = { PlaybackSnapshot(timeS = player.positionS, isPlaying = player.isPlaying) },
    theme = BraccatoTheme(fontSize = 32.dp),
    onSeek = { player.seekTo(it) },
    footer = { Text("Lyrics from …") },
)
```

| Parameter | |
|---|---|
| `lyrics` | Built with `buildLyrics`; a new value is a new song |
| `playback` | Read once per frame, see [Playback](/playback/) |
| `theme` | Look and motion, see [Theming](/theming/) |
| `tickOptions` | The user's offsets and preferences |
| `decorations` | What hangs under each line, see [Translations & Romanization](/translations/) |
| `onSeek` | Called with a time in seconds when a line is tapped |
| `footer` | Shown at the very end, after the credits: the place to name the source |
| `voices` | Who sings, see [Singer Labels](/singer-labels/) |
| `active` | False while the view is out of sight: it stays built, but asks for a frame a second |

## Scrolling by hand

When the user scrolls, the view stops following the song for a while, so they can read where they
landed. `rememberBraccatoLyricsState()` tells when that is, and brings the view back:

```kotlin
val state = rememberBraccatoLyricsState()
BraccatoLyrics(lyrics = built, playback = { /* ... */ }, state = state)

if (state.resumeAffordanceVisible) {
    Button(onClick = { state.resumeAutoscroll() }) { Text("Back to the song") }
}
```

## Unsynced lyrics

Lyrics without timing drift down on their own while the song plays, when `TickOptions(passiveScrollEnabled
= true)` asks for it, and pause when the user scrolls.
