// Port of packages/parsers/src/formatPredicates.ts.
package dev.kiyoshi.braccato.parsers

private val LRC_STAMP_REGEX = Regex("""\[\d+:\d+\.\d+\]""")

// Anchoring the cue number at the start of its digit run keeps a long run of digits linear.
private val SRT_CUE_REGEX = Regex("""(?<!\d)\d+\r?\n\d{2}:\d{2}:\d{2}[,.]\d+ --> \d{2}:\d{2}:\d{2}[,.]\d+""")

private val QRC_LINE_STAMP_REGEX = Regex("""\[\d+,\d+\]""")
private val QRC_WORD_STAMP_REGEX = Regex("""\(\d+,\d+\)""")

/** Whether [input] looks like TTML: it has a `<tt>` element. */
public fun isTtml(input: String): Boolean {
    return input.contains("<tt") && input.contains("</tt>")
}

/** Whether [input] looks like LRC: it has a `[mm:ss.xx]` time stamp. */
public fun isLrc(input: String): Boolean {
    return LRC_STAMP_REGEX.containsMatchIn(input)
}

/** Whether [input] looks like SRT: it has a numbered cue with a `-->` time range. */
public fun isSrt(input: String): Boolean {
    return SRT_CUE_REGEX.containsMatchIn(input)
}

/** Whether [input] looks like QRC: QQ Music's `<QrcInfos>` envelope, or `[start,duration]` line and `(start,duration)` word stamps. */
public fun isQrc(input: String): Boolean {
    if (input.contains("<QrcInfos>") || input.contains("LyricContent=")) return true
    return QRC_LINE_STAMP_REGEX.containsMatchIn(input) && QRC_WORD_STAMP_REGEX.containsMatchIn(input)
}
