---
title: "Stage Lyrics"
description: BraccatoStage shows only the lines being sung, as subtitles.
---

`BraccatoStage` shows only what is being sung: the current line at the bottom of its space, the line
before it above while the two overlap, the next waiting out of sight. It suits a video or a full-screen
player.

```kotlin
BraccatoStage(
    lyrics = built,
    playback = { PlaybackSnapshot(timeS = player.positionS, isPlaying = player.isPlaying) },
    modifier = Modifier.fillMaxSize(),
    onStageLayout = { box -> backdrop = box },
)
```

Lines are centred, as subtitles are. In a duet, when both sides sing, each singer takes a side, mirrored
for right-to-left lines, and lines sung by everyone stay in the middle.

## A backdrop behind the text

`onStageLayout` reports where the lines being sung sit, as a `StageBox` in the view's coordinates, or null
when nothing sung is on stage. A host draws a darkened or blurred patch from it, so the text reads over a
bright video.

The other parameters (`theme`, `tickOptions`, `decorations`, `voices`) are those of
[Scrolling Lyrics](/scrolling-lyrics/).
