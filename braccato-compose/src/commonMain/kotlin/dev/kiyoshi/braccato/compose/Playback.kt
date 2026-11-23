// What the view reads from the player each frame, and the time it draws from.
package dev.kiyoshi.braccato.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue

/** One reading of the player. */
@Immutable
public data class PlaybackSnapshot(
    /** Seconds into the song. */
    val timeS: Double,
    /** Whether the song is playing. */
    val isPlaying: Boolean,
    /** How fast it plays, 1 for normal speed. */
    val playbackRate: Double = 1.0,
    /**
     * When the time was read, on the view's wall clock, in milliseconds. Given, the view extrapolates
     * the time to now while playing; left out, the time is taken as current.
     */
    val sampledAtMs: Double? = null,
)

/** The lyric time and the wall clock, as of the last frame. Read while drawing. */
internal class LyricClock {
    var lyricTime by mutableDoubleStateOf(0.0)
    var wallMs by mutableDoubleStateOf(0.0)
}
