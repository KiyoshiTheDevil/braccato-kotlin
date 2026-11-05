// The scrolling lyrics view: upstream's `<braccato-lyrics>` element with the "scroll" layout. Once per
// frame it reads the player, ticks the core's LyricsTimeline and acts on what that decides: which
// lines read the clock, where the view scrolls, whether the user's own scroll is holding it.
//
// A scroll the timeline commits jumps the view and then lets every line ease in from where it was,
// each a little later the further it is from the line being sung (upstream's per-line scroll), so the
// lyric settles like a sheet rather than moving as a block.
package braccato.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import braccato.core.BuiltLine
import braccato.core.BuiltLyrics
import braccato.core.FrameStatus
import braccato.core.LyricsLayout
import braccato.core.LyricsTimeline
import braccato.core.ScrollAction
import braccato.core.ScrollItem
import braccato.core.ScrollPaddingMeasurements
import braccato.core.ScrollViewport
import braccato.core.TickOptions
import braccato.core.computeScrollPadding
import braccato.core.effectiveEndTime
import braccato.core.lyricClockShiftS
import braccato.core.resolveTickOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.TimeSource

/** One reading of the player. */
@Immutable
data class PlaybackSnapshot(
    /** Seconds into the song. */
    val timeS: Double,
    val isPlaying: Boolean,
    val playbackRate: Double = 1.0,
    /**
     * When the time was read, on the view's wall clock, in milliseconds. Given, the view extrapolates
     * the time to now while playing; left out, the time is taken as current.
     */
    val sampledAtMs: Double? = null,
)

/** What the view reports back, and the one thing a host can ask of it. */
@Stable
class BraccatoLyricsState {
    /** The user scrolled the view away and autoscroll waits: offer the way back. */
    var resumeAffordanceVisible by mutableStateOf(false)
        internal set

    internal var timeline: LyricsTimeline? = null

    /** Autoscroll back, now. */
    fun resumeAutoscroll() {
        timeline?.resumeAutoscroll()
        resumeAffordanceVisible = false
    }
}

@Composable
fun rememberBraccatoLyricsState(): BraccatoLyricsState = remember { BraccatoLyricsState() }

/** The lyric time and the wall clock, as of the last frame. Read while drawing. */
internal class LyricClock {
    var lyricTime by mutableDoubleStateOf(0.0)
    var wallMs by mutableDoubleStateOf(0.0)
}

/** One committed scroll, which every line follows on its own schedule. */
private class ScrollStagger(
    val startMs: Double,
    val deltaPx: Double,
    val activeIndex: Int,
    val scale: Double,
)

/**
 * The lines a decoration moved, sliding from where they were to where it put them:
 * `captureDecorationSlide` upstream. One entry per line, then the credits.
 */
private class DecorationSlide(val shifts: DoubleArray) {
    /** Until the next frame anchors it, the slide holds every line where it was. */
    var startMs = Double.POSITIVE_INFINITY
    var anchored = false
}

private fun BraccatoTheme.slideOffset(slide: DecorationSlide, index: Int, nowMs: Double): Float {
    val shift = slide.shifts.getOrElse(index) { 0.0 }
    if (shift == 0.0) return 0f
    val p = progress(nowMs - slide.startMs, decorationEntryMs)
    return (shift * (1 - decorationEntryEasing.transform(p))).toFloat()
}

/** Below half a pixel, upstream does not slide a line. */
private const val DECORATION_MIN_SHIFT_PX = 0.5

/** How many frames a decoration change waits for the layout it causes. */
private const val DECORATION_SHIFT_FRAMES = 3

private fun BraccatoTheme.staggerOffset(stagger: ScrollStagger, index: Int, nowMs: Double): Float {
    val relative = index - stagger.activeIndex
    val activeMs = lineScrollDurationMs(0, stagger.deltaPx)
    val ownMs = lineScrollDurationMs(relative, stagger.deltaPx)
    val durationMs = activeMs + (ownMs - activeMs) * stagger.scale
    val p = progress(nowMs - stagger.startMs, durationMs)
    return (stagger.deltaPx * (1 - lyricScrollEasing.transform(p))).toFloat()
}

/**
 * What makes lyrics the same song to the view: each line's timing, words, voice and kind, and what is
 * shown around them. Lyrics built again with nothing but other translations or romanizations on their
 * lines answer the same, so the view keeps its scroll, its clock and its place and only the decorations
 * change, the way upstream's `songKey` swaps lyrics into the view on screen instead of rebuilding it.
 */
internal fun BuiltLyrics.songIdentity(): List<Any?> =
    listOf(syncType, allZero, noLyrics, credits, isStageDuet) +
        lines.map { listOf(it.time, it.duration, it.isInstrumental, it.agent, it.lyric.words, it.words.map { w -> w.time to w.duration }) }

/** How long before its start a line reads the clock: a letter's wave can begin before its word. */
private const val LIVE_LEAD_S = 1.0

/**
 * Synchronized lyrics, scrolling to follow the song.
 *
 * @param lyrics Built with `buildLyrics`. A new value is a new song.
 * @param playback Read once per frame.
 * @param tickOptions The user's offsets and preferences; the play state comes from [playback].
 * @param decorations What hangs under each line. Defaults to what the line's own lyric carries.
 * @param onSeek Called with a player time, in seconds, when a line is clicked. Null leaves lines inert.
 * @param wallClockMs The clock [PlaybackSnapshot.sampledAtMs] is on.
 * @param footer Shown at the very end of the scrolling text, after the credits, like the extension's
 *   `.blyrics-footer`: the place for where the lyrics came from.
 */
@Composable
fun BraccatoLyrics(
    lyrics: BuiltLyrics,
    playback: () -> PlaybackSnapshot,
    modifier: Modifier = Modifier,
    theme: BraccatoTheme = BraccatoTheme.Default,
    state: BraccatoLyricsState = rememberBraccatoLyricsState(),
    tickOptions: TickOptions = TickOptions(isPlaying = false),
    decorations: (BuiltLine) -> LineDecorations = ::lyricDecorations,
    onSeek: ((Double) -> Unit)? = null,
    wallClockMs: (() -> Double)? = null,
    footer: (@Composable () -> Unit)? = null,
    // False while out of sight: everything stays built and measured, but only a frame a second is asked for. Read
    // as a function so that a change rebuilds nothing, which for a whole song's words takes a while.
    active: () -> Boolean = { true },
) {
    val lines = lyrics.lines
    val footerHeight = remember { doubleArrayOf(0.0) }
    // The song on screen, so lyrics built again with the same lines keep the view as it is.
    val song = remember(lyrics) { lyrics.songIdentity() }
    val timeline = remember(song, theme.timingOffsets) { LyricsTimeline(lyrics, LyricsLayout.SCROLL, theme.timingOffsets) }
    state.timeline = timeline

    val monotonic = remember { TimeSource.Monotonic.markNow() }
    val now = wallClockMs ?: { monotonic.elapsedNow().inWholeMicroseconds / 1000.0 }
    val currentPlayback by rememberUpdatedState(playback)
    val currentOptions by rememberUpdatedState(tickOptions)
    val currentTheme by rememberUpdatedState(theme)

    val clock = remember(song) { LyricClock() }
    val effectiveEnds = remember(song) { lines.indices.map { effectiveEndTime(lines, it) } }
    val live = remember(song) { mutableStateListOf<Boolean>().apply { repeat(lines.size) { add(false) } } }
    val positions = remember(song) { DoubleArray(lines.size) }
    val heights = remember(song) { DoubleArray(lines.size) }
    val credits = remember(song) { doubleArrayOf(0.0, 0.0) }
    val staggers = remember(song) { mutableStateListOf<ScrollStagger>() }
    val layoutSignature = remember(song) { IntArray(1) }
    val slides = remember(song) { mutableStateListOf<DecorationSlide>() }
    // How many more frames a decoration that just came or went may take to move the lines.
    val decorationShiftFrames = remember(song) { IntArray(1) }
    // The line the view last followed, which a decoration's shift is anchored on; -1 before any.
    val scrollAnchor = remember(song) { intArrayOf(-1) }

    /**
     * A line was placed: if a decoration just moved it, it slides from where it was. Read during
     * layout rather than on the next frame, so the line is never drawn even once at its new place.
     */
    fun notePlaced(index: Int, y: Double, was: Double) {
        if (decorationShiftFrames[0] <= 0 || was == 0.0 && index > 0 || abs(was - y) < DECORATION_MIN_SHIFT_PX) return
        val slide = slides.lastOrNull()?.takeIf { !it.anchored }
            ?: DecorationSlide(DoubleArray(lines.size + 1)).also { slides += it }
        slide.shifts[index] = was - y
    }
    var creditsFocused by remember(song) { mutableStateOf(false) }
    var paddingTop by remember(song) { mutableDoubleStateOf(0.0) }
    var paddingBottom by remember(song) { mutableDoubleStateOf(0.0) }
    var viewportHeight by remember { mutableDoubleStateOf(0.0) }
    val scrollState = rememberScrollState()

    val userScroll = remember(timeline) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    timeline.noteUserScroll(now())
                    staggers.clear()
                    state.resumeAffordanceVisible = true
                }
                return Offset.Zero
            }
        }
    }

    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(timeline) {
        while (true) {
            // Out of sight once a second, so that nothing has to be caught up with when it comes back.
            if (!currentActive()) withTimeoutOrNull(1000) { snapshotFlow { currentActive() }.first { it } }
            withFrameNanos {
                val theme = currentTheme
                val snapshot = currentPlayback()
                val wall = now()
                val options = currentOptions.copy(
                    isPlaying = snapshot.isPlaying,
                    playbackRate = snapshot.playbackRate,
                    eventCreationTime = snapshot.sampledAtMs,
                )
                val measured = viewportHeight > 0 && lines.isNotEmpty() && heights.all { it > 0 }
                val viewport = if (measured) {
                    ScrollViewport(
                        height = viewportHeight,
                        scrollTop = scrollState.value.toDouble(),
                        maxScrollTop = scrollState.maxValue.toDouble(),
                        linePositions = positions.toList(),
                        lineHeights = heights.toList(),
                        credits = if (credits[1] > 0) ScrollItem(credits[0], credits[1]) else null,
                        targetScrollRatio = theme.targetScrollRatio,
                    )
                } else {
                    null
                }

                if (decorationShiftFrames[0] > 0) decorationShiftFrames[0]--

                // A decoration that came or went moved the lines. Keep the line the view follows where
                // it is on screen, by scrolling as far as it moved, and let every other line slide from
                // where it was to its new place around it. Upstream slides and then scrolls back, which
                // carries the line being sung away and back; this holds it still.
                for (slide in slides) {
                    if (slide.anchored) continue
                    val anchor = scrollAnchor[0]
                    val moved = if (anchor in slide.shifts.indices) -slide.shifts[anchor] else 0.0
                    val consumed = if (moved != 0.0) scrollState.dispatchRawDelta(moved.toFloat()).toDouble() else 0.0
                    for (i in slide.shifts.indices) {
                        slide.shifts[i] = if (theme.animations.decorationEntry) slide.shifts[i] + consumed else 0.0
                    }
                    slide.startMs = wall
                    slide.anchored = true
                }
                slides.removeAll { wall - it.startMs > theme.decorationEntryMs + 50 }

                if (viewport != null) {
                    // A layout that moved since the last frame has to be scrolled to again, as upstream's
                    // relayout arranges, or the active line stays where the old layout put it.
                    val signature = viewport.height.hashCode() * 31 +
                        viewport.linePositions.hashCode() * 17 + viewport.lineHeights.hashCode() + credits.contentHashCode()
                    if (signature != layoutSignature[0]) {
                        if (layoutSignature[0] != 0) timeline.noteRelayout()
                        layoutSignature[0] = signature
                    }
                }

                val frame = timeline.tick(snapshot.timeS, options, wall, viewport)
                clock.wallMs = wall
                if (frame.status == FrameStatus.SYNCED) clock.lyricTime = frame.lyricTime
                creditsFocused = frame.creditsFocused
                state.resumeAffordanceVisible = frame.resumeAffordanceVisible

                val t = clock.lyricTime
                val tail = max(theme.highlightFadeOutMs, theme.scaleTransitionMs) / 1000 + 0.2
                for (i in lines.indices) {
                    val isLive = frame.status == FrameStatus.SYNCED && t >= lines[i].time - LIVE_LEAD_S && t < effectiveEnds[i] + tail
                    if (live[i] != isLive) live[i] = isLive
                }

                staggers.removeAll { wall - it.startMs > 2500 }
                val scroll = frame.scroll
                if (scroll != null) scrollAnchor[0] = scroll.anchorIndex
                if (scroll != null && scroll.action == ScrollAction.COMMIT) {
                    val delta = scroll.targetScrollTop - scrollState.value
                    if (abs(delta) >= 1) {
                        val consumed = scrollState.dispatchRawDelta(delta.toFloat()).toDouble()
                        if (theme.animations.scroll && scroll.smooth && consumed != 0.0) {
                            val active = scroll.lastActiveIndex
                            val step = listOfNotNull(
                                positions.getOrNull(active - 1)?.let { abs(positions[active] - it) },
                                positions.getOrNull(active + 1)?.let { abs(positions[active] - it) },
                            ).minOrNull() ?: heights.getOrElse(active) { 0.0 }
                            staggers += ScrollStagger(wall, consumed, active, lineScrollStaggerScale(scroll.naturalAdvance, step, abs(consumed)))
                        }
                    }
                }
                frame.passiveScrollTop?.let { target -> scrollState.dispatchRawDelta((target - scrollState.value).toFloat()) }

                if (measured) {
                    val last = lines.lastIndex
                    val lastBottom = positions[last] + heights[last]
                    val hasCredits = credits[1] > 0
                    val (top, bottom) = computeScrollPadding(
                        ScrollPaddingMeasurements(
                            viewportHeight = viewportHeight,
                            targetScrollRatio = theme.targetScrollRatio,
                            contentHeight = if (hasCredits) credits[0] + credits[1] else lastBottom,
                            firstLineHeight = heights[0],
                            lastLineCentre = if (hasCredits) credits[0] + credits[1] / 2 else positions[last] + heights[last] / 2,
                            lastLineHeight = if (hasCredits) credits[1] else heights[last],
                            footerHeight = footerHeight[0],
                        ),
                    )
                    if (abs(top - paddingTop) > 1) paddingTop = top
                    if (abs(bottom - paddingBottom) > 1) paddingBottom = bottom
                } else if (viewportHeight > 0 && paddingTop == 0.0) {
                    paddingTop = viewportHeight * theme.targetScrollRatio
                    paddingBottom = viewportHeight
                }
            }
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val height = with(density) { maxHeight.toPx().toDouble() }
        if (viewportHeight != height) viewportHeight = height

        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(userScroll)
                .verticalScroll(scrollState),
        ) {
            Spacer(Modifier.height(with(density) { paddingTop.toFloat().toDp() }))
            lines.forEachIndexed { index, line ->
                val clickTime = if (onSeek != null && !lyrics.allZero) {
                    {
                        val shift = lyricClockShiftS(lyrics.syncType, theme.timingOffsets, resolveTickOptions(tickOptions))
                        onSeek(max(0.0, line.time - shift))
                    }
                } else {
                    null
                }
                LyricLine(
                    line = line,
                    effectiveEnd = effectiveEnds[index],
                    theme = theme,
                    live = live[index],
                    time = { clock.lyricTime },
                    // Asked again when the host hands over another function or a state the function reads
                    // changes, and passed on only when what it answers is different.
                    decorations = remember(line, decorations) {
                        derivedStateOf(structuralEqualityPolicy()) { decorations(line) }
                    }.value,
                    onDecorationShift = { decorationShiftFrames[0] = DECORATION_SHIFT_FRAMES },
                    onClick = clickTime,
                    modifier = Modifier
                        .onPlaced { coordinates ->
                            val y = coordinates.positionInParent().y.toDouble()
                            notePlaced(index, y, positions[index])
                            positions[index] = y
                            heights[index] = coordinates.size.height.toDouble()
                        }
                        .graphicsLayer {
                            if (staggers.isEmpty() && slides.isEmpty()) return@graphicsLayer
                            val wall = clock.wallMs
                            translationY = staggers.sumOf { theme.staggerOffset(it, index, wall).toDouble() }.toFloat() +
                                slides.sumOf { theme.slideOffset(it, index, wall).toDouble() }.toFloat()
                        },
                )
            }
            lyrics.credits?.let { names ->
                Credits(
                    names = names,
                    theme = theme,
                    focused = creditsFocused || lyrics.syncType == braccato.types.LyricSyncType.NONE,
                    modifier = Modifier
                        .onPlaced { coordinates ->
                            val y = coordinates.positionInParent().y.toDouble()
                            notePlaced(lines.size, y, credits[0])
                            credits[0] = y
                            credits[1] = coordinates.size.height.toDouble()
                        }
                        .graphicsLayer {
                            if (staggers.isEmpty() && slides.isEmpty()) return@graphicsLayer
                            val wall = clock.wallMs
                            translationY = staggers.sumOf { theme.staggerOffset(it, lines.size, wall).toDouble() }.toFloat() +
                                slides.sumOf { theme.slideOffset(it, lines.size, wall).toDouble() }.toFloat()
                        },
                )
            }
            footer?.let { content ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .onPlaced { footerHeight[0] = it.size.height.toDouble() }
                        // One step behind what comes before it, so that it moves with the text rather than ahead of it.
                        .graphicsLayer {
                            if (staggers.isEmpty() && slides.isEmpty()) return@graphicsLayer
                            val wall = clock.wallMs
                            translationY = staggers.sumOf { theme.staggerOffset(it, lines.size + 1, wall).toDouble() }.toFloat() +
                                slides.sumOf { theme.slideOffset(it, lines.size, wall).toDouble() }.toFloat()
                        },
                ) { content() }
            }
            Spacer(Modifier.height(with(density) { paddingBottom.toFloat().toDp() }))
        }
    }
}

/** `.blyrics-credits`: who wrote the song, after the last line, brighter once the song is over. */
@Composable
internal fun Credits(names: String, theme: BraccatoTheme, focused: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val opacity by animateFloatAsState(
        if (focused) theme.creditsFocusedOpacity else theme.creditsOpacity,
        tween(350, easing = theme.decorationEntryEasing),
    )
    val style = remember(theme, density) {
        with(density) {
            TextStyle(
                fontFamily = theme.fontFamily,
                fontSize = theme.creditsFontSize.toSp(),
                fontWeight = theme.fontWeight,
                color = theme.textColor,
            )
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = opacity
                scaleX = theme.scale
                scaleY = theme.scale
                transformOrigin = transformOriginFor(LineSide.START)
            }
            .padding(vertical = theme.linePadding, horizontal = theme.fontSize * 0.25f),
    ) {
        BasicText("${theme.creditsLabel} $names", style = style)
    }
}
