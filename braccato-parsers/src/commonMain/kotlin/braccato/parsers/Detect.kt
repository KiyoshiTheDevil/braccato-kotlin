// Port of packages/parsers/src/detect.ts.
package braccato.parsers

private val PARSER_FOR_FORMAT: Map<LyricFormat, LyricParser> = mapOf(
    LyricFormat.TTML to TTMLParser,
    LyricFormat.LRC to LRCParser,
    LyricFormat.SRT to SRTParser,
    LyricFormat.QRC to QRCParser,
    LyricFormat.PLAIN to PlainParser,
)

fun detectParser(input: String): LyricParser {
    return PARSER_FOR_FORMAT.getValue(detectFormat(input))
}
