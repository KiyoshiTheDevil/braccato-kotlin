// Port of packages/parsers/src/types.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricSyncType

// The lyric shapes live in `braccato-types`, the one owner of them across the modules that produce
// and render lyrics. This module depends on it with `api`, so consumers reach `Lyric` and
// `LyricPart` through it as well.

/** [LyricSyncType] under the name this package published first. */
public typealias SyncType = LyricSyncType

/**
 * Song-level credits a lyrics file carries alongside its lines.
 *
 * @property songwriters The songwriters' names, each once, in the document's order.
 */
public data class LyricMetadata(
    val songwriters: List<String>,
)

/**
 * A parser for one lyrics format. [detectParser] picks the right one for a document.
 */
public interface LyricParser {
    /**
     * Reads the document's lines.
     *
     * @param duration The song's length in milliseconds. Some formats use it to add an instrumental
     *   line for the outro; 0 leaves that out.
     */
    public fun parse(input: String, duration: Double = 0.0): List<Lyric>

    /** Reads what the document says about the song beside its lines: so far, its songwriters. */
    public fun metadata(input: String): LyricMetadata

    /** Whether [input] looks like this parser's format. */
    public fun detect(input: String): Boolean
}
