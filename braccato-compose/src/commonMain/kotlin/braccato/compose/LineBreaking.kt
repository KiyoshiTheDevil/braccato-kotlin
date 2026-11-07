// Where a run of syllables with no space between them may wrap. Upstream leaves this to the browser:
// a long word group (`.blyrics-word-group-long`) is inline with `overflow-wrap: anywhere`, so the
// browser breaks it by the Unicode line breaking rules, which allow a break between two CJK
// characters but not before closing punctuation, the long vowel mark or a small kana, and not after
// opening punctuation. These are those rules for the syllable boundaries a group has.
package braccato.compose

import braccato.core.BuiltWord
import braccato.core.LineItem

private val BREAKABLE_SCRIPT = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\u3000-\\u303F\\uFF00-\\uFFEF]")

// Closing brackets and quotes, sentence and clause marks, the iteration and long vowel marks, and the
// small kana: a line may not start with one.
private const val NO_BREAK_BEFORE =
    ")]}〕〉》」』】〙〗〟｠»" +
        "、。，．・：；？！‼⁇⁈⁉，．,.:;?!" +
        "ヽヾーァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ々〻" +
        "‐゠–〜～…‥" +
        "）］｝」』"

// Opening brackets and quotes: a line may not end with one.
private const val NO_BREAK_AFTER = "([{〔〈《「『【〘〖〝｟«（［｛"

private fun lastCodePoint(text: String): String {
    if (text.isEmpty()) return ""
    val end = text.length
    val start = if (end >= 2 && text[end - 1].isLowSurrogate() && text[end - 2].isHighSurrogate()) end - 2 else end - 1
    return text.substring(start)
}

private fun firstCodePoint(text: String): String {
    if (text.isEmpty()) return ""
    return if (text.length >= 2 && text[0].isHighSurrogate() && text[1].isLowSurrogate()) text.substring(0, 2) else text.substring(0, 1)
}

/**
 * Whether a line may break between two syllables of one word group: only where either side is CJK,
 * and never where the next one must not start a line or the previous one must not end it. Latin
 * syllables run together ("Won" and "der") never break here.
 */
internal fun canBreakBetween(before: String, after: String): Boolean {
    val last = lastCodePoint(before.trimEnd())
    val first = firstCodePoint(after.trimStart())
    if (last.isEmpty() || first.isEmpty()) return true
    if (NO_BREAK_BEFORE.contains(first)) return false
    if (NO_BREAK_AFTER.contains(last)) return false
    return BREAKABLE_SCRIPT.containsMatchIn(last) || BREAKABLE_SCRIPT.containsMatchIn(first)
}

/**
 * A word with CJK in it, cut where a line may break. The layout breaks only between words, and a line
 * synced by line holds its whole Japanese line as one word, without a space: wider than the view, it
 * ran out over the edge (Kodama, 2026-11-07). Each piece keeps the word's place in time; a word timed
 * on its own gets its time shared out by length, so the sweep runs on across the pieces as it did.
 */
internal fun splitForBreaking(w: BuiltWord): List<BuiltWord> {
    if (w.text.length < 2) return listOf(w)
    if (!BREAKABLE_SCRIPT.containsMatchIn(w.text)) return splitLong(w)
    // A syllable or two of CJK fits any line, and its letter wave is planned for it whole.
    if (w.text.length <= 4) return listOf(w)
    val pieces = mutableListOf(StringBuilder())
    var i = 0
    while (i < w.text.length) {
        val n = if (w.text[i].isHighSurrogate() && i + 1 < w.text.length && w.text[i + 1].isLowSurrogate()) 2 else 1
        val cp = w.text.substring(i, i + n)
        val last = pieces.last()
        if (last.isNotEmpty() && canBreakBetween(last.toString(), cp)) pieces += StringBuilder(cp) else last.append(cp)
        i += n
    }
    return share(w, pieces.map { it.toString() })
}

/**
 * A long word in a script with spaces, without one in it ("jaba-daba-…-you", The Butcher Sisters on
 * LRCLIB): after each hyphen, and what is still longer than the wrap threshold in its chunks, which
 * core works out for such words ([BuiltWord.wrapChunks]) and nothing here used. The layout then
 * breaks between the pieces where the line is full; whole, the word ran out over the edge.
 */
private fun splitLong(w: BuiltWord): List<BuiltWord> {
    // Lettered words (the letter wave, on by default) have no chunks of their own: the same threshold.
    val chunk = w.wrapChunks?.firstOrNull()?.length
        ?: braccato.core.CoreSettings.longWordWrapThreshold.getNumberValue().toInt().coerceAtLeast(1).takeIf { w.text.length > it }
        ?: return listOf(w)
    val byHyphen = Regex("(?<=-)").split(w.text).filter { it.isNotEmpty() }
    return share(w, byHyphen.flatMap { if (it.length > chunk) it.chunked(chunk) else listOf(it) })
}

/** [w] as the given pieces of its text, each with its share of the time. */
private fun share(w: BuiltWord, texts: List<String>): List<BuiltWord> {
    if (texts.size <= 1) return listOf(w)
    val total = w.text.length.toDouble()
    var at = 0
    // A lettered word's letters go with its pieces, each piece taking letters until it is spelt out.
    var letter = 0
    fun lettersOf(text: String): List<String>? {
        val all = w.letters ?: return null
        val mine = mutableListOf<String>()
        var spelt = 0
        while (spelt < text.length && letter < all.size) { mine += all[letter]; spelt += all[letter].length; letter++ }
        return mine
    }
    return texts.mapIndexed { k, text ->
        val start = at
        at += text.length
        BuiltWord(
            text = text,
            time = if (w.isLineSynced) w.time else w.time + w.duration * start / total,
            duration = if (w.isLineSynced) w.duration else w.duration * text.length / total,
            isBackground = w.isBackground, explicit = w.explicit, isLineSynced = w.isLineSynced, isRtl = w.isRtl,
            isLongWord = w.isLongWord, letters = lettersOf(text), wrapChunks = null, wobbles = w.wobbles && k == 0,
        )
    }
}

internal fun LineItem.Group.splitForBreaking(): LineItem.Group =
    if (words.none { BREAKABLE_SCRIPT.containsMatchIn(it.text) || it.wrapChunks != null || it.letters != null }) this
    else LineItem.Group(text, isBackground, isLong, words.flatMap(::splitForBreaking))
