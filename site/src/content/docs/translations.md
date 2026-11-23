---
title: "Translations & Romanization"
description: Show a translation or a romanization under each line, now or once it arrives.
---

A translation or a romanization hangs under its line. The views take them from a `decorations`
function, asked once for each line.

## From the lyrics themselves

By default the views show what the file carried: a line's first translation and its romanization, timed
when the file times it. That is `lyricDecorations`, and it needs no setup.

## From somewhere else

A host that translates or romanizes on its own passes its own function. It is asked again whenever it
changes or a state it reads changes, so decorations can stream in:

```kotlin
val romaji = remember { mutableStateMapOf<Int, String>() }   // filled in as it is generated

BraccatoLyrics(
    lyrics = built,
    playback = { /* ... */ },
    decorations = { line ->
        lyricDecorations(line).copy(
            romanization = romaji[line.index]?.let { buildRomanization(line.content!!.main.text, line.language, it) },
        )
    },
)
```

`buildTranslation(mainText, text, language)` and `buildRomanization(mainText, lineLanguage, text,
timedParts)` return null when the text is the line itself, so nothing is shown twice.

## How they come and go

A decoration that arrives fades in and drops into place, and the lines below slide to make room. One that
goes fades and lifts out before the lines close up. The line being sung stays where it is on screen while
the others move.
