// One timed word: upstream's word span and its highlight overlay, drawn as one node. The base text
// is drawn in the inactive colour; the highlight is the same text in the active colour under a
// horizontal gradient that the swipe moves across it; the glow is that highlight again, blurred, on
// a layer of its own. A lettered word lays its graphemes out one by one so each can ride its wave.
package braccato.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import braccato.core.BuiltWord
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A word's glyph runs as measured: one for a plain word, one per grapheme for a lettered one. */
internal class WordLayout {
    var runs: List<TextLayoutResult> = emptyList()
    var xs: FloatArray = FloatArray(0)
    var width = 0f
    var height = 0f
}

/** Everything a word needs from the line it is in, to decide how it looks at a time. */
internal class WordContext(
    val theme: BraccatoTheme,
    val effectiveEnd: Double,
    /** Whether the line is close enough to its time to read the clock at all. */
    val live: Boolean,
    val time: () -> Double,
    val emPx: Float,
    val glowRadiusPx: Float,
    val baseColor: Color,
)

private fun measureWord(measurer: TextMeasurer, word: BuiltWord, style: TextStyle, maxWidth: Int, into: WordLayout) {
    val letters = word.letters
    val constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1))
    if (letters != null && letters.size > 1) {
        into.runs = letters.map { measurer.measure(it, style, overflow = TextOverflow.Visible, softWrap = false) }
        var x = 0f
        into.xs = FloatArray(letters.size) { i -> x.also { x += into.runs[i].size.width } }
        into.width = x
    } else {
        into.runs = listOf(measurer.measure(word.text, style, overflow = TextOverflow.Visible, constraints = constraints))
        into.xs = floatArrayOf(0f)
        into.width = into.runs[0].size.width.toFloat()
    }
    into.height = into.runs.maxOf { it.size.height }.toFloat()
}

/** Draws every run, each lifted by its letter's wave, with the paint [draw] chooses. */
private inline fun DrawScope.drawRuns(
    layout: WordLayout,
    lifts: List<LetterLift>?,
    emPx: Float,
    draw: DrawScope.(TextLayoutResult, Float) -> Unit,
) {
    layout.runs.forEachIndexed { i, run ->
        val x = layout.xs[i]
        val lift = lifts?.getOrNull(i)
        if (lift == null || (lift.translateEm == 0f && lift.scale == 1f)) {
            translate(left = x) { draw(run, x) }
        } else {
            withTransform({
                translate(left = x, top = lift.translateEm * emPx)
                scale(lift.scale, lift.scale, pivot = Offset(run.size.width / 2f, run.size.height / 2f))
            }) { draw(run, x) }
        }
    }
}

/** The gradient the swipe has reached, in the word's own coordinates; [shift] is the run's x in the word. */
private fun highlightBrush(state: HighlightState, width: Float, rtl: Boolean, shift: Float, color: Color): Brush {
    val startX = state.start * width
    val endX = state.end * width + 1f
    return if (rtl) {
        Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), startX = width - startX - shift, endX = width - endX - shift, tileMode = TileMode.Clamp)
    } else {
        Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), startX = startX - shift, endX = endX - shift, tileMode = TileMode.Clamp)
    }
}

@Composable
internal fun WordView(
    word: BuiltWord,
    style: TextStyle,
    measurer: TextMeasurer,
    context: WordContext,
    modifier: Modifier = Modifier,
) {
    val layout = remember(word, style) { WordLayout() }
    val waves = remember(word, context.theme) { planWordLetterWave(context.theme, word) }
    val theme = context.theme
    val activeColor = theme.activeColor
    val margin = ceil(context.glowRadiusPx * 2).toInt()

    fun lifts(t: Double): List<LetterLift>? =
        if (waves.isEmpty()) null else waves.map { letterLiftAt(theme, word, it, context.effectiveEnd, t) }

    Box(
        modifier
            .layout { measurable, constraints ->
                measureWord(measurer, word, style, constraints.maxWidth, layout)
                val placeable = measurable.measure(Constraints.fixed(ceil(layout.width).toInt(), ceil(layout.height).toInt()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (!context.live) {
                    drawRuns(layout, null, context.emPx) { run, _ -> drawText(run, color = context.baseColor) }
                    return@drawBehind
                }
                val t = context.time()
                val lifts = lifts(t)
                drawRuns(layout, lifts, context.emPx) { run, _ -> drawText(run, color = context.baseColor) }
                val state = highlightAt(theme, word, context.effectiveEnd, t)
                if (state.opacity <= 0f) return@drawBehind
                drawRuns(layout, lifts, context.emPx) { run, x ->
                    drawText(run, brush = highlightBrush(state, layout.width, word.isRtl, x, activeColor), alpha = state.opacity)
                }
            },
    ) {
        if (context.live && theme.animations.highlightGlow && margin > 0) {
            // The glow is the highlight blurred: it follows the lit part of the word, as a CSS
            // drop-shadow does, rather than the whole glyphs a text shadow would follow.
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val w = ceil(layout.width).toInt() + margin * 2
                        val h = ceil(layout.height).toInt() + margin * 2
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(0, 0) { placeable.place(IntOffset(-margin, -margin)) }
                    }
                    .graphicsLayer {
                        val state = highlightAt(theme, word, context.effectiveEnd, context.time())
                        val radius = state.glow * context.glowRadiusPx
                        alpha = if (radius > 0.5f) state.opacity else 0f
                        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                    }
                    .drawBehind {
                        val t = context.time()
                        val state = highlightAt(theme, word, context.effectiveEnd, t)
                        if (state.glow <= 0f || state.opacity <= 0f) return@drawBehind
                        translate(margin.toFloat(), margin.toFloat()) {
                            drawRuns(layout, lifts(t), context.emPx) { run, x ->
                                drawText(run, brush = highlightBrush(state, layout.width, word.isRtl, x, theme.glowColor))
                            }
                        }
                    },
            )
        }
    }
}

internal fun Float.px(): Int = roundToInt()
