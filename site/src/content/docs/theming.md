---
title: Theming
description: Colours, sizes, fonts and which effects run.
---

`BraccatoTheme` holds how the lyrics look and move. Its defaults are upstream's stylesheet.

```kotlin
BraccatoLyrics(
    lyrics = built,
    playback = { /* ... */ },
    theme = BraccatoTheme(
        fontFamily = myFont,
        fontSize = 34.dp,
        textColor = Color.White,
        inactiveOpacity = 0.35f,
    ),
)
```

## What it holds

- **Colours:** `textColor`, `activeOpacity` and `inactiveOpacity`, or `lyricActiveColor` and
  `lyricInactiveColor` outright; `glowColor`; `translatedColor`
- **Type:** `fontFamily`, `fontSize`, `fontWeight`, `lineHeight`, and the same for translations and
  romanizations
- **Layout:** `linePadding`, `scale` for lines not being sung, `activeScale`
- **Motion:** the sweep, glow, wobble, letter wave, instrumental note and decorations, each with its
  timing and easing
- **Credits:** `creditsLabel` and its opacities

## Which effects run

`BraccatoAnimations` switches each effect on or off:

```kotlin
BraccatoTheme(animations = BraccatoAnimations(wordWobble = false, highlightGlow = false))
```

`BraccatoAnimations().reducedMotion()` turns off the zoom, the wobble and the moving fill for readers who
ask for less motion; fades and the sweep stay.

## A theme from CSS

A Better Lyrics theme applies as it is, see [Better Lyrics Themes](/better-lyrics-themes/).
