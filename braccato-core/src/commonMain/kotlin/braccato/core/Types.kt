// Port of packages/core/src/types.ts and the word states in constants.ts, the parts that are not the
// DOM. The host adapter and the renderer contract describe a DOM view and are not carried over; the
// Compose renderer defines its own.
package braccato.core

// -- Word state --------------------------------------------

enum class WordState(val value: String) {
    UPCOMING("upcoming"),
    ACTIVE("active"),
    PAST("past"),
}

// -- Layout --------------------------------------------

/** `SCROLL` is the scrolling lyrics view. `STAGE` shows only what is being sung. */
enum class LyricsLayout { SCROLL, STAGE }

// -- Ticking --------------------------------------------

/**
 * The play state is the only thing a tick cannot be given a sensible default for. Everything else
 * describes a setting the consumer may not have, so it may be left out.
 */
data class TickOptions(
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
    val lyricOffset: Double? = null,
    val richsyncOffsetTrim: Double? = null,
    val lineOffsetTrim: Double? = null,
    /** Whether the user has passive scrolling switched on. Only unsynced lyrics use it. Defaults to false. */
    val passiveScrollEnabled: Boolean? = null,
    /**
     * How fast the song moves through its own timeline, as a multiple of real time. Defaults to 1, and
     * zero or less reads as 1 because `isPlaying` is what says a song is stopped.
     */
    val playbackRate: Double? = null,
)

/**
 * A tick with nothing left out. The engine reads every field unconditionally, so the defaults are
 * filled in at the edge rather than guessed at each read.
 */
data class ResolvedTickOptions(
    val isPlaying: Boolean,
    val eventCreationTime: Double,
    val smoothScroll: Boolean,
    val globalLyricOffset: Double,
    val lyricOffset: Double,
    val richsyncOffsetTrim: Double,
    val lineOffsetTrim: Double,
    val passiveScrollEnabled: Boolean,
    val playbackRate: Double,
)
