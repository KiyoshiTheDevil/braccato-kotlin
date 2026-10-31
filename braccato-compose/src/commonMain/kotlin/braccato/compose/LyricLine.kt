// One lyric line: upstream's `.blyrics--line` and what view.ts and inject.ts build inside it. The
// main words, the background vocals under them at three quarters size, a romanization pill and a
// translation; or, for an instrumental line, the note.
package braccato.compose

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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

/** What hangs under a line. `injectTranslation` and `injectRomanization` upstream. */
@Immutable
class LineDecorations(
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

/** The words of one content line, wrapped the way upstream's word groups wrap: whole groups at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentFlow(
    content: ContentLine,
    style: TextStyle,
    measurer: TextMeasurer,
    context: WordContext,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val space = remember(style) { with(density) { measurer.measure(" ", style).size.width.toDp() } }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(space, alignment),
    ) {
        for (item in content.items) {
            if (item !is LineItem.Group) continue
            val first = item.words.firstOrNull() ?: continue
            Row(
                Modifier.graphicsLayer {
                    if (!context.live || !first.wobbles) return@graphicsLayer
                    val wobble = wobbleAt(context.theme, first.time, context.effectiveEnd, context.time())
                    translationX = wobble.translateEm * context.emPx
                    scaleX = wobble.scaleX
                },
            ) {
                for (word in item.words) WordView(word, style, measurer, context)
            }
        }
    }
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

            decorations.romanization?.let { romanization ->
                val style = remember(theme, density) {
                    with(density) {
                        TextStyle(
                            fontFamily = theme.translatedFontFamily ?: theme.fontFamily,
                            fontSize = (theme.translatedFontSize / 1.25f).toSp(),
                            fontWeight = theme.translatedFontWeight,
                            color = theme.translatedColor,
                        )
                    }
                }
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
                            ContentFlow(timed.main, style.copy(color = Color.Unspecified), measurer, timedContext, Alignment.Start)
                        } else {
                            BasicText(romanization.text, style = style)
                        }
                    }
                }
            }

            decorations.translation?.let { translation ->
                val style = remember(theme, density) {
                    with(density) {
                        TextStyle(
                            fontFamily = theme.translatedFontFamily ?: theme.fontFamily,
                            fontSize = theme.translatedFontSize.toSp(),
                            fontWeight = theme.translatedFontWeight,
                            color = theme.translatedColor,
                        )
                    }
                }
                val translatedDirection = if (translation.direction == TextDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides translatedDirection) {
                    BasicText(translation.text, Modifier.padding(top = 8.dp), style = style)
                }
            }
        }
    }
}
