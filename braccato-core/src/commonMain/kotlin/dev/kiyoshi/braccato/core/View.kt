// Port of packages/core/src/view.ts, as a model instead of DOM.
//
// Upstream builds the container and one element per line here, then hands the render records to the
// engine. What a line is built from and what the container records about the song are ported; the
// result is a [BuiltLyrics] a renderer draws. Swapping lyrics into a view without rebuilding it
// (`swapLyrics`) is a DOM economy and is not ported: a Compose renderer keys its lines instead.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.Lyric
import dev.kiyoshi.braccato.types.LyricSyncType
import dev.kiyoshi.braccato.types.internal.jsTrim

/** What [buildLyrics] needs to know beside the lyrics themselves. */
public data class SetLyricsOptions(
    /** BCP 47 language of the original lyrics. Kana/Hangul provide a fallback when omitted. */
    val language: String? = null,
    /** These lyrics are a "not found" placeholder rather than a real result. */
    val noLyrics: Boolean = false,
    /** Who wrote the song, shown after the last line. */
    val songwriters: List<String> = emptyList(),
)

/** One line as the renderer draws it: upstream's `LineData` and the element built for it. Times in seconds. */
public class BuiltLine(
    /** The line's place in [BuiltLyrics.lines]. */
    public val index: Int,
    /** The lyric the line was built from, untouched. */
    public val lyric: Lyric,
    override val time: Double,
    override val duration: Double,
    /** Whether the line stands for a break without singing. */
    public val isInstrumental: Boolean,
    /** A sung line with no words, which marks a pause. */
    public val isBlank: Boolean,
    /** `v1` lead, `v2`/`v3` the other side, `v1000` the group. An instrumental takes its neighbours'. */
    public val agent: String?,
    /** Which way the line reads. */
    public val direction: TextDirection,
    /** The line's language, "" where it cannot be told. */
    public val language: String,
    /** Null for an instrumental line. */
    public val content: LineContent?,
) : TimedPart {
    /** Every word of the line, in the order they are sung. Empty for an instrumental. */
    public val words: List<BuiltWord> get() = content?.words ?: emptyList()
}

/** A song's lyrics as a renderer draws them, from [buildLyrics]. */
public class BuiltLyrics(
    /** The lines, instrumental breaks among them, in order. */
    public val lines: List<BuiltLine>,
    /** How finely the lyrics are timed. */
    public val syncType: LyricSyncType,
    /** Every line starts at zero: there is no time to seek to, and clicking a line does nothing. */
    public val allZero: Boolean,
    /** The lyrics are a "not found" placeholder, see [SetLyricsOptions.noLyrics]. */
    public val noLyrics: Boolean,
    /** The credits line after the last lyric, or null when there is none to show. */
    public val credits: String?,
    /** Stage layout: both sides are sung, so lines take sides rather than staying centred. */
    public val isStageDuet: Boolean,
)

internal fun formatSongwriters(names: List<String>): String {
    return if (names.size > 1) "${names.dropLast(1).joinToString(", ")} & ${names.last()}" else (names.firstOrNull() ?: "")
}

private fun buildLine(lyrics: List<Lyric>, lineIndex: Int, languages: List<String>): BuiltLine {
    val lyricItem = lyrics[lineIndex]
    val time = lyricItem.startTimeMs / 1000
    val duration = lyricItem.durationMs / 1000

    if (lyricItem.isInstrumental == true) {
        return BuiltLine(
            index = lineIndex,
            lyric = lyricItem,
            time = time,
            duration = duration,
            isInstrumental = true,
            isBlank = false,
            agent = findNearestAgent(lyrics, lineIndex),
            direction = if (isNearestLyricRtl(lyrics, lineIndex)) TextDirection.RTL else TextDirection.AUTO,
            language = languages[lineIndex],
            content = null,
        )
    }

    // Rebuilt parts stay local so the provider's lyrics survive intact and a second build over the
    // same list produces the same result.
    val sourceParts = lyricItem.parts
    val parts = if (sourceParts != null && sourceParts.isNotEmpty() && !CoreSettings.disableRichsync.getBooleanValue()) {
        sourceParts
    } else {
        buildLineSyncedParts(lyricItem)
    }

    return BuiltLine(
        index = lineIndex,
        lyric = lyricItem,
        time = time,
        duration = duration,
        isInstrumental = false,
        isBlank = lyricItem.words.jsTrim().isEmpty(),
        agent = lyricItem.agent?.ifEmpty { null },
        direction = detectDirection(lyricItem.words),
        language = languages[lineIndex],
        content = createLyricsLine(parts),
    )
}

/** `setLyrics`: builds every line, and what the container records about the song as a whole. */
public fun buildLyrics(
    lyrics: List<Lyric>,
    options: SetLyricsOptions = SetLyricsOptions(),
    layout: LyricsLayout = LyricsLayout.SCROLL,
): BuiltLyrics {
    val languages = resolveLyricLanguages(lyrics, options.language)
    val lines = lyrics.indices.map { lineIndex -> buildLine(lyrics, lineIndex, languages) }

    // Sides only when both are sung, so a song with one singer stays centred whichever voice it is.
    val sung = lyrics.filter { item -> item.isInstrumental != true && item.words.jsTrim().isNotEmpty() && item.agent != "v1000" }
    val onRight = { item: Lyric -> item.agent == "v2" || item.agent == "v3" }
    val isStageDuet = layout == LyricsLayout.STAGE && sung.any(onRight) && sung.any { !onRight(it) }

    val showCredits = options.songwriters.isNotEmpty() && !options.noLyrics && !CoreSettings.hideCredits.getBooleanValue()

    return BuiltLyrics(
        lines = lines,
        syncType = deriveSyncType(lyrics),
        allZero = lyrics.all { item -> item.startTimeMs == 0.0 },
        noLyrics = options.noLyrics,
        credits = if (showCredits) formatSongwriters(options.songwriters) else null,
        isStageDuet = isStageDuet,
    )
}
