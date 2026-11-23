// Hebrew, word timed: its letters from the right, the sweep from the right (Kodama Next, 2026-11-23).
package braccato.compose

import braccato.core.SetLyricsOptions
import braccato.core.buildLyrics
import braccato.parsers.parseTTMLContent
import kotlin.test.Test

private val HEBREW_TTML = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="he">
<body dur="20s"><div>
<p begin="2s" end="6s"><span begin="2s" end="3s">תגידי</span> <span begin="3s" end="3.6s">לי</span> <span begin="3.6s" end="6s">את</span></p>
<p begin="6.2s" end="10s"><span begin="6.2s" end="7s">שנרגע</span> <span begin="7s" end="10s">אותך</span></p>
</div></body></tt>"""

private val HEBREW_LINES_TTML = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="he"><body dur="20s"><div>
<p begin="2s" end="6s">תגידי לי את</p>
<p begin="6.2s" end="10s">שנרגע אותך</p>
</div></body></tt>"""

class RtlRenderTest {
    private val lyrics = buildLyrics(parseTTMLContent(HEBREW_TTML).lyrics, SetLyricsOptions())

    @Test fun `hebrew line synced`() {
        val lines = buildLyrics(parseTTMLContent(HEBREW_LINES_TTML).lyrics, SetLyricsOptions())
        for (t in listOf(3.0, 7.0)) renderForTest(lines, t, "hebrew-lines-$t", width = 700, height = 400)
    }

    @Test fun `hebrew at several moments`() {
        for (t in listOf(2.3, 2.6, 2.9, 3.3, 4.5, 6.6, 8.0)) renderForTest(lyrics, t, "hebrew-$t", width = 700, height = 400)
    }
}
