---
title: "Singer Labels"
description: Show the singers' names over the line being sung.
---

When a song has several singers, the views can show who sings the line being sung, in a small capsule
that splits when two sing together.

```kotlin
val names = parseTTMLAgentNames(ttml)   // by the agent ids the lines carry: v1, v2, v1000

BraccatoLyrics(
    lyrics = built,
    playback = { /* ... */ },
    voices = VoiceLabels(names = names),
)
```

The capsule shows only when two voices or more have a name. `VoiceLabels` also takes:

| | |
|---|---|
| `tint` | A colour for a singer's capsule, by agent id |
| `bottom` | How far above the view's bottom edge it sits |
| `backdrop`, `backdropOrigin` | What lies behind the lyrics, for the capsule to blur too, such as a video |
| `darken` | More darkening, for a bright picture behind it |
| `scale` | Its size against its own |

In a duet, lines take the side of their singer in the stage view.
