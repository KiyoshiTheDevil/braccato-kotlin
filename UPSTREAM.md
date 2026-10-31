# Upstream

braccato-kotlin is a port of [better-lyrics/braccato](https://github.com/better-lyrics/braccato), kept
file for file so upstream changes can be carried over as diffs.

## Ported from

| | |
|---|---|
| Repository | https://github.com/better-lyrics/braccato |
| Commit | `5740449a708d0065e85caeff32140950b3f5ea7d` (2026-10-30, `chore: version packages`) |
| `@braccato/types` | 1.0.0 → `braccato-types` |
| `@braccato/parsers` | 0.4.0 → `braccato-parsers` |
| `@braccato/core` | 1.17.1, not ported yet |

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

`JsCompat.kt` has no upstream counterpart. It holds the JavaScript behaviour the ports lean on and
Kotlin does not share: `parseInt`/`parseFloat`/`Number`, `Math.round`, `trim`, `split` with capture
groups, and JavaScript's `\s` and `$` in regexes.

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

As of this commit: 56 of 56 real-world files (34 LRC, 22 TTML) match, along with 11 hand-made edge cases
(entities, CDATA, BOM and CRLF, nesting at the depth limit, broken QRC and SRT).

## Carrying over an upstream change

1. `git diff <commit above>..<new commit> -- packages/types packages/parsers` in the upstream repository.
2. Apply each hunk to the Kotlin file the map above names, then port any new or changed tests.
3. Bump `version` in the module's `build.gradle.kts` and the version in `tools/upstream-compare/package.json`.
4. Run the tests and the comparison, then update the commit and the versions here.
