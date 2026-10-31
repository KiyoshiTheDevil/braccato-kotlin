// Port of packages/core/src/text.ts.
//
// Script detection for the text the module renders. Pure string work, no DOM and no host state.
//
// Upstream matches some of these on `Script_Extensions`, which the JVM's regex engine does not
// know. Those are matched on `Script` here, which differs only for the few characters shared
// between scripts, such as the Arabic comma or a combining mark that belongs to Latin.
package braccato.core

import braccato.types.internal.jsTrim

private val RTL_REGEX = Regex(
    "[\\p{IsArabic}\\p{IsHebrew}\\p{IsSyriac}\\p{IsThaana}\\p{IsNko}\\p{IsAdlam}\\p{IsMandaic}" +
        "\\p{IsHanifi_Rohingya}\\p{IsSamaritan}\\p{IsYezidi}\\p{IsMende_Kikakui}\\p{IsOld_Hungarian}]",
)

// Script_Extensions upstream. The characters listed after the scripts are the shared ones that
// join, which Script alone misses: the tatweel, the Arabic harakat, and the Mongolian punctuation.
private val JOINING_SCRIPT_REGEX = Regex(
    "[\\p{IsArabic}\\p{IsSyriac}\\p{IsNko}\\p{IsMongolian}\\p{IsAdlam}\\p{IsMandaic}\\p{IsHanifi_Rohingya}" +
        "\\u0640\\u064B-\\u0655\\u0670\\u060C\\u061B\\u061C\\u061F\\u06D4\\u1802\\u1803\\u1805]",
)

fun testRtl(text: String): Boolean = RTL_REGEX.containsMatchIn(text)

fun testJoiningScript(text: String): Boolean = JOINING_SCRIPT_REGEX.containsMatchIn(text)

/**
 * Any character outside the Latin and Common scripts (Script_Extensions upstream), so letters of
 * every other script, while Western European accents and "smart" punctuation do not count.
 */
private val nonLatinRegex = Regex("[^\\p{IsLatin}\\p{IsCommon}]")

/**
 * Checks if a given string contains any non-Latin characters.
 * @param text The string to check.
 * @return True if a non-Latin character is found, otherwise false.
 */
fun containsNonLatin(text: String): Boolean {
    return nonLatinRegex.containsMatchIn(text)
}

private val SCRIPT_TO_LANG: List<Pair<Regex, String>> = listOf(
    Regex("\\p{IsHiragana}|\\p{IsKatakana}") to "ja",
    Regex("\\p{IsHangul}") to "ko",
    Regex("\\p{IsHan}") to "zh",
    Regex("\\p{IsCyrillic}") to "ru",
    Regex("\\p{IsDevanagari}") to "hi",
    Regex("\\p{IsArabic}") to "ar",
    Regex("\\p{IsThai}") to "th",
    Regex("\\p{IsGreek}") to "el",
    Regex("\\p{IsHebrew}") to "he",
    Regex("\\p{IsBengali}") to "bn",
    Regex("\\p{IsTamil}") to "ta",
    Regex("\\p{IsTelugu}") to "te",
    Regex("\\p{IsMalayalam}") to "ml",
    Regex("\\p{IsKannada}") to "kn",
    Regex("\\p{IsGujarati}") to "gu",
    Regex("\\p{IsGurmukhi}") to "pa",
    Regex("\\p{IsSinhala}") to "si",
    Regex("\\p{IsMyanmar}") to "my",
    Regex("\\p{IsGeorgian}") to "ka",
    Regex("\\p{IsKhmer}") to "km",
    Regex("\\p{IsLao}") to "lo",
)

fun detectNonLatinLanguage(text: String): String? {
    for ((regex, lang) in SCRIPT_TO_LANG) {
        if (regex.containsMatchIn(text)) return lang
    }
    return null
}

private val PUNCTUATION = Regex("\\p{P}")

private fun comparable(text: String): String = text.lowercase().replace(PUNCTUATION, "").jsTrim()

fun isSameText(a: String, b: String): Boolean = comparable(a) == comparable(b)
