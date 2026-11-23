// Port of packages/core/src/types.ts and the word states in constants.ts, the parts that are not the
// DOM. The host adapter and the renderer contract describe a DOM view and are not carried over; the
// Compose renderer defines its own.
package braccato.core

import braccato.types.internal.InternalBraccatoApi

// -- Word state --------------------------------------------

/**
 * Where a word stands at a time: not yet sung, being sung, or sung.
 *
 * @property value The name upstream gives it.
 */
public enum class WordState(public val value: String) {
    UPCOMING("upcoming"),
    ACTIVE("active"),
    PAST("past"),
}

// -- Layout --------------------------------------------

/** `SCROLL` is the scrolling lyrics view. `STAGE` shows only what is being sung. */
public enum class LyricsLayout { SCROLL, STAGE }

// -- Ticking --------------------------------------------

/**
 * The play state is the only thing a tick cannot be given a sensible default for. Everything else
 * describes a setting the consumer may not have, so it may be left out.
 */
public data class TickOptions(
    /** Whether the song is playing. */
    val isPlaying: Boolean,
    /**
     * Wall clock timestamp of the player snapshot the time came from, in milliseconds. Defaults to
     * -1, which reads as a time that was not sampled from a live player.
     */
    val eventCreationTime: Double? = null,
    /** Defaults to true. */
    val smoothScroll: Boolean? = null,
    /** User offsets, in seconds, subtracted from the playback time before matching. Each defaults to 0. */
    val globalLyricOffset: Double? = null,
    /** The song's own lyric offset, in seconds, which moves the lyrics later. */
    val lyricOffset: Double? = null,
    /** Taken off the theme's word synced timing offset, in seconds. */
    val richsyncOffsetTrim: Double? = null,
    /** Taken off the theme's line synced timing offset, in seconds. */
    val lineOffsetTrim: Double? = null,
    /** Whether the user has passive scrolling switched on. Only unsynced lyrics use it. Defaults to false. */
    val passiveScrollEnabled: Boolean? = null,
    /**
     * How fast the song moves through its own timeline, as a multiple of real time. Defaults to 1, and
     * zero or less reads as 1 because `isPlaying` is what says a song is stopped.
     */
    val playbackRate: Double? = null,
    /**
     * Whether a paused clock that moves by less than the jump threshold resets the highlight to the new
     * time, as an editor scrubbing a paused song needs. Defaults to false (1.18.0).
     */
    val followPausedSeeks: Boolean? = null,
)

/**
 * A tick with nothing left out. The engine reads every field unconditionally, so the defaults are
 * filled in at the edge rather than guessed at each read.
 *
 * @suppress Internal to the braccato modules.
 */
@InternalBraccatoApi public data class ResolvedTickOptions(
    val isPlaying: Boolean,
    val eventCreationTime: Double,
    val smoothScroll: Boolean,
    val globalLyricOffset: Double,
    val lyricOffset: Double,
    val richsyncOffsetTrim: Double,
    val lineOffsetTrim: Double,
    val passiveScrollEnabled: Boolean,
    val playbackRate: Double,
    val followPausedSeeks: Boolean = false,
)
