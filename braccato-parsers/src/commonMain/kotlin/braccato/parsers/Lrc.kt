// Port of packages/parsers/src/lrc.ts.
package braccato.parsers

import braccato.types.internal.InternalBraccatoApi

import braccato.types.Lyric
import braccato.types.LyricPart
import braccato.types.internal.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val POSSIBLE_ID_TAGS = listOf("ti", "ar", "al", "au", "lr", "length", "by", "offset", "re", "tool", "ve", "#")
// `au` is the song's author and `lr` its lyricist. `by` names whoever made the file, not a songwriter.
private val SONGWRITER_ID_TAGS = listOf("au", "lr")

private val TIME_TAG_REGEX = Regex("""\[(\d+:\d+\.\d+)\]""")
private val ENHANCED_WORD_REGEX = Regex("""<(\d+:\d+\.\d+)>""")
private val ID_TAG_REGEX = Regex("""^\[(\w+):(.*)\]\z""")
// LySy enhanced LRC encodes background vocals as a trailing [bg:...] group. The [bg:] marker is
// format and comes off; whatever the source wrote inside, parens included, passes through untouched.
private val BG_MARKER_REGEX = Regex("""\[bg:(.*)\]$WS*\z""")
// A timed line with no words, or only music notes, marks where the line before it stops.
private val GAP_MARKER_REGEX = Regex("^[${JS_WHITESPACE}♪♫♬♩🎵🎶]*\\z")

private fun parseTime(timeStr: String?): Double {
    if (timeStr.isNullOrEmpty()) return 0.0

    val parts = timeStr.split(":")
    var totalMs = 0.0

    if (parts.size == 1) {
        totalMs = jsParseFloat(parts[0]) * 1000
    } else if (parts.size == 2) {
        val minutes = jsParseInt(parts[0])
        val seconds = jsParseFloat(parts[1])
        totalMs = minutes * 60 * 1000 + seconds * 1000
    } else if (parts.size == 3) {
        val hours = jsParseInt(parts[0])
        val minutes = jsParseInt(parts[1])
        val seconds = jsParseFloat(parts[2])
        totalMs = hours * 3600 * 1000 + minutes * 60 * 1000 + seconds * 1000
    }
    return jsRound(totalMs)
}

/** One parsed line and where the document says it ends, or null when only the next line can tell. */
private class LrcEntry(val lyric: Lyric, val statedEnd: Double?)

/**
 * Parse an LRC document, plain or enhanced, into lines with optional word level parts.
 *
 * Timings are returned as the document states them. `LRCParser.parse` runs `lrcFixers` over the
 * result afterwards, which is right for Musixmatch word-by-word lyrics and wrong for sources whose
 * timings are already clean, so callers that know their source can stay on this function.
 *
 * Blank lines are not lyrics. Each one ends the line before it, and the silences that leaves, along
 * with any before the first line or after a line's closing timestamp, become instrumental lines on
 * the same terms as the TTML gap fill.
 */
public fun parseLRC(
    lrcText: String,
    songDurationMs: Double,
    options: InstrumentalBreakOptions = InstrumentalBreakOptions(),
): List<Lyric> {
    val lines = lrcText.split("\n")
    val entries = mutableListOf<LrcEntry>()
    val idTags = mutableMapOf<String, String>()

    for (rawLine in lines) {
        val line = rawLine.jsTrim()

        val idTagMatch = ID_TAG_REGEX.find(line)
        if (idTagMatch != null && idTagMatch.groupValues[1] in POSSIBLE_ID_TAGS) {
            idTags[idTagMatch.groupValues[1]] = idTagMatch.groupValues[2]
            continue
        }

        // Tracked as the tags are read rather than spread into Math.min afterwards, which a line with
        // enough of them turns into a call with more arguments than the stack holds.
        var lineStartTime = Double.POSITIVE_INFINITY
        var lineEndTime = Double.NEGATIVE_INFINITY
        var timeTagCount = 0
        for (match in TIME_TAG_REGEX.findAll(line)) {
            val time = parseTime(match.groupValues[1])
            if (time < lineStartTime) lineStartTime = time
            if (time > lineEndTime) lineEndTime = time
            timeTagCount++
        }

        if (timeTagCount == 0) continue

        val lyricPart = line.replace(TIME_TAG_REGEX, "").jsTrim()

        val parts = mutableListOf<LyricPart>()
        var lastTime: Double? = null
        val plainText = StringBuilder()

        val bgMatch = BG_MARKER_REGEX.find(lyricPart)
        val leadText = if (bgMatch != null) lyricPart.substring(0, bgMatch.range.first) else lyricPart
        val bgText = bgMatch?.groupValues?.get(1)

        val leadFragments = leadText.jsSplit(ENHANCED_WORD_REGEX)

        // Musixmatch wraps each word with paired <ts>, separated by whitespace-only "gap" fragments;
        // canonical LRC has none.
        val isMusixmatchStyle = leadFragments.withIndex().any { (i, f) ->
            i % 2 == 0 && i > 0 && i < leadFragments.size - 1 && f.isNotEmpty() && f.jsTrim() == ""
        }

        // Returns the segment's last part, or null when the segment has no timestamps.
        fun consumeSegment(segmentText: String, isBackground: Boolean): LyricPart? {
            val firstPart = parts.size
            segmentText.jsSplit(ENHANCED_WORD_REGEX).forEachIndexed { index, rawFragment ->
                if (index % 2 == 0) {
                    var fragment = rawFragment
                    if (isMusixmatchStyle) {
                        val trimmed = fragment.jsTrim()
                        fragment = if (trimmed == "" && fragment.isNotEmpty()) " " else trimmed
                    }
                    plainText.append(fragment)
                    // Every fragment but the one before the first timestamp belongs to the part that the
                    // preceding timestamp opened, whatever time that timestamp states.
                    if (parts.isNotEmpty()) {
                        parts[parts.size - 1].words += fragment
                    }
                } else {
                    val startTime = parseTime(rawFragment)
                    val previousTime = lastTime
                    if (previousTime != null && parts.isNotEmpty()) {
                        parts[parts.size - 1].durationMs = startTime - previousTime
                    }
                    parts += LyricPart(startTimeMs = startTime, words = "", durationMs = 0.0, isBackground = isBackground)
                    lastTime = startTime
                }
            }
            return if (parts.size > firstPart) parts[parts.size - 1] else null
        }

        val leadLast = consumeSegment(leadText, false)
        val bgLast = if (bgText == null) null else consumeSegment(bgText, true)
        if (isCreditLine(leadText.replace(ENHANCED_WORD_REGEX, ""))) continue

        // A timestamp with nothing sung after it is where the word before it ends, not a word. Read only
        // once both segments are in, since untimed background text lands on the lead's last part.
        // Membership is by identity, as `includes` is upstream: two parts can be equal and still distinct.
        val closings = listOfNotNull(leadLast, bgLast).filter { part -> part.words.jsTrim() == "" }
        val finalPart = bgLast ?: leadLast
        val closedAt =
            if (finalPart != null && closings.any { it === finalPart }) closings.maxOf { part -> part.startTimeMs } else null
        val sungParts = parts.filter { part -> closings.none { it === part } }
        val words = plainText.toString().jsTrim()

        entries += LrcEntry(
            lyric = Lyric(
                startTimeMs = lineStartTime,
                words = if (GAP_MARKER_REGEX.containsMatchIn(words)) "" else words,
                durationMs = lineEndTime - lineStartTime,
                parts = if (sungParts.isNotEmpty()) sungParts.toMutableList() else null,
            ),
            statedEnd = closedAt ?: (if (timeTagCount > 1) lineEndTime else null),
        )
    }

    entries.sortBy { it.lyric.startTimeMs }
    val result = entries.map { entry -> entry.lyric }

    for (i in result.indices) {
        val lyric = result[i]
        val statedEnd = entries[i].statedEnd
        val lyricParts = lyric.parts
        if (statedEnd != null) {
            lyric.durationMs = max(statedEnd - lyric.startTimeMs, 0.0)
            val lastPart = lyricParts?.lastOrNull()
            if (lastPart != null && lastPart.durationMs == 0.0) lastPart.durationMs = max(statedEnd - lastPart.startTimeMs, 0.0)
        } else if (i + 1 < result.size) {
            val nextLyric = result[i + 1]
            if (lyric.durationMs == 0.0) {
                lyric.durationMs = max(nextLyric.startTimeMs - lyric.startTimeMs, 0.0)
            }
            if (lyricParts != null && lyricParts.isNotEmpty()) {
                var latestStart = nextLyric.startTimeMs
                for (value in lyricParts) {
                    latestStart = max(latestStart, value.startTimeMs)
                }
                val lastPart = lyricParts[lyricParts.size - 1]
                lastPart.durationMs = max(nextLyric.startTimeMs - lastPart.startTimeMs, 0.0)
                lyric.durationMs = max(latestStart - lyric.startTimeMs, 0.0)
            }
        } else {
            // The song duration is what the last line ends against, and a caller that passed none left
            // it at 0. Everything it would produce there runs backwards, so the lengths already on the
            // line are kept instead.
            val lineTail = songDurationMs - lyric.startTimeMs
            if (lyric.durationMs == 0.0 && lineTail > 0) {
                lyric.durationMs = lineTail
            }
            if (lyricParts != null && lyricParts.isNotEmpty()) {
                val lastPart = lyricParts[lyricParts.size - 1]
                val partTail = songDurationMs - lastPart.startTimeMs
                if (partTail > 0) lastPart.durationMs = partTail
            }
        }
    }

    val sung = result.filter { lyric -> lyric.words != "" }

    val offsetTag = idTags["offset"]
    if (!offsetTag.isNullOrEmpty()) {
        var offset = jsNumber(offsetTag)
        if (offset.isNaN()) offset = 0.0
        offset *= 1000
        for (lyric in sung) {
            lyric.startTimeMs -= offset
            lyric.parts?.forEach { part ->
                part.startTimeMs -= offset
            }
        }
    }

    return insertInstrumentalBreaks(sung, songDurationMs, options.instrumentalGapMs)
}

@InternalBraccatoApi public fun lrcFixers(lyrics: List<Lyric>) {
    for (lyric in lyrics) {
        val parts = lyric.parts ?: continue
        for (i in 1 until parts.size) {
            val thisPart = parts[i]
            val prevPart = parts[i - 1]
            if (thisPart.words == " " && prevPart.words != " ") {
                val deltaTime = thisPart.durationMs - prevPart.durationMs
                if (abs(deltaTime) <= 15 || thisPart.durationMs <= 100) {
                    val durationChange = thisPart.durationMs
                    prevPart.durationMs += durationChange
                    thisPart.durationMs -= durationChange
                    thisPart.startTimeMs += durationChange
                }
            }
        }
    }

    var shortDurationCount = 0
    var durationCount = 0
    for (lyric in lyrics) {
        val parts = lyric.parts
        if (parts == null || parts.isEmpty()) continue
        for (i in 0 until parts.size - 2) {
            val part = parts[i]
            if (part.words != " ") {
                if (part.durationMs <= 100) shortDurationCount++
                durationCount++
            }
        }
    }

    if (durationCount > 0 && shortDurationCount.toDouble() / durationCount > 0.5) {
        for (i in lyrics.indices) {
            val lyric = lyrics[i]
            val parts = lyric.parts
            if (parts == null || parts.isEmpty()) continue

            for (j in parts.indices) {
                val part = parts[j]
                if (part.words == " ") continue
                if (part.durationMs <= 400) {
                    var nextPart: LyricPart? = null
                    val nextLineParts = lyrics.getOrNull(i + 1)?.parts
                    if (j + 1 < parts.size) {
                        nextPart = parts[j + 1]
                    } else if (nextLineParts != null && nextLineParts.isNotEmpty()) {
                        nextPart = nextLineParts[0]
                    }

                    if (nextPart == null) {
                        part.durationMs = max(part.durationMs, 300.0)
                    } else if (nextPart.words == " ") {
                        part.durationMs += nextPart.durationMs
                        nextPart.startTimeMs += nextPart.durationMs
                        nextPart.durationMs = 0.0
                    } else {
                        part.durationMs = nextPart.startTimeMs - part.startTimeMs
                    }

                    // The next line's first word can sit on the far side of a gap, so the last word
                    // stops where its own line does.
                    val lineEnd = lyric.startTimeMs + lyric.durationMs
                    if (j == parts.size - 1 && lyric.durationMs > 0 && lineEnd > part.startTimeMs) {
                        part.durationMs = min(part.durationMs, lineEnd - part.startTimeMs)
                    }
                }
            }
        }
    }
}

public object LRCParser : LyricParser {
    override fun detect(input: String): Boolean {
        return isLrc(input)
    }

    override fun parse(input: String, duration: Double): List<Lyric> {
        val lyrics = parseLRC(input, duration)
        lrcFixers(lyrics)
        return lyrics
    }

    override fun metadata(input: String): LyricMetadata {
        val names = mutableListOf<String>()
        for (rawLine in input.split("\n")) {
            val line = rawLine.jsTrim()
            val idTagMatch = ID_TAG_REGEX.find(line)
            if (idTagMatch != null && idTagMatch.groupValues[1] in SONGWRITER_ID_TAGS) {
                names += splitCreditNames(idTagMatch.groupValues[2])
            } else {
                val text = line.replace(TIME_TAG_REGEX, "")
                if (text != line) {
                    names += songwritersInCreditLine(text.replaceFirst(BG_MARKER_REGEX, "").replace(ENHANCED_WORD_REGEX, ""))
                }
            }
        }
        return LyricMetadata(songwriters = uniqueNames(names))
    }
}
