// Port of packages/core/src/engine.ts, the arithmetic in it.
//
// Most of engine.ts drives a DOM view: it starts Web Animations from CSS keyframes, reads layout,
// culls lines off screen and corrects the drift between the browser's animation clock and the song.
// None of that has a counterpart under Compose, where a frame is drawn from the time directly. What
// is ported here is everything engine.ts decides from numbers alone, under upstream's names. The
// per-frame decisions `tickView` makes (which lines are active, when the view scrolls, how a seek is
// told apart from playback) are in Timeline.kt.
package braccato.core

import braccato.types.internal.InternalBraccatoApi

import braccato.types.internal.jsParseFloat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal const val USER_SCROLL_RESUME_DELAY_MS = 25000.0
internal const val PASSIVE_USER_SCROLL_RESUME_DELAY_MS = 5000.0

internal const val TIME_JUMP_THRESHOLD = 0.5
// One Gecko step (~45ms) plus margin: a read further off is a seek or a timer spike, not media lag.
internal const val CLOCK_RESYNC_THRESHOLD_S = 0.06
internal const val CLOCK_SLEW_PER_S = 0.1
internal const val SCROLL_PREPARE_LEAD_MS = 120.0
internal const val LINE_SCROLL_FALLBACK_DURATION_MS = 750.0

/**
 * Stands where a snapshot's wall clock timestamp would be when the time did not come from a live
 * player, and so says nothing about how long ago it was sampled.
 */
internal const val NO_PLAYER_SNAPSHOT = -1.0

// -- Playback clock --------------------------

/**
 * Smooths a media clock that steps rather than runs, such as Gecko's, which reads up to ~40ms late
 * and never early. A read ahead of the prediction is taken as it is; one slightly behind is followed
 * a tenth of the way per second; one far behind is a seek and is taken as it is.
 */
internal fun smoothedPlaybackTime(
    previousTime: Double,
    previousAt: Double,
    rawTime: Double,
    now: Double,
    playbackRate: Double,
): Double {
    if (previousAt == NO_PLAYER_SNAPSHOT) return rawTime
    val elapsedS = max(0.0, now - previousAt) / 1000
    val predictedTime = previousTime + elapsedS * playbackRate
    val errorS = rawTime - predictedTime
    if (errorS >= 0 || abs(errorS) >= CLOCK_RESYNC_THRESHOLD_S) return rawTime
    return predictedTime + errorS * min(1.0, elapsedS * CLOCK_SLEW_PER_S)
}

// -- Word states --------------------------

/** One word's timing, in seconds, as `updateWordStates` reads it. */
public interface TimedPart {
    public val time: Double
    public val duration: Double
}

/**
 * `updateWordStates` without the attribute writes: where each word of a line stands at a time. A
 * word without a duration of its own lasts until the next one starts, or the line ends.
 */
internal fun wordStatesAt(parts: List<TimedPart>, lineTime: Double, lineDuration: Double, currentTime: Double): List<WordState> {
    val lineEndTime = lineTime + lineDuration
    return parts.mapIndexed { i, part ->
        val endTime = if (part.duration > 0) part.time + part.duration else (parts.getOrNull(i + 1)?.time ?: lineEndTime)
        when {
            currentTime < part.time -> WordState.UPCOMING
            currentTime < endTime -> WordState.ACTIVE
            else -> WordState.PAST
        }
    }
}

// -- Highlight timing --------------------------

/**
 * When a word's highlight runs, from `startWordAnimations`. The swipe runs ahead of the word by a share
 * of its duration and lasts longer than it, both set by the theme.
 */
@InternalBraccatoApi public data class WordHighlightTiming(
    /** How far into the word the time is, in milliseconds. Negative before it starts. */
    val wordElapsedMs: Double,
    val swipeLeadMs: Double,
    /** How far into the swipe the time is, in milliseconds. */
    val swipeElapsedMs: Double,
    val swipeDurationMs: Double,
)

@InternalBraccatoApi public fun planWordHighlight(partTime: Double, partDuration: Double, currentTime: Double): WordHighlightTiming {
    val rawElapsedMs = (currentTime - partTime) * 1000
    // Providers do ship words that end before they start, so a negative duration counts as none.
    val timedDurationMs = max(0.0, partDuration * 1000)
    val swipeLeadMs = timedDurationMs * CoreSettings.SWIPE_LEAD_RATIO.getNumberValue()
    return WordHighlightTiming(
        wordElapsedMs = rawElapsedMs,
        swipeLeadMs = swipeLeadMs,
        swipeElapsedMs = rawElapsedMs + swipeLeadMs,
        swipeDurationMs = timedDurationMs * CoreSettings.SWIPE_DURATION_RATIO.getNumberValue(),
    )
}

internal data class SwipeEdge(val start: Double, val end: Double)

internal data class LetterSwipeWindow(
    val delayMs: Double,
    val durationMs: Double,
    val from: SwipeEdge,
    val to: SwipeEdge,
)

/**
 * The swipe, as a theme states it: an easing, and where the gradient's two edges run from and to, as
 * a share of the word. The values are the CSS strings the theme wrote.
 */
@InternalBraccatoApi public data class SwipeRamp(
    val easing: String,
    val startFrom: String,
    val startTo: String,
    val endFrom: String,
    val endTo: String,
)

// null unless the ramp is linear and forward; planLetterMaskSweep sweeps the whole word instead there.
internal fun computeLetterSwipeWindows(swipe: SwipeRamp, letterCount: Int, swipeDurationMs: Double): List<LetterSwipeWindow>? {
    if (swipe.easing != "linear" || swipeDurationMs <= 0 || letterCount <= 0) {
        return null
    }

    val startFrom = jsParseFloat(swipe.startFrom)
    val startTo = jsParseFloat(swipe.startTo)
    val endFrom = jsParseFloat(swipe.endFrom)
    val endTo = jsParseFloat(swipe.endTo)
    if (!listOf(startFrom, startTo, endFrom, endTo).all { it.isFinite() } || startTo <= startFrom || endTo <= endFrom) {
        return null
    }

    val startAt = { timeMs: Double -> startFrom + ((startTo - startFrom) * timeMs) / swipeDurationMs }
    val endAt = { timeMs: Double -> endFrom + ((endTo - endFrom) * timeMs) / swipeDurationMs }
    val timeWhereEnd = { value: Double -> (swipeDurationMs * (value - endFrom)) / (endTo - endFrom) }
    val timeWhereStart = { value: Double -> (swipeDurationMs * (value - startFrom)) / (startTo - startFrom) }

    return (0 until letterCount).map { index ->
        val beginMs = clamp(timeWhereEnd(index.toDouble() / letterCount), 0.0, swipeDurationMs)
        val finishMs = clamp(timeWhereStart((index + 1).toDouble() / letterCount), 0.0, swipeDurationMs)
        LetterSwipeWindow(
            delayMs = beginMs,
            durationMs = max(finishMs - beginMs, 1.0),
            from = SwipeEdge(start = startAt(beginMs), end = endAt(beginMs)),
            to = SwipeEdge(start = startAt(finishMs), end = endAt(finishMs)),
        )
    }
}

/**
 * One keyframe of a letter's mask sweep. Upstream writes the position as the CSS string
 * `"<x>% 0%"`; this is `x / 100`, the horizontal mask position as a fraction.
 */
internal data class LetterMaskKeyframe(
    val offset: Double,
    val maskPositionX: Double,
)

internal data class LetterMaskSweep(
    val keyframes: List<LetterMaskKeyframe>,
    val delayMs: Double,
    val durationMs: Double,
    val easing: String,
)

// Per-letter mask reveal. A linear forward ramp keeps the short windowed animations so a settled letter
// holds a finished one; any other easing runs the same geometry over the whole duration, letting the
// theme easing warp when each letter reveals, so it ends revealed rather than swept past.
internal fun planLetterMaskSweep(swipe: SwipeRamp, letterCount: Int, swipeDurationMs: Double, rtl: Boolean): List<LetterMaskSweep> {
    if (letterCount <= 0) return emptyList()
    val windows = computeLetterSwipeWindows(swipe.copy(easing = "linear"), letterCount, swipeDurationMs)
        ?: return emptyList()

    val maskSpan = letterCount + 2
    val maskPositionAt = { start: Double, index: Int ->
        val q = (0.5 * maskSpan - (start * letterCount - index)) / (maskSpan - 1)
        if (rtl) 1 - q else q
    }
    val linear = swipe.easing == "linear"
    val durationMs = if (swipeDurationMs > 0) swipeDurationMs else 1.0

    return windows.mapIndexed { index, window ->
        val from = maskPositionAt(window.from.start, index)
        val to = maskPositionAt(window.to.start, index)
        if (linear) {
            return@mapIndexed LetterMaskSweep(
                keyframes = listOf(LetterMaskKeyframe(0.0, from), LetterMaskKeyframe(1.0, to)),
                delayMs = window.delayMs,
                durationMs = window.durationMs,
                easing = "linear",
            )
        }
        val revealStart = clamp(window.delayMs / durationMs, 0.0, 1.0)
        val revealEnd = clamp((window.delayMs + window.durationMs) / durationMs, 0.0, 1.0)
        val keyframes = mutableListOf(LetterMaskKeyframe(0.0, from))
        if (revealStart > 0) keyframes += LetterMaskKeyframe(revealStart, from)
        if (revealEnd > revealStart) keyframes += LetterMaskKeyframe(revealEnd, to)
        if (revealEnd < 1) keyframes += LetterMaskKeyframe(1.0, to)
        LetterMaskSweep(keyframes = keyframes, delayMs = 0.0, durationMs = durationMs, easing = swipe.easing)
    }
}

// How far through its float a letter crests, as a keyframe offset.
@InternalBraccatoApi public const val LETTER_WAVE_CREST_OFFSET: Double = 0.4

@InternalBraccatoApi public data class LetterWaveTiming(
    val delayMs: Double,
    val durationMs: Double,
)

// When each letter's wave starts, counted from the start of the word, and how long it lasts. A letter
// crests just as the swipe finishes lighting it. If the swipe lights it faster than the float rises,
// it starts early enough to make that, which on a short word means before the word itself does. If the
// swipe lights it slower, it starts as the swipe touches it and its float stretches to crest on time.
@InternalBraccatoApi public fun planLetterWave(
    swipe: SwipeRamp,
    letterCount: Int,
    wordDurationMs: Double,
    swipeDurationMs: Double,
    swipeLeadMs: Double,
    floatDurationMs: Double,
): List<LetterWaveTiming> {
    val riseMs = floatDurationMs * LETTER_WAVE_CREST_OFFSET
    val windows = computeLetterSwipeWindows(swipe.copy(easing = "linear"), letterCount, swipeDurationMs)
    return (0 until letterCount).map { index ->
        if (windows == null) {
            // No windows for this ramp, so spread the letters evenly over the word.
            return@map LetterWaveTiming(delayMs = (wordDurationMs * index) / letterCount, durationMs = floatDurationMs)
        }
        // The swipe runs swipeLeadMs ahead of the word, so take the lead back off.
        val touchedMs = windows[index].delayMs - swipeLeadMs
        val litMs = touchedMs + windows[index].durationMs
        if (litMs - riseMs > touchedMs) {
            LetterWaveTiming(delayMs = touchedMs, durationMs = (litMs - touchedMs) / LETTER_WAVE_CREST_OFFSET)
        } else {
            LetterWaveTiming(delayMs = litMs - riseMs, durationMs = floatDurationMs)
        }
    }
}

// -- Glow --------------------------

private fun alphaToken(token: String): Double {
    val value = if (token.endsWith("%")) jsParseFloat(token) / 100 else jsParseFloat(token)
    return clamp(value, 0.0, 1.0)
}

private val SLASH_ALPHA = Regex("""/\s*([0-9]*\.?[0-9]+%?)\s*\)\s*$""")
private val COMMA_ALPHA = Regex("""^(?:rgba|hsla)\([^)]*,\s*([0-9]*\.?[0-9]+%?)\s*\)$""")
private val ALPHA_HEX = Regex("""^#([0-9a-f]{4}|[0-9a-f]{8})$""")
private val OPAQUE_FUNCTION = Regex("""^(?:rgb|hsl|hwb|lab|lch|oklab|oklch|color)\(""")
private val OPAQUE_HEX = Regex("""^#(?:[0-9a-f]{3}|[0-9a-f]{6})$""")
private val NAMED_COLOR = Regex("""^[a-z]+$""")

// null for an unrecognized format, which the caller treats as opaque so a visible glow is never dropped.
internal fun parseColorAlpha(value: String): Double? {
    val color = value.trim().lowercase()
    if (color.isEmpty()) return null
    if (color == "transparent") return 0.0

    SLASH_ALPHA.find(color)?.let { return alphaToken(it.groupValues[1]) }
    COMMA_ALPHA.find(color)?.let { return alphaToken(it.groupValues[1]) }

    ALPHA_HEX.find(color)?.let { hex ->
        val digits = hex.groupValues[1]
        val alphaHex = if (digits.length == 8) digits.substring(6) else digits.substring(3).repeat(2)
        return alphaHex.toInt(16) / 255.0
    }

    if (OPAQUE_FUNCTION.containsMatchIn(color) || OPAQUE_HEX.containsMatchIn(color) || NAMED_COLOR.containsMatchIn(color)) {
        return 1.0
    }
    return null
}

// -- Visible scroll band --------------------------

public data class ScrollInsets(
    val top: Double,
    val bottom: Double,
)

internal val NO_SCROLL_INSETS = ScrollInsets(top = 0.0, bottom = 0.0)

private fun resolveScrollInset(value: String, viewportHeight: Double): Double {
    val amount = jsParseFloat(value)
    if (!amount.isFinite() || amount <= 0) return 0.0
    return if (value.trim().endsWith("%")) (viewportHeight * amount) / 100 else amount
}

/**
 * The scroll element's `scroll-padding` is the part of its viewport a host has declared unreadable,
 * such as an edge faded out by a mask. Insets that leave no band at all are ignored rather than
 * obeyed, since there would be nowhere left to put a line.
 */
internal fun resolveScrollInsets(scrollPaddingTop: String, scrollPaddingBottom: String, viewportHeight: Double): ScrollInsets {
    val top = resolveScrollInset(scrollPaddingTop, viewportHeight)
    val bottom = resolveScrollInset(scrollPaddingBottom, viewportHeight)
    return if (top + bottom < viewportHeight) ScrollInsets(top = top, bottom = bottom) else NO_SCROLL_INSETS
}

/** A line as the scroll reads it: its time in seconds and where it sits in the content, in pixels. */
internal interface ScrollTargetLine {
    val time: Double
    val duration: Double
    val position: Double
    val height: Double
}

/**
 * The line the singer is on: the latest one still being sung. An earlier line still running is a
 * tail, such as a background echo or a held note, and a later one is still in its lookahead. A line
 * stays active past its own end until the next one starts, so a started line only wins while it runs.
 */
internal fun <Line : ScrollTargetLine> findScrollAnchor(sungLines: List<Line>, scrollTime: Double): Line {
    return sungLines.lastOrNull { line -> line.time <= scrollTime && scrollTime < line.time + line.duration }
        ?: sungLines.lastOrNull { line -> line.time <= scrollTime }
        ?: sungLines[0]
}

internal data class ScrollTarget(val scrollTop: Double, val overflowsBand: Boolean)

/**
 * Where to scroll so the lines being sung sit around the target, inside the visible band. When they
 * do not all fit, the anchor keeps its top in view and the rest give way. `overflowsBand` tells the
 * caller the view is pinned to the anchor, and has to move on once that line ends.
 *
 * @param sungLines In order, never empty. The last is the last active line.
 */
internal fun computeActiveLinesScrollTop(
    sungLines: List<ScrollTargetLine>,
    anchor: ScrollTargetLine,
    viewportHeight: Double,
    insets: ScrollInsets,
    targetOffset: Double,
): ScrollTarget {
    val first = sungLines[0]
    val last = sungLines[sungLines.size - 1]
    val averageCentre = sungLines.sumOf { line -> line.position + line.height / 2 } / sungLines.size
    val visibleBottom = viewportHeight - insets.bottom

    var scrollTop = averageCentre - targetOffset
    scrollTop = max(scrollTop, last.position + last.height - visibleBottom)
    scrollTop = min(scrollTop, last.position - insets.top)
    scrollTop = min(scrollTop, first.position - insets.top)
    scrollTop = max(scrollTop, anchor.position + anchor.height - visibleBottom)
    scrollTop = min(scrollTop, anchor.position - insets.top)
    return ScrollTarget(
        scrollTop = scrollTop,
        overflowsBand = last.position + last.height - first.position > visibleBottom - insets.top,
    )
}

// -- Ticking --------------------------

/**
 * Fills in everything a caller left out of a tick. The tick reads each of these arithmetically, so
 * a missing one would not fail: it would quietly turn the playback time into NaN and leave the view
 * matching no line at all.
 */
@InternalBraccatoApi public fun resolveTickOptions(options: TickOptions): ResolvedTickOptions {
    return ResolvedTickOptions(
        isPlaying = options.isPlaying,
        eventCreationTime = options.eventCreationTime ?: NO_PLAYER_SNAPSHOT,
        smoothScroll = options.smoothScroll ?: true,
        globalLyricOffset = options.globalLyricOffset ?: 0.0,
        lyricOffset = options.lyricOffset ?: 0.0,
        richsyncOffsetTrim = options.richsyncOffsetTrim ?: 0.0,
        lineOffsetTrim = options.lineOffsetTrim ?: 0.0,
        passiveScrollEnabled = options.passiveScrollEnabled ?: false,
        playbackRate = resolvePlaybackRate(options.playbackRate),
    )
}

// A rate of zero or less would freeze every animation that follows the song, which is a second
// answer to the question `isPlaying` already answers.
private fun resolvePlaybackRate(rate: Double?): Double {
    return if (rate != null && rate.isFinite() && rate > 0) rate else 1.0
}

// -- Layout --------------------------

/**
 * What a view has to know to size its scroll padding. Split out from the measurements so the
 * arithmetic can be checked against the degenerate cases, which is where it went wrong: an
 * unrendered container answers zero to every measurement.
 */
@InternalBraccatoApi public data class ScrollPaddingMeasurements(
    val viewportHeight: Double,
    val targetScrollRatio: Double,
    /** The container's own height with whatever bottom padding it currently carries taken back off. */
    val contentHeight: Double,
    val firstLineHeight: Double,
    /** Distance from the top of the container to the middle of the last line, or null with no lines. */
    val lastLineCentre: Double?,
    val lastLineHeight: Double,
    val footerHeight: Double,
)

@InternalBraccatoApi public data class ScrollPadding(val top: Double, val bottom: Double)

/**
 * Sizes the padding above the first line and below the last one so either can sit at the view's
 * target scroll position.
 *
 * The first two candidates are exact, and both are measured from the container. They are worth
 * nothing while it is not rendering: every line reports zero, which reads as content that already
 * runs past the last line and asks for no padding at all, and the last lines of the song then have
 * nowhere to scroll to. The viewport keeps its height whether the lyrics render or not, so the
 * space it alone demands below the last line is always knowable, and it is the floor. Over-padding
 * costs nothing visible; under-padding strands the end of every song.
 */
@InternalBraccatoApi public fun computeScrollPadding(measurements: ScrollPaddingMeasurements): ScrollPadding {
    val viewportHeight = measurements.viewportHeight
    val targetScrollRatio = measurements.targetScrollRatio
    val contentHeight = measurements.contentHeight
    val lastLineCentre = measurements.lastLineCentre

    val top = max(0.0, viewportHeight * targetScrollRatio - measurements.firstLineHeight / 2)

    val lastLineTargetContentHeight =
        if (lastLineCentre == null) viewportHeight else lastLineCentre + viewportHeight * (1 - targetScrollRatio)

    val trailingContentHeight = measurements.footerHeight + measurements.lastLineHeight / 2
    val viewportTailSpace = viewportHeight * (1 - targetScrollRatio) - trailingContentHeight

    val bottom = maxOf(
        lastLineTargetContentHeight - contentHeight,
        viewportHeight - contentHeight,
        viewportTailSpace,
        0.0,
    )

    return ScrollPadding(top = top, bottom = ceil(bottom))
}

/**
 * `getTargetScrollRatio`: a ratio the view itself was given wins, clamped to the viewport, and the
 * theme setting stands in for it otherwise.
 */
@InternalBraccatoApi public fun targetScrollRatio(scoped: Double? = null): Double {
    return if (scoped != null && scoped.isFinite()) max(0.0, min(1.0, scoped))
    else CoreSettings.SCROLL_POS_OFFSET_RATIO.getNumberValue()
}

// -- Passive scroll --------------------------

/**
 * Where unsynced lyrics drift to, from `passiveScrollEngine`: they scroll down at a steady pace for
 * the whole song's worth of lines, hold at the bottom, ease back to the top and hold there, round
 * and round.
 *
 * @param accumulatedTime Seconds the song has played since the lyrics appeared.
 * @return The scroll position, or null when there is nothing to scroll.
 */
internal fun passiveScrollTarget(accumulatedTime: Double, lineCount: Int, maxScroll: Double): Double? {
    if (lineCount == 0 || maxScroll <= 0) return null

    val scrollDuration = lineCount * CoreSettings.PASSIVE_SECONDS_PER_LINE.getNumberValue()
    val bottomPause = CoreSettings.PASSIVE_BOTTOM_PAUSE_S.getNumberValue()
    val resetDuration = CoreSettings.PASSIVE_RESET_DURATION_S.getNumberValue()
    val topPause = CoreSettings.PASSIVE_TOP_PAUSE_S.getNumberValue()
    val cycleLength = scrollDuration + bottomPause + resetDuration + topPause

    val cycleTime = accumulatedTime % cycleLength

    return if (cycleTime < scrollDuration) {
        // Phase 1: linear scroll down
        (cycleTime / scrollDuration) * maxScroll
    } else if (cycleTime < scrollDuration + bottomPause) {
        // Phase 2: hold at bottom
        maxScroll
    } else if (cycleTime < scrollDuration + bottomPause + resetDuration) {
        // Phase 3: ease-out scroll back to top
        val resetProgress = (cycleTime - scrollDuration - bottomPause) / resetDuration
        val eased = 1 - (1 - resetProgress) * (1 - resetProgress)
        maxScroll * (1 - eased)
    } else {
        // Phase 4: hold at top
        0.0
    }
}

/**
 * Where passive scrolling picks up after the user scrolled it: the accumulated time that puts the
 * cycle at the position they left the view at.
 */
internal fun passiveScrollResumeTime(scrollTop: Double, maxScroll: Double, lineCount: Int): Double? {
    if (maxScroll <= 0) return null
    val ratio = scrollTop / maxScroll
    return ratio * lineCount * CoreSettings.PASSIVE_SECONDS_PER_LINE.getNumberValue()
}
