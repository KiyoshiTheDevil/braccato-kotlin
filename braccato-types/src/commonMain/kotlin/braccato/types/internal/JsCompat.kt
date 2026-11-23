// Not a port of an upstream file. Upstream leans on JavaScript's own string and number semantics,
// and these helpers reproduce the ones that differ from Kotlin's, so the ported files can read
// line for line like the TypeScript they come from. Shared by every braccato module, and not meant
// for anyone else: using it takes an opt-in to [InternalBraccatoApi].
//
// Regex patterns in this module write JavaScript's `\s` as [JS_WHITESPACE] and its `$` (no `m` flag)
// as `\z`: Java's `\s` is ASCII only, and Java's `$` also matches before a trailing line break.
package braccato.types.internal

import kotlin.math.floor

@RequiresOptIn("Internal to the braccato modules; it may change without notice.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.CONSTRUCTOR)
public annotation class InternalBraccatoApi

/** JavaScript's WhiteSpace and LineTerminator characters, as the body of a regex character class. */
@InternalBraccatoApi
public const val JS_WHITESPACE: String = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** JavaScript's `\s`. */
@InternalBraccatoApi
public const val WS: String = "[$JS_WHITESPACE]"

@InternalBraccatoApi
public fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
    else -> c in ' '..' '
}

/** `String.prototype.trim`. */
@InternalBraccatoApi
public fun String.jsTrim(): String {
    var start = 0
    var end = length
    while (start < end && isJsWhitespace(this[start])) start++
    while (end > start && isJsWhitespace(this[end - 1])) end--
    return substring(start, end)
}

/** `String.prototype.trimStart`. */
@InternalBraccatoApi
public fun String.jsTrimStart(): String {
    var start = 0
    while (start < length && isJsWhitespace(this[start])) start++
    return substring(start)
}

/**
 * `String.prototype.split` with a regex, which splices every capture group into the result between
 * the pieces. Kotlin's `split` leaves them out.
 */
@InternalBraccatoApi
public fun String.jsSplit(regex: Regex): List<String> {
    val out = mutableListOf<String>()
    var last = 0
    for (match in regex.findAll(this)) {
        out += substring(last, match.range.first)
        for (group in 1 until match.groupValues.size) out += match.groupValues[group]
        last = match.range.last + 1
    }
    out += substring(last)
    return out
}

/** `Math.round`: halves go up, and NaN and the infinities pass through. */
@InternalBraccatoApi
public fun jsRound(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) return value
    val down = floor(value)
    return if (value - down >= 0.5) down + 1 else down
}

/** `Number.parseInt`: reads the longest prefix of digits, NaN when there are none. */
@InternalBraccatoApi
public fun jsParseInt(input: String, radix: Int = 10): Double {
    var s = input.jsTrimStart()
    var sign = 1.0
    if (s.startsWith("-")) {
        sign = -1.0
        s = s.substring(1)
    } else if (s.startsWith("+")) {
        s = s.substring(1)
    }
    if (radix == 16 && (s.startsWith("0x") || s.startsWith("0X"))) s = s.substring(2)
    var end = 0
    while (end < s.length && s[end].digitToIntOrNull(radix) != null) end++
    if (end == 0) return Double.NaN
    var value = 0.0
    for (i in 0 until end) value = value * radix + s[i].digitToInt(radix)
    return sign * value
}

private val PARSE_FLOAT_PREFIX = Regex("""^[+-]?(Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)""")

/** `Number.parseFloat`: reads the longest prefix that is a decimal literal, NaN when there is none. */
@InternalBraccatoApi
public fun jsParseFloat(input: String): Double {
    val match = PARSE_FLOAT_PREFIX.find(input.jsTrimStart()) ?: return Double.NaN
    return match.value.toDouble()
}

private val NUMBER_DECIMAL = Regex("""^[+-]?((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?|Infinity)\z""")
private val NUMBER_PREFIXED = Regex("""^0([xXoObB])([0-9a-fA-F]+)\z""")

/** `Number(string)`: the whole string as a number, 0 when it is blank, NaN when it is anything else. */
@InternalBraccatoApi
public fun jsNumber(input: String): Double {
    val s = input.jsTrim()
    if (s.isEmpty()) return 0.0
    if (NUMBER_DECIMAL.containsMatchIn(s)) return s.toDouble()
    val prefixed = NUMBER_PREFIXED.find(s) ?: return Double.NaN
    val radix = when (prefixed.groupValues[1].lowercase()) {
        "x" -> 16
        "o" -> 8
        else -> 2
    }
    val digits = prefixed.groupValues[2]
    if (digits.any { it.digitToIntOrNull(radix) == null }) return Double.NaN
    return jsParseInt(digits, radix)
}

/** `String.fromCodePoint` for one code point. */
@InternalBraccatoApi
public fun codePointToString(codePoint: Int): String {
    if (codePoint < 0x10000) return codePoint.toChar().toString()
    val offset = codePoint - 0x10000
    val high = (0xD800 + (offset shr 10)).toChar()
    val low = (0xDC00 + (offset and 0x3FF)).toChar()
    return charArrayOf(high, low).concatToString()
}
