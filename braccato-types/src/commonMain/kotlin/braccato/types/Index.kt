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

public data class LyricPart(
    var startTimeMs: Double,
    var words: String,
    var durationMs: Double,
    var isBackground: Boolean? = null,
    var explicit: Boolean? = null,
)

/** The single-language shape `translation` had before `translations` existed. */
public data class LyricTranslation(
    val text: String,
    val lang: String,
)

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

// Not SyncType, which `@braccato/parsers` published first for the same union and goes on exporting as
// an alias of this. The longer name is the one `@braccato/core` uses, and it is the one that reads
// unambiguously beside a provider's sync quality ("syllable" | "word" | "line" | "unsynced"), which
// is a different axis measured in the same vocabulary.
public enum class LyricSyncType(public val value: String) {
    RICHSYNC("richsync"),
    SYNCED("synced"),
    NONE("none"),
}
