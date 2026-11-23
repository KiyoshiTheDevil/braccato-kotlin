# Changelog

This port's own versions. Which upstream release each one carries is in [UPSTREAM.md](UPSTREAM.md).

## 0.1.0

The first published version.

- Carries `@braccato/types` 1.1.0, `@braccato/parsers` 0.4.3 and `@braccato/core` 1.22.0
- `braccato-types`: the lyric data shapes
- `braccato-parsers`: TTML, LRC (plain and enhanced), SRT, QRC and plain text, with instrumental breaks, credits and singers' names
- `braccato-core`: building lines, the timeline (clock, active lines, scrolling, passive scroll), the stage layout and theme settings, without a DOM
- `braccato-compose`: the scrolling and the stage views for Compose Multiplatform, desktop JVM and Android
- Better Lyrics themes apply through `BraccatoTheme.fromCss`
- Right-to-left lyrics: letters, word groups, glow and font as upstream draws them
