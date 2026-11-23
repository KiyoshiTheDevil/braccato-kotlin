// Port of packages/core/src/language.ts, the part that is not the DOM. `applyLyricLanguage` writes
// `lang` attributes; a Compose renderer reads the language off the built line instead.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.Lyric
import dev.kiyoshi.braccato.types.internal.jsTrim

// A well formed BCP 47 language tag, which is what `Intl.getCanonicalLocales` accepts: language,
// then optional script, region, variants, extensions and a private use part.
private val LANGUAGE_TAG = Regex(
    "^([a-z]{2,3}|[a-z]{5,8})" +
        "(-[a-z]{4})?" +
        "(-([a-z]{2}|[0-9]{3}))?" +
        "(-([a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*" +
        "(-[0-9a-wy-z](-[a-z0-9]{2,8})+)*" +
        "(-x(-[a-z0-9]{1,8})+)?$",
    RegexOption.IGNORE_CASE,
)

/**
 * `Intl.getCanonicalLocales(tag)[0]` for a well formed tag: lower case throughout, but a title case
 * script and an upper case region. Upstream also maps deprecated codes (`iw` to `he`); this does not.
 * Null for a tag that is not well formed, where upstream throws.
 */
private fun canonicalLocale(tag: String): String? {
    if (!LANGUAGE_TAG.matches(tag)) return null
    val subtags = tag.split("-")
    val out = mutableListOf(subtags[0].lowercase())
    var inExtension = false
    for (subtag in subtags.drop(1)) {
        if (subtag.length == 1) inExtension = true
        out += when {
            inExtension -> subtag.lowercase()
            subtag.length == 4 && subtag.all { it.isLetter() } -> subtag.lowercase().replaceFirstChar { it.uppercaseChar() }
            subtag.length == 2 || (subtag.length == 3 && subtag.all { it.isDigit() }) -> subtag.uppercase()
            else -> subtag.lowercase()
        }
    }
    return out.joinToString("-")
}

internal fun normalizeLanguage(language: String?): String {
    if (language.isNullOrEmpty() || language == "auto" || language == "und") return ""
    return canonicalLocale(language.jsTrim().replace("_", "-")) ?: ""
}

private val KANA = Regex("[\\p{IsHiragana}\\p{IsKatakana}]")
private val HANGUL = Regex("\\p{IsHangul}")
private val HAN = Regex("\\p{IsHan}")
private val CJK_LANGUAGE = Regex("^(ja|ko|zh|yue)(-|$)|-(Hans|Hant)(-|$)", RegexOption.IGNORE_CASE)

private fun detectCjkLanguage(text: String): String? {
    if (KANA.containsMatchIn(text)) return "ja"
    if (HANGUL.containsMatchIn(text)) return "ko"
    // Han alone cannot distinguish Japanese, Korean, or the Chinese writing systems.
    return null
}

private fun isCjkLanguage(language: String): Boolean {
    return CJK_LANGUAGE.containsMatchIn(language)
}

/** The language each line is in, "" where it cannot be told. Only `words` and `isInstrumental` are read. */
internal fun resolveLyricLanguages(lyrics: List<Lyric>, sourceLanguage: String? = null): List<String> {
    val language = normalizeLanguage(sourceLanguage)
    val hints = lyrics.map { line -> if (line.isInstrumental == true) null else detectCjkLanguage(line.words) }
    val songHints = hints.filterNotNull().toSet()
    val songHint = if (songHints.size == 1) songHints.first() else ""
    val fallback = if (isCjkLanguage(language)) language else songHint.ifEmpty { language }

    return lyrics.mapIndexed { index, line ->
        if (line.isInstrumental == true) return@mapIndexed ""
        val hint = hints[index]
        if (hint != null && fallback.split("-")[0] != hint) return@mapIndexed hint
        if (HAN.containsMatchIn(line.words) && !isCjkLanguage(fallback)) return@mapIndexed ""
        fallback
    }
}

/** The language a line's romanization is tagged with upstream: the line's own language, in Latin script. */
internal fun romanizationLanguage(language: String?): String {
    val normalized = normalizeLanguage(language)
    return "${if (normalized.isNotEmpty()) normalized.split("-")[0] else "und"}-Latn"
}
