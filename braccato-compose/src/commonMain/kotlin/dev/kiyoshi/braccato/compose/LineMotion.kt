// How lines move on their own once the view has decided where they go: each line easing in after a
// committed scroll (upstream's per-line scroll), and lines sliding from where a decoration that came or
// went moved them. Both are offsets drawn on top of the layout, worked out from the wall clock.
package dev.kiyoshi.braccato.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** One committed scroll, which every line follows on its own schedule. */
internal class ScrollStagger(
    val startMs: Double,
    val deltaPx: Double,
    val activeIndex: Int,
    val scale: Double,
)

/**
 * The lines a decoration moved, sliding from where they were to where it put them:
 * `captureDecorationSlide` upstream. One entry per line, then the credits.
 */
internal class DecorationSlide(val shifts: DoubleArray) {
    /** Until the next frame anchors it, the slide holds every line where it was. */
    var startMs = Double.POSITIVE_INFINITY
    var anchored = false
}

internal fun BraccatoTheme.slideOffset(slide: DecorationSlide, index: Int, nowMs: Double): Float {
    val shift = slide.shifts.getOrElse(index) { 0.0 }
    if (shift == 0.0) return 0f
    val p = progress(nowMs - slide.startMs, decorationEntryMs)
    return (shift * (1 - decorationEntryEasing.transform(p))).toFloat()
}

/** Below half a pixel, upstream does not slide a line. */
internal const val DECORATION_MIN_SHIFT_PX = 0.5

/** How many frames a decoration change waits for the layout it causes. */
internal const val DECORATION_SHIFT_FRAMES = 3

internal fun BraccatoTheme.staggerOffset(stagger: ScrollStagger, index: Int, nowMs: Double): Float {
    val relative = index - stagger.activeIndex
    val activeMs = lineScrollDurationMs(0, stagger.deltaPx)
    val ownMs = lineScrollDurationMs(relative, stagger.deltaPx)
    val durationMs = activeMs + (ownMs - activeMs) * stagger.scale
    val p = progress(nowMs - stagger.startMs, durationMs)
    return (stagger.deltaPx * (1 - lyricScrollEasing.transform(p))).toFloat()
}

/**
 * Moves what it is put on with the scrolls and slides in progress: [staggerIndex] is its place in the
 * per-line scroll, [slideIndex] its place among what a decoration moved.
 */
internal fun Modifier.followLineMotion(
    staggers: List<ScrollStagger>,
    slides: List<DecorationSlide>,
    theme: BraccatoTheme,
    clock: LyricClock,
    staggerIndex: Int,
    slideIndex: Int = staggerIndex,
): Modifier = graphicsLayer {
    if (staggers.isEmpty() && slides.isEmpty()) return@graphicsLayer
    val wall = clock.wallMs
    translationY = staggers.sumOf { theme.staggerOffset(it, staggerIndex, wall).toDouble() }.toFloat() +
        slides.sumOf { theme.slideOffset(it, slideIndex, wall).toDouble() }.toFloat()
}
