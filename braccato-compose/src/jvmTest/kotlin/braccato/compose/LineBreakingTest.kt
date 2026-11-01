package braccato.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineBreakingTest {
    @Test fun `CJK syllables may break between them`() {
        assertTrue(canBreakBetween("何", "で"))
        assertTrue(canBreakBetween("叶え", "て"))
        assertTrue(canBreakBetween("ねぇ、", "知"), "after a clause mark")
        assertTrue(canBreakBetween("한", "글"))
    }

    @Test fun `but not before what may not start a line`() {
        assertFalse(canBreakBetween("知", "っ"), "a small kana")
        assertFalse(canBreakBetween("ジ", "ー"), "the long vowel mark")
        assertFalse(canBreakBetween("ねぇ", "、"), "a clause mark")
        assertFalse(canBreakBetween("る", "」"), "a closing quote")
        assertFalse(canBreakBetween("な", "ゃ"))
    }

    @Test fun `nor after what may not end one`() {
        assertFalse(canBreakBetween("「", "何"))
        assertFalse(canBreakBetween("（", "笑"))
    }

    @Test fun `Latin syllables run together stay together`() {
        assertFalse(canBreakBetween("Won", "der"))
        assertFalse(canBreakBetween("in", "di"))
    }
}
