// Port of packages/core/src/imageHighlights.ts, the part that is not the DOM. The setting itself is
// `CoreSettings.imageHighlights`; wrapping highlights in image layers is the renderer's business.
package braccato.core

private val IMAGE_URL = Regex("""^url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s'"()]+))\s*\)$""", RegexOption.IGNORE_CASE)

/** A single CSS url(), not gradients or an arbitrary list of background layers. */
internal fun imageURL(value: String): String? {
    val match = IMAGE_URL.find(value.trim()) ?: return null
    return match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
}
