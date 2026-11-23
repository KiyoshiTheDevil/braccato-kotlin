package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.LyricPart
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Ported from upstream's inject.selfcheck.ts (1.21.3): letters as a reader sees them.
class LettersTest {
    @AfterTest fun reset() = setThemeSettings(emptyMap()).let { }

    private fun lettersOf(text: String): List<String>? {
        setThemeSettings(mapOf("blyrics-letter-wave" to "true"))
        return createLyricsLine(listOf(LyricPart(startTimeMs = 0.0, words = text, durationMs = 1000.0))).words[0].letters
    }

    @Test fun `a Kannada conjunct is one letter, the virama never ends one`() = assertEquals(listOf("ಕ್ಷ", "ಣ"), lettersOf("ಕ್ಷಣ"))

    @Test fun `a visible Tamil pulli keeps its own letter`() = assertEquals(listOf("வா", "ழ்", "க்", "கை"), lettersOf("வாழ்க்கை"))

    @Test fun `the Tamil ksha ligature stays one letter`() = assertEquals(listOf("க்ஷே", "த்", "தி", "ர", "ம்"), lettersOf("க்ஷேத்திரம்"))

    @Test fun `a Myanmar spacing vowel stays with its consonant`() = assertEquals(listOf("မြ", "န်", "မာ"), lettersOf("မြန်မာ"))

    @Test fun `a Sinhala al-lakuna without ZWJ keeps its own letter`() = assertEquals(listOf("අ", "ම්", "මා"), lettersOf("අම්මා"))

    @Test fun `a Sinhala ZWJ conjunct stays one letter`() = assertEquals(listOf("ශ්‍රී"), lettersOf("ශ්‍රී"))

    @Test fun `a Sinhala touching letter pair stays one letter`() = assertEquals(listOf("ක‍්ව"), lettersOf("ක‍්ව"))

    @Test fun `a Devanagari word past the wrap threshold breaks only between letters`() {
        setThemeSettings(mapOf("blyrics-letter-wave" to "false", "blyrics-long-word-wrap-threshold" to "2"))
        val word = createLyricsLine(listOf(LyricPart(startTimeMs = 0.0, words = "मुस्कुराएगा", durationMs = 1000.0))).words[0]
        assertEquals(listOf("मुस्कु", "राए", "गा"), word.wrapChunks)
    }

    @Test fun `a conjunct chain from an ICU without conjunct rules stays one letter`() =
        assertEquals(listOf("स्त्रि", "याँ"), joinLetterSegments(listOf("स्", "त्", "रि", "याँ")))
}
