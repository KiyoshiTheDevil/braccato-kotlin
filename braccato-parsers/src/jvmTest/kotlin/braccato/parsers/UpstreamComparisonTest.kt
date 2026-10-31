// Not a port of an upstream test. Reads a folder of real lyric files with the Kotlin parsers and
// compares the result, file by file, with what the published JavaScript parsers read from the same
// files (tools/upstream-compare/dump.mjs writes that side).
//
// It only runs when both folders are given, since the lyric files are not part of the repository:
//
//   BRACCATO_COMPARE_CORPUS=<lyrics folder> BRACCATO_COMPARE_EXPECTED=<dump.mjs output> ./gradlew :braccato-parsers:jvmTest
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricPart
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

// Matches SONG_DURATION_MS in dump.mjs.
private const val SONG_DURATION_MS = 240000.0

private val LYRIC_FILE = Regex("""\.(ttml|lrc|srt|qrc|txt)$""", RegexOption.IGNORE_CASE)

class UpstreamComparisonTest {
    @Test fun `reads every corpus file the way the JavaScript parsers do`() {
        val corpus = System.getenv("BRACCATO_COMPARE_CORPUS")?.let(::File)
        val expected = System.getenv("BRACCATO_COMPARE_EXPECTED")?.let(::File)
        if (corpus == null || expected == null) {
            println("UpstreamComparisonTest: BRACCATO_COMPARE_CORPUS / BRACCATO_COMPARE_EXPECTED not set, skipped")
            return
        }

        val files = corpus.listFiles { file -> LYRIC_FILE.containsMatchIn(file.name) }!!.sortedBy { it.name }
        val mismatches = mutableListOf<String>()
        for (file in files) {
            val want = File(expected, "${file.name}.json").readText()
            val got = toJson(read(file.readText())) + "\n"
            if (got != want) mismatches += "${file.name}: ${firstDifference(want, got)}"
        }
        println("UpstreamComparisonTest: ${files.size - mismatches.size} of ${files.size} file(s) match")
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n"))
    }
}

private fun read(text: String): Map<String, Any?> {
    val format = detectFormat(text)
    val parser = detectParser(text)
    val result = linkedMapOf<String, Any?>(
        "format" to format.value,
        "lyrics" to parser.parse(text, SONG_DURATION_MS).map(::lyricTree),
        "metadata" to mapOf("songwriters" to parser.metadata(text).songwriters),
    )
    if (format == LyricFormat.TTML) {
        val content = parseTTMLContent(text, ParseTTMLOptions(songDurationMs = SONG_DURATION_MS))
        result["ttml"] = mapOf(
            "isWordSynced" to content.isWordSynced,
            "language" to content.language,
            "lyrics" to content.lyrics.map(::lyricTree),
        )
    }
    if (format == LyricFormat.LRC) result["rawLrc"] = parseLRC(text, SONG_DURATION_MS).map(::lyricTree)
    return result
}

private fun partTree(part: LyricPart): Map<String, Any?> = mapOf(
    "durationMs" to part.durationMs,
    "explicit" to part.explicit,
    "isBackground" to part.isBackground,
    "startTimeMs" to part.startTimeMs,
    "words" to part.words,
)

private fun lyricTree(lyric: Lyric): Map<String, Any?> = mapOf(
    "agent" to lyric.agent,
    "durationMs" to lyric.durationMs,
    "isInstrumental" to lyric.isInstrumental,
    "key" to lyric.key,
    "parts" to lyric.parts?.map(::partTree),
    "romanization" to lyric.romanization,
    "startTimeMs" to lyric.startTimeMs,
    "timedRomanization" to lyric.timedRomanization?.map(::partTree),
    "translation" to lyric.translation?.let { mapOf("lang" to it.lang, "text" to it.text) },
    "translations" to lyric.translations,
    "words" to lyric.words,
)

/** `JSON.stringify(canonical(value), null, 1)`: sorted keys, nulls left out, one space of indent. */
private fun toJson(value: Any?, depth: Int = 0): String {
    val indent = " ".repeat(depth + 1)
    val closing = " ".repeat(depth)
    return when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean -> value.toString()
        is Double -> jsonNumber(value)
        is Int -> value.toString()
        is Map<*, *> -> {
            val entries = value.entries
                .filter { it.value != null }
                .sortedBy { it.key as String }
            if (entries.isEmpty()) "{}"
            else entries.joinToString(",\n", "{\n", "\n$closing}") { (key, v) ->
                "$indent${jsonString(key as String)}: ${toJson(v, depth + 1)}"
            }
        }
        is List<*> ->
            if (value.isEmpty()) "[]"
            else value.joinToString(",\n", "[\n", "\n$closing]") { "$indent${toJson(it, depth + 1)}" }
        else -> error("cannot write ${value::class}")
    }
}

private fun jsonNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "null"
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e21) return value.toLong().toString()
    return value.toString()
}

private fun jsonString(s: String): String {
    val out = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}

private fun firstDifference(want: String, got: String): String {
    val wantLines = want.lines()
    val gotLines = got.lines()
    val index = (0 until maxOf(wantLines.size, gotLines.size)).first { wantLines.getOrNull(it) != gotLines.getOrNull(it) }
    return "line ${index + 1}: expected `${wantLines.getOrNull(index)}`, got `${gotLines.getOrNull(index)}`"
}
