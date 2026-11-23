// Port of packages/core/src/inject.ts, as a model instead of DOM.
//
// Upstream builds a line's elements here: the main and background content lines, the word groups and
// the timed word spans the sweep animates. The decisions behind that structure are what is ported:
// how a part is split into words, how words group, which word wobbles, which is lettered, where a long
// word may wrap. The result is a [BuiltLine] a renderer draws, instead of elements.
//
// A part is not a word. Providers hand over parts that run to several words, and the unit the sweep
// animates is one word, so every part is split on whitespace with its timing pro-rated across the
// split by character count. A line that arrives with no timed parts at all is rebuilt the same way
// into zero duration words, so line synced lyrics reach the renderer in the shape it already knows.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.Lyric
import dev.kiyoshi.braccato.types.LyricPart
import dev.kiyoshi.braccato.types.LyricSyncType
import dev.kiyoshi.braccato.types.internal.JS_WHITESPACE
import dev.kiyoshi.braccato.types.internal.jsRound
import dev.kiyoshi.braccato.types.internal.jsTrim
import kotlin.math.max

private val LTR_SCRIPT_REGEX =
    Regex("[\\p{IsLatin}\\p{IsGreek}\\p{IsCyrillic}\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]")
private val SPACE_REGEX = Regex("^[$JS_WHITESPACE]+$")
private val CHUNK_REGEX = Regex("[$JS_WHITESPACE]+|[^$JS_WHITESPACE]+")
private val LETTER_OR_NUMBER = Regex("[\\p{L}\\p{N}]")

internal fun findNearestAgent(lyrics: List<Lyric>, fromIndex: Int): String? {
    // Look in the downwards direction first
    for (i in fromIndex + 1 until lyrics.size) {
        if (lyrics[i].isInstrumental != true && !lyrics[i].agent.isNullOrEmpty()) {
            return lyrics[i].agent
        }
    }
    for (i in fromIndex - 1 downTo 0) {
        if (lyrics[i].isInstrumental != true && !lyrics[i].agent.isNullOrEmpty()) {
            return lyrics[i].agent
        }
    }
    return null
}

internal fun isNearestLyricRtl(lyrics: List<Lyric>, fromIndex: Int): Boolean {
    // Look in the downwards direction first
    for (i in fromIndex + 1 until lyrics.size) {
        if (lyrics[i].isInstrumental != true && lyrics[i].words.jsTrim().isNotEmpty()) {
            return testRtl(lyrics[i].words)
        }
    }
    for (i in fromIndex - 1 downTo 0) {
        if (lyrics[i].isInstrumental != true && lyrics[i].words.jsTrim().isNotEmpty()) {
            return testRtl(lyrics[i].words)
        }
    }
    return false
}

// -- Lyric shape --------------------------------------------

// A line whose parts are missing, empty or overridden by the theme is rebuilt as line synced words,
// which carry no duration, so only pre-existing timed parts count as rich sync.
internal fun deriveSyncType(lyrics: List<Lyric>): LyricSyncType {
    val hasTimedParts =
        !CoreSettings.disableRichsync.getBooleanValue() &&
            lyrics.any { item -> item.isInstrumental != true && item.parts?.any { part -> part.durationMs != 0.0 } == true }

    if (hasTimedParts) return LyricSyncType.RICHSYNC
    return if (lyrics.all { item -> item.startTimeMs == 0.0 }) LyricSyncType.NONE else LyricSyncType.SYNCED
}

/** Which way a run of text reads. `AUTO` is upstream's `dir="auto"` with no class: left to the text. */
public enum class TextDirection { RTL, LTR, AUTO }

internal fun detectDirection(text: String): TextDirection {
    var index = 0
    while (index < text.length) {
        val end = if (text[index].isHighSurrogate() && index + 1 < text.length) index + 2 else index + 1
        val char = text.substring(index, end)
        if (testRtl(char)) return TextDirection.RTL
        if (LTR_SCRIPT_REGEX.containsMatchIn(char)) return TextDirection.LTR
        index = end
    }
    return TextDirection.AUTO
}

/** Whether text holds right-to-left script, so the browser's bidi ordering matters inside its run. */
internal fun isBidiSensitive(text: String): Boolean = testRtl(text)

// -- Tokens --------------------------------------------

/** A run of whitespace, or a part narrowed to one word with its share of the part's time. */
internal sealed interface RenderToken {
    val text: String

    data class Space(override val text: String) : RenderToken

    data class Part(override val text: String, val part: LyricPart) : RenderToken
}

/** Words that run together with no space between them: one, or one split into syllables. */
internal data class WordGroup(
    val text: String,
    val isBackground: Boolean,
    val tokens: List<RenderToken>,
    val backgroundGroup: Int? = null,
)

internal fun splitPartIntoTokens(part: LyricPart): List<RenderToken> {
    val chunks = CHUNK_REGEX.findAll(part.words).map { it.value }.toList()

    if (chunks.isEmpty()) return emptyList()

    var spaceChars = 0
    for (chunk in chunks) {
        if (SPACE_REGEX.matches(chunk)) spaceChars += chunk.length
    }

    val nonWhiteSpaceChars = part.words.length - spaceChars
    var cursor = 0
    return chunks.map { chunk ->
        if (SPACE_REGEX.matches(chunk)) return@map RenderToken.Space(chunk)
        val startTimeMs = part.startTimeMs + jsRound((part.durationMs * cursor) / nonWhiteSpaceChars)
        val endTimeMs = part.startTimeMs + jsRound((part.durationMs * (cursor + chunk.length)) / nonWhiteSpaceChars)
        cursor += chunk.length
        RenderToken.Part(
            text = chunk,
            part = LyricPart(
                startTimeMs = startTimeMs,
                words = chunk,
                durationMs = endTimeMs - startTimeMs,
                isBackground = part.isBackground,
                backgroundGroup = part.backgroundGroup,
                explicit = part.explicit,
            ),
        )
    }
}

internal fun normalizeParts(parts: List<LyricPart>): List<RenderToken> {
    return parts.flatMap(::splitPartIntoTokens)
}

/** Each element is a [WordGroup] or a [RenderToken.Space]. */
internal fun groupTokensByWord(tokens: List<RenderToken>, splitBackgroundGroups: Boolean = true): List<Any> {
    val groups = mutableListOf<Any>()
    var currentText = ""
    var currentBackground = false
    var currentGroup: Int? = null
    var currentTokens: MutableList<RenderToken>? = null

    fun flush() {
        val tokensSoFar = currentTokens
        if (tokensSoFar != null && tokensSoFar.isNotEmpty()) {
            groups += WordGroup(text = currentText, isBackground = currentBackground, tokens = tokensSoFar, backgroundGroup = currentGroup)
        }
        currentTokens = null
    }

    for (token in tokens) {
        if (token is RenderToken.Space) {
            flush()
            groups += token
            continue
        }

        val isBackground = (token as RenderToken.Part).part.isBackground == true
        val backgroundGroup = if (splitBackgroundGroups) token.part.backgroundGroup else null
        if (currentTokens == null || currentBackground != isBackground || currentGroup != backgroundGroup) {
            flush()
            currentText = ""
            currentBackground = isBackground
            currentGroup = backgroundGroup
            currentTokens = mutableListOf()
        }

        currentText += token.text
        currentTokens!!.add(token)
    }

    flush()
    return groups
}

// -- Built line --------------------------------------------

/** One timed word, upstream's `PartData` without its elements. Times in seconds. */
public class BuiltWord(
    /** The word's text, with the space after it when the line has one. */
    public val text: String,
    override val time: Double,
    override val duration: Double,
    /** Whether it belongs to the background vocals. */
    public val isBackground: Boolean,
    /** Whether the provider marks it explicit. */
    public val explicit: Boolean,
    /** A word with no duration of its own, from a line synced line: it fades in rather than sweeps. */
    public val isLineSynced: Boolean,
    /** Whether its script is written right to left. */
    public val isRtl: Boolean,
    /** Held longer than `blyrics-long-word-threshold`, which themes style differently. */
    public val isLongWord: Boolean,
    /** The word's graphemes, when its letters wave and sweep one at a time. Null otherwise. */
    public val letters: List<String>?,
    /**
     * Where a word too long to fit may wrap: its text in chunks of `blyrics-long-word-wrap-threshold`.
     * Null when it is short enough, lettered, or in a joining script, which must not be split.
     */
    public val wrapChunks: List<String>?,
    /** Only a word's first syllable carries its group, so a word split into syllables wobbles once, whole. */
    public val wobbles: Boolean,
) : TimedPart

/** What a content line holds, in order: words grouped as they run together, and the spaces between. */
public sealed interface LineItem {
    /**
     * The space between two groups.
     *
     * @property text The space's text.
     */
    public data class Space(val text: String) : LineItem

    /** Words sung as one, which wrap together and wobble together. */
    public class Group(
        /** The group's whole text. */
        public val text: String,
        /** Whether the group belongs to the background vocals. */
        public val isBackground: Boolean,
        /** Longer than twice the wrap threshold, which themes let break anywhere. */
        public val isLong: Boolean,
        /** The group's words, in order. */
        public val words: List<BuiltWord>,
    ) : LineItem
}

/** One content line: the main one, the background vocals under it, or a timed romanization. */
public class ContentLine(
    /** The line's whole text. */
    public val text: String,
    /** Its groups and the spaces between them, in reading order. */
    public val items: List<LineItem>,
    /** Whether it holds right-to-left script, so the order of its pieces needs bidi care. */
    public val isBidiSensitive: Boolean,
)

/** `createWordGroup`: the group's words, and whether they are lettered. */
private fun buildWordGroup(group: WordGroup, words: MutableList<BuiltWord>): LineItem.Group {
    val wrapThreshold = max(1.0, CoreSettings.longWordWrapThreshold.getNumberValue()).toInt()
    // Atomic grapheme spans disrupt bidi ordering inside a mixed word.
    val mixedDirection = testRtl(group.text) && codePoints(group.text).any { char ->
        LETTER_OR_NUMBER.containsMatchIn(char) && !testRtl(char)
    }
    val preserveJoining = testJoiningScript(group.text)
    val perLetter = CoreSettings.letterWave.getBooleanValue() && !preserveJoining && !mixedDirection

    val groupWords = mutableListOf<BuiltWord>()
    for (token in group.tokens) {
        if (token !is RenderToken.Part) continue
        val part = token.part
        // Letters as a reader sees them, conjuncts whole; only worked out where they are needed (1.21.3).
        val clusters = if (perLetter || (!preserveJoining && part.words.length > wrapThreshold)) letterClusters(part.words) else listOf(part.words)
        val word = BuiltWord(
            text = part.words,
            time = part.startTimeMs / 1000,
            duration = part.durationMs / 1000,
            isBackground = part.isBackground == true,
            explicit = part.explicit == true,
            isLineSynced = part.durationMs == 0.0,
            isRtl = testRtl(part.words),
            isLongWord = part.durationMs > CoreSettings.longWordThreshold.getNumberValue(),
            letters = if (perLetter) clusters else null,
            wrapChunks = if (!perLetter && !preserveJoining && clusters.size > wrapThreshold) {
                clusters.chunked(wrapThreshold) { it.joinToString("") }
            } else {
                null
            },
            wobbles = groupWords.isEmpty(),
        )
        groupWords += word
        words += word
    }

    return LineItem.Group(
        text = group.text,
        isBackground = group.isBackground,
        isLong = group.text.length > wrapThreshold * 2 && letterClusters(group.text).size > wrapThreshold * 2,
        words = groupWords,
    )
}

/** The content of a line built from its parts: `createLyricsLine`. */
public class LineContent(
    /** The sung line. */
    public val main: ContentLine,
    /**
     * The background vocals under it, a row for each group (`x-bg` span) in the order they are numbered.
     * Empty when the line has none.
     */
    public val backgrounds: List<ContentLine>,
    /** Every word, in the order upstream animates them. */
    public val words: List<BuiltWord>,
)

internal fun createLyricsLine(parts: List<LyricPart>, splitBackgroundLine: Boolean = true): LineContent {
    val lineText = parts.joinToString("") { part -> part.words }
    val mainText = if (splitBackgroundLine) {
        parts.filter { part -> part.isBackground != true }.joinToString("") { part -> part.words }
    } else {
        lineText
    }
    val backgroundTextOf = { group: Int ->
        parts.filter { part -> part.isBackground == true && (part.backgroundGroup ?: 0) == group }.joinToString("") { part -> part.words }
    }
    val groupedTokens = groupTokensByWord(normalizeParts(parts), splitBackgroundLine)
    val mainItems = mutableListOf<LineItem>()
    // A row for each background group, in the order its first word comes; laid out by number below.
    val backgroundRows = LinkedHashMap<Int, MutableList<LineItem>>()
    val words = mutableListOf<BuiltWord>()
    var pendingForegroundSpace = ""
    var pendingBackgroundSpace = ""

    for (item in groupedTokens) {
        if (item is RenderToken.Space) {
            pendingForegroundSpace += item.text
            pendingBackgroundSpace += item.text
        } else {
            item as WordGroup
            val shouldUseBackgroundLine = splitBackgroundLine && item.isBackground
            val target = if (shouldUseBackgroundLine) backgroundRows.getOrPut(item.backgroundGroup ?: 0) { mutableListOf() } else mainItems
            val pendingSpace = if (shouldUseBackgroundLine) pendingBackgroundSpace else pendingForegroundSpace
            if (target.isNotEmpty() && pendingSpace.isNotEmpty()) {
                target += LineItem.Space(pendingSpace)
            }
            target += buildWordGroup(item, words)
            if (shouldUseBackgroundLine) {
                pendingBackgroundSpace = ""
            } else {
                pendingForegroundSpace = ""
            }
        }
    }

    return LineContent(
        main = ContentLine(text = mainText, items = mainItems, isBidiSensitive = isBidiSensitive(mainText)),
        backgrounds = backgroundRows.entries.sortedBy { it.key }.map { (group, items) ->
            val text = backgroundTextOf(group)
            ContentLine(text = text, items = items, isBidiSensitive = isBidiSensitive(text))
        },
        words = words,
    )
}

internal fun buildLineSyncedParts(item: Lyric): List<LyricPart> {
    val parts = mutableListOf<LyricPart>()
    var wordIndex = 0

    for (token in CHUNK_REGEX.findAll(item.words).map { it.value }) {
        val isSpace = SPACE_REGEX.matches(token)
        val startTimeMs = item.startTimeMs + wordIndex * CoreSettings.lineSyncedAnimationDelay.getNumberValue()
        parts += LyricPart(startTimeMs = startTimeMs, words = token, durationMs = 0.0)

        if (!isSpace) {
            wordIndex += 1
        }
    }

    return parts
}

// -- Decorations --------------------------------------------

/** A translation hung under a line: `injectTranslation`. Equal when it shows the same. */
public data class BuiltTranslation(
    /** The translated line. */
    val text: String,
    /** Its language, "" where it cannot be told. */
    val language: String,
    /** Which way it reads. */
    val direction: TextDirection,
)

/**
 * A romanization hung under a line: `injectRomanization`. Timed, it carries words that sweep like the
 * line's. Equal when it shows the same, which is what upstream's `decorationSources` compares.
 */
public data class BuiltRomanization(
    /** The line in Latin script. */
    val text: String,
    /** The language it romanizes, "" where it cannot be told. */
    val language: String,
    /** Which way it reads. */
    val direction: TextDirection,
    /** The timed parts it was built from, or null when it is untimed and shows as plain text. */
    val timedParts: List<LyricPart>?,
) {
    /** The timed parts as words, built once. Null when untimed. */
    val content: LineContent? by lazy { timedParts?.let { createLyricsLine(it, splitBackgroundLine = false) } }
}

/**
 * The translation for a line, or null when it only repeats the line, which upstream leaves off.
 *
 * @param mainText The line's main text, as built.
 */
public fun buildTranslation(mainText: String, text: String, language: String? = null): BuiltTranslation? {
    if (isSameText(mainText, text)) return null
    return BuiltTranslation(text = text, language = normalizeLanguage(language), direction = detectDirection(text))
}

/**
 * The romanization for a line, or null when it only repeats the line. Its language is the line's, in
 * Latin script. Timed parts are built into words unless the theme turned rich sync off.
 */
public fun buildRomanization(
    mainText: String,
    lineLanguage: String,
    text: String,
    timedRomanization: List<LyricPart>? = null,
): BuiltRomanization? {
    if (isSameText(mainText, text)) return null
    val timed = timedRomanization != null && timedRomanization.isNotEmpty() && !CoreSettings.disableRichsync.getBooleanValue()
    return BuiltRomanization(
        text = text,
        language = romanizationLanguage(lineLanguage),
        direction = detectDirection(text),
        timedParts = if (timed) timedRomanization.toList() else null,
    )
}

// -- Text helpers --------------------------------------------

/** The string's code points, each as its own string, the way `[...text]` spreads it. */
internal fun codePoints(text: String): List<String> {
    val out = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val end = if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) index + 2 else index + 1
        out += text.substring(index, end)
        index = end
    }
    return out
}

/** `Intl.Segmenter` with grapheme granularity: what a reader sees as one character. */
internal expect fun splitGraphemes(text: String): List<String>
