// The songwriters' credits after the last line.
package dev.kiyoshi.braccato.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle

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
