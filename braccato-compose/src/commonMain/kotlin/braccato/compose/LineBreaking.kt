// Where a run of syllables with no space between them may wrap. Upstream leaves this to the browser:
// a long word group (`.blyrics-word-group-long`) is inline with `overflow-wrap: anywhere`, so the
// browser breaks it by the Unicode line breaking rules, which allow a break between two CJK
// characters but not before closing punctuation, the long vowel mark or a small kana, and not after
// opening punctuation. These are those rules for the syllable boundaries a group has.
package braccato.compose

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
