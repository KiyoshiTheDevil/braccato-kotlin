// Whether text moves smoothly under the small zooms and shifts the renderer animates. Skia places
// glyphs on a quarter-pixel grid and rasterizes them again per scale, so text drawn as text moves in
// uneven steps; drawn from a bitmap of its own size, it moves by the same amount every step. This renders a word at
// fine steps of scale and offset both ways and compares how evenly its rendered centre moves.
package dev.kiyoshi.braccato.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
private fun centroidX(style: TextStyle, scale: Float, shiftX: Float, fromBitmap: Boolean, control: Boolean = false): Double {
    val scene = ImageComposeScene(400, 120, Density(1f)) {
        val measurer = rememberTextMeasurer()
        Canvas(Modifier.fillMaxSize()) {
            val layout = measurer.measure("Higher", style)
            withTransform({
                translate(left = 20f + shiftX, top = 20f)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                if (control) drawRect(Color.White, topLeft = Offset(10f, 10f), size = androidx.compose.ui.geometry.Size(100f, 40f))
                else if (fromBitmap) drawGlyphs(renderGlyphs(layout), Color.White) else drawText(layout, color = Color.White)
            }
        }
    }
    val image = scene.render(0)
    scene.close()
    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
    image.readPixels(bitmap)
    val bytes = bitmap.readPixels()!!
    var sum = 0.0
    var weight = 0.0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val g = bytes[(y * image.width + x) * 4 + 1].toInt() and 0xFF
        sum += x * g
        weight += g
    }
    return sum / weight
}

/**
 * How far the steps stray from an even motion: the largest step over the average one. Even motion
 * is 1; a staircase, which holds and then jumps, is well above.
 */
private fun unevenness(values: List<Double>): Double {
    val steps = values.zipWithNext().map { (a, b) -> abs(b - a) }
    return steps.max() / steps.average()
}

private val BASE = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold)

class SmoothnessTest {
    private fun report(label: String, fromBitmap: Boolean): Pair<Double, Double> {
        val scales = (0..20).map { 0.95f + it * 0.0025f }.map { centroidX(BASE, it, 0f, fromBitmap) }
        val shifts = (0..24).map { it * 0.1f }.map { centroidX(BASE, 1f, it, fromBitmap) }
        val result = unevenness(scales) to unevenness(shifts)
        println("SmoothnessTest [$label]: zoom ${"%.2f".format(result.first)}, shift ${"%.2f".format(result.second)}")
        println("  zoom steps: " + scales.zipWithNext().joinToString(" ") { (a, b) -> "%.3f".format(b - a) })
        println("  shift steps: " + shifts.zipWithNext().joinToString(" ") { (a, b) -> "%.3f".format(b - a) })
        return result
    }

    // Shows the measurement itself is sound: a filled shape moves evenly.
    @Test fun `control - a plain rectangle`() {
        val shifts = (0..24).map { it * 0.1f }.map { centroidX(BASE, 1f, it, false, control = true) }
        println("SmoothnessTest [control rect] shift steps: " + shifts.zipWithNext().joinToString(" ") { (a, b) -> "%.3f".format(b - a) })
    }

    @Test fun `text moves evenly through a zoom and a shift`() {
        report("glyphs drawn as text", fromBitmap = false)
        val (zoom, shift) = report("glyphs drawn from a bitmap", fromBitmap = true)
        assertTrue(zoom < 1.2 && shift < 1.2, "text drawn from a bitmap should move evenly")
    }
}
