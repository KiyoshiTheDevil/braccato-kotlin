// Glyphs drawn from a bitmap while they move. Skia places a glyph on a quarter-pixel grid and
// rasterizes it again at every scale, so text under a slow zoom or a two pixel lift holds still for
// frames and then jumps: visible zoom steps. A browser avoids this by rasterizing a
// `will-change: transform` element once and transforming the bitmap; this does the same for a word
// while its line animates, and draws plain text once it is at rest, where hinted text is sharper.
//
// The bitmap is rendered at the size it is drawn at. Rendered larger and filtered down, it steps
// again: a 2:1 linear minification moves in a staircase of its own. SmoothnessTest measures both.
package braccato.compose

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** One glyph run, rendered once in white, ready to be tinted or masked by a gradient. */
internal class GlyphBitmap(val image: ImageBitmap)

internal fun DrawScope.renderGlyphs(run: TextLayoutResult): GlyphBitmap {
    val image = ImageBitmap(run.size.width.coerceAtLeast(1), run.size.height.coerceAtLeast(1))
    CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
        drawText(run, color = Color.White)
    }
    return GlyphBitmap(image)
}

private fun DrawScope.drawGlyphImage(glyphs: GlyphBitmap, alpha: Float, colorFilter: ColorFilter?) {
    drawImage(
        glyphs.image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(glyphs.image.width, glyphs.image.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(glyphs.image.width, glyphs.image.height),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.Low,
    )
}

/** The glyphs in one colour. */
internal fun DrawScope.drawGlyphs(glyphs: GlyphBitmap, color: Color, alpha: Float = 1f) {
    drawGlyphImage(glyphs, alpha, ColorFilter.tint(color, BlendMode.SrcIn))
}

/** The glyphs painted with a brush: the brush, kept only where the glyphs are. */
internal fun DrawScope.drawGlyphs(glyphs: GlyphBitmap, brush: Brush, alpha: Float = 1f) {
    val size = Size(glyphs.image.width.toFloat(), glyphs.image.height.toFloat())
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
        drawGlyphImage(glyphs, 1f, null)
        drawRect(brush, size = size, blendMode = BlendMode.SrcIn)
        canvas.restore()
    }
}

/**
 * Plain text that moves with its line, a translation or an untimed romanization: a bitmap while the
 * line animates, text at rest.
 */
@androidx.compose.runtime.Composable
internal fun MotionText(text: String, style: androidx.compose.ui.text.TextStyle, live: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val holder = androidx.compose.runtime.remember(text, style) { arrayOfNulls<Any>(2) }
    androidx.compose.foundation.layout.Box(
        modifier
            .then(
                androidx.compose.ui.Modifier.layout { measurable, constraints ->
                    val result = measurer.measure(text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxWidth))
                    if ((holder[0] as TextLayoutResult?)?.size != result.size) holder[1] = null
                    holder[0] = result
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(result.size.width, result.size.height))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
            )
            .then(
                androidx.compose.ui.Modifier.drawBehind {
                    val result = holder[0] as TextLayoutResult? ?: return@drawBehind
                    if (!live) {
                        holder[1] = null
                        drawText(result)
                        return@drawBehind
                    }
                    val glyphs = holder[1] as GlyphBitmap? ?: renderGlyphs(result).also { holder[1] = it }
                    drawGlyphs(glyphs, style.color)
                },
            ),
    )
}
