// Upstream's instrumental icon (instrumental.ts, instrumental.css): a note drawn twice, once empty in
// the inactive colour and once in the active colour, clipped below a wave whose surface rises over
// the break and calms as it does, rocking between two shapes meanwhile.
package braccato.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import braccato.core.INSTRUMENTAL_NOTE_PATH
import braccato.core.INSTRUMENTAL_WAVE_PATH_HIGH
import braccato.core.INSTRUMENTAL_WAVE_PATH_LOW
import braccato.core.INSTRUMENTAL_WAVE_RECT_PATH

private const val VIEW_BOX = 24f
private val NUMBER = Regex("""-?\d*\.?\d+""")

private fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

/** The wave between its high and its low shape, which share their commands, so their numbers line up. */
private fun waveBetween(fraction: Float): String {
    val high = NUMBER.findAll(INSTRUMENTAL_WAVE_PATH_HIGH).map { it.value.toFloat() }.toList()
    val low = NUMBER.findAll(INSTRUMENTAL_WAVE_PATH_LOW).map { it.value.toFloat() }.toList()
    var index = 0
    return NUMBER.replace(INSTRUMENTAL_WAVE_PATH_HIGH) {
        val value = lerp(high[index], low[index], fraction)
        index++
        value.toString()
    }
}

/**
 * The note for an instrumental line, as tall as a line of text and a third wider, hanging a third of
 * that into the line's start the way upstream's negative margin does.
 *
 * @param state Read on every frame; null draws the note at rest.
 */
@Composable
internal fun InstrumentalNote(
    size: Dp,
    activeColor: Color,
    inactiveColor: Color,
    rtl: Boolean,
    state: () -> InstrumentalState?,
    modifier: Modifier = Modifier,
) {
    val note = remember { parsePath(INSTRUMENTAL_NOTE_PATH) }
    val rect = remember { parsePath(INSTRUMENTAL_WAVE_RECT_PATH) }
    val width = size * 4 / 3
    Canvas(
        modifier
            .offset(x = if (rtl) size / 3 else -size / 3)
            .size(width = width, height = size),
    ) {
        val unit = this.size.height / VIEW_BOX
        translate(left = (this.size.width - VIEW_BOX * unit) / 2) {
            scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                drawPath(note, inactiveColor)
                val now = state() ?: return@scale
                if (now.fill <= 0f) return@scale
                val wave = parsePath(waveBetween(now.morph))
                clipPath(note) {
                    // The wave flattens towards its floor at y=4, then the whole surface rises.
                    translate(top = now.level * VIEW_BOX) {
                        val waveClip = Path().apply {
                            addPath(rect)
                            val flattened = Path().apply { addPath(wave) }
                            flattened.transform(
                                androidx.compose.ui.graphics.Matrix().apply {
                                    translate(0f, 4f)
                                    scale(1f, now.waveScale.coerceAtLeast(0.0001f))
                                    translate(0f, -4f)
                                },
                            )
                            addPath(flattened)
                        }
                        clipPath(waveClip) {
                            translate(top = -now.level * VIEW_BOX) {
                                drawPath(note, activeColor, alpha = now.fill)
                            }
                        }
                    }
                }
            }
        }
    }
}
