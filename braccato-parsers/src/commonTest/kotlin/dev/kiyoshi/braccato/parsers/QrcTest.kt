// Port of packages/parsers/src/__tests__/qrc.test.ts.
package dev.kiyoshi.braccato.parsers

import dev.kiyoshi.braccato.types.Lyric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private fun sung(lyrics: List<Lyric>) = lyrics.filter { it.isInstrumental != true }

class QRCParserTest {
    // -- detect --

    @Test fun `detects QRC format`() = assertTrue(QRCParser.detect("[1000,3000]Hello(1000,500) world(1500,500)"))

    @Test fun `rejects LRC format`() = assertFalse(QRCParser.detect("[00:12.50]Hello world"))

    @Test fun `detects a QQ Music envelope whose body is still escaped`() {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
  <LyricInfo LyricCount="1">
    <Lyric_1 LyricType="1" LyricContent="[1000,2000]Hello(1000,1000)"/>
  </LyricInfo>
</QrcInfos>"""
        assertTrue(QRCParser.detect(envelope))
    }

    @Test fun `detects an envelope even when the body carries no word timings`() {
        val envelope = """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,2000]Hello world"/></LyricInfo></QrcInfos>"""
        assertTrue(QRCParser.detect(envelope))
    }

    // -- parse --

    @Test fun `parses QRC with word timing`() {
        val result = QRCParser.parse("[1000,3000]Hello(1000,500) world(1500,1500)")

        assertEquals(1, result.size)
        assertEquals(1000.0, result[0].startTimeMs)
        assertEquals(3000.0, result[0].durationMs)
        assertEquals("Hello world", result[0].words)
        assertEquals(2, result[0].parts?.size)
        assertEquals("Hello", result[0].parts!![0].words)
        assertEquals(1000.0, result[0].parts!![0].startTimeMs)
        assertEquals(500.0, result[0].parts!![0].durationMs)
    }

    @Test fun `handles multiple lines`() {
        val qrc = "[1000,2000]First(1000,1000) line(2000,1000)\n[5000,3000]Second(5000,1500) line(6500,1500)"

        val result = QRCParser.parse(qrc)

        assertEquals(2, result.size)
        assertEquals(5000.0, result[1].startTimeMs)
    }

    @Test fun `handles empty input`() = assertEquals(emptyList(), QRCParser.parse(""))

    @Test fun `keeps a literal bracket inside a syllable`() {
        // The syllable text is whatever sits between two timing tokens, brackets included. A scan
        // that stopped at the first "(" would read this line as "world)".
        val lines = sung(QRCParser.parse("[0,2000]Hello (world)(0,1000)", 10000.0))

        assertEquals(1, lines.size)
        assertEquals("Hello (world)", lines[0].words)
        assertEquals(1, lines[0].parts?.size)
        assertEquals("Hello (world)", lines[0].parts!![0].words)
    }

    @Test fun `parses a long line of unmatched brackets without stalling`() {
        // A user dropped file can be anything, and a line of "(" with no timing token after it made
        // the old lazy scan backtrack over the whole line from every position. The bound is loose on
        // purpose.
        val line = "[0,5000]" + "(".repeat(100000)

        val started = TimeSource.Monotonic.markNow()
        QRCParser.parse(line, 10000.0)
        val elapsed = started.elapsedNow()

        assertTrue(elapsed.inWholeMilliseconds < 500, "took $elapsed")
    }

    // -- QrcInfos envelope --

    @Test fun `parses the body out of a LyricContent attribute`() {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
  <QrcHeadInfo SaveTime="0" Version="100"/>
  <LyricInfo LyricCount="1">
    <Lyric_1 LyricType="1" LyricContent="[1000,2000]Hello(1000,1000)world(2000,1000)
[4000,2000]Second(4000,1000)line(5000,1000)
"/>
  </LyricInfo>
</QrcInfos>"""

        val lines = sung(parseQRC(envelope, 10000.0))

        assertEquals(2, lines.size)
        assertEquals(1000.0, lines[0].startTimeMs)
        assertEquals("Helloworld", lines[0].words)
        assertEquals(2, lines[0].parts?.size)
        assertEquals(4000.0, lines[1].startTimeMs)
    }

    @Test fun `unescapes quot and amp in the envelope body`() {
        val envelope = """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,2000]&quot;Quoted&quot;(1000,1000) &amp;more(2000,1000)"/></LyricInfo></QrcInfos>"""

        val lines = sung(parseQRC(envelope, 10000.0))

        assertEquals(1, lines.size)
        assertEquals("\"Quoted\" &more", lines[0].words)
        assertEquals("\"Quoted\"", lines[0].parts!![0].words)
        assertEquals(" &more", lines[0].parts!![1].words)
    }

    @Test fun `skips the bracketed metadata tags a QQ Music body carries`() {
        val body = "[ti:Some Song]\n[ar:Some Artist]\n[1000,2000]Real(1000,1000)line(2000,1000)"

        val lines = sung(parseQRC(body, 10000.0))

        assertEquals(1, lines.size)
        assertEquals("Realline", lines[0].words)
    }

    // -- malformed input --
    // The same contract the other parsers hold to: a file that is not this format, or is this format
    // broken, comes back empty rather than thrown.

    private val unreadable = listOf(
        "an empty string" to "",
        "whitespace only" to "   \n\t  ",
        "text that is not QRC" to "just some prose, nothing timed about it",
        "another lyrics format entirely" to "<tt><body></body></tt>",
        "deeply nested tags" to "<a>".repeat(600) + "x" + "</a>".repeat(600),
        "line timings that are not numbers" to "[abc,def]Hello(xy,zw)",
        "a line bracket that never closes" to "[0,1000Hello(0,100)",
        "a line time with no comma in it" to "[01000]Hello(0,100)",
        "empty brackets" to "[]Hello()",
        "an envelope whose LyricContent is empty" to """<QrcInfos><Lyric_1 LyricContent=""/></QrcInfos>""",
        "an envelope that stops mid attribute" to """<QrcInfos><Lyric_1 LyricContent="[0,1000]Hi(0,100)""",
        "an envelope carrying no LyricContent at all" to "<QrcInfos><LyricInfo><Lyric_1/></LyricInfo></QrcInfos>",
        "nothing but the bracketed metadata tags" to "[ti:Song]\n[ar:Artist]",
    )

    @Test fun `returns nothing for unreadable input`() {
        for ((label, input) in unreadable) {
            assertEquals(emptyList(), QRCParser.parse(input, 20000.0), label)
            assertEquals(emptyList(), parseQRC(input, 20000.0, QrcMetadata(title = "Song", artist = "Artist")), label)
        }
    }

    @Test fun `reads timings that carry no text without inventing any`() {
        val result = sung(parseQRC("[0,1000](0,500)(500,500)", 20000.0))

        assertEquals(1, result.size)
        assertEquals("", result[0].words)
        assertEquals(listOf("", ""), result[0].parts!!.map { it.words })
    }

    // -- singers --

    @Test fun `lifts a leading Name into an agent and strips it from the text`() {
        val lines = sung(parseQRC("[1000,2000]Alice:(1000,100)Hello (1100,900)there(2000,1000)", 10000.0))

        assertEquals(1, lines.size)
        assertEquals("v1", lines[0].agent)
        assertEquals("Hello there", lines[0].words)
        assertEquals(2, lines[0].parts?.size)
        assertEquals(1100.0, lines[0].startTimeMs)
    }

    @Test fun `keeps the last named singer on the lines that follow`() {
        val body = listOf(
            "[1000,2000]Alice:(1000,100)Hello (1100,900)there(2000,1000)",
            "[4000,2000]world(4000,2000)",
            "[7000,2000]Bob:(7000,100)Bye(7100,1900)",
            "[10000,2000]now(10000,2000)",
        ).joinToString("\n")

        assertEquals(listOf("v1", "v1", "v2", "v2"), sung(parseQRC(body, 20000.0)).map { it.agent })
    }

    @Test fun `maps a group singer to the v1000 slot`() {
        val body = listOf(
            "[1000,2000]Alice:(1000,100)Solo(1100,1900)",
            "[4000,2000]合:(4000,100)Together(4100,1900)",
            "[7000,2000]ALL:(7000,100)Everyone(7100,1900)",
        ).joinToString("\n")

        val lines = sung(parseQRC(body, 20000.0))

        assertEquals(listOf("v1", "v1000", "v1000"), lines.map { it.agent })
        assertEquals("Together", lines[1].words)
    }

    @Test fun `drops credit lines instead of reading them as singers`() {
        val body = listOf(
            "[0,3000]作词:(0,1500)张三(1500,1500)",
            "[3000,2000]Produced by:(3000,500)Someone(3500,1500)",
            "[6000,2000]Real(6000,1000)lyric(7000,1000)",
        ).joinToString("\n")

        val lines = sung(parseQRC(body, 20000.0))

        assertEquals(1, lines.size)
        assertEquals("Reallyric", lines[0].words)
    }

    // -- title echo filtering --

    @Test fun `drops an opening line that repeats the title and artist`() {
        val body = "[0,2000]Bohemian(0,400) Rhapsody(400,400) -(800,400) Queen(1200,800)\n[3000,2000]Is(3000,1000) this(4000,1000)"

        val lines = sung(parseQRC(body, 20000.0, QrcMetadata(title = "Bohemian Rhapsody", artist = "Queen")))

        assertEquals(1, lines.size)
        assertEquals("Is this", lines[0].words)
    }

    @Test fun `keeps a title echo that appears after the opening lines`() {
        val body = listOf(
            "[0,1000]One(0,400) two(400,600)",
            "[1000,1000]Three(1000,400) four(1400,600)",
            "[2000,1000]Five(2000,400) six(2400,600)",
            "[3000,1000]Seven(3000,400) eight(3400,600)",
            "[4000,1000]Nine(4000,400) ten(4400,600)",
            "[5000,2000]Bohemian(5000,400) Rhapsody(5400,1600)",
        ).joinToString("\n")

        val lines = sung(parseQRC(body, 20000.0, QrcMetadata(title = "Bohemian Rhapsody", artist = "Queen")))

        assertEquals(6, lines.size)
        assertEquals("Bohemian Rhapsody", lines[5].words)
    }

    // -- instrumental breaks --

    @Test fun `fills a long silence between lines`() {
        val body = "[0,2000]First(0,1000) line(1000,1000)\n[20000,2000]Second(20000,1000) line(21000,1000)"

        val instrumentals = parseQRC(body, 40000.0).filter { it.isInstrumental == true }

        assertEquals(2, instrumentals.size)
        assertEquals(2000.0, instrumentals[0].startTimeMs)
        assertEquals(18000.0, instrumentals[0].durationMs)
        assertEquals(22000.0, instrumentals[1].startTimeMs)
    }
}

class QRCParserMetadataTest {
    @Test fun `reads the lyricist and composer credit lines`() {
        val body = "[0,3000]作词:(0,1500)周杰伦(1500,1500)\n[3000,3000]作曲:(3000,1500)方文山(4500,1500)\n[6000,2000]Real(6000,1000)lyric(7000,1000)"

        assertEquals(LyricMetadata(songwriters = listOf("周杰伦", "方文山")), QRCParser.metadata(body))
    }

    @Test fun `reads English songwriting prefixes`() {
        val body = "[0,3000]Written by:(0,1500)Max Martin(1500,1500)\n[3000,3000]Lyrics by:(3000,1500)Savan Kotecha(4500,1500)"

        assertEquals(listOf("Max Martin", "Savan Kotecha"), QRCParser.metadata(body).songwriters)
    }

    @Test fun `reads credits inside the QQ Music envelope`() {
        val envelope = """<?xml version="1.0" encoding="utf-8"?><QrcInfos><LyricInfo LyricCount="1"><Lyric_1 LyricType="1" LyricContent="[ti:Song]
[0,3000]词：(0,1500)林夕(1500,1500)
[3000,2000]Real(3000,1000)lyric(4000,1000)"/></LyricInfo></QrcInfos>"""

        assertEquals(listOf("林夕"), QRCParser.metadata(envelope).songwriters)
    }

    // -- edge cases --

    @Test fun `skips credits that are not songwriting`() {
        val body = "[0,3000]Produced by:(0,1500)Someone(1500,1500)\n[3000,3000]编曲:(3000,1500)Arranger(4500,1500)\n[6000,3000]混音:(6000,1500)Mixer(7500,1500)"

        assertEquals(emptyList(), QRCParser.metadata(body).songwriters)
    }

    @Test fun `splits a credit that lists several names and names each person once`() {
        val body = "[0,3000]作词:(0,1500)A/B(1500,1500)\n[3000,3000]作曲:(3000,1500)B、C(4500,1500)"

        assertEquals(listOf("A", "B", "C"), QRCParser.metadata(body).songwriters)
    }

    @Test fun `does not read a singer label as a songwriter`() =
        assertEquals(emptyList(), QRCParser.metadata("[0,2000]王力宏:(0,500)我(500,1500)").songwriters)

    @Test fun `reads nothing from empty input or a body without credits`() {
        assertEquals(emptyList(), QRCParser.metadata("").songwriters)
        assertEquals(emptyList(), QRCParser.metadata("[0,2000]Real(0,1000)lyric(1000,1000)").songwriters)
    }

    // -- regressions --

    @Test fun `keeps compound and prefixed credit roles out of the parsed lyrics`() {
        val body = "[0,3000]Rap作词:(0,1500)张三(1500,1500)\n[3000,3000]作曲/编曲:(3000,1500)李四(4500,1500)\n[6000,2000]歌(6000,1000)词(7000,1000)"

        val lines = sung(QRCParser.parse(body, 20000.0))
        assertEquals(listOf("歌词" to null), lines.map { it.words to it.agent })
    }

    @Test fun `still keeps the credit lines out of the parsed lyrics`() {
        val body = "[0,3000]作词:(0,1500)周杰伦(1500,1500)\n[6000,2000]Real(6000,1000)lyric(7000,1000)"

        assertEquals(listOf("Reallyric"), sung(QRCParser.parse(body, 20000.0)).map { it.words })
    }
}
