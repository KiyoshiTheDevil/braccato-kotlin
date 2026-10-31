// Port of packages/parsers/src/instrumentalBreaks.ts.
package braccato.parsers

import braccato.types.Lyric

private const val DEFAULT_INSTRUMENTAL_GAP_MS = 5000.0

data class InstrumentalBreakOptions(
    /** Silence longer than this becomes an instrumental line. Defaults to 5000. */
    val instrumentalGapMs: Double? = null,
)

/**
 * Turn the silences between lines into explicit instrumental lyrics, so a renderer has something to
 * show during an intro, a solo or an outro instead of holding the last sung line on screen.
 *
 * `songDurationMs` is only needed for the outro. Intro and inter-line gaps are computed from the
 * lines themselves, so passing 0 still yields those.
 *
 * A null `gapThreshold` is upstream's `undefined`, which falls back to the default.
 */
fun insertInstrumentalBreaks(
    lyrics: List<Lyric>,
    songDurationMs: Double,
    gapThreshold: Double? = DEFAULT_INSTRUMENTAL_GAP_MS,
): List<Lyric> {
    if (lyrics.isEmpty()) return lyrics
    val threshold = gapThreshold ?: DEFAULT_INSTRUMENTAL_GAP_MS

    val result = mutableListOf<Lyric>()

    val createInstrumental = { startTimeMs: Double, durationMs: Double ->
        Lyric(
            startTimeMs = startTimeMs,
            durationMs = durationMs,
            words = "",
            parts = mutableListOf(),
            isInstrumental = true,
        )
    }

    if (lyrics[0].startTimeMs > threshold) {
        result += createInstrumental(0.0, lyrics[0].startTimeMs)
    }

    for (i in lyrics.indices) {
        result += lyrics[i]

        if (i < lyrics.size - 1) {
            val currentEnd = lyrics[i].startTimeMs + lyrics[i].durationMs
            val nextStart = lyrics[i + 1].startTimeMs
            val gap = nextStart - currentEnd

            if (gap > threshold) {
                result += createInstrumental(currentEnd, gap)
            }
        }
    }

    val lastLyric = lyrics[lyrics.size - 1]
    val lastLyricEnd = lastLyric.startTimeMs + lastLyric.durationMs
    val outroGap = songDurationMs - lastLyricEnd

    if (outroGap > threshold) {
        result += createInstrumental(lastLyricEnd, outroGap)
    }

    return result
}
