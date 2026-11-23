package braccato.core

import java.text.BreakIterator

// BreakIterator follows the extended grapheme cluster rules since JDK 20, emoji sequences included,
// which is what `Intl.Segmenter` follows. Android's is backed by ICU and does the same.
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
