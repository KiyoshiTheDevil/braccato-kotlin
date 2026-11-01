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
package braccato.core

import braccato.types.Lyric
import braccato.types.LyricPart
import braccato.types.LyricSyncType
import braccato.types.internal.JS_WHITESPACE
import braccato.types.internal.jsRound
import braccato.types.internal.jsTrim
import kotlin.math.max

private val LTR_SCRIPT_REGEX =
    Regex("[\\p{IsLatin}\\p{IsGreek}\\p{IsCyrillic}\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]")
private val SPACE_REGEX = Regex("^[$JS_WHITESPACE]+$")
private val CHUNK_REGEX = Regex("[$JS_WHITESPACE]+|[^$JS_WHITESPACE]+")
private val LETTER_OR_NUMBER = Regex("[\\p{L}\\p{N}]")

fun findNearestAgent(lyrics: List<Lyric>, fromIndex: Int): String? {
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

fun isNearestLyricRtl(lyrics: List<Lyric>, fromIndex: Int): Boolean {
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
fun deriveSyncType(lyrics: List<Lyric>): LyricSyncType {
    val hasTimedParts =
        !CoreSettings.disableRichsync.getBooleanValue() &&
            lyrics.any { item -> item.isInstrumental != true && item.parts?.any { part -> part.durationMs != 0.0 } == true }

    if (hasTimedParts) return LyricSyncType.RICHSYNC
    return if (lyrics.all { item -> item.startTimeMs == 0.0 }) LyricSyncType.NONE else LyricSyncType.SYNCED
}

/** Which way a run of text reads. `AUTO` is upstream's `dir="auto"` with no class: left to the text. */
enum class TextDirection { RTL, LTR, AUTO }

fun detectDirection(text: String): TextDirection {
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
fun isBidiSensitive(text: String): Boolean = testRtl(text)

// -- Tokens --------------------------------------------

/** A run of whitespace, or a part narrowed to one word with its share of the part's time. */
sealed interface RenderToken {
    val text: String

    data class Space(override val text: String) : RenderToken

    data class Part(override val text: String, val part: LyricPart) : RenderToken
}

/** Words that run together with no space between them: one, or one split into syllables. */
data class WordGroup(
    val text: String,
    val isBackground: Boolean,
    val tokens: List<RenderToken>,
)

fun splitPartIntoTokens(part: LyricPart): List<RenderToken> {
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
                explicit = part.explicit,
            ),
        )
    }
}

fun normalizeParts(parts: List<LyricPart>): List<RenderToken> {
    return parts.flatMap(::splitPartIntoTokens)
}

/** Each element is a [WordGroup] or a [RenderToken.Space]. */
fun groupTokensByWord(tokens: List<RenderToken>): List<Any> {
    val groups = mutableListOf<Any>()
    var currentText = ""
    var currentBackground = false
    var currentTokens: MutableList<RenderToken>? = null

    fun flush() {
        val tokensSoFar = currentTokens
        if (tokensSoFar != null && tokensSoFar.isNotEmpty()) {
            groups += WordGroup(text = currentText, isBackground = currentBackground, tokens = tokensSoFar)
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
        if (currentTokens == null || currentBackground != isBackground) {
            flush()
            currentText = ""
            currentBackground = isBackground
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
class BuiltWord(
    val text: String,
    override val time: Double,
    override val duration: Double,
    val isBackground: Boolean,
    val explicit: Boolean,
    /** A word with no duration of its own, from a line synced line: it fades in rather than sweeps. */
    val isLineSynced: Boolean,
    val isRtl: Boolean,
    /** Held longer than `blyrics-long-word-threshold`, which themes style differently. */
    val isLongWord: Boolean,
    /** The word's graphemes, when its letters wave and sweep one at a time. Null otherwise. */
    val letters: List<String>?,
    /**
     * Where a word too long to fit may wrap: its text in chunks of `blyrics-long-word-wrap-threshold`.
     * Null when it is short enough, lettered, or in a joining script, which must not be split.
     */
    val wrapChunks: List<String>?,
    /** Only a word's first syllable carries its group, so a word split into syllables wobbles once, whole. */
    val wobbles: Boolean,
) : TimedPart

/** What a content line holds, in order: words grouped as they run together, and the spaces between. */
sealed interface LineItem {
    data class Space(val text: String) : LineItem

    class Group(
        val text: String,
        val isBackground: Boolean,
        /** Longer than twice the wrap threshold, which themes let break anywhere. */
        val isLong: Boolean,
        val words: List<BuiltWord>,
    ) : LineItem
}

/** One content line: the main one, the background vocals under it, or a timed romanization. */
class ContentLine(
    val text: String,
    val items: List<LineItem>,
    val isBidiSensitive: Boolean,
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
        val word = BuiltWord(
            text = part.words,
            time = part.startTimeMs / 1000,
            duration = part.durationMs / 1000,
            isBackground = part.isBackground == true,
            explicit = part.explicit == true,
            isLineSynced = part.durationMs == 0.0,
            isRtl = testRtl(part.words),
            isLongWord = part.durationMs > CoreSettings.longWordThreshold.getNumberValue(),
            letters = if (perLetter) splitGraphemes(part.words) else null,
            wrapChunks = if (!perLetter && !preserveJoining && part.words.length > wrapThreshold) {
                part.words.chunked(wrapThreshold)
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
        isLong = group.text.length > wrapThreshold * 2,
        words = groupWords,
    )
}

/** The content of a line built from its parts: `createLyricsLine`. */
class LineContent(
    val main: ContentLine,
    /** Null when the line has no background vocals. */
    val background: ContentLine?,
    /** Every word, in the order upstream animates them. */
    val words: List<BuiltWord>,
)

fun createLyricsLine(parts: List<LyricPart>, splitBackgroundLine: Boolean = true): LineContent {
    val lineText = parts.joinToString("") { part -> part.words }
    val mainText = if (splitBackgroundLine) {
        parts.filter { part -> part.isBackground != true }.joinToString("") { part -> part.words }
    } else {
        lineText
    }
    val backgroundText = parts.filter { part -> part.isBackground == true }.joinToString("") { part -> part.words }
    val groupedTokens = groupTokensByWord(normalizeParts(parts))
    val mainItems = mutableListOf<LineItem>()
    val backgroundItems = mutableListOf<LineItem>()
    val words = mutableListOf<BuiltWord>()
    var hasBackground = false
    var pendingForegroundSpace = ""
    var pendingBackgroundSpace = ""

    for (item in groupedTokens) {
        if (item is RenderToken.Space) {
            pendingForegroundSpace += item.text
            pendingBackgroundSpace += item.text
        } else {
            item as WordGroup
            val shouldUseBackgroundLine = splitBackgroundLine && item.isBackground
            val target = if (shouldUseBackgroundLine) backgroundItems else mainItems
            val pendingSpace = if (shouldUseBackgroundLine) pendingBackgroundSpace else pendingForegroundSpace
            if (target.isNotEmpty() && pendingSpace.isNotEmpty()) {
                target += LineItem.Space(pendingSpace)
            }
            target += buildWordGroup(item, words)
            if (shouldUseBackgroundLine) {
                hasBackground = true
                pendingBackgroundSpace = ""
            } else {
                pendingForegroundSpace = ""
            }
        }
    }

    return LineContent(
        main = ContentLine(text = mainText, items = mainItems, isBidiSensitive = isBidiSensitive(mainText)),
        background = if (hasBackground) {
            ContentLine(text = backgroundText, items = backgroundItems, isBidiSensitive = isBidiSensitive(backgroundText))
        } else {
            null
        },
        words = words,
    )
}

fun buildLineSyncedParts(item: Lyric): List<LyricPart> {
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
data class BuiltTranslation(
    val text: String,
    val language: String,
    val direction: TextDirection,
)

/**
 * A romanization hung under a line: `injectRomanization`. Timed, it carries words that sweep like the
 * line's. Equal when it shows the same, which is what upstream's `decorationSources` compares.
 */
data class BuiltRomanization(
    val text: String,
    val language: String,
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
fun buildTranslation(mainText: String, text: String, language: String? = null): BuiltTranslation? {
    if (isSameText(mainText, text)) return null
    return BuiltTranslation(text = text, language = normalizeLanguage(language), direction = detectDirection(text))
}

/**
 * The romanization for a line, or null when it only repeats the line. Its language is the line's, in
 * Latin script. Timed parts are built into words unless the theme turned rich sync off.
 */
fun buildRomanization(
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
expect fun splitGraphemes(text: String): List<String>
