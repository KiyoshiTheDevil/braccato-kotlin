// Renders the view off screen at chosen song times and writes each frame to build/snapshots, so a
// change can be looked at without starting the demo. Also checks that rendering a song does not throw.
package braccato.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import braccato.core.BuiltLyrics
import braccato.core.SetLyricsOptions
import braccato.core.buildLyrics
import braccato.parsers.parseTTMLContent
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

private val SAMPLE_TTML = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
<head><metadata>
  <ttm:agent type="person" xml:id="a"/><ttm:agent type="person" xml:id="b"/>
  <translations><translation type="subtitle" xml:lang="de"><text for="L2">Wir steigen höher, als wir je waren</text></translation></translations>
</metadata></head>
<body dur="40s"><div>
<p begin="6s" end="9s" itunes:key="L1" ttm:agent="a"><span begin="6s" end="6.4s">We</span> <span begin="6.4s" end="7s">rise</span> <span begin="7s" end="7.6s">into</span> <span begin="7.6s" end="9s">tomorrow</span></p>
<p begin="9.2s" end="12.5s" itunes:key="L2" ttm:agent="a"><span begin="9.2s" end="9.6s">Higher</span> <span begin="9.6s" end="10.1s">than</span> <span begin="10.1s" end="10.4s">we</span> <span begin="10.4s" end="11s">have</span> <span begin="11s" end="12.5s">been</span><span ttm:role="x-bg"><span begin="11.5s" end="12.5s">(been)</span></span></p>
<p begin="12.8s" end="15.5s" itunes:key="L3" ttm:agent="b"><span begin="12.8s" end="13.3s">Answer</span> <span begin="13.3s" end="13.8s">from</span> <span begin="13.8s" end="14.2s">the</span> <span begin="14.2s" end="15.5s">other</span></p>
<p begin="22s" end="25s" itunes:key="L4" ttm:agent="a"><span begin="22s" end="23s">After</span> <span begin="23s" end="25s">the solo</span></p>
</div></body></tt>"""

@OptIn(ExperimentalComposeUiApi::class)
private fun render(lyrics: BuiltLyrics, atS: Double, name: String, width: Int = 900, height: Int = 700, stage: Boolean = false, theme: BraccatoTheme = BraccatoTheme.Default) {
    var wall = 0.0
    val scene = ImageComposeScene(width, height, Density(1f)) {
        val modifier = Modifier.fillMaxSize().background(Color(0xFF15121C))
        val playback = { PlaybackSnapshot(timeS = atS, isPlaying = true) }
        if (stage) {
            BraccatoStage(lyrics, playback, modifier, theme = theme, wallClockMs = { wall })
        } else {
            BraccatoLyrics(lyrics, playback, modifier, theme = theme, wallClockMs = { wall })
        }
    }
    // Play up to the time, as a player would, so the view arrives the way it does in use.
    var image = scene.render(0)
    for (frame in 1..90) {
        wall = frame * 33.0
        image = scene.render(frame * 33_000_000L)
    }
    val out = File("build/snapshots").apply { mkdirs() }
    File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
}

class RenderTest {
    private val sample = buildLyrics(parseTTMLContent(SAMPLE_TTML).lyrics, SetLyricsOptions(songwriters = listOf("Ada", "Grace")))

    @Test fun `renders a song at several moments`() {
        for (t in listOf(6.3, 7.3, 10.3, 12.0, 14.0, 18.0, 30.0)) render(sample, t, "sample-$t")
        assertTrue(File("build/snapshots/sample-10.3.png").length() > 0)
    }

    @Test fun `renders the stage layout`() {
        for (t in listOf(7.3, 12.9, 30.0)) render(sample, t, "stage-$t", height = 400, stage = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `measures what a frame costs while playing`() {
        val path = System.getenv("BRACCATO_RENDER_FILE") ?: return
        val text = File(path).readText()
        val lyrics = buildLyrics(braccato.parsers.detectParser(text).parse(text, 240000.0))
        var timeS = 40.0
        var wall = 0.0
        val scene = ImageComposeScene(1000, 760, Density(1f)) {
            BraccatoLyrics(lyrics, { PlaybackSnapshot(timeS, true) }, Modifier.fillMaxSize().background(Color(0xFF15121C)), wallClockMs = { wall })
        }
        repeat(60) { scene.render(it * 16_666_667L) }
        val frames = 600
        val started = System.nanoTime()
        for (i in 0 until frames) {
            timeS += 1 / 60.0
            wall += 1000 / 60.0
            scene.render((60 + i) * 16_666_667L)
        }
        val perFrameMs = (System.nanoTime() - started) / 1e6 / frames
        println("RenderTest: ${"%.2f".format(perFrameMs)} ms per frame over $frames frames (composition, layout, drawing and readback)")
        scene.close()
    }

    @Test fun `renders a real file when one is given`() {
        val path = System.getenv("BRACCATO_RENDER_FILE") ?: return
        val atS = System.getenv("BRACCATO_RENDER_AT")?.toDouble() ?: 30.0
        val text = File(path).readText()
        val parser = braccato.parsers.detectParser(text)
        val lyrics = buildLyrics(parser.parse(text, 240000.0), SetLyricsOptions(songwriters = parser.metadata(text).songwriters))
        // BRACCATO_RENDER_WIDTH and BRACCATO_RENDER_FONT (dp) narrow the view, as on a phone.
        val width = System.getenv("BRACCATO_RENDER_WIDTH")?.toInt() ?: 900
        val font = System.getenv("BRACCATO_RENDER_FONT")?.toFloat()
        val theme = font?.let { BraccatoTheme(fontSize = androidx.compose.ui.unit.Dp(it), translatedFontSize = androidx.compose.ui.unit.Dp(it * 2 / 3)) } ?: BraccatoTheme.Default
        render(lyrics, atS, "file-$atS", width = width, theme = theme)
    }
}
