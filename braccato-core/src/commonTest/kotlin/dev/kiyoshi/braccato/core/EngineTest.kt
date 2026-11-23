// Port of the checks in packages/core/src/engine.selfcheck.ts that test the engine's arithmetic. The
// rest of that file drives a fake DOM through `tickView`; TimelineTest covers those decisions.
package dev.kiyoshi.braccato.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Line(
    override val time: Double,
    override val duration: Double,
    override val position: Double,
    override val height: Double,
) : ScrollTargetLine

private const val TARGET_SCROLL_RATIO = 0.37

class ScrollPaddingTest {
    // The scroll stops at the end of the content, so the last line only reaches the target position if
    // the content runs far enough past it. Under-padding strands the end of every song, and the tell is
    // a viewport that grew: fullscreen asks for far more room below the last line than a side panel.
    private val viewportHeightFullscreen = 1384.0
    private val tailSpaceDemanded = viewportHeightFullscreen * (1 - TARGET_SCROLL_RATIO)

    private val renderedMeasurements = ScrollPaddingMeasurements(
        viewportHeight = viewportHeightFullscreen,
        targetScrollRatio = TARGET_SCROLL_RATIO,
        contentHeight = 5939.0,
        firstLineHeight = 100.0,
        lastLineCentre = 5543.0,
        lastLineHeight = 120.0,
        footerHeight = 38.0,
    )

    @Test fun `the last line can always be scrolled to its target position`() {
        val rendered = computeScrollPadding(renderedMeasurements)
        assertTrue(
            rendered.bottom + (renderedMeasurements.contentHeight - renderedMeasurements.lastLineCentre!!) >= tailSpaceDemanded,
            "a rendering view's last line can reach the target scroll position",
        )

        // Every measurement taken from a container that is not rendering comes back zero.
        val unrendered = computeScrollPadding(
            ScrollPaddingMeasurements(viewportHeightFullscreen, TARGET_SCROLL_RATIO, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        assertTrue(unrendered.bottom >= tailSpaceDemanded, "an unrendered container still reserves what the viewport demands")

        assertEquals(
            ceil(tailSpaceDemanded),
            computeScrollPadding(ScrollPaddingMeasurements(viewportHeightFullscreen, TARGET_SCROLL_RATIO, 99999.0, 0.0, null, 0.0, 0.0)).bottom,
            "with no lines at all the floor is what the viewport demands",
        )

        assertTrue(
            computeScrollPadding(renderedMeasurements.copy(viewportHeight = 580.0)).bottom < rendered.bottom,
            "a smaller viewport asks for less room than fullscreen did",
        )
    }
}

class ActiveLinesScrollTest {
    private val noInsets = resolveScrollInsets("auto", "auto", 400.0)

    private fun isInBand(line: ScrollTargetLine, scrollTop: Double, height: Double, insets: ScrollInsets) =
        line.position >= scrollTop + insets.top && line.position + line.height <= scrollTop + height - insets.bottom

    private val shortPair = listOf(Line(10.0, 4.0, 1000.0, 40.0), Line(12.0, 4.0, 1050.0, 40.0))

    @Test fun `resolves scroll padding into pixel insets`() {
        assertEquals(
            listOf(ScrollInsets(30.0, 40.0), ScrollInsets(30.0, 0.0), ScrollInsets(0.0, 0.0), ScrollInsets(0.0, 0.0)),
            listOf(
                resolveScrollInsets("12%", "16%", 250.0),
                resolveScrollInsets("30px", "0px", 250.0),
                resolveScrollInsets("-10px", "", 250.0),
                noInsets,
            ),
            "percent, pixels, a negative or nothing: each is a pixel inset and only positive ones count",
        )
        assertEquals(ScrollInsets(0.0, 0.0), resolveScrollInsets("60%", "50%", 250.0), "insets that leave no band are ignored")
    }

    @Test fun `several active lines stay inside the visible band`() {
        assertEquals(
            ScrollTarget(scrollTop = 1045.0 - 148, overflowsBand = false),
            computeActiveLinesScrollTop(shortPair, shortPair[0], 400.0, noInsets, 148.0),
            "two active lines that fit have their shared centre at the target",
        )

        for (insets in listOf(noInsets, resolveScrollInsets("12%", "16%", 260.0))) {
            for (anchor in shortPair) {
                val (scrollTop) = computeActiveLinesScrollTop(shortPair, anchor, 260.0, insets, 96.0)
                assertTrue(shortPair.all { isInBand(it, scrollTop, 260.0, insets) }, "every fitting line is in the band whichever is the anchor")
            }
        }

        val fittingGroups = listOf(
            listOf(Line(10.0, 4.0, 1000.0, 80.0), Line(12.0, 4.0, 1090.0, 80.0)),
            listOf(Line(10.0, 6.0, 1000.0, 40.0), Line(11.0, 5.0, 1050.0, 40.0), Line(12.0, 4.0, 1100.0, 40.0)),
        )
        for (group in fittingGroups) {
            val insets = resolveScrollInsets("12%", "16%", 260.0)
            for (anchor in group) {
                val (scrollTop, overflowsBand) = computeActiveLinesScrollTop(group, anchor, 260.0, insets, 96.0)
                assertTrue(
                    !overflowsBand && group.all { isInBand(it, scrollTop, 260.0, insets) },
                    "regression: taller or more active lines that still fit keep the first line out of the fade",
                )
            }
        }

        val lowTargetPair = listOf(Line(0.0, 4.0, 0.0, 190.0), Line(2.0, 4.0, 190.0, 190.0))
        assertEquals(
            0.0,
            computeActiveLinesScrollTop(lowTargetPair, lowTargetPair[1], 400.0, noInsets, 40.0).scrollTop,
            "regression: with no scroll padding and a low target, two lines that fit both stay in view",
        )
    }

    @Test fun `chooses the line the singer is on as the anchor`() {
        assertEquals(
            listOf(shortPair[0], shortPair[1], shortPair[0], shortPair[1]),
            listOf(findScrollAnchor(shortPair, 11.0), findScrollAnchor(shortPair, 12.0), findScrollAnchor(shortPair, 9.5), findScrollAnchor(shortPair, 17.0)),
            "the latest that has started, or the first while none has",
        )

        val heldLine = shortPair[0].copy(duration = 10.0)
        val shortLine = shortPair[1].copy(duration = 1.0)
        assertEquals(heldLine, findScrollAnchor(listOf(heldLine, shortLine), 13.5), "an earlier line still being sung beats a later one that ended")
    }

    @Test fun `keeps the line being sung readable in a small faded view`() {
        val pipHeight = 180.0
        val pipInsets = resolveScrollInsets("12%", "16%", pipHeight)
        val pipTargetOffset = pipHeight * TARGET_SCROLL_RATIO
        val echoLine = Line(138.0, 5.8, 1000.0, 92.0)
        val leadLine = Line(142.5, 3.0, 1102.0, 92.0)

        val leadTarget = computeActiveLinesScrollTop(
            listOf(echoLine, leadLine), findScrollAnchor(listOf(echoLine, leadLine), 142.6), pipHeight, pipInsets, pipTargetOffset,
        )
        assertTrue(
            leadTarget.overflowsBand && isInBand(leadLine, leadTarget.scrollTop, pipHeight, pipInsets),
            "regression: with an echo still running when the next line starts, the line the singer is on stays readable",
        )

        val lookaheadTarget = computeActiveLinesScrollTop(
            listOf(echoLine, leadLine), findScrollAnchor(listOf(echoLine, leadLine), 142.1), pipHeight, pipInsets, pipTargetOffset,
        )
        assertTrue(
            isInBand(echoLine, lookaheadTarget.scrollTop, pipHeight, pipInsets),
            "regression: the next line in its lookahead does not push the line being sung into the fade",
        )

        val oversizedLine = listOf(Line(0.0, 5.0, 500.0, 600.0))
        assertEquals(
            500 - pipInsets.top,
            computeActiveLinesScrollTop(oversizedLine, oversizedLine[0], pipHeight, pipInsets, 66.0).scrollTop,
            "one line taller than the band has its top at the top of the band",
        )
    }
}

class TickOptionsTest {
    private val options = TickOptions(isPlaying = true)

    @Test fun `resolves the rate a tick was taken at`() {
        assertEquals(1.0, resolveTickOptions(options).playbackRate, "a tick that says nothing about rate plays at 1x")
        assertEquals(
            listOf(1.0, 1.0, 1.0, 1.0),
            listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY).map { resolveTickOptions(options.copy(playbackRate = it)).playbackRate },
            "a rate no song can be played at reads as 1x",
        )
        assertEquals(0.25, resolveTickOptions(options.copy(playbackRate = 0.25)).playbackRate)
    }

    @Test fun `fills in everything a tick left out`() {
        assertEquals(
            ResolvedTickOptions(
                isPlaying = true,
                eventCreationTime = NO_PLAYER_SNAPSHOT,
                smoothScroll = true,
                globalLyricOffset = 0.0,
                lyricOffset = 0.0,
                richsyncOffsetTrim = 0.0,
                lineOffsetTrim = 0.0,
                passiveScrollEnabled = false,
                playbackRate = 1.0,
            ),
            resolveTickOptions(options),
        )
    }
}

class SwipeTest {
    // Per-letter swipe windows: each letter owns the slice of the sweep where its own reveal happens,
    // holding hidden before and shown after, so only the letters under the moving edge keep animating.
    private val swipeRamp = SwipeRamp(easing = "linear", startFrom = "-0.2", startTo = "1.4", endFrom = "-0.1", endTo = "1.5")
    private val swipeDurationMs = 1000.0
    private val swipeLetters = 5
    private val eps = 1e-6

    @Test fun `computes per-letter swipe windows`() {
        val windows = computeLetterSwipeWindows(swipeRamp, swipeLetters, swipeDurationMs)
        assertNotNull(windows, "a linear forward ramp has windows")
        assertEquals(swipeLetters, windows.size, "one window per letter")
        windows.forEachIndexed { index, window ->
            assertTrue(window.delayMs >= -eps && window.delayMs <= swipeDurationMs + eps, "a window's delay sits inside the sweep")
            assertTrue(window.durationMs > 0, "a window animates over a real span")
            assertTrue(window.from.end * swipeLetters - index <= eps, "the value held before a letter's slice leaves it unfilled")
            assertTrue(window.to.start * swipeLetters - index >= 1 - eps, "the value held after a letter's slice fills it")
            windows.getOrNull(index + 1)?.let { next ->
                assertTrue(next.delayMs >= window.delayMs - eps, "consecutive slices advance")
                assertTrue(next.delayMs <= window.delayMs + window.durationMs + eps, "consecutive slices overlap so the sweep never gaps")
            }
        }

        assertNull(computeLetterSwipeWindows(swipeRamp.copy(easing = "ease"), swipeLetters, swipeDurationMs), "a non-linear ramp defers")
        assertNull(computeLetterSwipeWindows(swipeRamp.copy(startTo = "-0.4"), swipeLetters, swipeDurationMs), "a ramp that does not move forward defers")
        assertNull(computeLetterSwipeWindows(swipeRamp, 0, swipeDurationMs), "a word with no letters has no windows")
    }

    @Test fun `plans a mask sweep for every lettered word`() {
        val linearSweep = planLetterMaskSweep(swipeRamp, swipeLetters, swipeDurationMs, false)
        assertEquals(swipeLetters, linearSweep.size, "every letter of a linear ramp gets a sweep")
        assertTrue(linearSweep.all { it.easing == "linear" && it.keyframes.size == 2 }, "each rides the short windowed plan")

        for (easing in listOf("ease", "cubic-bezier(0.4, 0, 0.2, 1)")) {
            val sweep = planLetterMaskSweep(swipeRamp.copy(easing = easing), swipeLetters, swipeDurationMs, false)
            assertEquals(swipeLetters, sweep.size, "every letter of a non-linear ramp still reveals")
            assertTrue(sweep.all { it.easing == easing && it.durationMs == swipeDurationMs && it.delayMs == 0.0 }, "each runs the whole duration under the theme easing")
            assertTrue(sweep.all { it.keyframes.first().offset == 0.0 && it.keyframes.last().offset == 1.0 }, "each reveal spans the whole timeline")
            assertTrue(
                sweep.indices.all { sweep[it].keyframes.last().maskPositionX == linearSweep[it].keyframes.last().maskPositionX },
                "each ends at the same fully revealed mask as the linear plan",
            )
            assertTrue(
                sweep.indices.all { sweep[it].keyframes.first().maskPositionX == linearSweep[it].keyframes.first().maskPositionX },
                "each starts hidden, the same as the linear plan",
            )
        }

        assertEquals(emptyList(), planLetterMaskSweep(swipeRamp, 0, swipeDurationMs, false), "a word with no letters has nothing to sweep")

        val ltr = planLetterMaskSweep(swipeRamp.copy(easing = "ease"), swipeLetters, swipeDurationMs, false)
        val rtl = planLetterMaskSweep(swipeRamp.copy(easing = "ease"), swipeLetters, swipeDurationMs, true)
        assertTrue(ltr.indices.any { ltr[it].keyframes[0].maskPositionX != rtl[it].keyframes[0].maskPositionX }, "RTL sweeps from the mirrored edge")
    }

    // With the default ramp the swipe touches letter i at i/n of the word and has lit it 1/n + 0.1 of the
    // word later. A long word lights each letter slower than a 900ms float rises, so each starts as it is
    // touched and stretches its float to crest as it is lit. A short word lights them faster, so each
    // keeps the 900ms float and starts early enough to crest as it is lit, the first ones before the word.
    private fun waves(wordMs: Double) =
        planLetterWave(swipeRamp, swipeLetters, wordMs, wordMs * 1.6, wordMs * 0.1, 900.0).map { wave ->
            listOf(jsRoundLong(wave.delayMs), jsRoundLong(wave.durationMs))
        }

    private fun jsRoundLong(value: Double): Long = floor(value + 0.5).roundToLong()

    @Test fun `plans the letter wave`() {
        assertEquals(
            listOf(listOf(0L, 2400L), listOf(640L, 2400L), listOf(1280L, 2400L), listOf(1920L, 2400L), listOf(2560L, 2400L)),
            waves(3200.0),
            "a long word's letters start as they are touched and stretch to crest as they are lit",
        )
        assertEquals(
            listOf(listOf(-240L, 900L), listOf(-160L, 900L), listOf(-80L, 900L), listOf(0L, 900L), listOf(80L, 900L)),
            waves(400.0),
            "a short word's letters start early enough to crest as they are lit",
        )
    }
}

class GlowAlphaTest {
    // The engine skips a word's per-frame blur only when its resolved glow color renders nothing, so
    // this has to read alpha out of every color form a theme might use, and fall back to opaque when
    // it cannot, so a visible glow is never dropped.
    @Test fun `reads alpha out of every color form`() {
        assertEquals(0.0001, parseColorAlpha("color(display-p3 1 1 1 / 0.0001)"), "slash alpha from color()")
        assertEquals(1.0, parseColorAlpha("color(display-p3 1 1 1 / 1)"), "full slash alpha")
        assertEquals(0.5, parseColorAlpha("rgb(255 255 255 / 50%)"), "percentage slash alpha")
        assertEquals(0.0001, parseColorAlpha("rgba(255, 255, 255, 0.0001)"), "legacy comma alpha")
        assertEquals(0.1, parseColorAlpha("hsla(0, 0%, 100%, 0.1)"), "legacy hsla alpha")
        assertEquals(128 / 255.0, parseColorAlpha("#ffffff80"), "8-digit hex alpha")
        assertEquals(0.0, parseColorAlpha("#fff0"), "4-digit hex alpha")
        assertEquals(0.0, parseColorAlpha("transparent"), "transparent is empty")
        assertEquals(1.0, parseColorAlpha("#ffffff"), "hex with no alpha is opaque")
        assertEquals(1.0, parseColorAlpha("white"), "a keyword is opaque")
        assertEquals(1.0, parseColorAlpha("rgb(255, 255, 255)"), "alphaless rgb is opaque")
        assertNull(parseColorAlpha(""), "nothing for an empty value")
        assertNull(parseColorAlpha("var(--x)"), "nothing for an unresolved reference")
    }
}

class SteppedClockTest {
    private fun close(actual: Double, expected: Double, message: String) =
        assertTrue(abs(actual - expected) < 1e-9, "$message (got $actual, expected $expected)")

    @Test fun `smooths a stepped media clock`() {
        close(smoothedPlaybackTime(0.0, -1.0, 12.3, 5000.0, 1.0), 12.3, "with no previous clock the read is taken as is")
        close(smoothedPlaybackTime(10.0, 1000.0, 11.03, 2000.0, 1.0), 11.03, "a read ahead of the prediction is moved up to")
        close(smoothedPlaybackTime(10.0, 1000.0, 10.96, 2000.0, 1.0), 10.996, "a read 40ms behind gives up a tenth of it per second")
        close(smoothedPlaybackTime(10.0, 1000.0, 9.98, 1020.0, 1.0), 10.02 - 0.04 * 0.002, "a lagging read one tick later barely moves the clock")
        close(smoothedPlaybackTime(10.0, 1000.0, 10.93, 2000.0, 1.0), 10.93, "a read 70ms behind is a seek rather than lag")
        close(smoothedPlaybackTime(10.0, 1000.0, 10.5, 2000.0, 1.0), 10.5, "a read far behind is a seek")
        close(smoothedPlaybackTime(10.0, 1000.0, 12.5, 2000.0, 2.0), 12.5, "a read ahead at double speed is moved up to")
        close(smoothedPlaybackTime(10.0, 1000.0, 11.97, 2000.0, 2.0), 11.997, "the prediction runs at double speed")

        var clock = 30.0
        var at = 0.0
        for (step in 1..600) {
            val now = step * 20.0
            val trueTime = 30 + now / 1000
            val steppedRead = floor(trueTime / 0.042) * 0.042
            clock = smoothedPlaybackTime(clock, at, steppedRead, now, 1.0)
            at = now
            assertTrue(clock <= trueTime + 1e-9, "invariant: a clock fed lagging reads never runs ahead of the true time")
            if (step > 100) assertTrue(trueTime - clock < 0.01, "reads stepped every 42ms settle within 10ms of the true time")
        }

        val spiked = smoothedPlaybackTime(20.0, 0.0, 21.1, 1000.0, 1.0)
        val recovered = smoothedPlaybackTime(spiked, 1000.0, 21.02, 1020.0, 1.0)
        assertEquals(21.1, spiked, "a read 100ms ahead is taken")
        assertEquals(21.02, recovered, "regression: a timer spike that falls back drops back at once")
    }
}
