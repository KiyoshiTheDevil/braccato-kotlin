package dev.kiyoshi.braccato.compose

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeCssTest {
    @Test fun `the lyric colours a theme sets take the place of the text colour at its opacities`() {
        val (theme, _) = BraccatoTheme.fromCss(":root { --blyrics-lyric-active-color: #ff0000; --blyrics-lyric-inactive-color: rgba(0, 0, 255, 0.5); }")
        assertEquals(Color(0xFFFF0000), theme.activeColor)
        assertEquals(Color(0f, 0f, 1f, 0.5f), theme.inactiveColor)
    }

    @Test fun `without them the text colour at its opacities stays`() {
        val (theme, _) = BraccatoTheme.fromCss(":root { --blyrics-text-color: #00ff00; --blyrics-inactive-opacity: 0.5; }")
        assertEquals(Color(0xFF00FF00), theme.activeColor)
        assertEquals(Color(0f, 1f, 0f, 0.5f), theme.inactiveColor)
    }
}
