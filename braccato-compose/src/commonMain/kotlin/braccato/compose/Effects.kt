// What every effect looks like at a moment, as a function of the lyric time. Upstream starts a Web
// Animation for each and lets the browser run it; here a frame asks where each one is. The keyframes,
// durations and easings are upstream's (engine.ts and the stylesheets), read off BraccatoTheme.
package braccato.compose

import androidx.compose.animation.core.Easing
import braccato.core.BuiltWord
import braccato.core.LetterWaveTiming
import braccato.core.planLetterWave
import braccato.core.planWordHighlight
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Where an animation of [durationMs] is, [elapsedMs] after it started, from 0 to 1. */
internal fun progress(elapsedMs: Double, durationMs: Double): Float {
    if (durationMs <= 0) return if (elapsedMs >= 0) 1f else 0f
    return (elapsedMs / durationMs).coerceIn(0.0, 1.0).toFloat()
}

internal fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** A keyframe: a value at an offset, and the easing of the segment that starts there. */
internal data class Keyframe(val offset: Float, val value: Float, val easing: Easing? = null)

/** A value along keyframes, the way Web Animations samples them: each segment eased by its start's easing. */
internal fun sample(keyframes: List<Keyframe>, fraction: Float): Float {
    if (fraction <= keyframes.first().offset) return keyframes.first().value
    for (i in 0 until keyframes.size - 1) {
        val a = keyframes[i]
        val b = keyframes[i + 1]
        if (fraction <= b.offset) {
            val local = if (b.offset > a.offset) (fraction - a.offset) / (b.offset - a.offset) else 1f
            return lerp(a.value, b.value, a.easing?.transform(local) ?: local)
        }
    }
    return keyframes.last().value
}

// -- Lines --------------------------------------------

/**
 * How large a line is drawn. It grows to the active scale as it starts and shrinks back once the next
 * line takes over, each over the theme's scale transition.
 */
internal fun lineScaleAt(theme: BraccatoTheme, lineTime: Double, effectiveEnd: Double, t: Double): Float {
    if (!theme.animations.lineScale) return if (t >= lineTime && t < effectiveEnd) theme.activeScale else theme.scale
    return when {
        t < lineTime -> theme.scale
        t < effectiveEnd -> lerp(theme.scale, theme.activeScale, theme.lineEnterEasing.transform(progress((t - lineTime) * 1000, theme.scaleTransitionMs)))
        else -> lerp(theme.activeScale, theme.scale, theme.lineExitEasing.transform(progress((t - effectiveEnd) * 1000, theme.scaleTransitionMs)))
    }
}

// -- Word highlight --------------------------------------------

/**
 * A word's highlight at a moment: how visible it is, where the gradient's lit edge and its transparent
 * edge are (as a share of the word's width), and how far its glow spreads (as a share of the theme's
 * starting radius).
 */
internal data class HighlightState(
    val opacity: Float,
    val start: Float,
    val end: Float,
    val glow: Float,
) {
    companion object {
        val Hidden = HighlightState(0f, 0f, 0f, 0f)
    }
}

/**
 * `startWordAnimations` and `startWordExitAnimation`, sampled. A rich word is swiped in from its start,
 * running ahead of it by the swipe lead; a word with no duration fades in whole. Once the next line
 * takes over, every highlight that started fades out together.
 */
internal fun highlightAt(theme: BraccatoTheme, word: BuiltWord, effectiveEnd: Double, t: Double): HighlightState {
    if (t < word.time || word.time >= effectiveEnd) return HighlightState.Hidden
    val litStart = theme.swipeStartTo.toFloat()
    val litEnd = theme.swipeEndTo.toFloat()
    // Where the glow rests once it has died down, as a share of its start: upstream's `glowTo`, held
    // after the shrink and through the fade out, and nothing at all with the glow off (1.21.6).
    val restingGlow = if (theme.animations.highlightGlow) theme.glowRadiusTo.value / theme.glowRadiusFrom.value.coerceAtLeast(0.001f) else 0f

    if (t >= effectiveEnd) {
        val fade = if (theme.animations.highlightFade) {
            theme.highlightFadeOutEasing.transform(progress((t - effectiveEnd) * 1000, theme.highlightFadeOutMs))
        } else {
            progress((t - effectiveEnd) * 1000, 1.0)
        }
        return HighlightState(opacity = 1f - fade, start = litStart, end = litEnd, glow = restingGlow)
    }

    val timing = planWordHighlight(word.time, word.duration, t)
    val glowDurationMs = if (word.isLineSynced) {
        theme.glowMinDurationMs
    } else {
        max(max(0.0, word.duration * 1000) * theme.glowDurationRatio, theme.glowMinDurationMs)
    }
    val glowProgress = (timing.wordElapsedMs / glowDurationMs)
    val glow = if (theme.animations.highlightGlow && glowProgress < 1) {
        val radius = lerp(1f, theme.glowRadiusTo.value / theme.glowRadiusFrom.value.coerceAtLeast(0.001f), theme.glowEasing.transform(glowProgress.toFloat().coerceIn(0f, 1f)))
        radius
    } else {
        restingGlow
    }

    if (word.isLineSynced) {
        val opacity = if (theme.animations.highlightFade) {
            theme.highlightFadeInEasing.transform(progress(timing.wordElapsedMs, theme.highlightFadeInMs))
        } else {
            1f
        }
        return HighlightState(opacity = opacity, start = litStart, end = litEnd, glow = glow)
    }

    if (!theme.animations.highlightSwipe) return HighlightState(1f, litStart, litEnd, glow)

    val swipe = theme.swipeEasing.transform(progress(timing.swipeElapsedMs, timing.swipeDurationMs))
    return HighlightState(
        opacity = 1f,
        start = lerp(theme.swipeStartFrom.toFloat(), litStart, swipe),
        end = lerp(theme.swipeEndFrom.toFloat(), litEnd, swipe),
        glow = glow,
    )
}

// -- Wobble --------------------------------------------

internal data class Wobble(val translateEm: Float, val scaleX: Float) {
    companion object {
        val Rest = Wobble(0f, 1f)
    }
}

/**
 * The word group's wobble, `[scaleX(1), peak, settle, scaleX(1)]` over the wobble duration from the
 * word's start, under the theme's overall easing.
 */
internal fun wobbleAt(theme: BraccatoTheme, wordTime: Double, effectiveEnd: Double, t: Double): Wobble {
    if (!theme.animations.wordWobble || t < wordTime || t >= effectiveEnd) return Wobble.Rest
    val elapsed = (t - wordTime) * 1000
    if (elapsed >= theme.wobbleDurationMs) return Wobble.Rest
    val fraction = theme.wobbleEasing.transform(progress(elapsed, theme.wobbleDurationMs))
    val settleOffset = max(theme.wobblePeakOffset, theme.wobbleSettleOffset)
    val frames = { peak: Float, rest: Float ->
        listOf(
            Keyframe(0f, rest),
            Keyframe(theme.wobblePeakOffset, peak, theme.wobblePeakEasing),
            Keyframe(settleOffset, rest),
            Keyframe(1f, rest, theme.wobbleEndEasing),
        )
    }
    return Wobble(
        translateEm = sample(frames(theme.wobblePeakTranslateEm, 0f), fraction),
        scaleX = sample(frames(theme.wobblePeakScaleX, 1f), fraction),
    )
}

// -- Letter wave --------------------------------------------

internal data class LetterLift(val translateEm: Float, val scale: Float) {
    companion object {
        val Rest = LetterLift(0f, 1f)
    }
}

/** Each letter's wave, planned once per word: `planLetterWave` against the word's own swipe. */
internal fun planWordLetterWave(theme: BraccatoTheme, word: BuiltWord): List<LetterWaveTiming> {
    val letters = word.letters ?: return emptyList()
    val timing = planWordHighlight(word.time, word.duration, word.time)
    return planLetterWave(
        theme.swipeRamp,
        letters.size,
        max(0.0, word.duration * 1000),
        timing.swipeDurationMs,
        timing.swipeLeadMs,
        theme.letterWaveDurationMs,
    )
}

/**
 * A letter rising to its crest as the swipe lights it and settling a little above where it started.
 * Long words also swell at the crest.
 */
internal fun letterLiftAt(theme: BraccatoTheme, word: BuiltWord, wave: LetterWaveTiming, effectiveEnd: Double, t: Double): LetterLift {
    if (!theme.animations.wordWobble || t >= effectiveEnd) return LetterLift.Rest
    val local = (t - word.time) * 1000 - wave.delayMs
    if (local < 0) return LetterLift.Rest
    val fraction = progress(local, wave.durationMs)
    val crest = braccato.core.LETTER_WAVE_CREST_OFFSET.toFloat()
    val translate = sample(
        listOf(
            Keyframe(0f, 0f, theme.letterWaveRiseEasing),
            Keyframe(crest, theme.letterWaveRiseEm, theme.letterWaveFallEasing),
            Keyframe(1f, theme.letterWaveSettleEm),
        ),
        fraction,
    )
    val scale = if (word.isLongWord) {
        sample(
            listOf(
                Keyframe(0f, 1f, theme.letterWaveRiseEasing),
                Keyframe(crest, theme.letterWaveEmphasisScale, theme.letterWaveFallEasing),
                Keyframe(1f, 1f),
            ),
            fraction,
        )
    } else {
        1f
    }
    return LetterLift(translate, scale)
}

// -- Instrumental --------------------------------------------

internal data class InstrumentalState(
    /** The fill's opacity. */
    val fill: Float,
    /** Where the fill's surface is, as a share of the note's height from the top. */
    val level: Float,
    /** The wave's height, 1 at rest. */
    val waveScale: Float,
    /** Between the high and the low wave shape, 0 to 1. */
    val morph: Float,
)

/** The note filling up over the break and its surface calming as it does. */
internal fun instrumentalAt(theme: BraccatoTheme, lineTime: Double, lineDuration: Double, effectiveEnd: Double, t: Double): InstrumentalState {
    val durationMs = max(1.0, lineDuration * 1000)
    val elapsed = (t - lineTime) * 1000
    val oscillation = if (theme.instrumentalWaveOscillationMs > 0) {
        val cycle = ((t * 1000) / theme.instrumentalWaveOscillationMs)
        val phase = (cycle % 2).toFloat()
        theme.instrumentalWaveOscillationEasing.transform(if (phase < 1) phase else 2 - phase)
    } else {
        0f
    }
    if (t < lineTime) return InstrumentalState(0f, theme.instrumentalFillFrom, theme.instrumentalWaveFrom, oscillation)
    if (!theme.animations.instrumental) {
        val fill = if (t < effectiveEnd) 1f else 1f - progress((t - effectiveEnd) * 1000, theme.highlightFadeOutMs)
        return InstrumentalState(fill, (theme.instrumentalFillFrom + theme.instrumentalFillTo) / 2, 1f, 0f)
    }
    val fill = if (t < effectiveEnd) {
        progress(elapsed, theme.instrumentalFillFadeMs)
    } else {
        1f - CssEasing.Ease.transform(progress((t - effectiveEnd) * 1000, theme.highlightFadeOutMs))
    }
    val p = progress(elapsed, durationMs)
    return InstrumentalState(
        fill = fill,
        level = lerp(theme.instrumentalFillFrom, theme.instrumentalFillTo, p),
        waveScale = lerp(theme.instrumentalWaveFrom, theme.instrumentalWaveTo, theme.instrumentalWaveEasing.transform(p)),
        morph = oscillation,
    )
}

// -- Line scroll stagger --------------------------------------------

/**
 * How long a line takes to follow a scroll, by how far it is from the line the view follows:
 * upstream's `--blyrics-line-scroll-*-duration` defaults. Lines the scroll moves towards lag more the
 * further they are, so the lyric settles like a sheet rather than jumping as a block.
 */
internal fun lineScrollDurationMs(relativeIndex: Int, scrollDeltaPx: Double): Double {
    val abs = kotlin.math.abs(relativeIndex)
    val isScrollingUp = scrollDeltaPx < 0
    val above = when {
        relativeIndex < 0 -> !isScrollingUp
        relativeIndex > 0 -> isScrollingUp
        else -> false
    }
    return if (above) 750.0 + min(abs, 6) * 20.0 else 750.0 + ln(abs + 2.0) * 80.0 + (abs + 1) * 20.0
}

/**
 * Upstream compresses the stagger towards the active line's duration when a scroll moves further than
 * one line, after a seek or a user scroll, so a long jump does not trail its lines behind it.
 */
internal fun lineScrollStaggerScale(naturalAdvance: Boolean, stepPx: Double, scrollDistancePx: Double): Double {
    if (naturalAdvance || scrollDistancePx <= 0) return 1.0
    return min(1.0, stepPx / scrollDistancePx)
}
