// Port of packages/parsers/src/__tests__/ttml.test.ts.
package braccato.parsers

import braccato.types.Lyric
import braccato.types.LyricTranslation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun nonInstrumental(lyrics: List<Lyric>) = lyrics.filter { it.isInstrumental != true }

class ParseTTMLTimeTest {
    @Test fun `parses offset-time with seconds`() = assertEquals(5500.0, parseTTMLTime("5.5s"))

    @Test fun `parses offset-time with milliseconds`() = assertEquals(1234.0, parseTTMLTime("1234ms"))

    @Test fun `parses offset-time with minutes`() = assertEquals(120000.0, parseTTMLTime("2m"))

    @Test fun `parses offset-time with hours`() = assertEquals(3600000.0, parseTTMLTime("1h"))

    @Test fun `parses clock-time hh_mm_ss_mmm`() = assertEquals(90500.0, parseTTMLTime("00:01:30.500"))

    @Test fun `parses mm_ss_mmm`() = assertEquals(90500.0, parseTTMLTime("01:30.500"))

    @Test fun `handles undefined`() = assertEquals(0.0, parseTTMLTime(null))

    @Test fun `handles numeric input`() = assertEquals(5000.0, parseTTMLTime(5000.0))
}

class TTMLParserTest {
    // -- detect --

    @Test fun `detects TTML content`() = assertTrue(TTMLParser.detect("""<tt xmlns="http://www.w3.org/ns/ttml"></tt>"""))

    @Test fun `rejects non-TTML`() = assertEquals(false, TTMLParser.detect("[00:12.50]Hello"))

    // -- parse --

    @Test fun `parses line-synced TTML`() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata></metadata></head>
  <body dur="60s">
    <div>
      <p begin="5s" end="10s" key="l1">Hello world</p>
      <p begin="10s" end="15s" key="l2">Second line</p>
    </div>
  </body>
</tt>"""

        val result = TTMLParser.parse(ttml)

        assertTrue(result.size >= 2)
        val lines = nonInstrumental(result)
        assertEquals(2, lines.size)
        assertEquals(5000.0, lines[0].startTimeMs)
        assertEquals("Hello world", lines[0].words)
        assertEquals(5000.0, lines[0].durationMs)
        assertEquals(10000.0, lines[1].startTimeMs)
    }

    @Test fun `parses word-synced TTML`() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata></metadata></head>
  <body dur="30s">
    <div>
      <p begin="5s" end="10s" key="l1">
        <span begin="5s" end="7s"><span>Hello </span></span>
        <span begin="7s" end="10s"><span>world</span></span>
      </p>
    </div>
  </body>
</tt>"""

        val lines = nonInstrumental(TTMLParser.parse(ttml))

        assertEquals(1, lines.size)
        val parts = lines[0].parts
        assertTrue(parts != null && parts.size >= 2)
    }

    @Test fun `decodes HTML and XML entities in line and word text`() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata></metadata></head>
  <body dur="30s">
    <div>
      <p begin="5s" end="10s" key="l1">It&rsquo;s caf&#233; &amp; more&#8230;</p>
      <p begin="10s" end="15s" key="l2"><span begin="10s" end="12s">don&#39;t</span><span begin="12s" end="15s">na&#xEF;ve</span></p>
    </div>
  </body>
</tt>"""

        val lines = nonInstrumental(TTMLParser.parse(ttml))

        assertEquals("It’s café & more…", lines[0].words)
        assertEquals(listOf("don't", "naïve"), lines[1].parts!!.map { it.words })
    }

    @Test fun `detects language`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="ja">
  <head><metadata></metadata></head>
  <body dur="10s">
    <div><p begin="0s" end="5s" key="l1">Test</p></div>
  </body>
</tt>"""

        assertEquals("ja", parseTTMLContent(ttml).language)
    }

    @Test fun `inserts instrumental breaks for large gaps`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata></metadata></head>
  <body dur="60s">
    <div>
      <p begin="0s" end="5s" key="l1">Line one</p>
      <p begin="20s" end="25s" key="l2">Line two</p>
    </div>
  </body>
</tt>"""

        val instrumentals = TTMLParser.parse(ttml).filter { it.isInstrumental == true }
        assertTrue(instrumentals.isNotEmpty())
    }

    @Test fun `handles translations`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="ja"
              xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
  <head>
    <metadata>
      <translations lang="en">
        <translation for="l1"><text>Hello world</text></translation>
      </translations>
    </metadata>
  </head>
  <body dur="10s">
    <div><p begin="0s" end="5s" key="l1">Konnichiwa</p></div>
  </body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)
        assertEquals(LyricTranslation(text = "Hello world", lang = "en"), lines[0].translation)
    }

    @Test fun `extracts transliterations attached to inner text elements`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="ja">
  <head>
    <metadata>
      <transliterations>
        <transliteration xml:lang="ja-Latn">
          <text for="l1"><span begin="0s" end="2s">konnichi</span><span begin="2s" end="5s">wa</span></text>
          <text for="l2"><span begin="5s" end="7s">sayou</span><span begin="7s" end="10s">nara</span></text>
        </transliteration>
      </transliterations>
    </metadata>
  </head>
  <body dur="10s">
    <div>
      <p begin="0s" end="5s" key="l1">こんにちは</p>
      <p begin="5s" end="10s" key="l2">さようなら</p>
    </div>
  </body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)
        assertEquals(2, lines.size)
        assertEquals("konnichiwa", lines[0].romanization)
        assertEquals(2, lines[0].timedRomanization?.size)
        assertEquals("sayounara", lines[1].romanization)
        assertEquals(2, lines[1].timedRomanization?.size)
    }

    @Test fun `skips a transliteration item that carries no text`() {
        // A <transliteration> child that is not a <text> still carries a `for`, so the item is
        // reached and its text is absent. Reading it as a paragraph used to throw a TypeError, and
        // one such child took the whole document with it.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="ja">
  <head>
    <metadata>
      <transliterations>
        <transliteration xml:lang="ja-Latn">
          <notext for="l1"/>
          <text for="l2"><span begin="5s" end="7s">sayou</span><span begin="7s" end="10s">nara</span></text>
        </transliteration>
      </transliterations>
    </metadata>
  </head>
  <body dur="10s">
    <div>
      <p begin="0s" end="5s" key="l1">こんにちは</p>
      <p begin="5s" end="10s" key="l2">さようなら</p>
    </div>
  </body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(2, lines.size)
        assertNull(lines[0].romanization)
        assertEquals("sayounara", lines[1].romanization)
    }

    @Test fun `returns an empty result for a document the XML parser rejects`() {
        // The reader caps how deep a document may nest and throws past it. The throw used to
        // escape, which every caller reads as "this parser does not throw" would not survive.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s" end="5s">${"<span>".repeat(600)}Deep${"</span>".repeat(600)}</p></div></body></tt>"""

        assertEquals(ParsedTTML(lyrics = emptyList(), isWordSynced = false), parseTTMLContent(ttml))
        assertEquals(emptyList(), TTMLParser.parse(ttml))
    }

    @Test fun `handles empty body`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata></metadata></head>
  <body></body>
</tt>"""

        assertEquals(emptyList(), TTMLParser.parse(ttml))
    }

    @Test fun `inserts an intro instrumental even when the body carries no dur`() {
        // Intentional. Instrumental insertion is not conditional on <body dur>, which is what the
        // implementation this was ported from does, and it is a change from 0.1.x, where a document
        // with no dur got no breaks at all. It matters because `@braccato/provider-blyrics` calls
        // `TTMLParser.parse(ttml)` with no duration, so a bLyrics document whose first line starts
        // late gains a leading instrumental line it did not have before.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <body><div>
    <p begin="10s" end="15s" itunes:key="L1">Late first line</p>
    <p begin="30s" end="35s" itunes:key="L2">After a solo</p>
  </div></body>
</tt>"""

        val result = TTMLParser.parse(ttml)

        assertEquals(
            listOf(
                Triple(0.0, 10000.0, true),
                Triple(10000.0, 5000.0, false),
                Triple(15000.0, 15000.0, true),
                Triple(30000.0, 5000.0, false),
            ),
            result.map { Triple(it.startTimeMs, it.durationMs, it.isInstrumental ?: false) },
        )
        // The outro is the one break a document with no dur still cannot have: nothing states where
        // the song ends.
        assertNull(result.last().isInstrumental)
    }

    @Test fun `ignores the duration argument`() {
        // `@braccato/provider-blyrics` calls `TTMLParser.parse(ttml)` with no second argument, so a
        // version that started honouring the parameter would silently hand that call site a 0 and
        // change its instrumental breaks. The song duration travels through `parseTTMLContent`.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <body><div><p begin="0s" end="5s" itunes:key="L1">Only line</p></div></body>
</tt>"""

        assertEquals(TTMLParser.parse(ttml), TTMLParser.parse(ttml, 600000.0))
        assertEquals(0, TTMLParser.parse(ttml, 600000.0).count { it.isInstrumental == true })

        val viaOption = parseTTMLContent(ttml, ParseTTMLOptions(songDurationMs = 600000.0))
        assertEquals(1, viaOption.lyrics.count { it.isInstrumental == true })
    }

    // -- malformed input --
    // A file a reader drops onto a page can be anything at all, so the contract for anything the
    // parser cannot use is that nothing comes out of it and nothing is thrown. Pinned rather than
    // assumed: two of the cases below used to throw.

    private val deeplyNested = """<tt><body><div><p begin="0s" end="5s">${"<span>".repeat(600)}x${"</span>".repeat(600)}</p></div></body></tt>"""

    private val unreadable = listOf(
        "an empty string" to "",
        "whitespace only" to "   \n\t  ",
        "text that is not TTML" to "just some prose, nothing timed about it",
        "another lyrics format entirely" to "[00:12.50]Hello world",
        "a mismatched closing tag" to "<tt><body></head></tt>",
        "tags nested deeper than the reader accepts" to deeplyNested,
        "a document with no body" to "<tt></tt>",
        "a body with no lines" to "<tt><body></body></tt>",
        "lines with no timing attributes" to "<tt><body><div><p>Hi</p></div></body></tt>",
        "timing attributes that are present but empty" to
            """<tt xml:lang=""><body dur=""><div><p begin="" end="" key="">Hi</p></div></body></tt>""",
    )

    @Test fun `returns nothing for unreadable input`() {
        for ((label, input) in unreadable) {
            assertEquals(emptyList(), TTMLParser.parse(input), label)
            assertEquals(emptyList(), parseTTMLContent(input).lyrics, label)
        }
    }

    @Test fun `keeps no words from a document that stops mid line`() {
        val truncated = """<tt><body><div><p begin="0s" end="5s">Hello"""

        assertTrue(TTMLParser.parse(truncated).all { it.words == "" })
    }

    @Test fun `still reads a document whose tags are left open at the end`() {
        // Leniency, not junk: everything the document states is there, only the closing tags are
        // missing, and a reader that dropped the line would be throwing away a readable file.
        val result = TTMLParser.parse("""<tt><body><div><p begin="0s" end="5s" key="k">Hi</p></div></body>""")

        assertEquals(listOf("Hi"), nonInstrumental(result).map { it.words })
    }

    @Test fun `reads the lines of a document whose metadata is malformed`() {
        // Well formed XML that is not the shape the reader expects: a translation with no text and a
        // transliteration child that is not a <text>. None of it reaches the lines, and none of it
        // stops them being read. The second one used to throw and take the document with it.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <head><metadata>
    <translations lang="en"><translation for="k"/></translations>
    <transliterations><transliteration><notext for="k"/></transliteration></transliterations>
  </metadata></head>
  <body dur="10s"><div><p begin="0s" end="5s" key="k">Hi</p></div></body>
</tt>"""

        val lines = nonInstrumental(TTMLParser.parse(ttml))
        assertEquals(listOf("Hi"), lines.map { it.words })
        assertNull(lines[0].translation)
        assertNull(lines[0].romanization)
    }

    @Test fun `does not throw on timing attributes that are not times`() {
        // The line survives carrying times that are not numbers rather than being dropped. Pinned as
        // it stands, since this is inherited behaviour and tightening it is the owner's call.
        val ttml = """<tt><body dur="banana"><div><p begin="banana" end="pear" key="k">Hi</p></div></body></tt>"""

        assertEquals(listOf(true), TTMLParser.parse(ttml).map { it.startTimeMs.isNaN() })
    }

    // -- agents --

    private val agentsTtml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <head><metadata>
    <ttm:agent type="person" xml:id="singer1"><ttm:name type="full">First</ttm:name></ttm:agent>
    <ttm:agent type="person" xml:id="singer2"/>
    <ttm:agent type="group" xml:id="chorus"/>
  </metadata></head>
  <body dur="20s"><div>
    <p begin="0s" end="5s" ttm:agent="singer1" itunes:key="L1">One</p>
    <p begin="5s" end="10s" ttm:agent="singer2" itunes:key="L2">Two</p>
    <p begin="10s" end="15s" ttm:agent="chorus" itunes:key="L3">Three</p>
    <p begin="15s" end="20s" ttm:agent="singer1" itunes:key="L4">Four</p>
  </div></body>
</tt>"""

    @Test fun `maps each person agent to a stable vocalist slot`() {
        val lines = nonInstrumental(parseTTMLContent(agentsTtml).lyrics)
        assertEquals(listOf("v1", "v2", "v1000", "v1"), lines.map { it.agent })
    }

    @Test fun `buckets a non-person agent into the group slot`() {
        val lines = nonInstrumental(parseTTMLContent(agentsTtml).lyrics)
        assertEquals("v1000", lines[2].agent)
    }

    @Test fun `passes an agent through untouched when no metadata declares it`() {
        val undeclared = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <body dur="10s"><div><p begin="0s" end="5s" ttm:agent="v7" itunes:key="L1">One</p></div></body>
</tt>"""
        val lines = nonInstrumental(parseTTMLContent(undeclared).lyrics)
        assertEquals("v7", lines[0].agent)
    }

    // -- background vocals --

    @Test fun `marks the parts inside a ttm_role x-bg span as background`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <body dur="10s"><div><p begin="0s" end="6s" itunes:key="L1"><span begin="0s" end="2s">Hello</span><span ttm:role="x-bg"><span begin="2s" end="4s">(echo)</span><span begin="4s" end="6s">(echo)</span></span></p></div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(3, lines[0].parts?.size)
        assertEquals(listOf(false, true, true), lines[0].parts!!.map { it.isBackground })
        assertEquals("Hello(echo)(echo)", lines[0].words)
    }

    // -- explicit content --

    private fun explicitTtml(attr: String) = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
              xmlns:amll="http://www.example.com/ns/amll" xml:lang="en">
  <body dur="10s"><div><p begin="0s" end="4s" itunes:key="L1"><span begin="0s" end="2s" $attr>Damn</span><span begin="2s" end="4s">it</span></p></div></body>
</tt>"""

    @Test fun `reads explicit=true`() {
        val lines = nonInstrumental(parseTTMLContent(explicitTtml("""explicit="true"""")).lyrics)
        assertEquals(listOf(true, false), lines[0].parts!!.map { it.explicit })
    }

    @Test fun `reads AMLL's obscene=true`() {
        val lines = nonInstrumental(parseTTMLContent(explicitTtml("""amll:obscene="true"""")).lyrics)
        assertEquals(listOf(true, false), lines[0].parts!!.map { it.explicit })
    }

    // -- itunes metadata --

    @Test fun `keeps every line that repeats an itunes_key`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <body dur="30s"><div>
    <p begin="0s" end="5s" itunes:key="L1">Chorus line</p>
    <p begin="5s" end="10s" itunes:key="L2">Verse line</p>
    <p begin="10s" end="15s" itunes:key="L1">Chorus line</p>
  </div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(3, lines.size)
        assertEquals(listOf(0.0, 5000.0, 10000.0), lines.map { it.startTimeMs })
        assertEquals(listOf("L1", "L2", "L1"), lines.map { it.key })
    }

    @Test fun `tolerates itunes_songPart on the div`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <body dur="20s">
    <div begin="0s" end="10s" itunes:songPart="Verse"><p begin="0s" end="5s" itunes:key="L1">Verse line</p></div>
    <div begin="10s" end="20s" itunes:songPart="Chorus"><p begin="10s" end="15s" itunes:key="L2">Chorus line</p></div>
  </body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(listOf("Verse line", "Chorus line"), lines.map { it.words })
    }

    // -- namespaces --

    @Test fun `parses a document whose prefixes are never declared`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
  <body dur="10s"><div><p begin="0s" end="5s" itunes:key="L1" ttm:agent="v1">Unbound</p></div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(1, lines.size)
        assertEquals("Unbound", lines[0].words)
        assertEquals("L1", lines[0].key)
        assertEquals("v1", lines[0].agent)
    }

    // -- offset times --

    @Test fun `reads begin and end given in offset-time units`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
  <body dur="5m"><div><p begin="60000ms" end="1.5m" itunes:key="L1"><span begin="60.5s" end="61s">Late</span></p></div></body>
</tt>"""

        val result = parseTTMLContent(ttml)
        val lines = nonInstrumental(result.lyrics)

        assertEquals(60000.0, lines[0].startTimeMs)
        assertEquals(30000.0, lines[0].durationMs)
        assertEquals(60500.0, lines[0].parts!![0].startTimeMs)
        assertEquals(500.0, lines[0].parts!![0].durationMs)
        // dur="5m" is the only source of the outro, so it has to have been read as 300000
        val outro = result.lyrics.filter { it.isInstrumental == true }.last()
        assertEquals(300000.0, outro.startTimeMs + outro.durationMs)
    }

    // -- Apple dialect translations --

    @Test fun `reads a translation attached to an inner text element`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="ja">
  <head><metadata>
    <translations>
      <translation type="subtitle" xml:lang="en"><text for="L1">Hello world</text></translation>
      <translation type="subtitle" xml:lang="fr"><text for="L1">Bonjour</text></translation>
    </translations>
  </metadata></head>
  <body dur="10s"><div><p begin="0s" end="5s" itunes:key="L1">Konnichiwa</p></div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals<Map<String, String>?>(mapOf("en" to "Hello world", "fr" to "Bonjour"), lines[0].translations)
        assertEquals(LyricTranslation(text = "Hello world", lang = "en"), lines[0].translation)
    }

    @Test fun `reads a translation whose text is wrapped in further elements`() {
        // 0.1.x read the translation with textContent, which gathered the whole subtree. Reading only
        // the first child, and only when it was a text node, lost a wrapped one entirely.
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="ja">
  <head><metadata>
    <translations lang="en">
      <translation for="L1"><text><span>Hello</span><span> world</span></text></translation>
    </translations>
  </metadata></head>
  <body dur="10s"><div><p begin="0s" end="5s" itunes:key="L1">Konnichiwa</p></div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(LyricTranslation(text = "Hello world", lang = "en"), lines[0].translation)
        assertEquals<Map<String, String>?>(mapOf("en" to "Hello world"), lines[0].translations)
    }

    @Test fun `carries a translation onto every line sharing an itunes_key`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
              xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="ja">
  <head><metadata>
    <translations>
      <translation type="subtitle" xml:lang="en"><text for="L1">Hello world</text></translation>
    </translations>
  </metadata></head>
  <body dur="20s"><div>
    <p begin="0s" end="5s" itunes:key="L1">Konnichiwa</p>
    <p begin="5s" end="10s" itunes:key="L1">Konnichiwa</p>
  </div></body>
</tt>"""

        val lines = nonInstrumental(parseTTMLContent(ttml).lyrics)

        assertEquals(2, lines.size)
        assertEquals(listOf("Hello world", "Hello world"), lines.map { it.translations?.get("en") })
    }
}

class TTMLParserMetadataTest {
    private val body = """<body dur="10s"><div><p begin="0s" end="5s">Line</p></div></body>"""

    @Test fun `reads Apple's iTunesMetadata songwriters`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en"><head><metadata><ttm:agent type="person" xml:id="v1"/><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations/><songwriters><songwriter>Abel Tesfaye</songwriter><songwriter>Max Martin</songwriter></songwriters><audio lyricOffset="0.046" role="spatial"/></iTunesMetadata></metadata></head>$body</tt>"""

        assertEquals(LyricMetadata(songwriters = listOf("Abel Tesfaye", "Max Martin")), TTMLParser.metadata(ttml))
    }

    @Test fun `reads lrc_red's sourceMetadata songwriters`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal"><head><metadata><sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal" leadingSilence="0"><translations/><songwriters><songwriter>Oscar Thomas Holter</songwriter></songwriters></sourceMetadata></metadata></head>$body</tt>"""

        assertEquals(listOf("Oscar Thomas Holter"), TTMLParser.metadata(ttml).songwriters)
    }

    @Test fun `reads Composer's meta pairs under either key`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:composer="https://composer.boidu.dev/ns"><head><metadata><composer:meta key="artists" value="Someone"/><composer:meta key="songwriter" value="A"/><composer:meta key="songwriters" value="B"/></metadata></head>$body</tt>"""

        assertEquals(listOf("A", "B"), TTMLParser.metadata(ttml).songwriters)
    }

    // -- edge cases --

    @Test fun `reads a pretty-printed head`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml">
  <head>
    <metadata>
      <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <songwriters>
          <songwriter>Chester Bennington</songwriter>
          <songwriter>Mike Shinoda</songwriter>
        </songwriters>
      </iTunesMetadata>
    </metadata>
  </head>
  $body
</tt>"""

        assertEquals(listOf("Chester Bennington", "Mike Shinoda"), TTMLParser.metadata(ttml).songwriters)
    }

    @Test fun `decodes entities and keeps a name with a comma whole`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><head><metadata><iTunesMetadata><songwriters><songwriter>Tom &amp; Jerry</songwriter><songwriter>Smith, John</songwriter></songwriters></iTunesMetadata></metadata></head>$body</tt>"""

        assertEquals(listOf("Tom & Jerry", "Smith, John"), TTMLParser.metadata(ttml).songwriters)
    }

    @Test fun `names a person once when two sources credit them`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:composer="https://composer.boidu.dev/ns"><head><metadata><composer:meta key="songwriter" value="A"/><iTunesMetadata><songwriters><songwriter>A</songwriter><songwriter> </songwriter></songwriters></iTunesMetadata></metadata></head>$body</tt>"""

        assertEquals(listOf("A"), TTMLParser.metadata(ttml).songwriters)
    }

    @Test fun `reads nothing from a document without a head, with an empty list, or that is not XML`() {
        assertEquals(emptyList(), TTMLParser.metadata("""<tt xmlns="http://www.w3.org/ns/ttml">$body</tt>""").songwriters)
        assertEquals(
            emptyList(),
            TTMLParser.metadata(
                """<tt xmlns="http://www.w3.org/ns/ttml"><head><metadata><iTunesMetadata><songwriters/></iTunesMetadata></metadata></head>$body</tt>""",
            ).songwriters,
        )
        assertEquals(emptyList(), TTMLParser.metadata("").songwriters)
        assertEquals(emptyList(), TTMLParser.metadata("<tt><body>").songwriters)
    }

    // -- invariants --

    @Test fun `leaves the parsed lyrics and their result shape alone`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><head><metadata><iTunesMetadata><songwriters><songwriter>A</songwriter></songwriters></iTunesMetadata></metadata></head>$body</tt>"""

        val parsed = parseTTMLContent(ttml)
        assertNull(parsed.language)
        assertEquals(listOf("Line"), nonInstrumental(parsed.lyrics).map { it.words })
    }
}
