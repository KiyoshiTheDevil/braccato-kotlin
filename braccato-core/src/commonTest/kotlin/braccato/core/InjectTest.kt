// Port of packages/core/src/wordState.selfcheck.ts, and of the checks in inject.selfcheck.ts that are
// about what a line is built from rather than which elements it is built into.
package braccato.core

import braccato.types.Lyric
import braccato.types.LyricPart
import braccato.types.LyricSyncType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordStateTest {
    private fun states(parts: List<LyricPart>, lineDurationMs: Double, time: Double): List<WordState> {
        val content = createLyricsLine(parts)
        return wordStatesAt(content.words, parts[0].startTimeMs / 1000, lineDurationMs / 1000, time)
    }

    @Test fun `a rich line moves each word from upcoming through active to past`() {
        val rich = listOf(
            LyricPart(startTimeMs = 0.0, words = "Hello ", durationMs = 400.0),
            LyricPart(startTimeMs = 400.0, words = "world", durationMs = 600.0),
        )
        assertEquals(listOf(WordState.UPCOMING, WordState.UPCOMING), states(rich, 1000.0, -0.1), "freshly built words are upcoming")
        assertEquals(listOf(WordState.ACTIVE, WordState.UPCOMING), states(rich, 1000.0, 0.2))
        assertEquals(listOf(WordState.PAST, WordState.ACTIVE), states(rich, 1000.0, 0.5))
        assertEquals(listOf(WordState.PAST, WordState.PAST), states(rich, 1000.0, 1.2))
    }

    @Test fun `a zero duration word lasts until the next one starts, or the line ends`() {
        val lineSynced = listOf(
            LyricPart(startTimeMs = 0.0, words = "One ", durationMs = 0.0),
            LyricPart(startTimeMs = 100.0, words = "Two ", durationMs = 0.0),
            LyricPart(startTimeMs = 200.0, words = "Three", durationMs = 0.0),
        )
        assertEquals(WordState.ACTIVE, states(lineSynced, 1000.0, 0.05)[0])
        assertEquals(WordState.UPCOMING, states(lineSynced, 1000.0, 0.05)[1])
        assertEquals(WordState.PAST, states(lineSynced, 1000.0, 0.15)[0])
        assertEquals(WordState.ACTIVE, states(lineSynced, 1000.0, 0.15)[1])
        assertEquals(WordState.ACTIVE, states(lineSynced, 1000.0, 0.5)[2])
        assertEquals(WordState.PAST, states(lineSynced, 1000.0, 1.5)[2])
    }
}

class LineBuildTest {
    @AfterTest fun resetTheme() {
        setThemeSettings(emptyMap())
    }

    private val parts = listOf(
        LyricPart(startTimeMs = 0.0, words = "Hello ", durationMs = 400.0),
        LyricPart(startTimeMs = 400.0, words = "indistinguishable ", durationMs = 900.0),
        LyricPart(startTimeMs = 1300.0, words = "world", durationMs = 300.0, isBackground = true),
    )

    @Test fun `builds every word once, the background ones on a line of their own`() {
        // Letter wave and the wrap-break path are mutually exclusive, so this opts out to see the wrapping.
        setThemeSettings(mapOf("blyrics-letter-wave" to "false"))
        val content = createLyricsLine(parts)

        assertEquals(listOf("Hello", "indistinguishable", "world"), content.words.map { it.text })
        assertEquals("Hello indistinguishable ", content.main.text)
        assertEquals("world", content.background?.text)
        assertEquals(listOf("Hello", " ", "indistinguishable"), content.main.items.map { (it as? LineItem.Group)?.text ?: (it as LineItem.Space).text })
        assertEquals(listOf("indistingu", "ishable"), content.words[1].wrapChunks, "a word past the wrap threshold has its break points")
        assertNull(content.words[0].wrapChunks, "a short word has none")
        assertTrue(content.words.all { it.letters == null }, "letter wave off leaves words unlettered")
    }

    @Test fun `splits a part into words with its time pro-rated by characters`() {
        val tokens = splitPartIntoTokens(LyricPart(startTimeMs = 1000.0, words = "ab cdef", durationMs = 600.0))
        assertEquals(
            listOf<RenderToken>(
                RenderToken.Part("ab", LyricPart(startTimeMs = 1000.0, words = "ab", durationMs = 200.0)),
                RenderToken.Space(" "),
                RenderToken.Part("cdef", LyricPart(startTimeMs = 1200.0, words = "cdef", durationMs = 400.0)),
            ),
            tokens,
        )
    }

    @Test fun `groups syllables that run together into one word, which wobbles once`() {
        val content = createLyricsLine(
            listOf(
                LyricPart(startTimeMs = 0.0, words = "Won", durationMs = 200.0),
                LyricPart(startTimeMs = 200.0, words = "der ", durationMs = 200.0),
                LyricPart(startTimeMs = 400.0, words = "wall", durationMs = 300.0),
            ),
        )
        val groups = content.main.items.filterIsInstance<LineItem.Group>()
        assertEquals(listOf("Wonder", "wall"), groups.map { it.text })
        assertEquals(listOf(true, false), groups[0].words.map { it.wobbles })
    }

    @Test fun `letter wave splits a word into graphemes, but never a joining script`() {
        fun lettersOf(words: String) = createLyricsLine(listOf(LyricPart(startTimeMs = 0.0, words = words, durationMs = 400.0))).words[0].letters

        assertEquals(listOf("H", "i"), lettersOf("Hi"), "letter wave is on by default")
        assertEquals(listOf("मैं"), lettersOf("मैं"), "a Devanagari consonant and its matras stay one grapheme")
        assertEquals(listOf("আ", "মি"), lettersOf("আমি"), "each Bengali consonant keeps its vowel sign")
        assertEquals(listOf("👨‍👩‍👧"), lettersOf("👨‍👩‍👧"), "a ZWJ emoji sequence stays one grapheme")
        assertNull(lettersOf("بعدك"), "Arabic stays unlettered so its letters keep their joined forms")
        assertNull(lettersOf("ܫܠܡܐ"), "so does Syriac")
        assertNull(lettersOf("ـــ"), "so does a syllable of only tatweel")
        assertEquals(listOf("ש", "ל", "ו", "ם"), lettersOf("שלום"), "Hebrew letters do not join, so they split")
    }

    @Test fun `rebuilds an untimed line as words a fixed delay apart`() {
        val parts = buildLineSyncedParts(Lyric(startTimeMs = 1000.0, words = "one two  three", durationMs = 3000.0))
        assertEquals(listOf("one", " ", "two", "  ", "three"), parts.map { it.words })
        assertEquals(listOf(1000.0, 1050.0, 1050.0, 1100.0, 1100.0), parts.map { it.startTimeMs })
        assertTrue(parts.all { it.durationMs == 0.0 })
    }

    @Test fun `derives how the lyrics are timed`() {
        val timed = Lyric(startTimeMs = 1000.0, words = "a", durationMs = 500.0, parts = mutableListOf(LyricPart(1000.0, "a", 500.0)))
        val untimedParts = Lyric(startTimeMs = 1000.0, words = "a", durationMs = 500.0, parts = mutableListOf(LyricPart(1000.0, "a", 0.0)))
        val zero = Lyric(startTimeMs = 0.0, words = "a", durationMs = 0.0)

        assertEquals(LyricSyncType.RICHSYNC, deriveSyncType(listOf(timed)))
        assertEquals(LyricSyncType.SYNCED, deriveSyncType(listOf(untimedParts)))
        assertEquals(LyricSyncType.NONE, deriveSyncType(listOf(zero, zero)))

        setThemeSettings(mapOf("blyrics-disable-richsync" to "true"))
        assertEquals(LyricSyncType.SYNCED, deriveSyncType(listOf(timed)), "a theme can turn rich sync off")
    }

    @Test fun `builds a song, instrumentals taking their side from their neighbours`() {
        val built = buildLyrics(
            listOf(
                Lyric(startTimeMs = 0.0, words = "", durationMs = 5000.0, parts = mutableListOf(), isInstrumental = true),
                Lyric(startTimeMs = 5000.0, words = "שלום", durationMs = 2000.0, agent = "v2"),
                Lyric(startTimeMs = 7000.0, words = " ", durationMs = 1000.0),
            ),
            SetLyricsOptions(songwriters = listOf("A", "B", "C")),
        )

        val (intro, sung, blank) = built.lines
        assertEquals("v2", intro.agent)
        assertEquals(TextDirection.RTL, intro.direction)
        assertNull(intro.content)
        assertEquals(5.0, sung.time)
        assertEquals(TextDirection.RTL, sung.direction)
        assertTrue(blank.isBlank)
        assertEquals("A, B & C", built.credits)
        assertEquals(LyricSyncType.SYNCED, built.syncType)
    }

    @Test fun `leaves off a translation or romanization that repeats the line`() {
        assertNull(buildTranslation("Hello, world", "hello world"))
        assertNotNull(buildTranslation("Hallo Welt", "Hello world", "en"))

        val timed = buildRomanization("こんにちは", "ja", "konnichiwa", listOf(LyricPart(0.0, "konnichi", 2000.0), LyricPart(2000.0, "wa", 3000.0)))
        assertNotNull(timed)
        assertEquals("ja-Latn", timed.language)
        assertEquals(listOf("konnichiwa"), timed.content?.main?.items?.filterIsInstance<LineItem.Group>()?.map { it.text })
    }
}
