// Port of packages/parsers/src/credits.ts.
package dev.kiyoshi.braccato.parsers

import dev.kiyoshi.braccato.types.internal.*

private val FREE_TEXT_SEPARATORS = Regex("[/、，,]")
private val CREDIT_LINE_REGEX = Regex("^([^:：]+)[:：]$WS*(.+)\\z")

// The credits that name who wrote the song. `编曲` (arrangement) ends in `曲` too and is not one.
private val SONGWRITER_ROLES = listOf(
    "词",
    "詞",
    "作词",
    "作詞",
    "曲",
    "作曲",
    "词曲",
    "詞曲",
    "作词作曲",
    "作詞作曲",
    "writtenby",
    "lyricsby",
    "composedby",
    "lyricist",
    "composer",
)

private val CREDIT_ROLES = SONGWRITER_ROLES + listOf(
    "编曲",
    "編曲",
    "和声",
    "和聲",
    "混音",
    "吉他",
    "制作人",
    "製作人",
    "演唱",
    "原唱",
    "翻唱",
    "后期",
    "後期",
    "和音",
    "录音",
    "錄音",
    "策划",
    "策劃",
    "伴奏",
    "美工",
    "海报",
    "海報",
    "旁白",
    "producedby",
    "arrangedby",
    "mixing",
    "mastering",
    "vocal",
    "vocals",
    "guitar",
    "bass",
    "drums",
    "producer",
    "arranger",
)

// An unlisted CJK role still counts when it ends in a role noun, but only at role length, or a sung
// clause such as `我听见你的声音` would read as one.
private val ROLE_NOUN_SUFFIXES = listOf("词", "詞", "曲", "声", "聲", "音")
private const val ROLE_NOUN_MAX_LENGTH = 4
private val ROLE_SEPARATORS = Regex("[/&、,，・·]")

private val WHITESPACE_RUN = Regex("$WS+")
private val LATIN_RUN = Regex("[a-z]+")

private fun normalizeRole(role: String): String {
    return role.lowercase().replace(WHITESPACE_RUN, "")
}

private fun isRoleNoun(part: String): Boolean {
    if (part in CREDIT_ROLES) return true
    return part.length <= ROLE_NOUN_MAX_LENGTH && ROLE_NOUN_SUFFIXES.any { noun -> part.endsWith(noun) }
}

// QQ Music joins roles (`作曲/编曲`) and tags them in Latin (`Rap作词`), so a CJK role is read part by part.
private fun cjkRoleParts(normalized: String): List<String> {
    return normalized
        .replace(LATIN_RUN, "")
        .split(ROLE_SEPARATORS)
        .filter { it.isNotEmpty() }
}

/** Whether the text before a colon names a credit role, such as `作词` or `Produced by`, rather than a singer. */
internal fun isCreditRole(role: String): Boolean {
    val n = normalizeRole(role)
    if (n in CREDIT_ROLES) return true
    val parts = cjkRoleParts(n)
    return parts.isNotEmpty() && parts.all(::isRoleNoun)
}

private fun isSongwriterRole(role: String): Boolean {
    val n = normalizeRole(role)
    if (n in SONGWRITER_ROLES) return true
    return isCreditRole(role) && cjkRoleParts(n).any { part -> part in SONGWRITER_ROLES }
}

/** Whether a lyric line's text is a credit, such as `作词：周杰伦`, rather than a sung line. */
internal fun isCreditLine(text: String): Boolean {
    val credit = CREDIT_LINE_REGEX.find(text.jsTrim())
    return credit != null && isCreditRole(credit.groupValues[1])
}

/** The names a songwriting credit line lists, or none when the line credits anything else. */
internal fun songwritersInCreditLine(text: String): List<String> {
    val credit = CREDIT_LINE_REGEX.find(text.jsTrim())
    if (credit == null || !isSongwriterRole(credit.groupValues[1])) return emptyList()
    return splitCreditNames(credit.groupValues[2])
}

internal fun splitCreditNames(value: String): List<String> {
    return value
        .split(FREE_TEXT_SEPARATORS)
        .map { name -> name.jsTrim() }
        .filter { it.isNotEmpty() }
}

/** Trims, drops empty names and keeps the first of each exact duplicate, in order. */
internal fun uniqueNames(names: Iterable<String>): List<String> {
    val seen = LinkedHashSet<String>()
    for (name in names) {
        val trimmed = name.jsTrim()
        if (trimmed.isNotEmpty()) seen += trimmed
    }
    return seen.toList()
}
