---
title: "Building"
description: Turn parsed lines into what the views draw, once per song.
---

`buildLyrics` turns the parsed lines into `BuiltLyrics`: each line split into words and word groups,
its background vocals in rows of their own, the way it reads and the language it is in.

```kotlin
val built = buildLyrics(
    lyrics,
    SetLyricsOptions(
        language = "ja",                // BCP 47; kana and Hangul are told without it
        songwriters = listOf("Ada", "Grace"),
    ),
)
```

Build when the song changes, not on every frame. In Compose, `remember(lyrics) { buildLyrics(lyrics) }`
does that.

## What comes out

| | |
|---|---|
| `lines` | Every line as a `BuiltLine`, instrumental breaks among them |
| `syncType` | `RICHSYNC` (words timed), `SYNCED` (lines timed) or `NONE` |
| `credits` | The credits line after the last lyric, or null |
| `allZero` | No line has a time: there is nothing to seek to |
| `isStageDuet` | Both sides sing, so the stage puts lines on sides |

A `BuiltLine` has its `time` and `duration` in seconds, its `agent`, `direction` and `language`, and its
`content`: the `main` line and the `backgrounds` under it, each a list of word groups and spaces.

## Placeholders

`SetLyricsOptions(noLyrics = true)` marks lyrics that stand in for a missing result, a "no lyrics found"
line, so the views do not try to follow them.

## The same song again

Lyrics built again with the same lines (timing, words, voices) are taken as the same song: a view keeps
its scroll and place and changes only what hangs on the lines. That is what happens when translations
arrive later and the lyrics are built once more.
