---
title: "Parsing"
description: Read TTML, LRC, SRT, QRC and plain text into braccato's lyric shapes.
---

`braccato-parsers` reads lyrics files into `Lyric` lines, each with its timed `LyricPart`s when the file
times words or syllables.

## Any format

`detectParser` looks at the text and picks the parser for it. It tries TTML, then LRC, SRT and QRC, and
reads anything else as plain lines, so it never fails.

```kotlin
val parser = detectParser(text)
val lyrics: List<Lyric> = parser.parse(text, songDurationMs)
val songwriters: List<String> = parser.metadata(text).songwriters
```

Every parser has the same three functions: `detect(input)`, `parse(input, duration)` and
`metadata(input)`. The parsers are `TTMLParser`, `LRCParser`, `SRTParser`, `QRCParser` and
`PlainParser`, for a host that knows the format already.

## What a line holds

| Field | |
|---|---|
| `startTimeMs`, `durationMs` | When the line is sung, in milliseconds |
| `words` | The whole line's text |
| `parts` | Its timed pieces, for word or syllable synced lyrics; null when only the line is timed |
| `agent` | Who sings it: `v1`, `v2`, … for single voices, `v1000` for all together |
| `translations`, `romanization`, `timedRomanization` | What the file carries beside the line |
| `isInstrumental` | A break without singing |

A `LyricPart` holds `startTimeMs`, `durationMs` and `words`, and marks `isBackground` for background
vocals, with `backgroundGroup` numbering each group of them in a line.

## Instrumental breaks

Silences longer than five seconds become instrumental lines: before the first line, between lines and,
when the song's duration is passed, after the last. A view shows a note there instead of holding the
last line.

## Format by format

### TTML

`parseTTMLContent` takes options the plain parser leaves out, and says more about the document:

```kotlin
val result = parseTTMLContent(ttml, ParseTTMLOptions(songDurationMs = 215_000.0, instrumentalGapMs = 4000.0))
result.lyrics        // the lines
result.isWordSynced  // whether words or syllables are timed
result.language      // xml:lang, when the document gives it
```

It reads background vocals (`ttm:role="x-bg"`), singers (`ttm:agent`), explicit words, translations and
transliterations. `parseTTMLAgentNames` reads the singers' names, by the agent ids the lines carry.

### LRC

`LRCParser.parse` reads plain and enhanced LRC and then evens out the word timing with `lrcFixers`,
which word-by-word LRC with paired timestamps needs. For a source whose timings are already clean, call
`parseLRC` instead, which leaves them as they are.

### QRC

`parseQRC(qrc, songDurationMs, QrcMetadata(title, artist))` reads QQ Music's `<QrcInfos>` envelope or a
bare QRC body. With the metadata, opening lines that only repeat the title or the artist are dropped.

:::tip
Parse once when the lyrics arrive, not on every frame.
:::
