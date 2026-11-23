// Port of packages/parsers/src/__tests__/format.test.ts and detect.test.ts.
//
// format.test.ts also checks that format.js exports nothing but detectFormat at runtime, which is
// about the JavaScript module and has no counterpart here.
package dev.kiyoshi.braccato.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private const val DIGIT_RUN = 1_000_000
private val PREVIOUS_SRT = Regex("""\d+\r?\n\d{2}:\d{2}:\d{2}[,.]\d+ --> \d{2}:\d{2}:\d{2}[,.]\d+""")

private val SAMPLES = mapOf(
    "ttml" to """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.0" end="2.0">Hi</p></div></body></tt>""",
    "lrc" to "[ti:Song]\n[00:12.50]Hello\n[00:14.00]<00:14.00>Word <00:14.40>timed",
    "srt" to "1\n00:00:01,000 --> 00:00:04,000\nHello",
    "qrc" to "[1000,3000]Hel(1000,500)lo(1500,500)",
    "qrcEnvelope" to """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,2000]Hello world"/></LyricInfo></QrcInfos>""",
    "plain" to "just some words\nno timing at all",
)

class DetectFormatTest {
    @Test fun `detects each format`() {
        assertEquals(LyricFormat.TTML, detectFormat(SAMPLES.getValue("ttml")))
        assertEquals(LyricFormat.LRC, detectFormat(SAMPLES.getValue("lrc")))
        assertEquals(LyricFormat.SRT, detectFormat(SAMPLES.getValue("srt")))
        assertEquals(LyricFormat.QRC, detectFormat(SAMPLES.getValue("qrc")))
        assertEquals(LyricFormat.QRC, detectFormat(SAMPLES.getValue("qrcEnvelope")))
        assertEquals(LyricFormat.PLAIN, detectFormat(SAMPLES.getValue("plain")))
    }

    // -- edge cases --

    @Test fun `treats empty and whitespace input as plain`() {
        assertEquals(LyricFormat.PLAIN, detectFormat(""))
        assertEquals(LyricFormat.PLAIN, detectFormat("   \n\t"))
    }

    @Test fun `accepts CRLF SRT`() = assertEquals(LyricFormat.SRT, detectFormat("1\r\n00:00:01,000 --> 00:00:04,000\r\nHello"))

    @Test fun `does not call LRC without fractional seconds`() = assertEquals(LyricFormat.PLAIN, detectFormat("[00:12]Hello"))

    @Test fun `needs both line and word stamps for bare QRC`() = assertEquals(LyricFormat.PLAIN, detectFormat("[1000,3000]Hello"))

    // -- invariants --

    @Test fun `honours the priority TTML, LRC, SRT, QRC`() {
        assertEquals(LyricFormat.TTML, detectFormat("<tt>[00:01.00]x</tt>"))
        assertEquals(LyricFormat.LRC, detectFormat("[00:01.00]x\n[1000,200]y(1000,100)"))
        assertEquals(LyricFormat.SRT, detectFormat("1\n00:00:01,000 --> 00:00:02,000\n[1000,200]y(1000,100)"))
    }

    @Test fun `agrees with detectParser on every sample`() {
        val byFormat = mapOf(
            LyricFormat.TTML to TTMLParser,
            LyricFormat.LRC to LRCParser,
            LyricFormat.SRT to SRTParser,
            LyricFormat.QRC to QRCParser,
            LyricFormat.PLAIN to PlainParser,
        )
        for (text in SAMPLES.values) {
            assertSame(byFormat.getValue(detectFormat(text)), detectParser(text))
        }
    }

    // -- regressions --

    @Test fun `regression - srt detection stays linear on a long run of digits`() {
        val started = TimeSource.Monotonic.markNow()
        assertEquals(LyricFormat.PLAIN, detectFormat("1".repeat(DIGIT_RUN)))
        val elapsed = started.elapsedNow()
        assertTrue(elapsed.inWholeMilliseconds < 1000, "took $elapsed")
    }

    @Test fun `srt detection agrees with the previous pattern`() {
        val inputs = SAMPLES.values + listOf(
            "1\r\n00:00:01,000 --> 00:00:04,000\r\nHello",
            "12345\n00:00:01.5 --> 00:00:04.25\nx",
            "a1\n00:00:01,000 --> 00:00:02,000",
            "1\n0:00:01,000 --> 00:00:02,000",
            "1\n\n00:00:01,000 --> 00:00:02,000",
            "99 00:00:01,000 --> 00:00:02,000",
            "",
        )
        for (input in inputs) assertEquals(PREVIOUS_SRT.containsMatchIn(input), isSrt(input), input)
    }
}

class DetectParserTest {
    @Test fun `detects LRC`() = assertSame(LRCParser, detectParser("[00:12.50]Hello"))

    @Test fun `detects SRT`() = assertSame(SRTParser, detectParser("1\n00:00:01,000 --> 00:00:04,000\nHello"))

    @Test fun `detects TTML`() = assertSame(TTMLParser, detectParser("<tt><body></body></tt>"))

    @Test fun `detects QRC`() = assertSame(QRCParser, detectParser("[1000,3000]Hello(1000,500)"))

    @Test fun `routes a QQ Music envelope to the QRC parser`() {
        val envelope = """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,2000]Hello world"/></LyricInfo></QrcInfos>"""
        assertSame(QRCParser, detectParser(envelope))
    }

    @Test fun `falls back to PlainParser`() = assertSame(PlainParser, detectParser("Just some text\nAnother line"))
}
