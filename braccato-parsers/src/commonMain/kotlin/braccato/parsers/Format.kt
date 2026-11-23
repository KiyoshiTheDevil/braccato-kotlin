// Port of packages/parsers/src/format.ts.
package braccato.parsers

public enum class LyricFormat(public val value: String) {
    TTML("ttml"),
    LRC("lrc"),
    SRT("srt"),
    QRC("qrc"),
    PLAIN("plain"),
}

// Priority matters: a TTML file can contain LRC-looking text, and LRC can sit beside QRC stamps.
public fun detectFormat(input: String): LyricFormat {
    if (isTtml(input)) return LyricFormat.TTML
    if (isLrc(input)) return LyricFormat.LRC
    if (isSrt(input)) return LyricFormat.SRT
    if (isQrc(input)) return LyricFormat.QRC
    return LyricFormat.PLAIN
}
