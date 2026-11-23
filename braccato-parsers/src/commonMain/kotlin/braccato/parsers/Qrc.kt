// Port of packages/parsers/src/qrc.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricPart
import braccato.types.internal.*
import kotlin.math.abs
import kotlin.math.max

/** Song metadata, used to drop opening lines that only echo the title or the artist. */
public data class QrcMetadata(
    val title: String? = null,
    val artist: String? = null,
)

// -- Line/Word Time Parsing --------------------------

private class LineTime(val startTime: Double, val duration: Double, val rest: String)

private fun parseLineTime(src: String): LineTime? {
    if (src.isEmpty() || src[0] != '[') return null
    val close = src.indexOf("]")
    if (close == -1) return null
    val comma = src.indexOf(",", 1)
    if (comma == -1 || comma > close) return null
    val startTime = jsParseInt(src.substring(1, comma))
    val duration = jsParseInt(src.substring(comma + 1, close))
    if (startTime.isNaN() || duration.isNaN()) return null
    return LineTime(startTime = startTime, duration = duration, rest = src.substring(close + 1))
}

private class ParsedWord(
    var text: String,
    val time: Double,
    val duration: Double,
)

private val TIMING_TOKEN_REGEX = Regex("""\((\d+),(\d+)\)""")

/**
 * Only the timing tokens are matched; each syllable is the span of text between the end of the
 * previous token and the start of this one. Capturing that text in the pattern instead costs either
 * correctness or time: a lazy `.*?` backtracks over the whole line from every start position when a
 * line holds unmatched brackets, and a `[^(]*` that avoids the backtracking cannot read a syllable
 * with a bracket in it. Reading the span keeps both.
 */
private fun parseWords(src: String): List<ParsedWord> {
    val words = mutableListOf<ParsedWord>()
    var textStart = 0

    for (match in TIMING_TOKEN_REGEX.findAll(src)) {
        words += ParsedWord(
            text = src.substring(textStart, match.range.first),
            time = jsParseInt(match.groupValues[1]),
            duration = jsParseInt(match.groupValues[2]),
        )
        textStart = match.range.last + 1
    }

    return words
}

// -- Singer/Agent Detection --------------------------

private class AgentsCtx(
    val aliases: MutableMap<String, String>,
    var nextVoiceId: Int,
    var currentSinger: String?,
)

private class ParsedLine(
    var time: Double,
    var duration: Double,
    var text: String,
    var syllables: List<ParsedWord>,
    var agent: String?,
)

private fun assignAgent(singerName: String, parsedLine: ParsedLine, ctx: AgentsCtx) {
    if (ctx.aliases[singerName].isNullOrEmpty()) {
        val upper = singerName.uppercase()
        val type = if (upper == "合" || upper == "ALL" || upper == "合唱") "group" else "person"
        ctx.aliases[singerName] = if (type == "group") "v1000" else "v${ctx.nextVoiceId++}"
    }
    parsedLine.agent = ctx.aliases[singerName]
}

private fun updateLineTiming(parsedLine: ParsedLine) {
    if (parsedLine.syllables.isNotEmpty()) {
        val first = parsedLine.syllables[0]
        val last = parsedLine.syllables[parsedLine.syllables.size - 1]
        parsedLine.time = first.time
        parsedLine.duration = last.time + last.duration - first.time
    }
}

private val NON_WORD_REGEX = Regex("[^a-z0-9　-鿿가-힯]")
private val SINGER_ONLY_REGEX = Regex("^([^:：]+)$WS*[:：]$WS*\\z")
private val SINGER_PREFIX_REGEX = Regex("^([^:：]+)$WS*[:：]$WS*([\\s\\S]*)\\z")
private val AFTER_COLON_REGEX = Regex("[:：]$WS*([\\s\\S]*)\\z")

private fun extractSinger(
    parsedLine: ParsedLine,
    ctx: AgentsCtx,
    isFirstFewLines: Boolean,
    metadata: QrcMetadata?,
): Boolean {
    if (parsedLine.syllables.isEmpty()) {
        if (ctx.currentSinger != null) parsedLine.agent = ctx.currentSinger
        return true
    }

    // Drop lines near the start that echo title/artist
    if (isFirstFewLines) {
        if (metadata != null && (!metadata.title.isNullOrEmpty() || !metadata.artist.isNullOrEmpty())) {
            val norm = { s: String -> s.lowercase().replace(NON_WORD_REGEX, "") }
            val text = norm(parsedLine.text)
            val title = norm(metadata.title ?: "")
            val artist = norm(metadata.artist ?: "")

            if (title.isNotEmpty() && text.contains(title) && artist.isNotEmpty() && text.contains(artist)) return false
            if (title.isNotEmpty() && text.contains(title) && artist.isEmpty() && text.length < title.length + 15) return false
            if (artist.isNotEmpty() && text == artist) return false

            val ref = "${metadata.title ?: ""} ${metadata.artist ?: ""}"
            if (stringSimilarity(parsedLine.text, ref) > 0.5) return false
        }

        val first = parsedLine.syllables[0]
        val allSameDuration =
            parsedLine.syllables.size > 2 &&
                parsedLine.syllables.all { w -> abs(w.duration - first.duration) < 10 }
        if (allSameDuration) return false
    }

    if (ctx.currentSinger != null) parsedLine.agent = ctx.currentSinger

    var accText = ""
    var syllablesToRemove = 0
    for (syl in parsedLine.syllables) {
        accText += syl.text
        syllablesToRemove++
        if (accText.contains(":") || accText.contains("：")) break
        if (accText.length > 40) return true
    }

    // Case A: the whole accumulated text is "Name:" with nothing after the colon
    val fullMatch = SINGER_ONLY_REGEX.find(accText)
    if (fullMatch != null) {
        val singerName = fullMatch.groupValues[1].jsTrim()
        if (isCreditRole(singerName)) return false
        if (singerName.length > 30) return true

        parsedLine.syllables = parsedLine.syllables.drop(syllablesToRemove)
        parsedLine.text = parsedLine.text.drop(accText.length)
        assignAgent(singerName, parsedLine, ctx)
        ctx.currentSinger = ctx.aliases[singerName]
        updateLineTiming(parsedLine)
        return true
    }

    // Case B: "Name: lyrics..." where the colon lands mid-syllable with content after it
    val prefixBeforeColon = SINGER_PREFIX_REGEX.find(accText) ?: return true

    val singerName = prefixBeforeColon.groupValues[1].jsTrim()
    if (isCreditRole(singerName)) return false
    if (singerName.length > 20) return true

    val afterColon = prefixBeforeColon.groupValues[2]
    val colonSylIdx = syllablesToRemove - 1
    val colonSyl = parsedLine.syllables[colonSylIdx]
    val colonMatch = AFTER_COLON_REGEX.find(colonSyl.text)
    val remainderInColonSyl = colonMatch?.groupValues?.get(1) ?: ""

    if (remainderInColonSyl.isNotEmpty()) {
        colonSyl.text = remainderInColonSyl
        parsedLine.syllables = parsedLine.syllables.drop(colonSylIdx)
    } else {
        parsedLine.syllables = parsedLine.syllables.drop(syllablesToRemove)
    }

    assignAgent(singerName, parsedLine, ctx)
    ctx.currentSinger = ctx.aliases[singerName]
    parsedLine.text = afterColon
    updateLineTiming(parsedLine)

    return true
}

// -- Envelope --------------------------

private val LYRIC_CONTENT_REGEX = Regex("LyricContent=\"([\\s\\S]*?)\"$WS*(?:/?>|[a-zA-Z]+=)")
private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+:""")

/**
 * QQ Music serves QRC inside an XML envelope with the body hidden in a `LyricContent` attribute.
 * A bare body is passed through untouched, since that is what a caller holding an already unwrapped
 * document has.
 */
private fun unwrapEnvelope(qrcXml: String): String {
    val attrMatch = LYRIC_CONTENT_REGEX.find(qrcXml) ?: return qrcXml
    return attrMatch.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")
}

// -- Main Parser --------------------------

/**
 * Parse a QRC document, either the `<QrcInfos>` envelope QQ Music returns or a bare QRC body.
 *
 * `metadata` is optional and only used to drop opening lines that echo the song title or artist,
 * which QQ Music routinely prepends to a lyric body.
 */
public fun parseQRC(qrcXml: String, songDurationMs: Double, metadata: QrcMetadata? = null): List<Lyric> {
    if (qrcXml.isEmpty()) return emptyList()

    val lyricContent = unwrapEnvelope(qrcXml)

    val parsedLines = mutableListOf<ParsedLine>()
    val agentsCtx = AgentsCtx(aliases = mutableMapOf(), nextVoiceId = 1, currentSinger = null)

    for (raw in lyricContent.split("\n")) {
        val trimmed = raw.jsTrim()
        if (trimmed.isEmpty()) continue

        // Skip the bracketed metadata tags a QQ Music body carries
        if (METADATA_TAG_REGEX.containsMatchIn(trimmed)) continue

        val lineTime = parseLineTime(trimmed) ?: continue

        val syllables = parseWords(lineTime.rest)
        val text = syllables.joinToString("") { w -> w.text }

        val parsed = ParsedLine(
            time = lineTime.startTime,
            duration = lineTime.duration,
            text = text,
            syllables = syllables,
            agent = null,
        )

        if (extractSinger(parsed, agentsCtx, parsedLines.size < 5, metadata)) {
            parsedLines += parsed
        }
    }

    if (parsedLines.isEmpty()) return emptyList()

    val lyrics = parsedLines.mapIndexed { index, line ->
        val parts = line.syllables.map { syl ->
            LyricPart(
                startTimeMs = syl.time,
                words = syl.text,
                durationMs = syl.duration,
            )
        }

        // Take the duration from the next line when the line carries none
        var duration = line.duration
        if (duration == 0.0 && index + 1 < parsedLines.size) {
            duration = max(parsedLines[index + 1].time - line.time, 0.0)
        } else if (duration == 0.0) {
            duration = songDurationMs - line.time
        }

        if (parts.isNotEmpty()) {
            val lastPart = parts[parts.size - 1]
            if (lastPart.durationMs == 0.0) {
                if (index + 1 < parsedLines.size) {
                    lastPart.durationMs = max(parsedLines[index + 1].time - lastPart.startTimeMs, 0.0)
                } else {
                    lastPart.durationMs = songDurationMs - lastPart.startTimeMs
                }
            }
        }

        Lyric(
            startTimeMs = line.time,
            words = line.text,
            durationMs = duration,
            parts = if (parts.isNotEmpty()) parts.toMutableList() else null,
            agent = line.agent,
        )
    }

    return insertInstrumentalBreaks(lyrics, songDurationMs)
}

private fun readSongwriters(qrcXml: String): List<String> {
    val names = mutableListOf<String>()
    for (raw in unwrapEnvelope(qrcXml).split("\n")) {
        val lineTime = parseLineTime(raw.jsTrim()) ?: continue

        val text = parseWords(lineTime.rest).joinToString("") { w -> w.text }
        names += songwritersInCreditLine(text)
    }
    return uniqueNames(names)
}

public object QRCParser : LyricParser {
    override fun detect(input: String): Boolean {
        return isQrc(input)
    }

    override fun parse(input: String, duration: Double): List<Lyric> {
        return parseQRC(input, duration)
    }

    override fun metadata(input: String): LyricMetadata {
        return LyricMetadata(songwriters = if (input.isNotEmpty()) readSongwriters(input) else emptyList())
    }
}
