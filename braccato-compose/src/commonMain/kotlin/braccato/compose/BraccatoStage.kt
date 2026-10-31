// The stage view: upstream's "stage" layout (stage.ts, `applyStage` in engine.ts). Only what is
// being sung shows: the current line on the stage's floor, the line before it above while the two
// overlap, the next one rising in from below. The core plans the roles and the places; this moves the
// lines there with upstream's motion and fades.
package braccato.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import braccato.core.BuiltLine
import braccato.core.BuiltLyrics
import braccato.core.FrameStatus
import braccato.core.LyricsLayout
import braccato.core.LyricsTimeline
import braccato.core.StageBox
import braccato.core.StageGeometry
import braccato.core.StageMetrics
import braccato.core.StageRole
import braccato.core.TickOptions
import braccato.core.effectiveEndTime
import braccato.core.layoutStage
import kotlin.time.TimeSource

private val STAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val STAGE_MOTION_MS = 380
private const val STAGE_FADE_IN_MS = 300
private const val STAGE_FADE_IN_DELAY_MS = 70
private const val STAGE_FADE_OUT_MS = 220
private const val STAGE_GAP_EM = 0.32f

/**
 * Centred, as subtitles are; in a duet each singer takes a side, mirrored for right-to-left lines,
 * and everyone together stays in the middle.
 */
internal fun stageSide(line: BuiltLine, isDuet: Boolean): LineSide {
    if (!isDuet || line.agent == "v1000") return LineSide.CENTER
    val secondVoice = line.agent == "v2" || line.agent == "v3"
    val rtl = line.direction == braccato.core.TextDirection.RTL
    return if (secondVoice != rtl) LineSide.RIGHT else LineSide.START
}

/**
 * Synchronized lyrics as subtitles: only the lines being sung, sitting at the bottom of the space.
 *
 * @param onStageLayout Where the lines being sung sit, in this view's coordinates, or null when nothing
 *   sung is on stage. A host draws its own backdrop from it.
 */
@Composable
fun BraccatoStage(
    lyrics: BuiltLyrics,
    playback: () -> PlaybackSnapshot,
    modifier: Modifier = Modifier,
    theme: BraccatoTheme = BraccatoTheme.Default,
    tickOptions: TickOptions = TickOptions(isPlaying = false),
    decorations: (BuiltLine) -> LineDecorations = ::lyricDecorations,
    onStageLayout: ((StageBox?) -> Unit)? = null,
    wallClockMs: (() -> Double)? = null,
) {
    val lines = lyrics.lines
    val timeline = remember(lyrics, theme.timingOffsets) { LyricsTimeline(lyrics, LyricsLayout.STAGE, theme.timingOffsets) }
    val items = remember(timeline) { timeline.stageItems() }
    val monotonic = remember { TimeSource.Monotonic.markNow() }
    val now = wallClockMs ?: { monotonic.elapsedNow().inWholeMicroseconds / 1000.0 }
    val currentPlayback by rememberUpdatedState(playback)
    val currentOptions by rememberUpdatedState(tickOptions)
    val currentOnStageLayout by rememberUpdatedState(onStageLayout)

    val clock = remember(lyrics) { LyricClock() }
    val effectiveEnds = remember(lyrics) { lines.indices.map { effectiveEndTime(lines, it) } }
    var roles by remember(lyrics) { mutableStateOf(List(items.size) { StageRole.QUEUED }) }
    val heights = remember(lyrics) { mutableStateListOf<Float>().apply { repeat(items.size) { add(0f) } } }
    val widths = remember(lyrics) { FloatArray(items.size) }
    var lastBox by remember(lyrics) { mutableStateOf<StageBox?>(null) }
    // Sides only when both are sung, so a song with one singer stays centred whichever voice it is.
    val isDuet = remember(lyrics) {
        val sung = lines.filter { !it.isInstrumental && !it.isBlank && it.agent != "v1000" }
        val onRight = { line: BuiltLine -> line.agent == "v2" || line.agent == "v3" }
        sung.any(onRight) && sung.any { !onRight(it) }
    }

    LaunchedEffect(timeline) {
        while (true) {
            withFrameNanos {
                val snapshot = currentPlayback()
                val options = currentOptions.copy(isPlaying = snapshot.isPlaying, playbackRate = snapshot.playbackRate, eventCreationTime = snapshot.sampledAtMs)
                val frame = timeline.tick(snapshot.timeS, options, now(), null)
                clock.wallMs = now()
                if (frame.status == FrameStatus.SYNCED) {
                    clock.lyricTime = frame.lyricTime
                    frame.stageRoles?.let { if (it != roles) roles = it }
                }
            }
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val stageHeight = with(density) { maxHeight.toPx() }
        val gap = with(density) { theme.fontSize.toPx() } * STAGE_GAP_EM
        val metrics = items.indices.map { StageMetrics(height = heights[it].toDouble(), left = 0.0, width = widths[it].toDouble(), originX = 0.0) }
        val placements = remember(roles, metrics, stageHeight) {
            layoutStage(roles, items, metrics, List(items.size) { null }, StageGeometry(stageHeight.toDouble(), gap.toDouble(), theme.activeScale.toDouble()))
        }
        if (placements.box != lastBox) {
            lastBox = placements.box
            currentOnStageLayout?.invoke(placements.box)
        }

        val current = roles.indexOf(StageRole.CURRENT)
        // The lines that can be seen or are on their way in or out: the one leaving, the one on stage
        // with the one above it, and the next one waiting below.
        val shown = if (current < 0) listOf(0) else ((current - 2)..(current + 1)).filter { it in items.indices }

        for (index in shown) {
            key(index) {
                val role = roles[index]
                val visible = role == StageRole.CURRENT || role == StageRole.PREVIOUS
                val targetY = placements.placements[index].y.toFloat()
                val y = remember { Animatable(targetY) }
                val alpha = remember { Animatable(if (visible) 1f else 0f) }
                LaunchedEffect(targetY) {
                    if (!theme.animations.lineScale) y.snapTo(targetY) else y.animateTo(targetY, tween(STAGE_MOTION_MS, easing = STAGE_EASING))
                }
                LaunchedEffect(visible) {
                    alpha.animateTo(
                        if (visible) 1f else 0f,
                        if (visible) tween(STAGE_FADE_IN_MS, STAGE_FADE_IN_DELAY_MS) else tween(STAGE_FADE_OUT_MS),
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0))
                            if (heights[index] != placeable.height.toFloat()) heights[index] = placeable.height.toFloat()
                            widths[index] = placeable.width.toFloat()
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            translationY = y.value
                            this.alpha = alpha.value
                        },
                ) {
                    if (index < lines.size) {
                        val line = lines[index]
                        LyricLine(
                            line = line,
                            effectiveEnd = effectiveEnds[index],
                            theme = theme,
                            live = role != StageRole.QUEUED,
                            time = { clock.lyricTime },
                            decorations = remember(line) { decorations(line) },
                            onClick = null,
                            side = stageSide(line, isDuet),
                            verticalPadding = theme.fontSize * 0.1f,
                        )
                    } else {
                        lyrics.credits?.let { Credits(it, theme, focused = role == StageRole.CURRENT) }
                    }
                }
            }
        }
    }
}
