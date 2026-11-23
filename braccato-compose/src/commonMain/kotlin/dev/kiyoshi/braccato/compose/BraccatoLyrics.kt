// The scrolling lyrics view: upstream's `<braccato-lyrics>` element with the "scroll" layout. Once per
// frame it reads the player, ticks the core's LyricsTimeline and acts on what that decides: which
// lines read the clock, where the view scrolls, whether the user's own scroll is holding it.
//
// A scroll the timeline commits jumps the view and then lets every line ease in from where it was,
// each a little later the further it is from the line being sung (upstream's per-line scroll), so the
// lyric settles like a sheet rather than moving as a block.
package dev.kiyoshi.braccato.compose

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
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
import dev.kiyoshi.braccato.core.BuiltLine
import dev.kiyoshi.braccato.core.BuiltLyrics
import dev.kiyoshi.braccato.core.FrameStatus
import dev.kiyoshi.braccato.core.LyricsLayout
import dev.kiyoshi.braccato.core.LyricsTimeline
import dev.kiyoshi.braccato.core.ScrollAction
import dev.kiyoshi.braccato.core.ScrollItem
import dev.kiyoshi.braccato.core.ScrollPadding
import dev.kiyoshi.braccato.core.ScrollPaddingMeasurements
import dev.kiyoshi.braccato.core.ScrollViewport
import dev.kiyoshi.braccato.core.TickOptions
import dev.kiyoshi.braccato.core.computeScrollPadding
import dev.kiyoshi.braccato.core.effectiveEndTime
import dev.kiyoshi.braccato.core.lyricClockShiftS
import dev.kiyoshi.braccato.core.resolveTickOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

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
public fun BraccatoLyrics(
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
    /** Who sings: their names over the line being sung, when two or more have one. */
    voices: VoiceLabels? = null,
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
    val showVoices = voices?.useful == true

    var singing by remember(song) { mutableStateOf<Singing?>(null) }
    val textLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val glass = remember(textLayer) { VoiceGlass(textLayer) }
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
                // Who sings now; between lines the last ones stay.
                if (showVoices && frame.status == FrameStatus.SYNCED) singersAt(lines, effectiveEnds, t)?.let { if (it != singing) singing = it }
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
                    val (top, bottom) = scrollPaddingFor(theme, viewportHeight, positions, heights, credits, footerHeight[0])
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
                .then(
                    // The text into a layer for the name capsule to blur, and left out where it lies.
                    if (!showVoices) Modifier else Modifier.drawWithContent {
                        glass.text.record { this@drawWithContent.drawContent() }
                        val shape = glass.shape
                        if (shape == null) drawLayer(glass.text)
                        else clipPath(shape, androidx.compose.ui.graphics.ClipOp.Difference) { drawLayer(glass.text) }
                    },
                )
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
                        .followLineMotion(staggers, slides, theme, clock, staggerIndex = index),
                )
            }
            lyrics.credits?.let { names ->
                Credits(
                    names = names,
                    theme = theme,
                    focused = creditsFocused || lyrics.syncType == dev.kiyoshi.braccato.types.LyricSyncType.NONE,
                    modifier = Modifier
                        .onPlaced { coordinates ->
                            val y = coordinates.positionInParent().y.toDouble()
                            notePlaced(lines.size, y, credits[0])
                            credits[0] = y
                            credits[1] = coordinates.size.height.toDouble()
                        }
                        .followLineMotion(staggers, slides, theme, clock, staggerIndex = lines.size),
                )
            }
            footer?.let { content ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .onPlaced { footerHeight[0] = it.size.height.toDouble() }
                        // One step behind what comes before it, so that it moves with the text rather than ahead of it.
                        .followLineMotion(staggers, slides, theme, clock, staggerIndex = lines.size + 1, slideIndex = lines.size),
                ) { content() }
            }
            Spacer(Modifier.height(with(density) { paddingBottom.toFloat().toDp() }))
        }
        if (showVoices) {
            VoiceBlob(
                voices = voices!!,
                // Out of the way of the button back to the sung line, which stands where it does.
                singing = if (state.resumeAffordanceVisible) null else singing,
                theme = theme,
                glass = glass,
            )
        }
    }
}

/**
 * Who sings at lyric time [t]: the voices of the first line being sung and of those that really overlap
 * it. Null between lines.
 */
private fun singersAt(lines: List<BuiltLine>, effectiveEnds: List<Double>, t: Double): Singing? {
    val now = lines.indices.filter { !lines[it].isInstrumental && t >= lines[it].time && t < effectiveEnds[it] }
    if (now.isEmpty()) return null
    // A second voice only for a real overlap: lines that touch for a moment had the capsule start to split
    // and fold back at once, names over each other.
    val first = now.first()
    val together = now.filter { it == first || min(effectiveEnds[it], effectiveEnds[first]) - max(lines[it].time, lines[first].time) >= VOICE_OVERLAP_S }
    return Singing(together.map { lines[it].agent ?: "v1" }.distinct())
}

/**
 * The space above the first line and below the last, so that each can reach the scroll target: the
 * credits count as the last line when there are any. Pixels, top and bottom.
 */
private fun scrollPaddingFor(
    theme: BraccatoTheme,
    viewportHeight: Double,
    positions: DoubleArray,
    heights: DoubleArray,
    credits: DoubleArray,
    footerHeight: Double,
): ScrollPadding {
    val last = heights.lastIndex
    val hasCredits = credits[1] > 0
    return computeScrollPadding(
        ScrollPaddingMeasurements(
            viewportHeight = viewportHeight,
            targetScrollRatio = theme.targetScrollRatio,
            contentHeight = if (hasCredits) credits[0] + credits[1] else positions[last] + heights[last],
            firstLineHeight = heights[0],
            lastLineCentre = if (hasCredits) credits[0] + credits[1] / 2 else positions[last] + heights[last] / 2,
            lastLineHeight = if (hasCredits) credits[1] else heights[last],
            footerHeight = footerHeight,
        ),
    )
}
