---
title: "Playback"
description: Hand the view the player's position, and how it stays smooth.
---

The views read `playback` once per frame. It answers a `PlaybackSnapshot`:

```kotlin
PlaybackSnapshot(
    timeS = player.positionS,     // seconds into the song
    isPlaying = player.isPlaying,
    playbackRate = 1.0,           // for players that play faster or slower
    sampledAtMs = null,           // when the time was read, on the view's wall clock
)
```

## Players that move in steps

Some players report their position only now and then: Media3's `ExoPlayer.currentPosition` changes about
ten times a second. Hand it over as it is. While a song plays, the view carries a reading the player
keeps repeating forward on the wall clock, never more than 250 ms and never backwards, so the sweep
moves on every frame.

A host that knows when it read the position can pass `sampledAtMs`, and the view extrapolates from that
instead.

## Seeks

A time that moves further than playback explains is a seek: the view resets its effects and scrolls to
the new place. A small move while paused leaves the highlight where it is, unless
`TickOptions(followPausedSeeks = true)` asks to follow it, as an editor scrubbing a paused song needs.

## Offsets

`TickOptions` carries what the user or the song sets:

| | |
|---|---|
| `globalLyricOffset` | The user's own offset, in seconds, taken off the player's time |
| `lyricOffset` | The song's offset, in seconds |
| `richsyncOffsetTrim`, `lineOffsetTrim` | Taken off the theme's own timing offsets |
| `smoothScroll` | Animate scrolls, rather than jump |

The theme's timing offsets are in `BraccatoTheme.timingOffsets`.
