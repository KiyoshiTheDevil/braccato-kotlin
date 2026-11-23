// Port of packages/parsers/src/srt.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.internal.*

private val SRT_TIME_REGEX = Regex("""^(\d+):(\d+):(\d+)[,.](\d+)\z""")
private val BLOCK_SEPARATOR_REGEX = Regex("""\r?\n\r?\n""")
private val LINE_SEPARATOR_REGEX = Regex("""\r?\n""")
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")

private fun parseSRTTime(timeStr: String): Double {
    val match = SRT_TIME_REGEX.find(timeStr.jsTrim()) ?: return 0.0
    val hours = jsParseInt(match.groupValues[1])
    val minutes = jsParseInt(match.groupValues[2])
    val seconds = jsParseInt(match.groupValues[3])
    val ms = jsParseInt(match.groupValues[4].padEnd(3, '0').take(3))
    return hours * 3600000 + minutes * 60000 + seconds * 1000 + ms
}

/** The [LyricParser] for SRT subtitles: a lyric per cue. */
public object SRTParser : LyricParser {
    override fun detect(input: String): Boolean {
        return isSrt(input)
    }

    override fun parse(input: String, duration: Double): List<Lyric> {
        val blocks = input.jsTrim().split(BLOCK_SEPARATOR_REGEX)
        val lyrics = mutableListOf<Lyric>()

        for (block in blocks) {
            val lines = block.split(LINE_SEPARATOR_REGEX)
            if (lines.size < 2) continue

            val timeLine = lines.find { it.contains("-->") } ?: continue

            val (startStr, endStr) = timeLine.split("-->").map { it.jsTrim() }
            val startTimeMs = parseSRTTime(startStr)
            val endTimeMs = parseSRTTime(endStr)

            val timeLineIndex = lines.indexOf(timeLine)
            val text = lines
                .drop(timeLineIndex + 1)
                .joinToString("\n")
                .replace(HTML_TAG_REGEX, "")
                .jsTrim()

            if (text.isNotEmpty()) {
                lyrics += Lyric(
                    startTimeMs = startTimeMs,
                    words = text,
                    durationMs = endTimeMs - startTimeMs,
                )
            }
        }

        return lyrics
    }

    override fun metadata(input: String): LyricMetadata {
        return LyricMetadata(songwriters = emptyList())
    }
}
