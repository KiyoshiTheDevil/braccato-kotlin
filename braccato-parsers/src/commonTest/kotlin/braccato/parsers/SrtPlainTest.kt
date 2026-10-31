// Port of packages/parsers/src/__tests__/srt.test.ts and plain.test.ts.
package braccato.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SRTParserTest {
    @Test fun `detects SRT format`() = assertTrue(SRTParser.detect("1\n00:00:01,000 --> 00:00:04,000\nHello world"))

    @Test fun `detects SRT with dot separator`() = assertTrue(SRTParser.detect("1\n00:00:01.000 --> 00:00:04.000\nHello"))

    @Test fun `rejects non-SRT text`() = assertFalse(SRTParser.detect("[00:12.50]Hello"))

    @Test fun `parses standard SRT`() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n2\n00:00:05,000 --> 00:00:08,500\nSecond line"

        val result = SRTParser.parse(srt)

        assertEquals(2, result.size)
        assertEquals(1000.0, result[0].startTimeMs)
        assertEquals(3000.0, result[0].durationMs)
        assertEquals("Hello world", result[0].words)
        assertEquals(5000.0, result[1].startTimeMs)
        assertEquals(3500.0, result[1].durationMs)
    }

    @Test fun `handles multi-line subtitles`() {
        val result = SRTParser.parse("1\n00:00:01,000 --> 00:00:04,000\nLine one\nLine two")

        assertEquals(1, result.size)
        assertEquals("Line one\nLine two", result[0].words)
    }

    @Test fun `strips HTML tags`() {
        val result = SRTParser.parse("1\n00:00:01,000 --> 00:00:04,000\n<i>Italic</i> and <b>bold</b>")
        assertEquals("Italic and bold", result[0].words)
    }

    @Test fun `handles dot time separator`() {
        val result = SRTParser.parse("1\n00:01:30.500 --> 00:01:35.000\nNinety seconds")
        assertEquals(90500.0, result[0].startTimeMs)
    }

    @Test fun `handles empty input`() = assertEquals(emptyList(), SRTParser.parse(""))

    @Test fun `skips malformed blocks`() {
        val result = SRTParser.parse("1\nnot a time line\nsome text\n\n2\n00:00:01,000 --> 00:00:04,000\nValid line")

        assertEquals(1, result.size)
        assertEquals("Valid line", result[0].words)
    }

    @Test fun `reads no songwriters, since SRT has nowhere to keep them`() = assertEquals(
        LyricMetadata(songwriters = emptyList()),
        SRTParser.metadata("1\n00:00:01,000 --> 00:00:02,000\nWritten by: Someone"),
    )

    @Test fun `SRT metadata reads nothing from empty input`() =
        assertEquals(LyricMetadata(songwriters = emptyList()), SRTParser.metadata(""))
}

class PlainParserTest {
    @Test fun `always returns true`() {
        assertTrue(PlainParser.detect("anything"))
        assertTrue(PlainParser.detect(""))
    }

    @Test fun `splits by newlines`() {
        val result = PlainParser.parse("Line one\nLine two\nLine three")

        assertEquals(3, result.size)
        assertEquals("Line one", result[0].words)
        assertEquals("Line two", result[1].words)
        assertEquals("Line three", result[2].words)
    }

    @Test fun `sets all times to zero`() {
        for (lyric in PlainParser.parse("Line one\nLine two")) {
            assertEquals(0.0, lyric.startTimeMs)
            assertEquals(0.0, lyric.durationMs)
        }
    }

    @Test fun `handles single line`() = assertEquals(1, PlainParser.parse("Single line").size)

    @Test fun `reads no songwriters, since plain text has nowhere to keep them`() =
        assertEquals(LyricMetadata(songwriters = emptyList()), PlainParser.metadata("Written by: Someone\nA line"))

    @Test fun `plain metadata reads nothing from empty input`() =
        assertEquals(LyricMetadata(songwriters = emptyList()), PlainParser.metadata(""))
}
