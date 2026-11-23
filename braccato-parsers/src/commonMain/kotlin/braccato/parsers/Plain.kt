// Port of packages/parsers/src/plain.ts.
package braccato.parsers

import braccato.types.Lyric

/** The [LyricParser] for text without timing: a lyric per line of text, all at 0. */
public object PlainParser : LyricParser {
    override fun detect(input: String): Boolean {
        return true
    }

    override fun parse(input: String, duration: Double): List<Lyric> {
        return input.split("\n").map { words ->
            Lyric(
                startTimeMs = 0.0,
                words = words,
                durationMs = 0.0,
            )
        }
    }

    override fun metadata(input: String): LyricMetadata {
        return LyricMetadata(songwriters = emptyList())
    }
}
