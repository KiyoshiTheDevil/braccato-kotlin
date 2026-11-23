# Upstream

braccato-kotlin is a port of [better-lyrics/braccato](https://github.com/better-lyrics/braccato), kept
file for file so upstream changes can be carried over as diffs.

## Ported from

| | |
|---|---|
| Repository | https://github.com/better-lyrics/braccato |
| Commit | `b5b9571e7d8a8d72f705a38e18c285175c30b3ed` (2026-11-20, `chore: version packages`) |
| `@braccato/types` | 1.1.0 → `braccato-types` |
| `@braccato/parsers` | 0.4.3 → `braccato-parsers` |
| `@braccato/core` | 1.22.0 → `braccato-core`, the part that is not the DOM |

First ported at `5740449` (core 1.17.1); brought up to `b5b9571` on 2026-11-23, see [the last sync](#the-sync-to-1220).

## File map

Every ported file starts with a `// Port of …` line naming its source.

| Upstream | Kotlin |
|---|---|
| `packages/types/src/index.ts` | `braccato-types/…/types/Index.kt` |
| `packages/parsers/src/types.ts` | `braccato-parsers/…/parsers/Types.kt` |
| `packages/parsers/src/format.ts` | `Format.kt` |
| `packages/parsers/src/formatPredicates.ts` | `FormatPredicates.kt` |
| `packages/parsers/src/detect.ts` | `Detect.kt` |
| `packages/parsers/src/plain.ts` | `Plain.kt` |
| `packages/parsers/src/srt.ts` | `Srt.kt` |
| `packages/parsers/src/lrc.ts` | `Lrc.kt` |
| `packages/parsers/src/qrc.ts` | `Qrc.kt` |
| `packages/parsers/src/ttml.ts` | `Ttml.kt` |
| `packages/parsers/src/credits.ts` | `Credits.kt` |
| `packages/parsers/src/instrumentalBreaks.ts` | `InstrumentalBreaks.kt` |
| `packages/parsers/src/stringSimilarity.ts` | `StringSimilarity.kt` |
| `packages/parsers/src/ttmlTypes.ts` | none; the tree is `XmlNode` in `XmlReader.kt` |
| `packages/parsers/src/index.ts` | none; Kotlin has no barrel file |
| `fast-xml-parser` (dependency) | `XmlReader.kt`, the subset the TTML parser relies on |
| `packages/parsers/src/__tests__/*.test.ts` | `braccato-parsers/src/commonTest/…/*Test.kt` |

`braccato-types/…/types/internal/JsCompat.kt` has no upstream counterpart. It holds the JavaScript
behaviour the ports lean on and Kotlin does not share: `parseInt`/`parseFloat`/`Number`,
`Math.round`, `trim`, `split` with capture groups, and JavaScript's `\s` and `$` in regexes. It is
public only to the braccato modules, behind the `InternalBraccatoApi` opt-in.

### `@braccato/core`

Upstream's core is a DOM renderer: most of its engine (`engine.ts` until 1.20.1, since then split into
`engine/*.ts`, one module per concern) starts Web Animations from CSS keyframes, reads layout and
corrects the browser's animation clock against the song. That has no
counterpart under Compose, where a frame is drawn from the time directly. What is ported is
everything the renderer decides from numbers, under upstream's names where a function exists.

| Upstream | Kotlin (`braccato-core/…/core/`) |
|---|---|
| `engine/` arithmetic: `clock.ts`, `sweepPlans.ts`, `letterPlans.ts`, `glow.ts`, `scrollViewport.ts`, `passiveScroll.ts`, `tick.ts` (tick options) | `Engine.kt`: clock smoothing, word states, swipe windows, letter mask sweep, letter wave, glow alpha, scroll insets and target, scroll padding, tick options, passive scroll |
| `engine/tick.ts`, `autoScroll.ts`, `userScroll.ts`, `clock.ts`: the per-frame decisions | `Timeline.kt` (new shape, see below) |
| `engine.ts`, `inject.ts`, `imageHighlights.ts`: `registerThemeSetting` calls | `CoreSettings.kt` |
| `themeSettings.ts` | `ThemeSettings.kt` |
| `inject.ts` | `Inject.kt`: tokens, word groups, background groups, lettering, wrap points, line synced parts, sync type, direction, translation and romanization |
| `letters.ts` | `Letters.kt`: `letterClusters`, `joinLetterSegments` |
| `view.ts` | `View.kt`: `buildLyrics`, credits, stage duet |
| `stage.ts` | `Stage.kt` |
| `language.ts` | `Language.kt` |
| `text.ts` | `Text.kt` |
| `util.ts` | `Util.kt`: `clamp`, `positiveModulo`, `roundedMs`, `toMs` |
| `types.ts`, word states in `constants.ts` | `Types.kt` |
| `instrumental.ts` | `Instrumental.kt`: the SVG path data |
| `imageHighlights.ts` | `ImageHighlights.kt`: `imageURL` |
| `renderer.ts`, `element.ts`, `seek.ts`, `constants.ts` class names, `styles/*.css` | `braccato-compose`, below |

Selfchecks ported as tests: `stage`, `themeSettings`, `imageHighlights`, `wordState`, `instrumental`
(the path shapes), the language inference in `language`, and the arithmetic checks in `engine`. The
selfchecks that drive a fake DOM (`element`, `renderer`, `swap`, `boundary`, `direction`, most of
`engine` and `inject`) test DOM behaviour; `TimelineTest` and `InjectTest` cover the same rules on the
model instead.

**Left out on purpose:** Web Animations setup and its drift learning (`learnAnimationTimingOffset`),
swallowing the scroll events a programmatic scroll fires (`skipScrolls`), the two second lead
animations are set up with, off-screen culling, `will-change` management, the debug canvas, and
`swapLyrics` (rebuilding a view in place, which a Compose renderer does by keying its lines).

### The renderer (`braccato-compose`)

Not a line-by-line port: upstream's renderer is DOM, CSS and Web Animations. What is carried over is
what it shows, with upstream's values.

| Upstream | Kotlin (`braccato-compose/…/compose/`) |
|---|---|
| `styles/variables.css`, `lyrics.css`, `instrumental.css` defaults | `BraccatoTheme.kt` |
| a theme's `--blyrics-*` custom properties | `BraccatoTheme.withCustomProperties`, `CssValues.kt` |
| the keyframes and timings in `engine.ts` (`startWordAnimations`, `startLineAnimation`, wave, exit) | `Effects.kt`, sampled per frame |
| the word span and highlight overlay from `inject.ts`, `.blyrics-word-highlight` | `WordView.kt` |
| `.blyrics--line` and its decorations | `LyricLine.kt` |
| `instrumental.ts`, `instrumental.css` | `InstrumentalNote.kt` |
| `element.ts`, `renderer.ts`, scroll layout, per-line scroll in `engine.ts` | `BraccatoLyrics.kt` |
| stage layout, `applyStage`, `stage.css` | `BraccatoStage.kt` |

Differences in the renderer:

- **Effects are sampled, not run.** Each frame computes every effect from the lyric time, so there is
  nothing to keep in step with the song and nothing to reset after a seek.
- **Moving words are drawn from a bitmap.** Skia places glyphs on a quarter-pixel grid and
  rasterizes them again at every scale, so text under a slow zoom or a small lift moves in visible
  steps. While a line animates, its words are rendered once at their own size and the bitmap is
  transformed, as a browser does with a `will-change: transform` layer; at rest they are plain text.
  `SmoothnessTest` measures the difference.
- **Wrapping follows upstream's word groups, with one addition.** Syllables with no space between
  them form a group. A group of up to twice `blyrics-long-word-wrap-threshold` characters moves to
  the next line whole and wobbles as one, as upstream's inline-block does; a longer group fills the
  line and breaks between syllables (`.blyrics-word-group-long`, inline, so it does not wobble).
  Breaks inside a group follow the line breaking rules for CJK (none before 、。」ー or a small kana,
  none after 「（), and Latin syllables never part (`LineBreaking.kt`). The addition: a short group too
  wide for even an empty line breaks the same way, where upstream lets it run out of the view. In
  practice that is CJK, written without spaces, on a phone.
- **The glow is the lit highlight blurred on its own layer**, matching CSS `drop-shadow` (which
  follows painted pixels) rather than a text shadow (which would follow whole glyphs). Its radius is
  converted so the blur is CSS's: a shadow's blur radius is twice the Gaussian's standard deviation in
  CSS, while Compose's `BlurEffect` reads its radius as `0.57735 * r + 0.5`. Passed through as it
  was, the 0.8rem glow spread a fifth wider and looked softer than upstream's (`GlowTest`).
- **A lettered word sweeps with the word-level gradient** laid across its letters. For the default
  linear swipe this is the same picture as upstream's per-letter masks; under a non-linear swipe
  easing the letters reveal on the whole word's curve rather than each on its own.
- **Lines are laid out in one scrolling column**, not culled off screen. Fine at song length; a lazy
  list is the change to make if that ever matters.
- **Decorations come and go as upstream's do, and leave gently too.** A translation or romanization
  that arrives fades and drops in (`blyrics-decoration-in`, 350 ms) and the lines it moved slide from
  where they were (`captureDecorationSlide`). Upstream takes one away at once; here it fades and lifts
  out over the same time before the lines close up. One difference in how the lines move: upstream
  slides them and then scrolls back to the active line, so switching translations on for a whole song
  carries the line being sung away and back. Here the scroll moves with the line the view follows
  in the same frame, so it stays where it is while the others slide into place around it.
- **No `swapLyrics`, but the same effect for the common case.** The view keys its state on the song
  rather than on the lyrics object: lyrics built again with the same lines (timing, words, voices) keep
  the scroll, the clock and the place, and only what hangs on the lines changes. Upstream needs the
  consumer to pass a `songKey` for that.
- **Not yet carried over:** image highlights (`blyrics-image-highlights`), the debug canvas. CSS a theme writes beyond `--blyrics-*` properties
  (selectors, `::before` labels, blurs on inactive lines) has no equivalent; only the properties
  listed in `BraccatoTheme.withCustomProperties` are read.

## Differences on purpose

- **Times are `Double`.** A JavaScript number is a double, and the parsers produce NaN and
  fractions in the same places upstream does.
- **Optional fields are nullable**, with null for `undefined`. `Lyric` and `LyricPart` are data
  classes with `var` fields, because the parsers finish lines by mutating them, as upstream does.
- **Valueless XML attributes** (`<p flag>`, or an unquoted `key=k1`) are the boolean `true` in
  fast-xml-parser and the string `"true"` here.
- **DOCTYPE declarations are skipped**, so entities they declare are not expanded.
- `format.test.ts` checks the JavaScript module's runtime exports; that check is not ported.
  `environment.test.ts` (no DOM in the test run) has no counterpart either.
- **`tickView` is split.** Upstream decides and writes in one pass; `LyricsTimeline.tick` returns
  the decisions (active and animating lines, a seek, the scroll action and target, stage roles,
  passive scroll) as a `TimelineFrame` and the renderer acts on them. The rules are upstream's.
- **One playback clock per timeline**, where upstream's is module scope and shared by every view in
  a bundle. Clock smoothing, on for Gecko upstream, is off unless asked for.
- **Held readings are carried forward** (`PlaybackClock.interpolateHeldReadings`, on by default). Not
  in upstream, whose media element moves every frame: Media3's `ExoPlayer.currentPosition` changes
  about ten times a second, which made the swipe move in 100 ms steps on Android. A repeated reading
  runs on the wall clock, at most 250 ms ahead, and the clock never steps back for a source that is
  moving forward.
- **Theme settings are registered together** in `CoreSettings.kt`. Upstream registers each where
  it is used, at import; Kotlin initialises top-level values on first use, which would leave a
  setting first read after a theme was applied at its default.
- **Theme timing offsets** (`--blyrics-*-timing-offset`) are CSS durations upstream; here they are
  `TimingOffsets`, with the defaults from `styles/variables.css`.
- **Script matching uses `Script`, not `Script_Extensions`**, which the JVM's regex engine does not
  know. The shared characters that matter for joining (tatweel, harakat, Mongolian punctuation) are
  listed explicitly.
- **Mask positions are numbers.** `planLetterMaskSweep` gives `maskPositionX` as a fraction where
  upstream writes the CSS string `"<x>% 0%"`.
- **Language tags are canonicalised by case only.** `Intl.getCanonicalLocales` also maps deprecated
  codes (`iw` to `he`).
- **Letters are split with `BreakIterator`**, upstream with `Intl.Segmenter`. Both follow the
  extended grapheme cluster rules.

## The sync to 1.22.0

From core 1.17.1 to 1.22.0, parsers 0.4.0 to 0.4.3, types 1.0.0 to 1.1.0. Carried over:

- **Background groups** (types 1.1.0, parsers 0.4.1, core 1.20.0): `LyricPart.backgroundGroup`, the
  TTML parser numbering each `x-bg` span and reading timing written on the span itself, and a row for
  each group (`LineContent.backgrounds`, drawn one under the other). It replaces this port's own fix for
  two background spans side by side, which put a space between them.
- **`followPausedSeeks`** (1.18.0): a paused clock moving by less than a jump resets the highlight only
  when the host asks.
- **Letter clusters** (1.21.3): conjuncts and spacing vowel signs stay whole in the letter wave and in
  wraps, and the long word thresholds count letters, not UTF-16 units.
- **The glow at rest** (1.21.6): it holds `glowTo` once it has died down and through the fade out, and
  nothing with the glow off.
- Found on the way, older than 1.17.1: `--blyrics-lyric-active-color` and `--blyrics-lyric-inactive-color`
  are read now (`BraccatoTheme.lyricActiveColor`, `lyricInactiveColor`).

Not carried over, because they answer browser questions:

- `countEveryUserScroll` (1.19.0) and the reader pausing unsynced drift (1.19.1). Both are about telling
  the reader's scroll events from the ones the engine's own writes fire (`skipScrolls`); a Compose host
  reports the reader's scroll itself (`noteUserScroll`), so every one counts already.
- The sweep, glow and line scrolls run off the main thread (1.21.0, 1.21.2, 1.21.5): a compositor matter.
  The two-halo glow of 1.21.0 went back to one drop shadow in 1.21.6, which is what this port draws.
- WebKit's baseline drift in wrapped sweeps (1.22.0), the engine's split into modules (1.20.1), and
  `TTMLParser.metadata` reusing the last parse (0.4.2), which only saves work.

## Checking against the JavaScript parsers

`tools/upstream-compare` runs the published `@braccato/parsers` over a folder of lyric files, and
`UpstreamComparisonTest` reads the same folder with the Kotlin port and compares the output file by
file:

```bash
cd tools/upstream-compare && npm install && node dump.mjs <lyrics folder> <output folder>
```

```bash
BRACCATO_COMPARE_CORPUS=<lyrics folder> BRACCATO_COMPARE_EXPECTED=<output folder> ./gradlew :braccato-parsers:jvmTest
```

Last run against parsers 0.4.0, before the sync to 0.4.3, and not run again since: 56 of 56 real-world files (34 LRC, 22 TTML) match, along with 11 hand-made edge cases
(entities, CDATA, BOM and CRLF, nesting at the depth limit, broken QRC and SRT).

## Carrying over an upstream change

1. `git diff <commit above>..<new commit> -- packages/types packages/parsers packages/core` in the upstream repository.
2. Apply each hunk to the Kotlin file the map above names, then port any new or changed tests.
3. Bump the version in `tools/upstream-compare/package.json`. This port's own version moves on its own (see README).
4. Run the tests and the comparison, then update the commit and the versions here.
