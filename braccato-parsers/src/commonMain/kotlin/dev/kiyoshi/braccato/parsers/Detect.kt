// Port of packages/parsers/src/detect.ts.
package dev.kiyoshi.braccato.parsers

private val PARSER_FOR_FORMAT: Map<LyricFormat, LyricParser> = mapOf(
    LyricFormat.TTML to TTMLParser,
    LyricFormat.LRC to LRCParser,
    LyricFormat.SRT to SRTParser,
    LyricFormat.QRC to QRCParser,
    LyricFormat.PLAIN to PlainParser,
)

/**
 * The parser for [input]'s format, as [detectFormat] tells it. Never fails: text in no known format is
 * read as plain lyrics, a line per line of text.
 */
public fun detectParser(input: String): LyricParser {
    return PARSER_FOR_FORMAT.getValue(detectFormat(input))
}
