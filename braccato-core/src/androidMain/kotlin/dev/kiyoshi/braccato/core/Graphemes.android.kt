package dev.kiyoshi.braccato.core

import android.icu.text.BreakIterator

// ICU's character instance follows the extended grapheme cluster rules, emoji sequences included,
// which is what `Intl.Segmenter` follows.
internal actual fun splitGraphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val out = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return out
}
