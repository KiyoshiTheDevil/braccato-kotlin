// How the line being sung moves on screen while decorations come and go during playback. Prints the
// active line's top for every frame, so a jump or a swing away and back shows in the numbers.
package dev.kiyoshi.braccato.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.kiyoshi.braccato.core.buildLyrics
import dev.kiyoshi.braccato.core.buildTranslation
import dev.kiyoshi.braccato.parsers.detectParser
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class DecorationMotionTest {
    /** The topmost row with fully bright pixels: the lit part of the active line. */
    private fun activeTop(image: org.jetbrains.skia.Image): Int? {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
        image.readPixels(bitmap)
        val bytes = bitmap.readPixels()!!
        for (y in 0 until image.height) for (x in 0 until image.width step 2) {
            val i = (y * image.width + x) * 4
            if ((bytes[i].toInt() and 0xFF) > 245 && (bytes[i + 1].toInt() and 0xFF) > 245 && (bytes[i + 2].toInt() and 0xFF) > 245) return y
        }
        return null
    }

    private fun run(label: String, staggerFrames: Int, asRomanization: Boolean = false) {
        val path = System.getenv("BRACCATO_RENDER_FILE") ?: return
        val startS = System.getenv("BRACCATO_RENDER_AT")?.toDouble() ?: 30.0
        val text = File(path).readText()
        val lyrics = buildLyrics(detectParser(text).parse(text, 240000.0))
        val streamed = mutableStateMapOf<Int, String>()
        var timeS = startS
        var wall = 0.0
        val scene = ImageComposeScene(500, 800, Density(1f)) {
            BraccatoLyrics(
                lyrics,
                { PlaybackSnapshot(timeS, true) },
                Modifier.fillMaxSize().background(Color(0xFF15121C)),
                theme = BraccatoTheme(fontSize = androidx.compose.ui.unit.Dp(30f), translatedFontSize = androidx.compose.ui.unit.Dp(20f)),
                decorations = { line ->
                    val t = streamed[line.index]
                    val main = line.content?.main?.text
                    if (t == null || main == null) LineDecorations.None
                    else if (asRomanization) LineDecorations(romanization = dev.kiyoshi.braccato.core.buildRomanization(main, line.language, t))
                    else LineDecorations(translation = buildTranslation(main, t, "de"))
                },
                wallClockMs = { wall },
            )
        }
        fun frame(n: Int) = scene.render(n * 16_666_667L).also { timeS += 1 / 60.0; wall += 1000 / 60.0 }
        for (n in 0 until 120) frame(n)

        val tops = mutableListOf<Int?>()
        val sung = lyrics.lines.filter { !it.isInstrumental }
        var next = 0
        for (n in 120 until 300) {
            // Switch on at frame 140, one line every staggerFrames; switch off at frame 230, all at once.
            if (staggerFrames == 0 && n == 140) {
                sung.forEach { streamed[it.index] = "Übersetzung der Zeile ${it.index}" }
            } else if (staggerFrames > 0 && n >= 140 && n < 230 && next < sung.size && (n - 140) % staggerFrames == 0) {
                streamed[sung[next].index] = "Übersetzung der Zeile ${sung[next].index}"
                next++
            }
            if (n == 230) streamed.clear()
            tops += activeTop(frame(n))
        }
        println("DecorationMotionTest [$label]: " + tops.joinToString(" ") { it?.toString() ?: "-" })
        scene.close()
    }

    @Test fun `all at once`() = run("all at once", 0)

    @Test fun `one line every frame`() = run("one line every frame", 1)

    @Test fun `one line every four frames`() = run("one line every four frames", 4)

    /** As kodama-next does it: the lyrics are built again on every switch, translations on the lines. */
    @Test fun `lyrics built again with translations on their lines`() {
        val path = System.getenv("BRACCATO_RENDER_FILE") ?: return
        val text = File(path).readText()
        val parser = detectParser(text)
        fun build(translated: Boolean) = buildLyrics(
            parser.parse(text, 240000.0).onEach { lyric ->
                if (translated && lyric.isInstrumental != true) lyric.translation = dev.kiyoshi.braccato.types.LyricTranslation("Übersetzung: ${lyric.words.take(12)}", "de")
            },
        )
        var lyrics by androidx.compose.runtime.mutableStateOf(build(false))
        var timeS = System.getenv("BRACCATO_RENDER_AT")?.toDouble() ?: 30.0
        var wall = 0.0
        val scene = ImageComposeScene(500, 800, Density(1f)) {
            BraccatoLyrics(
                lyrics,
                { PlaybackSnapshot(timeS, true) },
                Modifier.fillMaxSize().background(Color(0xFF15121C)),
                theme = BraccatoTheme(fontSize = androidx.compose.ui.unit.Dp(30f), translatedFontSize = androidx.compose.ui.unit.Dp(20f)),
                wallClockMs = { wall },
            )
        }
        fun frame(n: Int) = scene.render(n * 16_666_667L).also { timeS += 1 / 60.0; wall += 1000 / 60.0 }
        for (n in 0 until 120) frame(n)
        val tops = mutableListOf<Int?>()
        for (n in 120 until 300) {
            if (n == 140) lyrics = build(true)
            if (n == 230) lyrics = build(false)
            tops += activeTop(frame(n))
        }
        println("DecorationMotionTest [rebuilt lyrics]: " + tops.joinToString(" ") { it?.toString() ?: "-" })
        scene.close()
    }

    @Test fun `romanization pills, all at once`() = run("romanization pills, all at once", 0, asRomanization = true)
}
