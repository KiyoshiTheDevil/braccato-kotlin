// Port of `@braccato/types` (packages/types/src/index.ts).
//
// The lyric data shapes, in the one place both halves of braccato can agree on them.
//
// `braccato-parsers` produces these and the renderer consumes them, so a field added on one side
// and not the other is a silent mismatch across a module boundary.
//
// Times are milliseconds held as `Double`, which is what a JavaScript number is. The parsers do
// arithmetic on them the way upstream does, NaN included, and the comparison tests depend on that.
// The fields are `var` because upstream's parsers finish a line by mutating it (`lrcFixers`).
// A field upstream marks optional is nullable here, and null is its `undefined`.
package braccato.types

// -- Lyric data --------------------------------------------

/**
 * One timed piece of a line: a word, a syllable, or a run of words sung together.
 *
 * @property startTimeMs When it starts, in milliseconds from the start of the song.
 * @property words Its text, with the spaces that follow it in the line.
 * @property durationMs How long it is sung, in milliseconds.
 * @property isBackground Whether it belongs to the background vocals under the line.
 * @property backgroundGroup Which of the line's background groups (`x-bg` spans) the part is in, counted from 0.
 * @property explicit Whether the provider marks it explicit.
 */
public data class LyricPart(
    var startTimeMs: Double,
    var words: String,
    var durationMs: Double,
    var isBackground: Boolean? = null,
    var explicit: Boolean? = null,
    var backgroundGroup: Int? = null,
)

/**
 * The single-language shape `translation` had before `translations` existed.
 *
 * @property text The translated line.
 * @property lang Its language, as a BCP 47 tag.
 */
public data class LyricTranslation(
    val text: String,
    val lang: String,
)

/**
 * One line of lyrics, as the parsers produce it and `buildLyrics` takes it.
 *
 * @property startTimeMs When the line starts, in milliseconds from the start of the song.
 * @property words The whole line's text.
 * @property durationMs How long the line lasts, in milliseconds. 0 for lyrics without timing.
 * @property key The line's id in its source document (TTML's `itunes:key`), when it has one.
 * @property parts The line's timed pieces, for word or syllable synced lyrics. Null when only the line is timed.
 * @property agent Who sings it: `v1`, `v2`, … for single voices, `v1000` for all together.
 * @property translations Translations of the line, by language tag.
 * @property translation The older single translation. Read [translations] first.
 * @property romanization The line in Latin script, for lyrics in another script.
 * @property timedRomanization The romanization with its own timing, piece for piece.
 * @property isInstrumental Whether the line stands for a break without singing (an intro, a solo).
 */
public data class Lyric(
    var startTimeMs: Double,
    var words: String,
    var durationMs: Double,
    var key: String? = null,
    var parts: MutableList<LyricPart>? = null,
    var agent: String? = null,
    var translations: MutableMap<String, String>? = null,
    var translation: LyricTranslation? = null, // old property
    var romanization: String? = null,
    var timedRomanization: MutableList<LyricPart>? = null,
    var isInstrumental: Boolean? = null,
)

// Not SyncType: the longer name is the one `@braccato/core` uses, and it reads unambiguously beside a
// provider's sync quality ("syllable" | "word" | "line" | "unsynced"), a different axis in the same vocabulary.
/**
 * How finely a song's lyrics are timed: [RICHSYNC] word by word, [SYNCED] line by line, [NONE] not at all.
 *
 * `braccato-parsers` also exports it as `SyncType`, the name it published first.
 *
 * @property value The name upstream uses for it.
 */
public enum class LyricSyncType(public val value: String) {
    RICHSYNC("richsync"),
    SYNCED("synced"),
    NONE("none"),
}
