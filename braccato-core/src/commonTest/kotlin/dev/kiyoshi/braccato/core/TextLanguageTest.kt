// Port of the language inference checks in packages/core/src/language.selfcheck.ts, the wave path
// check in instrumental.selfcheck.ts, and checks for text.ts, which upstream does not test on its own.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.Lyric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun lyrics(vararg words: String) = words.map { Lyric(startTimeMs = 0.0, words = it, durationMs = 0.0) }

class LanguageTest {
    private val han = "刃直海角骨入"

    @Test fun `resolves each line's language`() {
        assertEquals(listOf("ja-JP"), resolveLyricLanguages(lyrics(han), "ja-JP"))
        assertEquals(listOf("ko-KR"), resolveLyricLanguages(lyrics(han), "ko-KR"))
        assertEquals(listOf("zh-TW"), resolveLyricLanguages(lyrics(han), "zh_TW"))
        assertEquals(listOf("zh-Hans-CN"), resolveLyricLanguages(lyrics(han), "zh-Hans-CN"))
        assertEquals(listOf("yue-Hant-HK"), resolveLyricLanguages(lyrics(han), "yue-Hant-HK"))
        assertEquals(listOf("ja", "ja"), resolveLyricLanguages(lyrics("海の声", han)))
        assertEquals(listOf("ja", "ja"), resolveLyricLanguages(lyrics("海の声", han), "en"))
        assertEquals(listOf("ko", "ko"), resolveLyricLanguages(lyrics("한글", han)))
        assertEquals(listOf("ja", "ja"), resolveLyricLanguages(lyrics("ｶﾀｶﾅ", han)))
        assertEquals(listOf("ja", "ko", "ja"), resolveLyricLanguages(lyrics("海の声", "한글", han), "ja"))
        assertEquals(listOf("ja", "ko", ""), resolveLyricLanguages(lyrics("海の声", "한글", han)))
        assertEquals(listOf("en-US"), resolveLyricLanguages(lyrics("hello"), "en_US"))
        assertEquals(listOf("fr"), resolveLyricLanguages(lyrics("bonjour"), "fr"))
        assertEquals(
            listOf("", ""),
            resolveLyricLanguages(
                listOf(
                    Lyric(startTimeMs = 0.0, words = "間奏", durationMs = 0.0, isInstrumental = true),
                    Lyric(startTimeMs = 0.0, words = han, durationMs = 0.0),
                ),
            ),
        )
        for (language in listOf(null, "", "und", "auto", "not a tag", "en")) {
            assertEquals(listOf(""), resolveLyricLanguages(lyrics(han), language), "Han alone must not be guessed as Chinese ($language)")
        }
    }

    @Test fun `canonicalises language tags the way Intl does`() {
        assertEquals("en-US", normalizeLanguage("EN_us"))
        assertEquals("zh-Hant-TW", normalizeLanguage("zh-hant-tw"))
        assertEquals("es-419", normalizeLanguage("es-419"))
        assertEquals("", normalizeLanguage("not a tag"))
        assertEquals("ja-Latn", romanizationLanguage("ja-JP"))
        assertEquals("und-Latn", romanizationLanguage(""))
    }
}

class TextTest {
    @Test fun `detects right-to-left and joining scripts`() {
        assertTrue(testRtl("שלום"))
        assertTrue(testRtl("بعدك"))
        assertFalse(testRtl("hello"))
        assertTrue(testJoiningScript("بعدك"))
        assertTrue(testJoiningScript("ܫܠܡܐ"))
        assertTrue(testJoiningScript("ـــ"), "the tatweel is shared between scripts and still joins")
        assertFalse(testJoiningScript("שלום"), "Hebrew letters do not join")
    }

    @Test fun `tells Latin from other scripts`() {
        assertFalse(containsNonLatin("Café “quoted” – 123"))
        assertTrue(containsNonLatin("Привет"))
        assertEquals("ja", detectNonLatinLanguage("カタカナ"))
        assertEquals("ru", detectNonLatinLanguage("Привет"))
        assertNull(detectNonLatinLanguage("hello"))
    }

    @Test fun `compares text without case or punctuation`() {
        assertTrue(isSameText("Hello, world!", "hello world"))
        assertFalse(isSameText("Hello", "Bonjour"))
    }
}

class InstrumentalTest {
    private fun pathSignature(path: String): String {
        val segments = Regex("[A-Za-z][^A-Za-z]*").findAll(path).map { it.value }.toList()
        assertTrue(segments.isNotEmpty(), "the path \"$path\" holds at least one command")
        return segments.joinToString(" ") { segment ->
            val argumentCount = Regex("""-?\d*\.?\d+""").findAll(segment.substring(1)).count()
            "${segment[0]}$argumentCount"
        }
    }

    @Test fun `the two wave shapes morph into each other`() {
        assertEquals("M2 Q4 Z0", pathSignature("M -4 3 Q 1 2 5 3 Z"), "each command carries the count of numbers that followed it")
        assertEquals(
            pathSignature(INSTRUMENTAL_WAVE_PATH_HIGH),
            pathSignature(INSTRUMENTAL_WAVE_PATH_LOW),
            "the ripple's two shapes interpolate smoothly rather than snapping at the halfway point",
        )
    }
}
