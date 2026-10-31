// Port of packages/parsers/src/stringSimilarity.ts.
package braccato.parsers

/**
 * @author Stephen Brown
 * Source: https://github.com/stephenjjbrown/string-similarity-js/
 * @license MIT License - https://github.com/stephenjjbrown/string-similarity-js/blob/master/LICENSE.md
 * @param rawStr1 First string to match
 * @param rawStr2 Second string to match
 * @param substringLength Optional. Length of substring to be used in calculating similarity. Default 2.
 * @param caseSensitive Optional. Whether you want to consider case in string matching. Default false;
 * @return Number between 0 and 1, with 0 being a low match score.
 */
internal fun stringSimilarity(
    rawStr1: String,
    rawStr2: String,
    substringLength: Int = 2,
    caseSensitive: Boolean = false,
): Double {
    // Case folding reads as a parameter reassignment upstream. Only the two bindings moved; the
    // algorithm below is the original.
    val str1 = if (caseSensitive) rawStr1 else rawStr1.lowercase()
    val str2 = if (caseSensitive) rawStr2 else rawStr2.lowercase()
    if (str1.length < substringLength || str2.length < substringLength) return 0.0
    val map = HashMap<String, Int>()
    for (i in 0 until str1.length - (substringLength - 1)) {
        val substr1 = str1.substring(i, i + substringLength)
        map[substr1] = (map[substr1] ?: 0) + 1
    }
    var match = 0
    for (j in 0 until str2.length - (substringLength - 1)) {
        val substr2 = str2.substring(j, j + substringLength)
        val count = map[substr2] ?: 0
        if (count > 0) {
            map[substr2] = count - 1
            match++
        }
    }
    return (match * 2).toDouble() / (str1.length + str2.length - (substringLength - 1) * 2)
}
