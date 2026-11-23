---
title: "Upstream"
description: Which Braccato release is inside, and what was left out on purpose.
---

braccato-kotlin is a port, kept file for file with [Braccato](https://github.com/better-lyrics/braccato)
so upstream changes can be carried over as diffs.

## Which release is inside

| | |
|---|---|
| `@braccato/types` | 1.1.0 |
| `@braccato/parsers` | 0.4.3 |
| `@braccato/core` | 1.22.0, the part that is not the DOM |

The port's own versions move on their own; the
[changelog](https://github.com/KiyoshiTheDevil/braccato-kotlin/blob/main/CHANGELOG.md) says which upstream
release each one carries.

## What is not ported

- `@braccato/provider-blyrics`, the chain that fetches lyrics from providers: an app brings its own sources
- `@braccato/rics`, which compiles RICS themes to CSS: compile first, then hand over the CSS
- What answers browser questions: Web Animations and their drift, telling the engine's own scroll events
  from the reader's, work moved off the main thread, and fixes for one browser engine

## Differences

Effects are worked out from the lyric time on every frame rather than started and kept in step, and a
few things were added where an app needs them, such as carrying a player's coarse position forward.
[UPSTREAM.md](https://github.com/KiyoshiTheDevil/braccato-kotlin/blob/main/UPSTREAM.md) lists every
difference and maps each upstream file to its Kotlin counterpart.
