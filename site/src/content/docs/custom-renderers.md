---
title: "Custom Renderers"
description: Drive your own renderer with braccato-core's timeline.
---

`braccato-core` is everything the views decide from numbers alone. An app that draws lyrics in another
way takes it on its own and asks a `LyricsTimeline` once per frame:

```kotlin
val timeline = LyricsTimeline(buildLyrics(lyrics))   // once per song

// Once per frame, with the player's time in seconds and where the lines are laid out:
val frame = timeline.tick(playerTimeS, TickOptions(isPlaying = true), now = wallClockMs, viewport)
```

`viewport` is a `ScrollViewport`: the view's height and scroll, and each line's top and height. Without
it, nothing about scrolling is decided.

## What a frame says

| | |
|---|---|
| `status` | `SYNCED`, `UNSYNCED`, `PLACEHOLDER` or `SKIPPED` |
| `lyricTime` | The time the lines are matched against, offsets applied |
| `activeLines`, `animatingLines` | Which lines are sung, and which run their effects |
| `scroll` | Where to scroll, whether to animate, and which line to follow |
| `passiveScrollTop` | For unsynced lyrics, where the view drifts to |
| `stageRoles` | For the stage layout, each line's role |

When the user scrolls, call `timeline.noteUserScroll(now)`; `resumeAutoscroll()` brings it back.

The stage layout is in `planStage` and `layoutStage`, and the theme settings in `CoreSettings` and
`setThemeSettings`.
