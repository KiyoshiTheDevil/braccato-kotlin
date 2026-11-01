// Not a port of one upstream function, but of the decisions inside engine.ts's `tickView`, taken
// apart from the DOM writes they drive. Upstream decides and writes in one pass per frame: which lines
// are active (and adds a class), whether the view scrolls (and sets scrollTop), whether a time is a
// seek (and resets animations). Here one [LyricsTimeline.tick] returns those decisions as a
// [TimelineFrame], and the renderer acts on them.
//
// Left out on purpose, because they answer browser questions a Compose renderer does not have:
// - the drift learning between the Web Animations clock and the song (`learnAnimationTimingOffset`),
// - swallowing the scroll events a programmatic scroll fires (`skipScrolls`),
// - the two second lead upstream sets animations up with, which only spreads their setup cost.
//
// One difference in shape: upstream's playback clock is module scope, shared by every view in the
// bundle. Here each timeline owns one, so two views cannot replay each other's clock.
package braccato.core

import braccato.types.LyricSyncType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -- Theme timing --------------------------------------------

/**
 * The timing offsets a theme sets as CSS durations (`--blyrics-richsync-timing-offset`,
 * `--blyrics-timing-offset`, `--blyrics-scroll-timing-offset`). Defaults are styles/variables.css's.
 * In seconds.
 */
data class TimingOffsets(
    val richsync: Double = 0.15,
    val line: Double = 0.115,
    val scroll: Double = 0.5,
)

/**
 * Seconds to add to the player time to get the lyric time a view renders: the theme's offset and the
 * trim for its sync type, less the global and per-song offsets. `lyricClockShiftS` upstream.
 */
fun lyricClockShiftS(syncType: LyricSyncType, offsets: TimingOffsets, options: ResolvedTickOptions): Double {
    val syncTypeShiftS = if (syncType == LyricSyncType.RICHSYNC) {
        offsets.richsync - options.richsyncOffsetTrim
    } else {
        offsets.line - options.lineOffsetTrim
    }
    return syncTypeShiftS - options.globalLyricOffset - options.lyricOffset
}

// -- Playback clock --------------------------------------------

/** What the clock made of one reading. */
data class ClockReading(
    /** The time to render, extrapolated from the snapshot and smoothed. Seconds. */
    val time: Double,
    /** The time moved further than playback explains: a seek. */
    val timeJumped: Boolean,
    /** A media clock (no snapshot) that went back while playing, by less than a jump: a short loop. */
    val mediaSteppedBack: Boolean,
    /** The clock moved while paused, which only a host seeking it does, as a scrub does. */
    val pausedSeek: Boolean,
)

/** How far a held reading is carried forward before the clock waits for the source. */
const val MAX_HELD_LEAD_S = 0.25

/**
 * The last player snapshot, and how the next one is read against it.
 *
 * @param smoothSteppedClock Smooth a media clock that steps rather than runs, the way upstream does for
 *   Gecko's ~40 ms steps. Off by default; [interpolateHeldReadings] covers coarser sources.
 * @param interpolateHeldReadings Carry a reading forward on the wall clock while the source repeats it.
 *   Not upstream's: a browser's media clock moves every frame, but some players do not. Media3's
 *   `ExoPlayer.currentPosition` changes about ten times a second, and the swipe drawn from it would
 *   move in 100 ms steps. While playing without a snapshot, a reading the source keeps returning
 *   runs on from when it first appeared, at most [MAX_HELD_LEAD_S] ahead of it; a new reading that
 *   lands behind what was already shown holds the clock until the source catches up, rather than
 *   running the swipe backwards. A source that moves every frame never repeats a reading, so this
 *   changes nothing for it. On by default.
 */
class PlaybackClock(
    private val smoothSteppedClock: Boolean = false,
    private val interpolateHeldReadings: Boolean = true,
) {
    var lastTime = 0.0
        private set
    var lastPlayState = false
        internal set
    private var lastEventCreationTime = NO_PLAYER_SNAPSHOT
    private var smoothedTime = 0.0
    private var smoothedAt = NO_PLAYER_SNAPSHOT
    private var heldReading = Double.NaN
    private var heldSince = 0.0
    private var lastShown = Double.NaN

    /**
     * Forgets the last player snapshot, so the next tick is treated as the first one of a new song
     * rather than as a jump away from the previous one.
     */
    fun reset() {
        lastTime = 0.0
        lastPlayState = false
        lastEventCreationTime = NO_PLAYER_SNAPSHOT
        smoothedAt = NO_PLAYER_SNAPSHOT
        heldReading = Double.NaN
        lastShown = Double.NaN
    }

    fun read(currentTime: Double, options: ResolvedTickOptions, now: Double): ClockReading {
        val eventCreationTime = options.eventCreationTime
        val isPlaying = options.isPlaying

        val timeJumped =
            abs(currentTime - lastTime - (eventCreationTime - lastEventCreationTime) / 1000) > TIME_JUMP_THRESHOLD
        // Without a player snapshot the reading is the media clock, which runs backwards only on a seek, so a
        // loop shorter than the jump threshold still re-arms lines. A snapshot host's extrapolated clock is
        // pulled back by every correction, so it is left to the threshold.
        val mediaSteppedBack =
            eventCreationTime == NO_PLAYER_SNAPSHOT && isPlaying && currentTime < lastTime - CLOCK_RESYNC_THRESHOLD_S
        // A clock that was already paused only moves when the host seeks it, however small the step, as a
        // scrub does. The tick that pauses lands a moment past the last playing one and is not a seek.
        val pausedSeek = !isPlaying && !lastPlayState && currentTime != lastTime

        lastTime = currentTime
        lastPlayState = isPlaying
        lastEventCreationTime = eventCreationTime

        var timeOffset = now - eventCreationTime
        if (!isPlaying || eventCreationTime == NO_PLAYER_SNAPSHOT) {
            timeOffset = 0.0
        }

        var time = currentTime + timeOffset / 1000

        if (smoothSteppedClock) {
            if (isPlaying && !timeJumped) {
                time = smoothedPlaybackTime(smoothedTime, smoothedAt, time, now, options.playbackRate)
            }
            smoothedTime = time
            smoothedAt = if (isPlaying) now else NO_PLAYER_SNAPSHOT
        } else if (interpolateHeldReadings && isPlaying && eventCreationTime == NO_PLAYER_SNAPSHOT) {
            time = carryHeldReading(currentTime, now, options.playbackRate)
        } else {
            heldReading = Double.NaN
            lastShown = Double.NaN
        }

        return ClockReading(time = time, timeJumped = timeJumped, mediaSteppedBack = mediaSteppedBack, pausedSeek = pausedSeek)
    }

    private fun carryHeldReading(reading: Double, now: Double, playbackRate: Double): Double {
        val previousReading = heldReading
        if (reading != heldReading) {
            heldReading = reading
            heldSince = now
        }
        val carriedS = max(0.0, now - heldSince) / 1000 * playbackRate
        var shown = reading + min(carriedS, MAX_HELD_LEAD_S)
        // The source moved on, by less than the clock had already run ahead: wait for it rather than
        // step back. A source that went back (a seek, a loop) is taken as it is.
        val previous = lastShown
        if (!previous.isNaN() && !previousReading.isNaN() && reading >= previousReading && shown < previous) {
            shown = previous
        }
        lastShown = shown
        return shown
    }
}

// -- Line selection --------------------------------------------

/**
 * Whether a line is in the scroll's active set at a scroll time: from just before it starts until
 * the next line starts or it ends, whichever is later. Upstream marks these `blyrics--active`.
 */
fun isLineActive(lines: List<TimedPart>, index: Int, lyricScrollTime: Double, earlyScrollConsiderS: Double): Boolean {
    val line = lines[index]
    val nextTime = lines.getOrNull(index + 1)?.time ?: Double.POSITIVE_INFINITY
    return lyricScrollTime >= line.time - earlyScrollConsiderS &&
        (lyricScrollTime < nextTime || lyricScrollTime < line.time + line.duration)
}

/** When a line stops being animated: the next line's start, or its own end and a little more. */
fun effectiveEndTime(lines: List<TimedPart>, index: Int): Double {
    val line = lines[index]
    val nextTime = lines.getOrNull(index + 1)?.time ?: Double.POSITIVE_INFINITY
    return max(nextTime, line.time + line.duration + 0.05)
}

/**
 * Whether a line's animations run at a time, upstream's "selected" (`blyrics--animating`).
 *
 * @param leadS How early before its start the line counts. Upstream sets animations up two seconds
 *   early while playing, which only matters to the Web Animations it creates; zero is the line's own start.
 */
fun isLineAnimating(lines: List<TimedPart>, index: Int, currentTime: Double, leadS: Double = 0.0): Boolean {
    return currentTime + leadS >= lines[index].time && currentTime < effectiveEndTime(lines, index)
}

// -- Scroll --------------------------------------------

/** Where the renderer has laid the lines out, in the scroll content's coordinates. Pixels. */
data class ScrollViewport(
    val height: Double,
    val scrollTop: Double,
    val maxScrollTop: Double,
    /** Each line's top, by line index. */
    val linePositions: List<Double>,
    /** Each line's height, by line index. */
    val lineHeights: List<Double>,
    val insets: ScrollInsets = NO_SCROLL_INSETS,
    /** The credits after the last line, when there are any laid out. */
    val credits: ScrollItem? = null,
    /** 0 puts the active lines at the top, 0.5 in the middle. */
    val targetScrollRatio: Double = targetScrollRatio(),
)

data class ScrollItem(val position: Double, val height: Double)

enum class ScrollAction {
    /** Leave the view where it is. */
    NONE,

    /** The next line starts within [SCROLL_PREPARE_LEAD_MS]: a renderer may start moving towards it. */
    PREPARE,

    /** Scroll to the target now. */
    COMMIT,
}

data class ScrollDecision(
    val action: ScrollAction,
    /** Where the view should be, already clamped to what it can reach. */
    val targetScrollTop: Double,
    /** Whether to animate there, as opposed to jumping. */
    val smooth: Boolean,
    /**
     * The view is advancing from where the timeline last left it, not after a seek or a user scroll.
     * Upstream keeps a theme's full per-line stagger only for these.
     */
    val naturalAdvance: Boolean,
    /** The line the view follows. */
    val anchorIndex: Int,
    /** The last line of the active set. */
    val lastActiveIndex: Int,
)

// -- Frame --------------------------------------------

enum class FrameStatus {
    /** Paused at zero: nothing was decided. */
    SKIPPED,

    /** The lyrics are a "not found" placeholder. */
    PLACEHOLDER,

    /** The lyrics carry no time; only [TimelineFrame.passiveScrollTop] says anything. */
    UNSYNCED,

    SYNCED,
}

/** Everything one tick decided. Line indices are into [BuiltLyrics.lines]. */
data class TimelineFrame(
    val status: FrameStatus,
    /** The lyric time the lines are matched against, offsets applied. Seconds. */
    val lyricTime: Double = 0.0,
    /** The lyric time the scroll is matched against, which runs ahead by the scroll offset. Seconds. */
    val scrollTime: Double = 0.0,
    val clock: ClockReading? = null,
    /** Lines in the scroll's active set. */
    val activeLines: List<Int> = emptyList(),
    /** Lines whose animations run. */
    val animatingLines: List<Int> = emptyList(),
    /** The last line has been sung and the credits hold the focus. */
    val creditsFocused: Boolean = false,
    /** Null for the stage layout, without a viewport, or while the user's scroll holds the view. */
    val scroll: ScrollDecision? = null,
    /** Unsynced lyrics with passive scrolling on: where the view drifts to. */
    val passiveScrollTop: Double? = null,
    /** Whether to offer the way back to autoscroll. */
    val resumeAffordanceVisible: Boolean = false,
    /** Stage layout: each item's role, lines first, then the credits when there are any. */
    val stageRoles: List<StageRole>? = null,
)

/**
 * One view's timeline: the clock, the selection, the scroll state. The renderer feeds it the playback
 * time once per frame and draws what it answers.
 */
class LyricsTimeline(
    val lyrics: BuiltLyrics,
    val layout: LyricsLayout = LyricsLayout.SCROLL,
    val offsets: TimingOffsets = TimingOffsets(),
    val clock: PlaybackClock = PlaybackClock(),
) {
    private val lines: List<BuiltLine> = lyrics.lines

    private var scrollPos = -1.0
    private var scrollResumeTime = 0.0
    private var wasUserScrolling = false
    private var lastScrollElements: Set<Int> = emptySet()
    private var pinnedScrollLine: Int? = null
    private var creditsFocused = false

    private var passiveScrollAccumulatedTime = 0.0
    private var passiveLastWallTime = 0.0

    // -- User scroll --------------------------

    /**
     * The user scrolled the view, which pauses autoscroll long enough to read where it landed.
     * Unsynced lyrics are drifting on their own rather than following the song, so they resume sooner.
     */
    fun noteUserScroll(now: Double) {
        val isPassive = lyrics.syncType == LyricSyncType.NONE && !lyrics.noLyrics
        scrollResumeTime = now + (if (isPassive) PASSIVE_USER_SCROLL_RESUME_DELAY_MS else USER_SCROLL_RESUME_DELAY_MS)
        wasUserScrolling = true
    }

    /** The user asked for autoscroll back, now. */
    fun resumeAutoscroll() {
        scrollResumeTime = 0.0
    }

    /**
     * The lines were measured again: the view resized, the scroll padding changed, a decoration came
     * in. The next tick scrolls to where the active lines now are, as upstream's `relayout` arranges by
     * marking the view as scrolled.
     */
    fun noteRelayout() {
        wasUserScrolling = true
    }

    // -- Tick --------------------------

    /**
     * Renders the view at a playback time, in seconds.
     *
     * @param now Wall clock time in milliseconds, the clock `eventCreationTime` is on.
     * @param viewport Where the lines are laid out. Without one, nothing about scrolling is decided.
     */
    fun tick(currentTimeS: Double, tickOptions: TickOptions, now: Double, viewport: ScrollViewport? = null): TimelineFrame {
        val options = resolveTickOptions(tickOptions)
        val isPlaying = options.isPlaying

        if (currentTimeS == 0.0 && !isPlaying) {
            return TimelineFrame(status = FrameStatus.SKIPPED, resumeAffordanceVisible = wasUserScrolling)
        }

        if (lyrics.noLyrics) return TimelineFrame(status = FrameStatus.PLACEHOLDER)

        if (lyrics.syncType == LyricSyncType.NONE) {
            // The stage shows lines by their time, and unsynced lines have none.
            if (layout == LyricsLayout.STAGE) return TimelineFrame(status = FrameStatus.UNSYNCED)
            if (!clock.lastPlayState && isPlaying) {
                scrollResumeTime = 0.0
            }
            clock.lastPlayState = isPlaying
            val passive = if (options.passiveScrollEnabled && CoreSettings.PASSIVE_SCROLL_ENABLED.getBooleanValue()) {
                passiveScroll(isPlaying, now, viewport)
            } else {
                null
            }
            return TimelineFrame(
                status = FrameStatus.UNSYNCED,
                passiveScrollTop = passive,
                resumeAffordanceVisible = wasUserScrolling,
            )
        }

        val reading = clock.read(currentTimeS, options, now)
        val currentTime = reading.time + lyricClockShiftS(lyrics.syncType, offsets, options)
        val lyricScrollTime = currentTime + offsets.scroll
        val earlyScrollConsiderS = CoreSettings.EARLY_SCROLL_CONSIDER.getNumberValue()

        val activeElems = mutableListOf<Int>()
        val animating = mutableListOf<Int>()
        var newLyricSelected = reading.timeJumped

        for (index in lines.indices) {
            if (isLineActive(lines, index, lyricScrollTime, earlyScrollConsiderS)) {
                activeElems += index
                if (index !in lastScrollElements && lyricScrollTime >= lines[index].time) {
                    newLyricSelected = true
                }
            }
            if (isLineAnimating(lines, index, currentTime)) animating += index
        }

        val lastSungLine = lines.lastOrNull { line -> !line.isInstrumental }
        val nowCreditsFocused =
            lastSungLine != null &&
                (viewport?.credits?.height ?: 0.0) > 0 &&
                lyricScrollTime >= lastSungLine.time + lastSungLine.duration
        if (nowCreditsFocused != creditsFocused) {
            creditsFocused = nowCreditsFocused
            newLyricSelected = true
        }

        if (layout == LyricsLayout.STAGE) {
            return TimelineFrame(
                status = FrameStatus.SYNCED,
                lyricTime = currentTime,
                scrollTime = lyricScrollTime,
                clock = reading,
                activeLines = activeElems,
                animatingLines = animating,
                creditsFocused = creditsFocused,
                stageRoles = planStage(stageItems(), currentTime),
            )
        }

        var scroll: ScrollDecision? = null
        if (viewport != null && lines.isNotEmpty() && (scrollResumeTime < now || scrollPos == -1.0)) {
            scroll = decideScroll(viewport, activeElems, lyricScrollTime, newLyricSelected, reading, options, lastSungLine)
        }

        if (wasUserScrolling && scrollResumeTime < now) {
            wasUserScrolling = false
        }

        return TimelineFrame(
            status = FrameStatus.SYNCED,
            lyricTime = currentTime,
            scrollTime = lyricScrollTime,
            clock = reading,
            activeLines = activeElems,
            animatingLines = animating,
            creditsFocused = creditsFocused,
            scroll = scroll,
            resumeAffordanceVisible = wasUserScrolling,
        )
    }

    /** The active lines at the positions the viewport reports. */
    private inner class PlacedLine(val index: Int, viewport: ScrollViewport) : ScrollTargetLine {
        override val time = lines[index].time
        override val duration = lines[index].duration
        override val position = viewport.linePositions[index]
        override val height = viewport.lineHeights[index]
    }

    private fun decideScroll(
        viewport: ScrollViewport,
        activeIndices: List<Int>,
        lyricScrollTime: Double,
        newLyricSelectedSoFar: Boolean,
        reading: ClockReading,
        options: ResolvedTickOptions,
        lastSungLine: BuiltLine?,
    ): ScrollDecision {
        var newLyricSelected = newLyricSelectedSoFar
        val activeElems = activeIndices.ifEmpty { listOf(0) }

        // Offset so lyrics appear towards the center of the screen.
        val scrollPosOffset = viewport.height * viewport.targetScrollRatio

        val lastActiveLyric = activeElems.last()

        // Ignore lyrics close to finishing unless it is the last active lyric.
        val endingThreshold = CoreSettings.LYRIC_ENDING_THRESHOLD_S.getNumberValue()
        val sungLines = activeElems
            .filterIndexed { position, index ->
                lyricScrollTime < lines[index].time + lines[index].duration - endingThreshold || position == activeElems.size - 1
            }
            .map { index -> PlacedLine(index, viewport) }

        val scrollAnchor = findScrollAnchor(sungLines, lyricScrollTime)
        val scrollTarget = computeActiveLinesScrollTop(sungLines, scrollAnchor, viewport.height, viewport.insets, scrollPosOffset)
        var targetScrollTop = scrollTarget.scrollTop
        val pinned = pinnedScrollLine
        if (pinned != null &&
            pinned != scrollAnchor.index &&
            (sungLines.none { it.index == pinned } || lyricScrollTime >= lines[pinned].time + lines[pinned].duration)
        ) {
            newLyricSelected = true
        }

        val credits = viewport.credits
        if (creditsFocused && credits != null && lastSungLine != null) {
            // Tall credits would otherwise carry the last line out of view while it is still being sung.
            targetScrollTop = min(
                credits.position + credits.height / 2 - scrollPosOffset,
                viewport.linePositions[lastSungLine.index] - viewport.insets.top,
            )
        }

        // Past either end the scroll is clamped, leaving the view aiming at a position it never reached
        // and re-aiming once per remaining line.
        targetScrollTop = clamp(targetScrollTop, 0.0, viewport.maxScrollTop)

        val scrollTop = viewport.scrollTop
        val timeUntilUpcomingScrollMs = (lines[lastActiveLyric].time - lyricScrollTime) * 1000
        // Only a scroll from where the timeline itself left the view keeps the theme's full stagger.
        val naturalAdvance = !reading.timeJumped && !wasUserScrolling && scrollPos >= 0 && abs(scrollTop - scrollPos) <= 2
        val moves = abs(scrollTop - targetScrollTop) > 2

        var action = ScrollAction.NONE
        if (options.smoothScroll &&
            !newLyricSelected &&
            !wasUserScrolling &&
            timeUntilUpcomingScrollMs > 0 &&
            timeUntilUpcomingScrollMs <= SCROLL_PREPARE_LEAD_MS &&
            lastActiveLyric !in lastScrollElements &&
            moves
        ) {
            action = ScrollAction.PREPARE
        }

        if (wasUserScrolling || newLyricSelected) {
            // Remember future lines only when their group is committed, so they cannot scroll
            // again at their start. Entering the lookahead window alone does not commit a group.
            lastScrollElements = activeElems.toSet()
            pinnedScrollLine = if (scrollTarget.overflowsBand) scrollAnchor.index else null
            scrollPos = targetScrollTop
            action = ScrollAction.COMMIT
        }

        return ScrollDecision(
            action = action,
            targetScrollTop = targetScrollTop,
            smooth = options.smoothScroll && moves,
            naturalAdvance = naturalAdvance,
            anchorIndex = scrollAnchor.index,
            lastActiveIndex = lastActiveLyric,
        )
    }

    private fun passiveScroll(isPlaying: Boolean, now: Double, viewport: ScrollViewport?): Double? {
        if (viewport == null) return null

        // -- Accumulate play time --------------------------
        if (passiveLastWallTime > 0 && isPlaying) {
            val wallDelta = (now - passiveLastWallTime) / 1000
            passiveScrollAccumulatedTime += min(wallDelta, 0.5)
        }
        passiveLastWallTime = now

        // -- User scroll interruption --------------------------
        if (scrollResumeTime > now) return null

        if (wasUserScrolling) {
            wasUserScrolling = false
            // Re-sync accumulated time to current scroll position so scroll continues from where user left off
            passiveScrollResumeTime(viewport.scrollTop, viewport.maxScrollTop, lines.size)?.let {
                passiveScrollAccumulatedTime = it
            }
        }

        return passiveScrollTarget(passiveScrollAccumulatedTime, lines.size, viewport.maxScrollTop)
    }

    /** `stageElements`: one item per line, then the credits when there are any. */
    fun stageItems(): List<StageItem> {
        val items = mutableListOf<StageItem>()
        var lastEnd = 0.0
        for (line in lines) {
            val end = line.time + line.duration
            lastEnd = max(lastEnd, end)
            items += StageItem(
                kind = when {
                    line.isInstrumental -> StageItemKind.INSTRUMENTAL
                    line.isBlank -> StageItemKind.BLANK
                    else -> StageItemKind.LINE
                },
                start = line.time,
                end = end,
            )
        }
        if (lyrics.credits != null) {
            items += StageItem(kind = StageItemKind.CREDITS, start = lastEnd, end = Double.POSITIVE_INFINITY)
        }
        return items
    }
}
