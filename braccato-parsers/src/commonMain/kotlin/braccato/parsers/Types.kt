// Port of packages/parsers/src/types.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricSyncType

// The lyric shapes live in `braccato-types`, the one owner of them across the modules that produce
// and render lyrics. This module depends on it with `api`, so consumers reach `Lyric` and
// `LyricPart` through it as well.

// Published by this package first, under the shorter name. Same union as `LyricSyncType`, kept as an
// alias so existing imports resolve.
typealias SyncType = LyricSyncType

/** Song-level credits a lyrics file carries alongside its lines. */
data class LyricMetadata(
    val songwriters: List<String>,
)

// The parser contract belongs to this package, so it stays declared here.
interface LyricParser {
    fun parse(input: String, duration: Double = 0.0): List<Lyric>
    fun metadata(input: String): LyricMetadata
    fun detect(input: String): Boolean
}
