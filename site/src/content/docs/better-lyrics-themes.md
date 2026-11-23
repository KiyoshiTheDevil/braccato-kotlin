---
title: "Better Lyrics Themes"
description: Apply a Better Lyrics theme's CSS to the views.
---

Better Lyrics themes are stylesheets. braccato-kotlin reads the parts of one that describe the lyrics:

- **Custom properties**, such as `--blyrics-text-color`, `--blyrics-font-size` or
  `--blyrics-lyric-active-color`, where they are plain values
- **Settings** written in comments, such as `blyrics-target-scroll-pos-ratio = 0.5;`

```kotlin
val (theme, rebuild) = BraccatoTheme.fromCss(css)
if (rebuild) built = buildLyrics(lyrics)   // a setting the lines are built from changed
```

`fromCss` returns the theme and whether the lyrics must be built again: some settings, such as
`blyrics-letter-wave`, change how lines are built.

:::caution
Settings are process wide, as upstream's are: they apply to every view.
:::

## What is not read

CSS beyond the `--blyrics-*` properties, such as selectors, `::before` labels or blurs on inactive lines,
has no equivalent in Compose. A RICS theme is compiled to CSS first, with `@braccato/rics`.
