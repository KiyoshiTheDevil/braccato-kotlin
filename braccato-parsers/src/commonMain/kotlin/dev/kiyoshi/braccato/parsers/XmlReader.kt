// Not a port of a braccato file. Upstream's TTML parser reads documents with fast-xml-parser
// (5.10, `preserveOrder`, `removeNSPrefix`, `htmlEntities`, `trimValues: false`), and this is the
// part of that library the parser relies on, rebuilt from its `OrderedObjParser` so a document reads
// the same here as it does there, leniency included:
//
// - Namespace prefixes come off element and attribute names, and `xmlns` declarations are dropped.
// - Text is kept exactly as written, whitespace-only runs included, once entities are decoded.
// - A closing tag closes whatever is open, whatever its name. Tags left open at the end stay in the
//   tree. Text after the last tag is dropped, as is text outside the root element.
// - Nesting deeper than 100 open tags, or a tag, comment or CDATA section that never closes, is an
//   error, which `parse` reports by throwing [XmlReadException].
//
// Two deliberate differences:
// - DOCTYPE declarations are skipped rather than read, so entities declared in one are not expanded.
// - An attribute written without a quoted value (`<p flag>`, or `key=k1`, which reads as two of them)
//   is the boolean `true` upstream. Attribute values are strings here, so it is the string "true".
package dev.kiyoshi.braccato.parsers

import dev.kiyoshi.braccato.types.internal.*

internal class XmlReadException(message: String) : Exception(message)

/**
 * One node of fast-xml-parser's ordered output: either an element (`{ name: children, ":@": attributes }`)
 * or a text node (`{ "#text": text }`).
 */
internal class XmlNode(
    val name: String,
    /** Attribute values by name, prefix stripped. Null when the element carries none, as `:@` is absent then. */
    val attributes: Map<String, String>? = null,
    val text: String? = null,
) {
    val children: MutableList<XmlNode> = mutableListOf()

    val isText: Boolean get() = name == TEXT_NODE_NAME

    /** `node[name]` upstream: this element's children when it is a `<name>`, else null. */
    fun childrenOf(elementName: String): List<XmlNode>? = if (name == elementName && !isText) children else null

    /** `node[":@"]?.["@_name"]` upstream. */
    fun attr(attributeName: String): String? = attributes?.get(attributeName)

    companion object {
        const val TEXT_NODE_NAME = "#text"
    }
}

private const val MAX_NESTED_TAGS = 100

// fast-xml-parser's `htmlEntities: true` table: the XML five, COMMON_HTML and CURRENCY from
// `@nodable/entities`. Numeric references are decoded besides.
private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&", "apos" to "'", "gt" to ">", "lt" to "<", "quot" to "\"",
    "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«",
    "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
    "rdquo" to "”", "bull" to "•", "para" to "¶", "sect" to "§",
    "deg" to "°", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "cent" to "¢", "pound" to "£", "curren" to "¤", "yen" to "¥", "euro" to "€",
    "dollar" to "$", "fnof" to "ƒ", "inr" to "₹", "af" to "؋", "birr" to "ብር",
    "peso" to "₱", "rub" to "₽", "won" to "₩", "yuan" to "¥", "cedil" to "¸",
)

private val ATTRIBUTE_REGEX = Regex("""([^$JS_WHITESPACE=]+)$WS*(=$WS*(['"])([\s\S]*?)\3)?""")

/** `EntityDecoder._resolveNCR`, for an XML 1.0 document. Null leaves the reference as written. */
private fun resolveNumericReference(token: String): String? {
    val second = token.getOrNull(1)
    val codePoint = if (second == 'x' || second == 'X') {
        jsParseInt(token.substring(2), 16)
    } else {
        jsParseInt(token.substring(1), 10)
    }
    if (codePoint.isNaN() || codePoint < 0 || codePoint > 0x10FFFF) return null
    val cp = codePoint.toInt()
    // NUL, lone surrogates and the C0 controls XML 1.0 forbids are removed rather than decoded.
    if (cp == 0 || cp in 0xD800..0xDFFF) return ""
    if (cp in 0x01..0x1F && cp != 0x09 && cp != 0x0A && cp != 0x0D) return ""
    return codePointToString(cp)
}

/** `EntityDecoder.decode`: one pass, so `&amp;lt;` comes out as `&lt;`. Unknown references stay. */
internal fun decodeEntities(str: String): String {
    if (str.isEmpty() || str.indexOf('&') == -1) return str

    val out = StringBuilder()
    val len = str.length
    var last = 0
    var i = 0
    while (i < len) {
        if (str[i] != '&') {
            i++
            continue
        }

        var j = i + 1
        while (j < len && str[j] != ';' && (j - i) <= 32) j++
        if (j >= len || str[j] != ';') {
            i++
            continue
        }

        val token = str.substring(i + 1, j)
        if (token.isEmpty()) {
            i++
            continue
        }

        val replacement = if (token[0] == '#') resolveNumericReference(token) else NAMED_ENTITIES[token]
        if (replacement == null) {
            i++
            continue
        }

        out.append(str, last, i)
        out.append(replacement)
        last = j + 1
        i = last
    }
    if (last < len) out.append(str, last, len)
    return out.toString()
}

/** `resolveNameSpace` with `removeNSPrefix`: `xmlns` and `xmlns:*` vanish, `a:b` becomes `b`. */
private fun resolveNameSpace(name: String): String {
    val tags = name.split(":")
    if (tags[0] == "xmlns") return ""
    if (tags.size == 2) return tags[1]
    return name
}

private fun buildAttributesMap(attrStr: String): Map<String, String>? {
    val attrs = LinkedHashMap<String, String>()
    for (match in ATTRIBUTE_REGEX.findAll(attrStr)) {
        val attrName = resolveNameSpace(match.groupValues[1])
        if (attrName.isEmpty()) continue
        // Group 2 is the whole `="value"`. Without it the attribute is a boolean one.
        attrs[attrName] = if (match.groups[2] != null) decodeEntities(match.groupValues[4]) else "true"
    }
    return attrs.ifEmpty { null }
}

private class TagExp(
    val tagName: String,
    val tagExp: String,
    val closeIndex: Int,
    val attrExpPresent: Boolean,
)

/** `tagExpWithClosingIndex` and `readTagExp`: the tag from `i` (its `<`) to its closing string. */
private fun readTagExp(xmlData: String, i: Int, removeNSPrefix: Boolean, closingChar: String = ">"): TagExp? {
    var attrBoundary: Char? = null
    val data = StringBuilder()
    var closeIndex = -1
    var index = i + 1
    while (index < xmlData.length) {
        val c = xmlData[index]
        if (attrBoundary != null) {
            if (c == attrBoundary) attrBoundary = null
            data.append(c)
        } else if (c == '"' || c == '\'') {
            attrBoundary = c
            data.append(c)
        } else if (c == closingChar[0] && (closingChar.length == 1 || xmlData.getOrNull(index + 1) == closingChar[1])) {
            closeIndex = index
            break
        } else {
            // A tab outside an attribute value reads as a space.
            data.append(if (c == '\t') ' ' else c)
        }
        index++
    }
    if (closeIndex == -1) return null

    val raw = data.toString()
    var tagExp = raw
    val separatorIndex = raw.indexOfFirst(::isJsWhitespace)
    var tagName = raw
    var attrExpPresent = true
    if (separatorIndex != -1) {
        tagName = raw.substring(0, separatorIndex)
        tagExp = raw.substring(separatorIndex + 1).jsTrimStart()
    }

    if (removeNSPrefix) {
        val colonIndex = tagName.indexOf(":")
        if (colonIndex != -1) {
            tagName = tagName.substring(colonIndex + 1)
            attrExpPresent = tagName != raw.substring(colonIndex + 1)
        }
    }

    return TagExp(tagName = tagName, tagExp = tagExp, closeIndex = closeIndex, attrExpPresent = attrExpPresent)
}

private fun findClosingIndex(xmlData: String, str: String, i: Int, errMsg: String): Int {
    val closingIndex = xmlData.indexOf(str, i)
    if (closingIndex == -1) throw XmlReadException(errMsg)
    return closingIndex + str.length - 1
}

/** Skips a DOCTYPE declaration, internal subset included, and returns the index of its closing `>`. */
private fun skipDocType(xmlData: String, i: Int): Int {
    val bracket = xmlData.indexOf('[', i)
    val close = xmlData.indexOf('>', i)
    if (close == -1) throw XmlReadException("DOCTYPE is not closed.")
    if (bracket == -1 || bracket > close) return close
    val subsetEnd = xmlData.indexOf(']', bracket)
    if (subsetEnd == -1) throw XmlReadException("DOCTYPE is not closed.")
    return findClosingIndex(xmlData, ">", subsetEnd, "DOCTYPE is not closed.")
}

/** `OrderedObjParser.parseXml`: the document's top-level nodes. */
internal fun readXml(input: String): List<XmlNode> {
    val xmlData = input.replace(Regex("\r\n?"), "\n")
    val xmlObj = XmlNode("!xml")
    var currentNode = xmlObj
    val tagsNodeStack = ArrayList<XmlNode>()
    val textData = StringBuilder()

    fun saveTextToParentTag(parentNode: XmlNode) {
        if (textData.isEmpty()) return
        val text = decodeEntities(textData.toString())
        if (text.isNotEmpty()) parentNode.children += XmlNode(XmlNode.TEXT_NODE_NAME, text = text)
        textData.clear()
    }

    val xmlLen = xmlData.length
    var i = 0
    while (i < xmlLen) {
        val ch = xmlData[i]
        if (ch == '<') {
            val c1 = xmlData.getOrNull(i + 1)
            if (c1 == '/') { // Closing Tag
                val closeIndex = findClosingIndex(xmlData, ">", i, "Closing Tag is not closed.")
                saveTextToParentTag(currentNode)
                currentNode = tagsNodeStack.removeLastOrNull() ?: xmlObj
                textData.clear()
                i = closeIndex
            } else if (c1 == '?') { // Processing instruction, such as the XML declaration
                val tagData = readTagExp(xmlData, i, false, "?>") ?: throw XmlReadException("Pi Tag is not closed.")
                saveTextToParentTag(currentNode)
                val childNode = XmlNode(tagData.tagName)
                childNode.children += XmlNode(XmlNode.TEXT_NODE_NAME, text = "")
                currentNode.children += childNode
                i = tagData.closeIndex + 1
            } else if (c1 == '!' && xmlData.getOrNull(i + 2) == '-' && xmlData.getOrNull(i + 3) == '-') { // Comment
                i = findClosingIndex(xmlData, "-->", i + 4, "Comment is not closed.")
            } else if (c1 == '!' && xmlData.getOrNull(i + 2) == 'D') { // DOCTYPE
                i = skipDocType(xmlData, i)
            } else if (c1 == '!' && xmlData.getOrNull(i + 2) == '[') { // CDATA
                val closeIndex = findClosingIndex(xmlData, "]]>", i, "CDATA is not closed.") - 2
                val tagExp = if (i + 9 <= closeIndex) xmlData.substring(i + 9, closeIndex) else ""
                saveTextToParentTag(currentNode)
                currentNode.children += XmlNode(XmlNode.TEXT_NODE_NAME, text = tagExp)
                i = closeIndex + 2
            } else { // Opening tag
                val result = readTagExp(xmlData, i, true)
                    ?: throw XmlReadException("Tag is not closed at position $i.")

                var tagName = result.tagName
                var tagExp = result.tagExp
                var attrExpPresent = result.attrExpPresent

                if (textData.isNotEmpty() && currentNode.name != "!xml") {
                    saveTextToParentTag(currentNode)
                }

                var isSelfClosing = false
                if (tagExp.isNotEmpty() && tagExp.lastIndexOf("/") == tagExp.length - 1) {
                    isSelfClosing = true
                    if (tagName.endsWith("/")) {
                        tagName = tagName.substring(0, tagName.length - 1)
                        tagExp = tagName
                    } else {
                        tagExp = tagExp.substring(0, tagExp.length - 1)
                    }
                    attrExpPresent = tagName != tagExp
                }

                val attributes = if (tagName != tagExp && attrExpPresent) buildAttributesMap(tagExp) else null
                val childNode = XmlNode(tagName, attributes)

                if (isSelfClosing) {
                    currentNode.children += childNode
                } else {
                    if (tagsNodeStack.size > MAX_NESTED_TAGS) {
                        throw XmlReadException("Maximum nested tags exceeded")
                    }
                    tagsNodeStack += currentNode
                    currentNode.children += childNode
                    currentNode = childNode
                }
                textData.clear()
                i = result.closeIndex
            }
        } else {
            textData.append(ch)
        }
        i++
    }
    return xmlObj.children
}
