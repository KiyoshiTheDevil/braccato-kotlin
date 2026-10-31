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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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

private fun BraccatoTheme.staggerOffset(stagger: ScrollStagger, index: Int, nowMs: Double): Float {
    val relative = index - stagger.activeIndex
    val activeMs = lineScrollDurationMs(0, stagger.deltaPx)
    val ownMs = lineScrollDurationMs(relative, stagger.deltaPx)
    val durationMs = activeMs + (ownMs - activeMs) * stagger.scale
    val p = progress(nowMs - stagger.startMs, durationMs)
    return (stagger.deltaPx * (1 - lyricScrollEasing.transform(p))).toFloat()
}

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
) {
    val lines = lyrics.lines
    val timeline = remember(lyrics, theme.timingOffsets) { LyricsTimeline(lyrics, LyricsLayout.SCROLL, theme.timingOffsets) }
    state.timeline = timeline

    val monotonic = remember { TimeSource.Monotonic.markNow() }
    val now = wallClockMs ?: { monotonic.elapsedNow().inWholeMicroseconds / 1000.0 }
    val currentPlayback by rememberUpdatedState(playback)
    val currentOptions by rememberUpdatedState(tickOptions)
    val currentTheme by rememberUpdatedState(theme)

    val clock = remember(lyrics) { LyricClock() }
    val effectiveEnds = remember(lyrics) { lines.indices.map { effectiveEndTime(lines, it) } }
    val live = remember(lyrics) { mutableStateListOf<Boolean>().apply { repeat(lines.size) { add(false) } } }
    val positions = remember(lyrics) { DoubleArray(lines.size) }
    val heights = remember(lyrics) { DoubleArray(lines.size) }
    val credits = remember(lyrics) { doubleArrayOf(0.0, 0.0) }
    val staggers = remember(lyrics) { mutableStateListOf<ScrollStagger>() }
    val layoutSignature = remember(lyrics) { IntArray(1) }
    var creditsFocused by remember(lyrics) { mutableStateOf(false) }
    var paddingTop by remember(lyrics) { mutableDoubleStateOf(0.0) }
    var paddingBottom by remember(lyrics) { mutableDoubleStateOf(0.0) }
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

    LaunchedEffect(timeline) {
        while (true) {
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
                            footerHeight = 0.0,
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
                    decorations = remember(line) { decorations(line) },
                    onClick = clickTime,
                    modifier = Modifier
                        .onPlaced { coordinates ->
                            positions[index] = coordinates.positionInParent().y.toDouble()
                            heights[index] = coordinates.size.height.toDouble()
                        }
                        .graphicsLayer {
                            if (staggers.isEmpty()) return@graphicsLayer
                            val wall = clock.wallMs
                            translationY = staggers.sumOf { theme.staggerOffset(it, index, wall).toDouble() }.toFloat()
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
                            credits[0] = coordinates.positionInParent().y.toDouble()
                            credits[1] = coordinates.size.height.toDouble()
                        }
                        .graphicsLayer {
                            if (staggers.isEmpty()) return@graphicsLayer
                            val wall = clock.wallMs
                            translationY = staggers.sumOf { theme.staggerOffset(it, lines.size, wall).toDouble() }.toFloat()
                        },
                )
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
