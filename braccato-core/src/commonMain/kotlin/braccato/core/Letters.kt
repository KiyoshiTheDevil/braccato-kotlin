// Port of packages/core/src/letters.ts.
//
// A word's letters as a reader sees them: grapheme clusters, joined further where a virama or a spacing
// vowel sign ties two of them into one conjunct, which neither a wrap nor the letter wave may tear apart.
package braccato.core

// Canonical_Combining_Class=Virama minus the ones that stay visible; Sinhala al-lakuna joins only beside a ZWJ.
private val CONJUNCT_VIRAMA_REGEX = Regex(
    "(?:[\\x{94D}\\x{9CD}\\x{A4D}\\x{ACD}\\x{B4D}\\x{C4D}\\x{CCD}\\x{D4D}\\x{1039}\\x{17D2}\\x{1A60}\\x{1B44}\\x{1BAB}\\x{2D7F}" +
        "\\x{A9C0}\\x{AAF6}\\x{10A3F}\\x{11046}\\x{1107F}\\x{110B9}\\x{11133}\\x{111C0}\\x{11235}\\x{1134D}\\x{113D0}\\x{11442}" +
        "\\x{114C2}\\x{115BF}\\x{1163F}\\x{116B6}\\x{11839}\\x{1193E}\\x{119E0}\\x{11A47}\\x{11A99}\\x{11C3F}\\x{11D45}\\x{11D97}" +
        "\\x{11F42}]\\x{200D}?|\\x{200D}\\x{0DCA}|\\x{0DCA}\\x{200D})\\z",
)
private val LETTER_START_REGEX = Regex("^\\p{L}")
private val MARK_START_REGEX = Regex("^\\p{M}")

private fun joinsTamilLigature(previous: String, next: String): Boolean =
    (previous.endsWith("க்") && next.startsWith("ஷ")) || (previous.endsWith("ஸ்") && next.startsWith("ரீ"))

/** The letters of [text]: its grapheme clusters, with conjuncts kept whole. */
internal fun letterClusters(text: String): List<String> = joinLetterSegments(splitGraphemes(text))

internal fun joinLetterSegments(segments: Iterable<String>): List<String> {
    val clusters = mutableListOf<String>()
    var previous = ""
    for (segment in segments) {
        val joins = clusters.isNotEmpty() &&
            (MARK_START_REGEX.containsMatchIn(segment) ||
                (LETTER_START_REGEX.containsMatchIn(segment) &&
                    (CONJUNCT_VIRAMA_REGEX.containsMatchIn(previous) || joinsTamilLigature(previous, segment))))
        if (joins) clusters[clusters.lastIndex] += segment else clusters += segment
        previous = segment
    }
    return clusters
}
