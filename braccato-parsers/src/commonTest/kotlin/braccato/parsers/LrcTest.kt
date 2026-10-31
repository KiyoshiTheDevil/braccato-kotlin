// Port of packages/parsers/src/__tests__/lrc.test.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Most of these tests are about the sung lines, so they read past the intro and breaks parseLRC adds.
private fun sung(lyrics: List<Lyric>) = lyrics.filter { it.isInstrumental != true }

private fun instrumentals(lyrics: List<Lyric>) =
    lyrics.filter { it.isInstrumental == true }.map { listOf(it.startTimeMs, it.durationMs) }

private fun span(start: Double, duration: Double) = listOf(start, duration)

class LRCParserTest {
    // -- detect --

    @Test fun `detects standard LRC format`() = assertTrue(LRCParser.detect("[00:12.50]Hello world"))

    @Test fun `rejects non-LRC text`() = assertFalse(LRCParser.detect("Just plain text"))

    @Test fun `rejects XML`() = assertFalse(LRCParser.detect("<tt><body></body></tt>"))

    // -- parse --

    @Test fun `parses simple synced lyrics`() {
        val lrc = "[00:12.50]Hello world\n[00:15.00]Second line\n[00:20.00]Third line"

        val result = sung(LRCParser.parse(lrc, 30000.0))

        assertEquals(3, result.size)
        assertEquals(12500.0, result[0].startTimeMs)
        assertEquals("Hello world", result[0].words)
        assertEquals(15000.0, result[1].startTimeMs)
        assertEquals("Second line", result[1].words)
    }

    @Test fun `calculates durations from next line start`() {
        val lrc = "[00:10.00]Line one\n[00:15.00]Line two\n[00:25.00]Line three"

        val result = sung(LRCParser.parse(lrc, 30000.0))

        assertEquals(5000.0, result[0].durationMs)
        assertEquals(10000.0, result[1].durationMs)
        assertEquals(5000.0, result[2].durationMs) // songDuration - startTime
    }

    @Test fun `handles enhanced LRC with word timestamps`() {
        val lrc = "[00:10.00]<00:10.00>Hello <00:10.50>world <00:11.00>today"

        val result = sung(LRCParser.parse(lrc, 20000.0))

        assertEquals(1, result.size)
        assertNotNull(result[0].parts)
        assertTrue(result[0].parts!!.isNotEmpty())
        assertTrue(result[0].words.contains("Hello"))
        assertTrue(result[0].words.contains("world"))
    }

    @Test fun `skips ID tags`() {
        val lrc = "[ti:Song Title]\n[ar:Artist Name]\n[al:Album Name]\n[00:05.00]First lyric line"

        val result = LRCParser.parse(lrc, 10000.0)

        assertEquals(1, result.size)
        assertEquals("First lyric line", result[0].words)
    }

    @Test fun `applies offset from ID tags`() {
        val result = sung(LRCParser.parse("[offset:0.5]\n[00:10.00]Line with offset", 20000.0))

        assertEquals(10000.0 - 500, result[0].startTimeMs)
    }

    @Test fun `handles mm_ss_xx format`() {
        val result = sung(LRCParser.parse("[01:30.50]Ninety seconds in", 120000.0))

        assertEquals(90500.0, result[0].startTimeMs)
    }

    @Test fun `returns empty array for empty input`() = assertEquals(emptyList(), LRCParser.parse("", 0.0))

    @Test fun `returns empty array for input with only ID tags`() =
        assertEquals(emptyList(), LRCParser.parse("[ti:Title]\n[ar:Artist]", 0.0))

    @Test fun `keeps the words of a part that starts at zero`() {
        // The text after a timestamp is appended to the part that timestamp opened. Testing that
        // part for truthiness instead of existence dropped the words of anything starting at 0.
        val result = LRCParser.parse("[00:00.00]<00:00.00>Hello <00:00.50>World", 5000.0)

        assertEquals("Hello World", result[0].words)
        assertEquals(listOf("Hello ", "World"), result[0].parts!!.map { it.words })
        assertEquals(0.0, result[0].parts!![0].startTimeMs)
    }

    @Test fun `never gives a negative duration when the caller omits the song duration`() {
        // The last line and its last word end against the song duration, and the default is 0, so
        // subtracting from it used to run every one of them backwards.
        val lrc = "[00:10.00]Line one\n[00:20.00]<00:20.00>Line <00:21.00>two"

        for (lyrics in listOf(sung(LRCParser.parse(lrc)), sung(parseLRC(lrc, 0.0)))) {
            assertEquals(2, lyrics.size)
            for (line in lyrics) {
                assertTrue(line.durationMs >= 0)
                for (part in line.parts ?: emptyList()) {
                    assertTrue(part.durationMs >= 0)
                }
            }
        }
    }

    @Test fun `still ends the last line against a song duration when it is given one`() {
        val result = sung(LRCParser.parse("[00:10.00]Line one\n[00:20.00]Line two", 30000.0))

        assertEquals(10000.0, result[1].durationMs)
    }

    @Test fun `survives a line carrying more time tags than a call can take arguments`() {
        // Reducing the tags to a start and an end by spreading them into Math.min blew the stack at
        // roughly 125,000 of them, which a dropped file reaches long before a real lyric does.
        val lrc = "[00:00.00]".repeat(200000) + "Hello"

        assertEquals("Hello", LRCParser.parse(lrc, 10000.0)[0].words)
    }

    // -- instrumental gaps: blank lines --

    @Test fun `turns a long blank line into an instrumental line`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]\n[00:10.00]D", 20000.0)

        assertEquals(
            listOf(
                Lyric(startTimeMs = 1000.0, durationMs = 3000.0, words = "A", parts = null),
                Lyric(startTimeMs = 4000.0, durationMs = 6000.0, words = "", parts = mutableListOf(), isInstrumental = true),
                Lyric(startTimeMs = 10000.0, durationMs = 10000.0, words = "D", parts = null),
            ),
            result,
        )
    }

    @Test fun `drops a short blank line and ends the line before it where the blank starts`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]\n[00:06.00]B", 20000.0)

        assertEquals(
            listOf(Triple("A", 1000.0, 3000.0), Triple("B", 6000.0, 14000.0)),
            result.map { Triple(it.words, it.startTimeMs, it.durationMs) },
        )
    }

    @Test fun `leaves a gap exactly at the threshold alone, like the TTML gap fill`() {
        assertEquals(emptyList(), instrumentals(parseLRC("[00:01.00]A\n[00:04.00]\n[00:09.00]B", 20000.0)))
    }

    @Test fun `merges blank lines in a row into one break`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]\n[00:08.00]\n[00:20.00]B", 30000.0)

        assertEquals(listOf(span(4000.0, 16000.0)), instrumentals(result))
        assertEquals(3000.0, result[0].durationMs)
    }

    @Test fun `turns a leading blank line into an intro`() {
        assertEquals(listOf(span(0.0, 20000.0)), instrumentals(parseLRC("[00:00.00]\n[00:20.00]A", 30000.0)))
    }

    @Test fun `turns a trailing blank line into an outro`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]B\n[00:10.00]", 60000.0)

        assertEquals(listOf(span(10000.0, 50000.0)), instrumentals(result))
        assertEquals(6000.0, result[1].durationMs)
    }

    @Test fun `still ends the last line at a trailing blank line when no song duration is given`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]B\n[00:10.00]", 0.0)

        assertEquals(2, result.size)
        assertEquals(6000.0, result[1].durationMs)
    }

    @Test fun `treats a line of only music notes as a blank line`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00] ♪ \n[00:20.00]B\n[00:24.00]♫♪\n[00:26.00]C", 30000.0)

        assertEquals(listOf(span(4000.0, 16000.0)), instrumentals(result))
        assertEquals(listOf("A", "", "B", "C"), result.map { it.words })
    }

    @Test fun `keeps a line that has words around a music note`() {
        assertEquals(
            listOf("♪ la la ♪", "B"),
            sung(parseLRC("[00:01.00]♪ la la ♪\n[00:04.00]B", 10000.0)).map { it.words },
        )
    }

    @Test fun `treats an empty background group as blank but keeps a background-only line`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00][bg:]\n[00:20.00][bg:(ooh)]\n[00:22.00]B", 30000.0)

        assertEquals(listOf(span(4000.0, 16000.0)), instrumentals(result))
        assertEquals(listOf("A", "(ooh)", "B"), sung(result).map { it.words })
    }

    // -- instrumental gaps: intro and outro --

    @Test fun `adds an intro when the first line starts after the threshold`() {
        assertEquals(listOf(span(0.0, 12000.0)), instrumentals(parseLRC("[00:12.00]A\n[00:15.00]B", 20000.0)))
    }

    @Test fun `adds no intro when the first line starts within the threshold`() {
        assertEquals(emptyList(), instrumentals(parseLRC("[00:05.00]A\n[00:08.00]B", 20000.0)))
    }

    @Test fun `runs a last line with no stated end to the song end, so there is no outro`() {
        val result = parseLRC("[00:01.00]A\n[00:04.00]B", 60000.0)

        assertEquals(emptyList(), instrumentals(result))
        assertEquals(56000.0, result[1].durationMs)
    }

    // -- instrumental gaps: instrumentalGapMs --

    private val gapLrc = "[00:01.00]A\n[00:04.00]\n[00:12.00]B"

    @Test fun `instrumentalGapMs defaults to the TTML threshold`() {
        assertEquals(listOf(span(4000.0, 8000.0)), instrumentals(parseLRC(gapLrc, 20000.0)))
    }

    @Test fun `instrumentalGapMs is respected when raised`() {
        assertEquals(
            emptyList(),
            instrumentals(parseLRC(gapLrc, 20000.0, InstrumentalBreakOptions(instrumentalGapMs = 10000.0))),
        )
    }

    @Test fun `instrumentalGapMs is respected when lowered`() {
        val result = parseLRC(
            "[00:01.00]A\n[00:04.00]\n[00:07.00]B",
            20000.0,
            InstrumentalBreakOptions(instrumentalGapMs = 2000.0),
        )

        assertEquals(listOf(span(4000.0, 3000.0)), instrumentals(result))
    }

    // -- instrumental gaps: enhanced LRC --

    @Test fun `ends the last word and the line at a closing timestamp, with no empty part`() {
        val line = parseLRC("[00:01.00]<00:01.00>Hello <00:01.50>world <00:02.00>\n[00:03.00]B", 10000.0)[0]

        assertEquals(1000.0, line.durationMs)
        assertEquals(
            listOf(Triple("Hello ", 1000.0, 500.0), Triple("world ", 1500.0, 500.0)),
            line.parts!!.map { Triple(it.words, it.startTimeMs, it.durationMs) },
        )
    }

    @Test fun `starts a break at the closing timestamp without needing a blank line`() {
        val result = parseLRC("[00:01.00]<00:01.00>Hello <00:02.00>\n[00:20.00]B", 30000.0)

        assertEquals(listOf(span(2000.0, 18000.0)), instrumentals(result))
    }

    @Test fun `starts the break at the closing timestamp when a later blank line also marks it`() {
        val result = parseLRC("[00:01.00]<00:01.00>Hello <00:02.00>\n[00:05.00]\n[00:20.00]B", 30000.0)

        assertEquals(1000.0, result[0].durationMs)
        assertEquals(listOf(span(2000.0, 18000.0)), instrumentals(result))
    }

    @Test fun `ends an unclosed last word at the blank line after it`() {
        val line = parseLRC("[00:01.00]<00:01.00>Hello <00:02.00>world\n[00:05.00]\n[00:20.00]B", 30000.0)[0]

        assertEquals(4000.0, line.durationMs)
        assertEquals("world", line.parts!![1].words)
        assertEquals(3000.0, line.parts!![1].durationMs)
    }

    @Test fun `finds the breaks between Musixmatch paired lines`() {
        val lrc = listOf(
            "[00:01.00]<00:01.00>Hello<00:01.40> <00:01.44>world<00:01.90> ",
            "[00:02.00]<00:02.00>Again<00:02.50> ",
            "[00:20.00]<00:20.00>After<00:20.50> ",
        ).joinToString("\n")

        val result = parseLRC(lrc, 30000.0)

        assertEquals(
            listOf(span(1000.0, 900.0), span(2000.0, 500.0), span(20000.0, 500.0)),
            sung(result).map { span(it.startTimeMs, it.durationMs) },
        )
        assertEquals(listOf(span(2500.0, 17500.0), span(20500.0, 9500.0)), instrumentals(result))
    }

    @Test fun `ends a line with background vocals at the later of its two closing timestamps`() {
        val line = parseLRC("[00:01.00]<00:01.00>Hi <00:02.00> [bg:<00:01.50>(ooh) <00:03.00>]\n[00:20.00]B", 30000.0)[0]

        assertEquals(2000.0, line.durationMs)
        assertEquals(listOf("Hi ", "(ooh) "), line.parts!!.map { it.words })
    }

    @Test fun `adds an outro after a closed last line`() {
        assertEquals(listOf(span(2000.0, 28000.0)), instrumentals(parseLRC("[00:01.00]<00:01.00>Bye <00:02.00>", 30000.0)))
    }

    @Test fun `treats a line end tag as the end of a plain line`() {
        val result = parseLRC("[00:01.00]Hello[00:02.00]\n[00:20.00]B", 30000.0)

        assertEquals(1000.0, result[0].durationMs)
        assertEquals(listOf(span(2000.0, 18000.0)), instrumentals(result))
    }

    @Test fun `places breaks after the offset tag is applied`() {
        assertEquals(
            listOf(span(3000.0, 16000.0)),
            instrumentals(parseLRC("[offset:1]\n[00:01.50]A\n[00:04.00]\n[00:20.00]B", 30000.0)),
        )
    }

    @Test fun `returns nothing for a file of only blank lines`() {
        assertEquals(emptyList(), parseLRC("[00:00.00]\n[00:10.00]", 30000.0))
    }

    // -- through LRCParser.parse --

    @Test fun `keeps a short closed last word at its closing timestamp before a break`() {
        val lrc = "[00:01.00]<00:01.00>a<00:01.10> <00:01.12>b<00:01.20> \n[00:20.00]<00:20.00>c<00:20.10> "

        val line = LRCParser.parse(lrc, 30000.0)[0]
        val last = line.parts!!.last()

        assertTrue(last.startTimeMs + last.durationMs <= line.startTimeMs + line.durationMs)
    }

    @Test fun `does not stretch a short unclosed last word across a short gap`() {
        val lrc = "[00:01.00]<00:01.00>a <00:01.10>b <00:01.20>c\n[00:01.50]\n[00:03.00]<00:03.00>d <00:03.10>e"

        val line = LRCParser.parse(lrc, 10000.0)[0]
        val last = line.parts!!.last()

        assertTrue(last.startTimeMs + last.durationMs <= 1500)
    }

    // -- regressions --

    @Test fun `regression - sorts lines a file lists out of order before finding the gaps`() {
        val result = parseLRC("[01:00.00]C\n[00:10.00]A\n[00:20.00]B", 120000.0)

        assertEquals(listOf("", "A", "B", "C"), result.map { it.words })
        assertEquals(listOf(span(0.0, 10000.0)), instrumentals(result))
    }

    @Test fun `regression - keeps an untimed background group out of the closed lead word`() {
        val line = sung(parseLRC("[00:10.00]<00:10.00>Hello <00:10.50>world<00:11.00> [bg:(ooh)]\n[00:30.00]Next", 60000.0))[0]

        assertEquals(listOf("Hello ", "world", " (ooh)"), line.parts!!.map { it.words })
    }

    @Test fun `regression - keeps a short closed last word at its stated length when a break follows`() {
        val lrc =
            "[00:10.00]<00:10.00>a <00:10.05>b <00:10.10>c<00:10.48>\n[00:20.00]<00:20.00>d <00:20.05>e <00:20.10>f<00:20.48>"

        val line = sung(LRCParser.parse(lrc, 30000.0))[0]

        val part = line.parts!![2]
        assertEquals(Triple("c", 10100.0, 380.0), Triple(part.words, part.startTimeMs, part.durationMs))
    }

    // -- invariants --

    private val song = listOf(
        "[ti:Song]",
        "[00:00.00]",
        "[00:14.20]<00:14.20>First <00:14.80>line<00:15.40> ",
        "[00:16.00]<00:16.00>Second <00:16.70>line",
        "[00:18.50]",
        "[00:19.00]<00:19.00>Third<00:19.60> [bg:<00:19.20>(echo)<00:20.10>]",
        "[00:31.00]♪",
        "[00:45.00]<00:45.00>Last <00:45.60>line<00:46.30>",
        "[00:47.00]",
    ).joinToString("\n")

    private fun invariantCases() = listOf(
        "parseLRC" to parseLRC(song, 60000.0),
        "LRCParser.parse" to LRCParser.parse(song, 60000.0),
    )

    @Test fun `invariants - emits no blank sung rows`() {
        for ((label, lyrics) in invariantCases()) {
            assertTrue(sung(lyrics).all { it.words.trim() != "" }, label)
        }
    }

    @Test fun `invariants - keeps lines in time order without breaks overlapping their neighbours`() {
        for ((label, lyrics) in invariantCases()) {
            for (i in 1 until lyrics.size) {
                assertTrue(lyrics[i].startTimeMs >= lyrics[i - 1].startTimeMs, label)
                if (lyrics[i].isInstrumental == true || lyrics[i - 1].isInstrumental == true) {
                    assertTrue(lyrics[i].startTimeMs >= lyrics[i - 1].startTimeMs + lyrics[i - 1].durationMs, label)
                }
            }
        }
    }

    @Test fun `invariants - never gives a negative duration`() {
        for ((label, lyrics) in invariantCases()) {
            for (lyric in lyrics) {
                assertTrue(lyric.durationMs >= 0, label)
                for (part in lyric.parts ?: emptyList()) assertTrue(part.durationMs >= 0, label)
            }
        }
    }

    @Test fun `invariants - covers the song with an intro, the breaks and an outro`() {
        for ((label, lyrics) in invariantCases()) {
            assertEquals(
                listOf(span(0.0, 14200.0), span(20100.0, 24900.0), span(46300.0, 13700.0)),
                instrumentals(lyrics),
                label,
            )
        }
    }

    // -- malformed input --
    // The same contract the other parsers hold to: a file that is not this format, or is this format
    // broken, comes back empty rather than thrown.

    private val unreadable = listOf(
        "an empty string" to "",
        "whitespace only" to "   \n\t  ",
        "text that is not LRC" to "just some prose, nothing timed about it",
        "another lyrics format entirely" to "<tt><body></body></tt>",
        "deeply nested tags" to "<a>".repeat(600) + "x" + "</a>".repeat(600),
        "a time tag that is not numbers" to "[aa:bb.cc]Hello",
        "an empty time tag" to "[]Hello",
        "a time tag that stops halfway" to "[00:10.0",
        "ID tags with empty values" to "[ti:]\n[ar:]",
        "punctuation that only looks like timings" to "::::\n....",
    )

    @Test fun `returns nothing for unreadable input`() {
        for ((label, input) in unreadable) {
            assertEquals(emptyList(), LRCParser.parse(input, 20000.0), label)
            assertEquals(emptyList(), parseLRC(input, 20000.0), label)
        }
    }

    @Test fun `keeps an unclosed word timestamp as text rather than dropping the line`() {
        val result = sung(LRCParser.parse("[00:10.00]<00:10.00>Hello <00:10.50", 20000.0))

        assertEquals(1, result.size)
        assertEquals("Hello <00:10.50", result[0].words)
    }

    @Test fun `ignores an empty offset tag instead of shifting every line by NaN`() {
        val result = sung(LRCParser.parse("[offset:]\n[00:10.00]Hi", 20000.0))

        assertEquals(10000.0, result[0].startTimeMs)
    }

    // -- lrcFixers --

    @Test fun `merges short space durations into previous word`() {
        val lyrics = listOf(
            Lyric(
                startTimeMs = 0.0,
                words = "Hello world",
                durationMs = 1000.0,
                parts = mutableListOf(
                    LyricPart(startTimeMs = 0.0, words = "Hello", durationMs = 100.0),
                    LyricPart(startTimeMs = 100.0, words = " ", durationMs = 80.0),
                    LyricPart(startTimeMs = 180.0, words = "world", durationMs = 200.0),
                ),
            ),
        )

        lrcFixers(lyrics)

        assertEquals(180.0, lyrics[0].parts!![0].durationMs)
        assertEquals(0.0, lyrics[0].parts!![1].durationMs)
    }

    @Test fun `fudges short word durations when most are short`() {
        val lyrics = listOf(
            Lyric(
                startTimeMs = 0.0,
                words = "a b c d e",
                durationMs = 5000.0,
                parts = mutableListOf(
                    LyricPart(startTimeMs = 0.0, words = "a", durationMs = 10.0),
                    LyricPart(startTimeMs = 100.0, words = "b", durationMs = 10.0),
                    LyricPart(startTimeMs = 200.0, words = "c", durationMs = 10.0),
                    LyricPart(startTimeMs = 300.0, words = "d", durationMs = 10.0),
                    LyricPart(startTimeMs = 400.0, words = "e", durationMs = 10.0),
                ),
            ),
        )

        lrcFixers(lyrics)

        // After fudging, durations should be recalculated
        assertTrue(lyrics[0].parts!![0].durationMs > 10)
    }

    // -- Musixmatch paired timestamps --
    // Musixmatch closes every word with a second timestamp, so the whitespace between words lands
    // in its own fragment. Trimming those fragments the way canonical LRC needs would swallow the
    // space and run the words together.

    private val paired = "[00:10.00]<00:10.00>Hello<00:10.40> <00:10.44>world<00:10.90> "

    @Test fun `keeps the space between two paired words`() {
        val result = sung(LRCParser.parse(paired, 20000.0))

        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].words)
        // The closing timestamp ends "world" rather than opening an empty part of its own.
        assertEquals(listOf("Hello", " ", "world"), result[0].parts!!.map { it.words })
    }

    @Test fun `keeps the separating spaces in canonical enhanced LRC too`() {
        val result = sung(LRCParser.parse("[00:10.00]<00:10.00>Hello <00:10.50>world <00:11.00>today", 20000.0))

        assertEquals("Hello world today", result[0].words)
        assertEquals(listOf("Hello ", "world ", "today"), result[0].parts!!.map { it.words })
    }

    // -- parseLRC --
    // The extension applies `lrcFixers` to Musixmatch word-by-word lyrics only, so the raw parse
    // has to be reachable on its own.

    @Test fun `returns the timings the document states, with no fixers applied`() {
        val result = sung(parseLRC(paired, 20000.0))

        assertEquals(400.0, result[0].parts!![0].durationMs)
        assertEquals(10400.0, result[0].parts!![1].startTimeMs)
        assertEquals(40.0, result[0].parts!![1].durationMs)
    }

    @Test fun `differs from LRCParser_parse, which runs the fixers`() {
        val fixed = sung(LRCParser.parse(paired, 20000.0))

        assertEquals(440.0, fixed[0].parts!![0].durationMs)
        assertEquals(10440.0, fixed[0].parts!![1].startTimeMs)
        assertEquals(0.0, fixed[0].parts!![1].durationMs)
    }

    @Test fun `parseLRC applies an offset ID tag`() {
        val result = sung(parseLRC("[offset:0.5]\n[00:10.00]Line with offset", 20000.0))

        assertEquals(9500.0, result[0].startTimeMs)
    }

    @Test fun `ignores an unparseable offset instead of shifting every line to NaN`() {
        val result = sung(parseLRC("[offset:banana]\n[00:10.00]Line", 20000.0))

        assertEquals(10000.0, result[0].startTimeMs)
    }

    // -- LySy background vocals --

    private val lysyLine =
        "[01:09.858]<01:09.858>We <01:10.182>rise <01:12.830>throne<01:13.201> [bg:<01:13.887>(Throne, <01:14.238><01:14.544>throne, <01:14.944><01:15.282>throne)<01:15.679>]"

    @Test fun `flags bg words as background and keeps the source parens`() {
        val parts = sung(parseLRC(lysyLine, 120000.0))[0].parts!!
        val lead = parts.filter { it.isBackground != true }
        val bg = parts.filter { it.isBackground == true }

        assertEquals(listOf("We ", "rise ", "throne"), lead.map { it.words })
        assertTrue(lead.all { it.isBackground == false })

        assertEquals(listOf("(Throne, ", "throne, ", "throne)"), bg.filter { it.words.trim().isNotEmpty() }.map { it.words })
        assertTrue(bg.all { it.isBackground == true })

        for (part in parts) {
            assertFalse(part.words.contains('[') || part.words.contains(']'))
        }
    }

    @Test fun `regression - does not stretch the lead's closed last word to where the background starts`() {
        val throne = sung(parseLRC(lysyLine, 120000.0))[0].parts!!.first { it.words == "throne" }

        assertEquals(371.0, throne.durationMs)
    }

    @Test fun `keeps background text and its parens in line words, without the bg marker`() {
        val parsed = sung(parseLRC(lysyLine, 120000.0))[0]

        assertEquals("We rise throne (Throne, throne, throne)", parsed.words)
        assertFalse(parsed.words.contains('[') || parsed.words.contains(']'))
    }

    @Test fun `carries per-word timing for the background row`() {
        val parts = sung(parseLRC(lysyLine, 120000.0))[0].parts!!
        val bg = parts.filter { it.isBackground == true && it.words.trim().isNotEmpty() }

        assertEquals(
            listOf(
                Triple("(Throne, ", 73887.0, 351.0),
                Triple("throne, ", 74544.0, 400.0),
                Triple("throne)", 75282.0, 397.0),
            ),
            bg.map { Triple(it.words, it.startTimeMs, it.durationMs) },
        )
    }
}

class LRCParserMetadataTest {
    @Test fun `reads the song author from au`() {
        assertEquals(
            LyricMetadata(songwriters = listOf("Freddie Mercury")),
            LRCParser.metadata("[au:Freddie Mercury]\n[00:01.00]Is this the real life"),
        )
    }

    @Test fun `reads the lyricist from lr`() = assertEquals(listOf("方文山"), LRCParser.metadata("[lr:方文山]\n[00:01.00]一").songwriters)

    @Test fun `keeps the order the tags appear in`() =
        assertEquals(listOf("B", "A"), LRCParser.metadata("[lr:B]\n[au:A]\n[00:01.00]x").songwriters)

    @Test fun `reads the lyricist and composer from timed credit lines`() {
        val lrc = "[00:00.00] 作词 : 周杰伦\n[00:00.50] 作曲 : 方文山\n[00:29.36]故事的小黄花"
        assertEquals(listOf("周杰伦", "方文山"), LRCParser.metadata(lrc).songwriters)
    }

    @Test fun `reads English songwriting credit lines`() {
        val lrc = "[00:00.00]Written by: Max Martin/Savan Kotecha\n[00:10.00]Real lyric"
        assertEquals(listOf("Max Martin", "Savan Kotecha"), LRCParser.metadata(lrc).songwriters)
    }

    @Test fun `reads a credit line split into enhanced word timings`() {
        val lrc = "[00:00.00]<00:00.00>Lyrics <00:00.50>by: <00:01.00>Sia <00:01.50>Furler\n[00:10.00]Real lyric"
        assertEquals(listOf("Sia Furler"), LRCParser.metadata(lrc).songwriters)
    }

    @Test fun `combines the credit tags with the credit lines`() {
        val lrc = "[au:A]\n[00:00.00]作词：A、B\n[00:10.00]Real lyric"
        assertEquals(listOf("A", "B"), LRCParser.metadata(lrc).songwriters)
    }

    // -- edge cases --

    @Test fun `keeps every repeated tag rather than only the last`() =
        assertEquals(listOf("A", "B"), LRCParser.metadata("[au:A]\n[au:B]\n[00:01.00]x").songwriters)

    @Test fun `splits a tag that lists several names`() =
        assertEquals(listOf("周杰伦", "方文山"), LRCParser.metadata("[au: 周杰伦 / 方文山 ]\n[00:01.00]x").songwriters)

    @Test fun `names a person once when both tags credit them`() =
        assertEquals(listOf("A"), LRCParser.metadata("[au:A]\n[lr:A]\n[00:01.00]x").songwriters)

    @Test fun `ignores by, which credits the file rather than the song`() =
        assertEquals(emptyList(), LRCParser.metadata("[by:lrc maker]\n[00:01.00]x").songwriters)

    @Test fun `skips credit lines that are not songwriting`() {
        val lrc = "[00:00.00]编曲：钟兴民\n[00:00.50]Produced by: Someone\n[00:01.00]混音：Mixer"
        assertEquals(emptyList(), LRCParser.metadata(lrc).songwriters)
    }

    @Test fun `reads Traditional Chinese songwriting credits`() =
        assertEquals(listOf("周杰倫", "方文山"), LRCParser.metadata("[00:00.00]作詞：周杰倫\n[00:00.50]詞：方文山").songwriters)

    @Test fun `leaves a background vocal out of the names`() =
        assertEquals(listOf("A"), LRCParser.metadata("[00:00.00]作词：A [bg:(B)]").songwriters)

    @Test fun `ignores a credit line without a time tag, which parsing drops too`() =
        assertEquals(emptyList(), LRCParser.metadata("作词：A\n[00:01.00]x").songwriters)

    @Test fun `does not read a singer label or a lyric with a colon as a songwriter`() =
        assertEquals(emptyList(), LRCParser.metadata("[00:01.00]Drake: yeah\n[00:02.00]Listen: I wrote this").songwriters)

    @Test fun `reads nothing from a document without credit tags or from empty input`() {
        assertEquals(emptyList(), LRCParser.metadata("[ti:Title]\n[ar:Artist]\n[00:01.00]x").songwriters)
        assertEquals(emptyList(), LRCParser.metadata("").songwriters)
        assertEquals(emptyList(), LRCParser.metadata("[au:]\n[00:01.00]x").songwriters)
    }

    // -- regressions --

    @Test fun `keeps credit tags out of the parsed lines`() {
        val result = LRCParser.parse("[au:A]\n[lr:B]\n[00:01.00]Only line", 5000.0)
        assertEquals(listOf("Only line"), result.map { it.words })
    }

    @Test fun `keeps timed credit lines out of the parsed lines`() {
        val lrc =
            "[00:00.00] 作词 : 周杰伦\n[00:00.50] 编曲 : 钟兴民\n[00:01.00]<00:01.00>Lyrics <00:01.50>by: <00:02.00>A\n[00:10.00]Only line"
        assertEquals(listOf("Only line"), sung(parseLRC(lrc, 20000.0)).map { it.words })
    }

    @Test fun `keeps a sung line whose words before the colon end in a role noun`() =
        assertEquals(listOf("我听见你的声音：别走"), parseLRC("[00:01.00]我听见你的声音：别走", 5000.0).map { it.words })

    @Test fun `keeps a lead line whose background vocal holds a colon`() =
        assertEquals(1, parseLRC("[00:01.00]hello [bg:(回声: yeah)]", 5000.0).size)

    @Test fun `drops a Traditional Chinese credit block as a whole`() {
        val lrc = "[00:00.00]作詞：周杰倫\n[00:00.50]作曲：周杰倫\n[00:01.00]編曲：鍾興民\n[00:10.00]Only line"
        assertEquals(listOf("Only line"), sung(parseLRC(lrc, 20000.0)).map { it.words })
    }

    @Test fun `keeps a lyric that only happens to hold a colon`() {
        val lrc = "[00:01.00]Drake: yeah\n[00:02.00]Listen: I wrote this"
        assertEquals(listOf("Drake: yeah", "Listen: I wrote this"), parseLRC(lrc, 5000.0).map { it.words })
    }
}
