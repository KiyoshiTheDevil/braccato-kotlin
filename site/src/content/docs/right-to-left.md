---
title: "Right-to-Left Scripts"
description: Hebrew, Arabic and other scripts written from right to left.
---

Lines in a right-to-left script read right to left throughout: their word order, the letters of words
that wave letter by letter, the sweep and its glow, and the nudge of a word group as it starts.

- **Side:** a right-to-left line sits on the right, and in a duet the sides are mirrored
- **Letters:** words in scripts whose letters join, such as Arabic, are never split into letters, so they
  stay shaped
- **Font:** right-to-left lines use the platform's font by default (`BraccatoTheme.rtlFontFamily`), since
  an app's own font seldom holds those scripts, and the fallback for them comes at regular weight. Set it
  to null to keep `fontFamily`

Scripts with conjuncts, such as Devanagari, Tamil or Sinhala, keep each conjunct whole when a long word
wraps or its letters wave.
