// What the scrolling view tells its host, and the one thing the host can ask of it.
package dev.kiyoshi.braccato.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kiyoshi.braccato.core.LyricsTimeline

/** What the view reports back, and the one thing a host can ask of it. */
@Stable
public class BraccatoLyricsState {
    /** The user scrolled the view away and autoscroll waits: offer the way back. */
    public var resumeAffordanceVisible: Boolean by mutableStateOf(false)
        internal set

    internal var timeline: LyricsTimeline? = null

    /** Autoscroll back, now. */
    public fun resumeAutoscroll() {
        timeline?.resumeAutoscroll()
        resumeAffordanceVisible = false
    }
}

/** A [BraccatoLyricsState] that lives as long as the composition. */
@Composable
public fun rememberBraccatoLyricsState(): BraccatoLyricsState = remember { BraccatoLyricsState() }
