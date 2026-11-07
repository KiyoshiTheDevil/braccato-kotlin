// One lyric line: upstream's `.blyrics--line` and what view.ts and inject.ts build inside it. The
// main words, the background vocals under them at three quarters size, a romanization pill and a
// translation; or, for an instrumental line, the note.
package braccato.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import braccato.core.BuiltLine
import braccato.core.BuiltRomanization
import braccato.core.BuiltTranslation
import braccato.core.ContentLine
import braccato.core.LineItem
import braccato.core.TextDirection
import braccato.core.buildRomanization
import braccato.core.buildTranslation

/** What hangs under a line. `injectTranslation` and `injectRomanization` upstream. Equal when it shows the same. */
@Immutable
data class LineDecorations(
    val translation: BuiltTranslation? = null,
    val romanization: BuiltRomanization? = null,
) {
    companion object {
        val None = LineDecorations()
    }
}

/**
 * The decorations a line's own lyric carries: its first translation and its romanization, timed when
 * the provider timed it. A host that streams them in later passes its own instead.
 */
fun lyricDecorations(line: BuiltLine): LineDecorations {
    val content = line.content ?: return LineDecorations.None
    val lyric = line.lyric
    val translationText = lyric.translation?.text ?: lyric.translations?.values?.firstOrNull()
    val translationLang = lyric.translation?.lang ?: lyric.translations?.keys?.firstOrNull()
    return LineDecorations(
        translation = translationText?.let { buildTranslation(content.main.text, it, translationLang) },
        romanization = lyric.romanization?.let { buildRomanization(content.main.text, line.language, it, lyric.timedRomanization) },
    )
}

/** Which side a line sits on, on screen: `[data-agent]` and `[data-direction]` upstream. */
internal enum class LineSide { START, RIGHT, CENTER }

internal fun lineSide(line: BuiltLine): LineSide = when {
    line.agent == "v1000" -> LineSide.CENTER
    line.agent == "v2" || line.agent == "v3" || line.direction == TextDirection.RTL -> LineSide.RIGHT
    else -> LineSide.START
}

/** The horizontal alignment for a side, under the line's own layout direction. */
private fun alignmentFor(side: LineSide, rtl: Boolean): Alignment.Horizontal = when (side) {
    LineSide.CENTER -> Alignment.CenterHorizontally
    LineSide.RIGHT -> if (rtl) Alignment.Start else Alignment.End
    LineSide.START -> if (rtl) Alignment.End else Alignment.Start
}

internal fun transformOriginFor(side: LineSide): TransformOrigin = when (side) {
    LineSide.START -> TransformOrigin(0f, 0.5f)
    LineSide.RIGHT -> TransformOrigin(1f, 0.5f)
    LineSide.CENTER -> TransformOrigin(0.5f, 0.5f)
}

internal fun BraccatoTheme.lineTextStyle(density: androidx.compose.ui.unit.Density, sizeFactor: Float = 1f, weight: FontWeight = fontWeight): TextStyle =
    with(density) {
        TextStyle(
            fontFamily = fontFamily,
            fontSize = (fontSize * sizeFactor).toSp(),
            fontWeight = weight,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }

/** What the flow layout decided for one word, read by its wobble each frame. */
private class FlowSlot {
    /** Whether the word's group wobbles: it was laid out whole. */
    var wobbles = false

    /** The time of the group's first syllable, which the group wobbles from. */
    var groupTime = 0.0

    /** How far the word's centre is from its group's centre, which a group's scaleX moves it by. */
    var fromGroupCentre = 0f
}

/**
 * The words of one content line, wrapped the way upstream wraps them.
 *
 * A group of syllables with no space between them is an inline-block upstream, which moves to the
 * next line whole and wobbles as one. A long group (`.blyrics-word-group-long`) is inline instead,
 * so it fills the line and breaks between syllables where the line breaking rules allow, and does not
 * wobble, since a CSS transform does not apply to an inline box. One addition: a short group too wide
 * for even an empty line breaks the same way rather than running out of the view, which upstream
 * lets it do. In practice that is CJK, written without spaces.
 *
 * Each word is measured at its full width, whatever room is left, so words never squeeze into each
 * other at the end of a line.
 */
@Composable
private fun ContentFlow(
    content: ContentLine,
    style: TextStyle,
    measurer: TextMeasurer,
    context: WordContext,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    val spacePx = remember(style) { measurer.measure(" ", style).size.width }
    // Each group, and whether a space comes before it.
    val groups = remember(content) {
        var pendingSpace = false
        buildList {
            for (item in content.items) {
                when (item) {
                    is LineItem.Space -> pendingSpace = true
                    is LineItem.Group -> if (item.words.isNotEmpty()) {
                        add(item.splitForBreaking() to pendingSpace)
                        pendingSpace = false
                    }
                }
            }
        }
    }
    val words = remember(groups) { groups.flatMap { it.first.words } }
    val slots = remember(words) { List(words.size) { FlowSlot() } }

    Layout(
        content = {
            words.forEachIndexed { index, word ->
                val slot = slots[index]
                WordView(
                    word,
                    style,
                    measurer,
                    context,
                    Modifier.graphicsLayer {
                        if (!context.live || !slot.wobbles) return@graphicsLayer
                        val wobble = wobbleAt(context.theme, slot.groupTime, context.effectiveEnd, context.time())
                        translationX = wobble.translateEm * context.emPx + slot.fromGroupCentre * (wobble.scaleX - 1)
                        scaleX = wobble.scaleX
                    },
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        // Positions along each line, in reading order.
        val lines = mutableListOf(mutableListOf<Pair<Int, Int>>())
        val lineWidths = mutableListOf(0)
        var x = 0
        fun newLine() {
            lineWidths[lineWidths.lastIndex] = x
            lines += mutableListOf<Pair<Int, Int>>()
            lineWidths += 0
            x = 0
        }
        fun put(index: Int) {
            lines.last() += index to x
            x += placeables[index].width
        }

        var first = 0
        for ((group, spaceBefore) in groups) {
            val indices = first until first + group.words.size
            first += group.words.size
            val groupWidth = indices.sumOf { placeables[it].width }
            if (spaceBefore && x > 0) x += spacePx

            if (!group.isLong && groupWidth <= maxWidth) {
                if (x + groupWidth > maxWidth && x > 0) newLine()
                val groupStart = x
                for (i in indices) {
                    slots[i].wobbles = true
                    slots[i].groupTime = group.words[0].time
                    slots[i].fromGroupCentre = (x + placeables[i].width / 2f) - (groupStart + groupWidth / 2f)
                    put(i)
                }
                continue
            }

            // Inline: fill the line, breaking between syllables where the rules allow, and anywhere at
            // all within a run that is wider than a whole line.
            val segments = mutableListOf(mutableListOf(indices.first))
            for (i in indices.drop(1)) {
                if (canBreakBetween(words[i - 1].text, words[i].text)) segments += mutableListOf(i) else segments.last() += i
            }
            for (segment in segments) {
                val segmentWidth = segment.sumOf { placeables[it].width }
                if (x + segmentWidth > maxWidth && x > 0) newLine()
                for (i in segment) {
                    slots[i].wobbles = false
                    if (segmentWidth > maxWidth && x + placeables[i].width > maxWidth && x > 0) newLine()
                    put(i)
                }
            }
        }
        lineWidths[lineWidths.lastIndex] = x

        val width = lineWidths.max().coerceIn(constraints.minWidth, maxWidth)
        val lineHeights = lines.map { line -> line.maxOfOrNull { (i, _) -> placeables[i].height } ?: 0 }
        val height = lineHeights.sum()
        val rtl = layoutDirection == LayoutDirection.Rtl
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { l, line ->
                val lineLeft = alignment.align(lineWidths[l], width, layoutDirection)
                for ((i, along) in line) {
                    val placeable = placeables[i]
                    val left = if (rtl) lineLeft + lineWidths[l] - along - placeable.width else lineLeft + along
                    placeable.place(left, y)
                }
                y += lineHeights[l]
            }
        }
    }
}

/**
 * A translation or romanization coming and going. In, it fades and drops into place as upstream's
 * `blyrics-decoration-in` does (350 ms, from 0.4em above). Out, when the host takes it away, it fades
 * and lifts back the same distance over the same time before it leaves the layout; upstream drops it
 * at once. A new value in its place
 * comes in afresh, as a decoration upstream injects again does. [onShift] is told whenever what is
 * laid out changes, so the view can slide the lines it moved.
 */
@Composable
private fun <T : Any> AnimatedDecoration(
    value: T?,
    theme: BraccatoTheme,
    emPx: Float,
    onShift: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(value) }
    var leaving by remember { mutableStateOf(false) }
    val animate = theme.animations.decorationEntry
    // 0 to 1 over the entry, or over the exit while leaving.
    val progress = remember { Animatable(if (value != null && !animate) 1f else 0f) }
    val durationMs = theme.decorationEntryMs.toInt()
    LaunchedEffect(value, animate) {
        if (value != null) {
            if (value != shown || leaving) {
                shown = value
                leaving = false
                progress.snapTo(0f)
            }
            if (animate) progress.animateTo(1f, tween(durationMs, easing = LinearEasing)) else progress.snapTo(1f)
        } else if (shown != null) {
            leaving = true
            progress.snapTo(0f)
            if (animate) progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
            shown = null
            leaving = false
        }
    }
    val laidOut = shown
    val previous = remember { arrayOf<Any?>(laidOut) }
    SideEffect {
        if (previous[0] != laidOut) {
            previous[0] = laidOut
            onShift()
        }
    }
    if (laidOut == null) return
    Box(
        Modifier.graphicsLayer {
            // Leaving runs the entry's curve forwards from shown to gone, so it starts at once.
            val eased = theme.decorationEntryEasing.transform(progress.value)
            val present = if (leaving) 1 - eased else eased
            alpha = present
            translationY = -0.4f * emPx * (1 - present)
        },
    ) { content(laidOut) }
}

/**
 * @param effectiveEnd When the line stops being sung: the next line's start, or its own end.
 * @param live Whether the line reads the clock; a line well before or after its time draws at rest.
 * @param time The lyric time, in seconds. Read while drawing, never while composing.
 */
@Composable
internal fun LyricLine(
    line: BuiltLine,
    effectiveEnd: Double,
    theme: BraccatoTheme,
    live: Boolean,
    time: () -> Double,
    decorations: LineDecorations,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    side: LineSide = lineSide(line),
    verticalPadding: Dp = theme.linePadding,
    onDecorationShift: () -> Unit = {},
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val rtl = line.direction == TextDirection.RTL
    val emPx = with(density) { theme.fontSize.toPx() }
    val mainStyle = remember(theme, density) { theme.lineTextStyle(density) }
    val backgroundStyle = remember(theme, density) { theme.lineTextStyle(density, 0.75f, FontWeight.Medium) }
    val glowRadiusPx = with(density) { theme.glowRadiusFrom.toPx() }

    val mainContext = WordContext(theme, effectiveEnd, live, time, emPx, glowRadiusPx, theme.inactiveColor)
    // Background words are a shade fainter: 78 % of the inactive colour.
    val backgroundBase = theme.inactiveColor.copy(alpha = theme.inactiveColor.alpha * 0.78f)
    val backgroundContext = WordContext(theme, effectiveEnd, live, time, emPx * 0.75f, glowRadiusPx * 0.75f, backgroundBase)

    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        val alignment = alignmentFor(side, rtl)
        Column(
            modifier
                .fillMaxWidth()
                .then(clickModifier)
                .graphicsLayer {
                    val scale = if (live) lineScaleAt(theme, line.time, effectiveEnd, time()) else theme.scale
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = transformOriginFor(side)
                }
                .padding(vertical = verticalPadding, horizontal = theme.fontSize * 0.25f),
            horizontalAlignment = alignment,
        ) {
            val content = line.content
            if (line.isInstrumental || content == null) {
                InstrumentalNote(
                    size = theme.fontSize,
                    activeColor = theme.activeColor,
                    inactiveColor = theme.inactiveColor,
                    rtl = side == LineSide.RIGHT,
                    state = { if (live) instrumentalAt(theme, line.time, line.duration, effectiveEnd, time()) else null },
                )
                return@Column
            }

            ContentFlow(content.main, mainStyle, measurer, mainContext, alignment)
            content.background?.let { background ->
                ContentFlow(background, backgroundStyle, measurer, backgroundContext, alignment, Modifier.padding(top = theme.fontSize * 0.1f))
            }

            // Which way wrapped decorations line up, on the screen: a right-hand line's translation ran
            // over two lines flush left beneath it (Kodama, 2026-11-07). Absolute, since the decorations
            // set a layout direction of their own.
            val toRight = (side == LineSide.RIGHT && !rtl) || (side == LineSide.START && rtl)
            val decorationAlign = when {
                side == LineSide.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                toRight -> androidx.compose.ui.text.style.TextAlign.Right
                else -> androidx.compose.ui.text.style.TextAlign.Left
            }
            val romanizedStyle = remember(theme, density) {
                with(density) {
                    TextStyle(
                        fontFamily = theme.translatedFontFamily ?: theme.fontFamily,
                        fontSize = (theme.translatedFontSize / 1.25f).toSp(),
                        fontWeight = theme.translatedFontWeight,
                        color = theme.translatedColor,
                    )
                }
            }
            AnimatedDecoration(decorations.romanization, theme, with(density) { (theme.translatedFontSize / 1.25f).toPx() }, onDecorationShift) { romanization ->
                val romanizedDirection = if (romanization.direction == TextDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides romanizedDirection) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        val timed = romanization.content
                        if (timed != null) {
                            val timedContext = WordContext(theme, effectiveEnd, live, time, emPx / 1.25f, glowRadiusPx / 1.25f, theme.inactiveColor)
                            ContentFlow(timed.main, romanizedStyle.copy(color = Color.Unspecified), measurer, timedContext, Alignment.Start)
                        } else {
                            MotionText(romanization.text, romanizedStyle.copy(textAlign = decorationAlign), live)
                        }
                    }
                }
            }

            val translatedStyle = remember(theme, density) {
                with(density) {
                    TextStyle(
                        fontFamily = theme.translatedFontFamily ?: theme.fontFamily,
                        fontSize = theme.translatedFontSize.toSp(),
                        fontWeight = theme.translatedFontWeight,
                        color = theme.translatedColor,
                    )
                }
            }
            AnimatedDecoration(decorations.translation, theme, with(density) { theme.translatedFontSize.toPx() }, onDecorationShift) { translation ->
                val translatedDirection = if (translation.direction == TextDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides translatedDirection) {
                    MotionText(translation.text, translatedStyle.copy(textAlign = decorationAlign), live, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
