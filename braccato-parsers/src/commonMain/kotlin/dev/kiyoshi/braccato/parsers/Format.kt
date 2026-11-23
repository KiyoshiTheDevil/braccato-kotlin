// Port of packages/parsers/src/format.ts.
package dev.kiyoshi.braccato.parsers

/**
 * The lyrics formats this package reads.
 *
 * @property value The format's name in upstream.
 */
public enum class LyricFormat(public val value: String) {
    TTML("ttml"),
    LRC("lrc"),
    SRT("srt"),
    QRC("qrc"),
    PLAIN("plain"),
}

// Priority matters: a TTML file can contain LRC-looking text, and LRC can sit beside QRC stamps.
/**
 * Which format [input] is in. TTML is tried first, then LRC, SRT and QRC; anything else is [LyricFormat.PLAIN].
 */
public fun detectFormat(input: String): LyricFormat {
    if (isTtml(input)) return LyricFormat.TTML
    if (isLrc(input)) return LyricFormat.LRC
    if (isSrt(input)) return LyricFormat.SRT
    if (isQrc(input)) return LyricFormat.QRC
    return LyricFormat.PLAIN
}
