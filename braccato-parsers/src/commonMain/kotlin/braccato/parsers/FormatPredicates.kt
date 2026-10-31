// Port of packages/parsers/src/formatPredicates.ts.
package braccato.parsers

private val LRC_STAMP_REGEX = Regex("""\[\d+:\d+\.\d+\]""")

// Anchoring the cue number at the start of its digit run keeps a long run of digits linear.
private val SRT_CUE_REGEX = Regex("""(?<!\d)\d+\r?\n\d{2}:\d{2}:\d{2}[,.]\d+ --> \d{2}:\d{2}:\d{2}[,.]\d+""")

private val QRC_LINE_STAMP_REGEX = Regex("""\[\d+,\d+\]""")
private val QRC_WORD_STAMP_REGEX = Regex("""\(\d+,\d+\)""")

fun isTtml(input: String): Boolean {
    return input.contains("<tt") && input.contains("</tt>")
}

fun isLrc(input: String): Boolean {
    return LRC_STAMP_REGEX.containsMatchIn(input)
}

fun isSrt(input: String): Boolean {
    return SRT_CUE_REGEX.containsMatchIn(input)
}

fun isQrc(input: String): Boolean {
    if (input.contains("<QrcInfos>") || input.contains("LyricContent=")) return true
    return QRC_LINE_STAMP_REGEX.containsMatchIn(input) && QRC_WORD_STAMP_REGEX.containsMatchIn(input)
}
