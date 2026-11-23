// Port of packages/parsers/src/ttml.ts.
//
// Upstream walks the object tree fast-xml-parser builds, and `ttmlTypes.ts` describes its shapes. Here
// the tree is [XmlNode] from XmlReader.kt, and the accessors map one to one: `node.span` is
// `node.childrenOf("span")`, `node[":@"]?.["@_begin"]` is `node.attr("begin")`, and `"#text" in node`
// is `node.isText`.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricPart
import braccato.types.LyricTranslation
import braccato.types.internal.*

private val OFFSET_TIME_REGEX = Regex("""^([\d.]+)(h|m|s|ms)\z""")
private val NON_NUMERIC_REGEX = Regex("""[^0-9.]""")

/**
 * Parse time in hh:mm:ss.xx or offset-time with unit indicators "h", "m", "s", "ms" (e.g 432.25s)
 */
private fun parseTime(timeStr: String?): Double {
    if (timeStr.isNullOrEmpty()) return 0.0

    val offsetTimeMatch = OFFSET_TIME_REGEX.find(timeStr)
    if (offsetTimeMatch != null) {
        val value = jsParseFloat(offsetTimeMatch.groupValues[1])
        when (offsetTimeMatch.groupValues[2]) {
            "h" -> return jsRound(value * 60 * 60 * 1000)
            "m" -> return jsRound(value * 60 * 1000)
            "s" -> return jsRound(value * 1000)
            "ms" -> return jsRound(value)
        }
    }

    // removes any non-numerical character except dots
    val parts = timeStr.split(":").map { value -> value.replace(NON_NUMERIC_REGEX, "") }
    var totalMs = 0.0

    if (parts.size == 1) {
        // Format: ss.mmm
        totalMs = jsParseFloat(parts[0]) * 1000
    } else if (parts.size == 2) {
        // Format: mm:ss.mmm
        totalMs = jsParseInt(parts[0]) * 60 * 1000 + jsParseFloat(parts[1]) * 1000
    } else if (parts.size == 3) {
        // Format: hh:mm:ss.mmm
        totalMs =
            jsParseInt(parts[0]) * 3600 * 1000 +
                jsParseInt(parts[1]) * 60 * 1000 +
                jsParseFloat(parts[2]) * 1000
    }

    return jsRound(totalMs)
}

internal fun parseTTMLTime(timeStr: String?): Double = parseTime(timeStr)

/** Upstream takes a number as it is, except a falsy one (0 or NaN), which reads as 0. */
internal fun parseTTMLTime(time: Double): Double = if (time == 0.0 || time.isNaN()) 0.0 else time

// -- Agents --------------------------------------------

private fun extractAgentMapping(metadataElements: List<XmlNode>): Map<String, String> {
    val mapping = LinkedHashMap<String, String>()
    if (metadataElements.isEmpty()) return mapping

    val agentElements = metadataElements.filter { e -> e.childrenOf("agent") != null && e.attributes != null }

    var voiceIndex = 0
    for (agent in agentElements) {
        val originalId = agent.attr("id")
        val agentType = agent.attr("type")
        if (originalId.isNullOrEmpty()) continue

        if (agentType == "person" || agentType == "character") {
            voiceIndex++
            mapping[originalId] = "v$voiceIndex"
        } else {
            mapping[originalId] = "v1000"
        }
    }
    return mapping
}

/**
 * The singers' names, by the agent ids lines carry once parsed (v1, v2, …, v1000 for a group), as the
 * document's head gives them (`<ttm:agent xml:id="…" type="person"><ttm:name>…</ttm:name>`). Not
 * upstream, which reads no names: for a host that shows who sings. Agents without a name are left out.
 */
public fun parseTTMLAgentNames(content: String): Map<String, String> {
    val names = LinkedHashMap<String, String>()
    var voiceIndex = 0
    val agentRegex = Regex("""<(?:[\w.-]+:)?agent\b([^>]*?)(/>|>(.*?)</(?:[\w.-]+:)?agent>)""", RegexOption.DOT_MATCHES_ALL)
    for (m in agentRegex.findAll(content)) {
        val attrs = m.groupValues[1]
        val id = Regex("""(?:[\w.-]+:)?\bid\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)
        if (id.isNullOrEmpty()) continue
        val type = Regex("""\btype\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)
        val mapped = if (type == "person" || type == "character") "v${++voiceIndex}" else "v1000"
        val name = Regex("""<(?:[\w.-]+:)?name\b[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[3])?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
        if (!name.isNullOrEmpty()) names[mapped] = name
    }
    return names
}

// -- Spans --------------------------------------------

/**
 * The text of a timed span. Apple and AMLL both put the word straight inside the timed span, but a
 * document that wraps it in a further untimed span is still readable, so the whole subtree counts.
 */
private fun collectSpanText(children: List<XmlNode>?): String {
    if (children == null) return ""
    val text = StringBuilder()
    for (child in children) {
        if (child.isText) text.append(child.text)
        else child.childrenOf("span")?.let { text.append(collectSpanText(it)) }
    }
    return text.toString()
}

private class LyricPartParse(val parts: MutableList<LyricPart>, val text: String, val isWordSynced: Boolean)

private fun parseLyricPart(paragraph: List<XmlNode>, beginTime: Double): LyricPartParse {
    val text = StringBuilder()
    var parts = mutableListOf<LyricPart>()
    var isWordSynced = false

    for (element in paragraph) {
        var isBackground = false
        var localP: List<XmlNode> = listOf(element)

        if (element.attr("role") == "x-bg") {
            // traverse one span in. This is a bg lyric
            isBackground = true
            localP = element.childrenOf("span") ?: emptyList()
            // Not upstream: two background spans side by side are two calls, and with nothing between
            // them their words ran into one ("ベイビー" + "Uh-ha" = "ベイビーUh-ha", IRIS OUT on Spicy
            // Lyrics, 2026-11-07). A space keeps them apart, as one between their spans would.
            val last = parts.lastOrNull()
            if (last != null && last.isBackground == true && !last.words.endsWith(" ")) {
                parts += LyricPart(startTimeMs = last.startTimeMs + last.durationMs, durationMs = 0.0, words = " ", isBackground = true)
                text.append(" ")
            }
        }

        for (subPart in localP) {
            val subPartSpan = subPart.childrenOf("span")
            if (subPart.isText && !subPart.text.isNullOrEmpty()) {
                text.append(subPart.text)
                val lastPart = parts.lastOrNull()

                parts += LyricPart(
                    startTimeMs = if (lastPart != null) lastPart.startTimeMs + lastPart.durationMs else beginTime,
                    durationMs = 0.0,
                    words = subPart.text,
                    isBackground = isBackground,
                )
            } else if (subPartSpan != null) {
                val spanText = collectSpanText(subPartSpan)
                if (spanText.isEmpty()) continue

                val startTimeMs = parseTime(subPart.attr("begin"))
                val endTimeMs = parseTime(subPart.attr("end"))
                val explicit = subPart.attr("explicit") == "true" || subPart.attr("obscene") == "true"

                parts += LyricPart(
                    startTimeMs = startTimeMs,
                    durationMs = endTimeMs - startTimeMs,
                    isBackground = isBackground,
                    words = spanText,
                    explicit = explicit,
                )
                text.append(spanText)

                isWordSynced = true
            }
        }
    }

    if (!isWordSynced) {
        parts = mutableListOf()
    }

    return LyricPartParse(parts = parts, text = text.toString(), isWordSynced = isWordSynced)
}

// -- AMLL TTML Namespace Recovery --------------------------------------------
// Some exporters (AMLL, etc.) use prefixes without declaring them; inject synthetic xmlns to keep parsers happy.
// The reader here strips prefixes without resolving them, so this changes nothing it reads. It is kept so
// the text handed to the reader is the text upstream hands to fast-xml-parser.

private val ELEMENT_PREFIX_REGEX = Regex("""</?([A-Za-z][\w.-]*):""")
private val ATTRIBUTE_PREFIX_REGEX = Regex("""$WS([A-Za-z][\w.-]*):[\w.-]+$WS*=""")
private val DECLARED_PREFIX_REGEX = Regex("""xmlns:([A-Za-z][\w.-]*)$WS*=""")
private val ROOT_TT_TAG_REGEX = Regex("""<tt\b[^>]*>""")

private fun declareMissingNamespaces(content: String): String {
    val rootMatch = ROOT_TT_TAG_REGEX.find(content) ?: return content

    val rootTag = rootMatch.value
    val declared = mutableSetOf("xml", "xmlns")
    for (match in DECLARED_PREFIX_REGEX.findAll(rootTag)) {
        declared += match.groupValues[1]
    }

    val used = LinkedHashSet<String>()
    for (match in ELEMENT_PREFIX_REGEX.findAll(content)) {
        used += match.groupValues[1]
    }
    for (match in ATTRIBUTE_PREFIX_REGEX.findAll(content)) {
        used += match.groupValues[1]
    }

    val missing = used.filter { prefix -> prefix !in declared }
    if (missing.isEmpty()) return content

    val additions = missing.joinToString("") { prefix -> " xmlns:$prefix=\"urn:braccato:unbound:$prefix\"" }
    val patchedRootTag = rootTag.dropLast(1) + additions + ">"
    return content.replaceFirst(rootTag, patchedRootTag)
}

// -- Parser --------------------------------------------

public data class ParseTTMLOptions(
    /** Used for the outro instrumental when the document's `<body>` carries no `dur`. */
    val songDurationMs: Double? = null,
    /** Silence longer than this becomes an instrumental line. Defaults to 5000. */
    val instrumentalGapMs: Double? = null,
)

public data class ParsedTTML(
    val lyrics: List<Lyric>,
    val isWordSynced: Boolean,
    val language: String? = null,
)

/**
 * Find the `<metadata>` child that holds `key`, either directly or nested one container deep, and
 * return the whole element so both its own attributes and its children are reachable.
 */
private fun findInMetadata(metadataArray: List<XmlNode>, key: String): XmlNode? {
    val direct = metadataArray.find { e -> e.childrenOf(key) != null }
    if (direct != null) return direct

    for (element in metadataArray) {
        if (element.isText) continue
        val nested = element.children.find { e -> e.childrenOf(key) != null }
        if (nested != null) return nested
    }
    return null
}

/**
 * Reads the document into its `<tt>` element, or null for anything that is not one.
 *
 * The reader throws on a document it will not read at all, nesting past its own depth cap among them.
 * Null is the only thing this says about input it cannot use, so a throw says it the same way rather
 * than reaching a caller that has no channel for it.
 */
private fun readTtmlRoot(xml: String): XmlNode? {
    // A TTML document that arrived inside a JSON string keeps its escaped quotes.
    val cleanedXml = xml.replace("\\\"", "\"")

    val rawObj = try {
        readXml(declareMissingNamespaces(cleanedXml))
    } catch (_: XmlReadException) {
        return null
    }

    return rawObj.find { e -> e.childrenOf("tt") != null }
}

private fun readMetadataElements(ttContainer: XmlNode?): List<XmlNode> {
    val head = ttContainer?.children?.find { e -> e.childrenOf("head") != null }
    val metadata = head?.children?.find { e -> e.childrenOf("metadata") != null }
    return metadata?.children ?: emptyList()
}

// Apple and lrc.red keep a `<songwriters>` list inside their own metadata container; Composer writes
// one `<composer:meta key="songwriter">` pair per name, which the namespace strip leaves as `meta`.
private fun readSongwriters(metadataElements: List<XmlNode>): List<String> {
    val names = mutableListOf<String>()

    val listElement = findInMetadata(metadataElements, "songwriters")
    for (container in listElement?.children ?: emptyList()) {
        names += collectSpanText(container.childrenOf("songwriter"))
    }

    for (element in metadataElements) {
        if (element.childrenOf("meta") == null) continue
        val key = element.attr("key")
        val value = element.attr("value")
        if ((key == "songwriter" || key == "songwriters") && !value.isNullOrEmpty()) names += value
    }

    return uniqueNames(names)
}

private fun String?.orIfEmpty(fallback: String?): String? = if (isNullOrEmpty()) fallback else this

public fun parseTTMLContent(xml: String, options: ParseTTMLOptions = ParseTTMLOptions()): ParsedTTML {
    val ttContainer = readTtmlRoot(xml) ?: return ParsedTTML(lyrics = emptyList(), isWordSynced = false)

    val tt = ttContainer.children

    val ttBodyContainer = tt.find { e -> e.childrenOf("body") != null }
    val ttBody = ttBodyContainer?.children
    val ttMeta = ttBodyContainer?.attributes

    val language = ttContainer.attr("lang").orIfEmpty(ttBodyContainer?.attr("lang"))

    if (ttBody == null) return ParsedTTML(lyrics = emptyList(), isWordSynced = false, language = language)

    val lyrics = LinkedHashMap<String, Lyric>()
    val lyricIds = mutableMapOf<String, MutableList<String>>()

    val metadataElements = readMetadataElements(ttContainer)
    val agentMapping = extractAgentMapping(metadataElements)

    val lines = ttBody.flatMap { e -> e.childrenOf("div") ?: emptyList() }.filter { e -> e.childrenOf("p") != null }

    val hasTimingData = lines.isNotEmpty() && lines[0].attributes != null
    if (!hasTimingData) {
        return ParsedTTML(lyrics = emptyList(), isWordSynced = false, language = language)
    }

    var isWordSynced = false

    for (line in lines) {
        val begin = line.attr("begin")
        if (begin.isNullOrEmpty()) continue

        val beginTimeMs = parseTime(begin)
        val endTimeMs = parseTime(line.attr("end"))

        val partParse = parseLyricPart(line.children, beginTimeMs)
        if (partParse.isWordSynced) isWordSynced = true

        val rawAgent = line.attr("agent")
        val normalizedAgent = if (!rawAgent.isNullOrEmpty()) (agentMapping[rawAgent] ?: rawAgent) else null

        // A key repeated across lines (a chorus, say) has to stay one entry per line, so each occurrence
        // gets a suffixed id and the key keeps its list of them for translations to fan back out over.
        val rawKey = line.attr("key")
        val id: String
        if (!rawKey.isNullOrEmpty()) {
            val seen = lyricIds[rawKey]
            if (seen != null) seen += "${rawKey}_${seen.size + 1}"
            else lyricIds[rawKey] = mutableListOf("${rawKey}_1")
            val ids = lyricIds.getValue(rawKey)
            id = ids[ids.size - 1]
        } else {
            id = lyrics.size.toString()
        }

        lyrics[id] = Lyric(
            agent = normalizedAgent,
            durationMs = endTimeMs - beginTimeMs,
            parts = partParse.parts,
            startTimeMs = beginTimeMs,
            words = partParse.text,
            key = rawKey ?: id,
        )
    }

    fun forEachLineWithKey(forKey: String, apply: (Lyric) -> Unit) {
        val ids = lyricIds[forKey] ?: return
        for (id in ids) {
            val line = lyrics[id]
            if (line != null) apply(line)
        }
    }

    // -- Translations --------------------------------------------
    // Two dialects reach this parser. Apple hangs the language off each `<translation>` and the line
    // key off the `<text>` inside it; the dialect this package shipped first hangs the language off
    // `<translations>` and the line key off `<translation>`. Both are read.
    val translationsElement = findInMetadata(metadataElements, "translations")
    if (translationsElement != null) {
        val outerLang = translationsElement.attr("lang")
        val containers = translationsElement.children

        for (container in containers) {
            val lang = container.attr("lang") ?: outerLang
            val containerFor = container.attr("for")

            for (item in container.childrenOf("translation") ?: emptyList()) {
                val forKey = item.attr("for") ?: containerFor
                // The whole subtree, so a `<text>` that wraps its translation in further elements reads
                // the same as a bare one. 0.1.x used textContent and gathered it the same way.
                val text = collectSpanText(item.childrenOf("text"))
                if (lang.isNullOrEmpty() || text.isEmpty() || forKey.isNullOrEmpty()) continue

                forEachLineWithKey(forKey) { line ->
                    val translations = line.translations ?: mutableMapOf<String, String>().also { line.translations = it }
                    translations[lang] = text
                    // `translation` predates `translations` in this package's published shape. The first
                    // language wins so a consumer reading it keeps getting one stable answer.
                    if (line.translation == null) line.translation = LyricTranslation(text = text, lang = lang)
                }
            }
        }
    }

    // -- Transliterations --------------------------------------------
    val transliterationsElement = findInMetadata(metadataElements, "transliterations")
    if (transliterationsElement != null) {
        val containers = transliterationsElement.children

        for (container in containers) {
            for (item in container.childrenOf("transliteration") ?: emptyList()) {
                val forKey = item.attr("for")
                // A `for` also reaches this loop on a child that is not a `<text>`, and that child has no
                // paragraph to read, so the item is passed over rather than parsed.
                val paragraph = item.childrenOf("text")
                if (forKey.isNullOrEmpty() || paragraph == null) continue

                forEachLineWithKey(forKey) { line ->
                    val parseResult = parseLyricPart(paragraph, line.startTimeMs)
                    line.romanization = parseResult.text
                    if (parseResult.parts.isNotEmpty()) line.timedRomanization = parseResult.parts
                }
            }
        }
    }

    val bodyDur = ttMeta?.get("dur")
    val songDurationMs = if (!bodyDur.isNullOrEmpty()) parseTime(bodyDur) else (options.songDurationMs ?: 0.0)
    val lyricArray = insertInstrumentalBreaks(lyrics.values.toList(), songDurationMs, options.instrumentalGapMs)

    return ParsedTTML(lyrics = lyricArray, isWordSynced = isWordSynced, language = language)
}

public object TTMLParser : LyricParser {
    override fun detect(input: String): Boolean {
        return isTtml(input)
    }

    // The duration is deliberately unused. `@braccato/provider-blyrics` calls this with one argument,
    // and a version that honoured the parameter would hand that call site a 0 song duration.
    override fun parse(input: String, duration: Double): List<Lyric> {
        return parseTTMLContent(input).lyrics
    }

    override fun metadata(input: String): LyricMetadata {
        return LyricMetadata(songwriters = readSongwriters(readMetadataElements(readTtmlRoot(input))))
    }
}
