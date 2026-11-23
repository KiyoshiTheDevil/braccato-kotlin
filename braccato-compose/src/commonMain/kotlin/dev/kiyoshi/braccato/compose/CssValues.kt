// Reads the CSS values a Better Lyrics theme sets on `--blyrics-*` custom properties: colours,
// lengths, durations, numbers and easings. Only what BraccatoTheme maps is read, and only the
// literal forms: a `var()`, `calc()` or `color-mix()` is left for the default to answer.
package dev.kiyoshi.braccato.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import dev.kiyoshi.braccato.core.toMs
import dev.kiyoshi.braccato.types.internal.jsParseFloat
import kotlin.math.roundToInt

/** CSS's named easings. */
internal object CssEasing {
    val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val EaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
}

private val CUBIC_BEZIER = Regex("""^cubic-bezier\(\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([^)]+)\)$""")

/** A CSS easing function, or null for one this does not read (`steps()`, `linear()` with stops). */
internal fun parseCssEasing(value: String): Easing? {
    return when (val v = value.trim().lowercase()) {
        "linear" -> LinearEasing
        "ease" -> CssEasing.Ease
        "ease-in" -> CssEasing.EaseIn
        "ease-out" -> CssEasing.EaseOut
        "ease-in-out" -> CssEasing.EaseInOut
        else -> {
            val match = CUBIC_BEZIER.find(v) ?: return null
            val (a, b, c, d) = match.destructured.toList().map { jsParseFloat(it).toFloat() }
            if (listOf(a, b, c, d).any { it.isNaN() }) null else CubicBezierEasing(a, b, c, d)
        }
    }
}

/** A CSS duration in milliseconds (`0.33s`, `150ms`), or null. */
internal fun parseCssDurationMs(value: String): Double? {
    val v = value.trim()
    if (!v.endsWith("s")) return null
    val ms = toMs(v)
    return if (ms.isNaN()) null else ms
}

/**
 * A CSS length in pixels. `em` is relative to [emPx], `rem` to [remPx]. A bare number reads as pixels.
 */
internal fun parseCssLengthPx(value: String, emPx: Double, remPx: Double = 16.0): Double? {
    val v = value.trim()
    val number = jsParseFloat(v)
    if (number.isNaN()) return null
    return when {
        v.endsWith("rem") -> number * remPx
        v.endsWith("em") -> number * emPx
        v.endsWith("px") || v.matches(Regex("""^-?[\d.]+$""")) -> number
        else -> null
    }
}

/** A CSS number, or null. */
internal fun parseCssNumber(value: String): Double? {
    val number = jsParseFloat(value.trim())
    return if (number.isNaN()) null else number
}

private val HEX = Regex("""^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$""")
private val RGB = Regex("""^rgba?\(([^)]*)\)$""")
private val HSL = Regex("""^hsla?\(([^)]*)\)$""")
private val COLOR_FN = Regex("""^color\(\s*(srgb|display-p3)\s+([^)]*)\)$""")

private val NAMED_COLORS = mapOf(
    "white" to Color.White,
    "black" to Color.Black,
    "transparent" to Color.Transparent,
    "red" to Color.Red,
    "green" to Color(0xFF008000),
    "blue" to Color.Blue,
    "yellow" to Color.Yellow,
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
)

private fun channel(token: String, max: Double): Double? {
    val t = token.trim()
    val number = jsParseFloat(t)
    if (number.isNaN()) return null
    return if (t.endsWith("%")) number / 100 else number / max
}

private fun alpha(token: String?): Double? {
    if (token == null) return 1.0
    val t = token.trim()
    val number = jsParseFloat(t)
    if (number.isNaN()) return null
    return (if (t.endsWith("%")) number / 100 else number).coerceIn(0.0, 1.0)
}

/** The comma or space separated arguments of a colour function, and its `/ alpha` if any. */
private fun colorArgs(body: String): Pair<List<String>, String?> {
    val (channels, slashAlpha) = body.split("/").let { it[0] to it.getOrNull(1) }
    val parts = channels.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
    return if (slashAlpha == null && parts.size == 4) parts.take(3) to parts[3] else parts to slashAlpha
}

private fun hslToColor(h: Double, s: Double, l: Double, a: Double): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val hp = ((h % 360 + 360) % 360) / 60
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0.0)
        hp < 2 -> Triple(x, c, 0.0)
        hp < 3 -> Triple(0.0, c, x)
        hp < 4 -> Triple(0.0, x, c)
        hp < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = l - c / 2
    return Color((r1 + m).toFloat(), (g1 + m).toFloat(), (b1 + m).toFloat(), a.toFloat())
}

/**
 * A CSS colour: hex, `rgb()`/`rgba()`, `hsl()`/`hsla()`, `color(srgb …)` and `color(display-p3 …)`
 * (read as sRGB, which differs only in how saturated the most saturated colours are), and a few
 * names. Null for anything else.
 */
internal fun parseCssColor(value: String): Color? {
    val v = value.trim().lowercase()
    NAMED_COLORS[v]?.let { return it }

    HEX.find(v)?.let { match ->
        var digits = match.groupValues[1]
        if (digits.length <= 4) digits = digits.map { "$it$it" }.joinToString("")
        if (digits.length == 6) digits += "ff"
        val argb = (digits.substring(6, 8) + digits.substring(0, 6)).toLong(16)
        return Color(argb.toInt())
    }

    RGB.find(v)?.let { match ->
        val (parts, a) = colorArgs(match.groupValues[1])
        if (parts.size != 3) return null
        val (r, g, b) = parts.map { channel(it, 255.0) ?: return null }
        val alpha = alpha(a) ?: return null
        return Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), alpha.toFloat())
    }

    HSL.find(v)?.let { match ->
        val (parts, a) = colorArgs(match.groupValues[1])
        if (parts.size != 3) return null
        val h = jsParseFloat(parts[0]).takeUnless { it.isNaN() } ?: return null
        val s = channel(parts[1], 100.0) ?: return null
        val l = channel(parts[2], 100.0) ?: return null
        return hslToColor(h, s.coerceIn(0.0, 1.0), l.coerceIn(0.0, 1.0), alpha(a) ?: return null)
    }

    COLOR_FN.find(v)?.let { match ->
        val (parts, a) = colorArgs(match.groupValues[2])
        if (parts.size != 3) return null
        val (r, g, b) = parts.map { channel(it, 1.0) ?: return null }
        return Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), (alpha(a) ?: return null).toFloat())
    }

    return null
}

private val CUSTOM_PROPERTY = Regex("""(--blyrics-[\w-]+)\s*:\s*([^;{}]+?)\s*(?:!important\s*)?[;}]""")
private val COMMENT = Regex("""/\*[\s\S]*?\*/""")

/**
 * Every `--blyrics-*` custom property a stylesheet declares, last declaration winning, wherever it
 * is declared. A theme's rules for particular lines are flattened into one value per property.
 */
internal fun readCustomProperties(css: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (match in CUSTOM_PROPERTY.findAll(css.replace(COMMENT, ""))) {
        out[match.groupValues[1]] = match.groupValues[2].trim()
    }
    return out
}

internal fun Double.roundPx(): Int = roundToInt()
