// A window to watch the renderer in: opens a lyric file (TTML, LRC, SRT, QRC or plain text) and plays
// it against a clock of its own, since there is no audio here. Optionally a Better Lyrics theme.
//
//   ./gradlew :demo:run --args="<lyrics file> [theme.css]"
package braccato.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import braccato.compose.BraccatoLyrics
import braccato.compose.BraccatoStage
import braccato.compose.BraccatoTheme
import braccato.compose.PlaybackSnapshot
import braccato.compose.rememberBraccatoLyricsState
import braccato.core.BuiltLyrics
import braccato.core.SetLyricsOptions
import braccato.core.buildLyrics
import braccato.parsers.detectParser
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val BACKGROUND = Color(0xFF15121C)
private val LABEL = TextStyle(color = Color.White, fontSize = 14.sp)

/** A clock that plays, pauses and seeks, standing in for a player. */
private class DemoClock {
    var positionS by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    private var lastFrameNanos = 0L

    fun advance(frameNanos: Long) {
        if (playing && lastFrameNanos != 0L) positionS += (frameNanos - lastFrameNanos) / 1e9
        lastFrameNanos = frameNanos
    }
}

private class Loaded(val name: String, val lyrics: BuiltLyrics, val durationS: Double)

private fun load(file: File): Loaded {
    val text = file.readText()
    val parser = detectParser(text)
    val raw = parser.parse(text, 0.0)
    val lastEnd = raw.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0.0
    val durationMs = lastEnd + 8000
    // Parsed again against the song length, so the outro and the credits behave as in a player.
    val lyrics = parser.parse(text, durationMs)
    return Loaded(file.name, buildLyrics(lyrics, SetLyricsOptions(songwriters = parser.metadata(text).songwriters)), durationMs / 1000)
}

private fun pickFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = LABEL,
    )
}

private fun formatTime(s: Double): String {
    val total = s.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun SeekBar(clock: DemoClock, durationS: Double, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(20.dp)
            .pointerInput(durationS) {
                detectTapGestures { offset -> clock.positionS = offset.x / size.width * durationS }
            }
            .drawBehind {
                val trackHeight = 4.dp.toPx()
                val top = (size.height - trackHeight) / 2
                drawRoundRect(Color.White.copy(alpha = 0.15f), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = Size(size.width, trackHeight), cornerRadius = CornerRadius(trackHeight))
                val fraction = (clock.positionS / durationS).coerceIn(0.0, 1.0).toFloat()
                drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = Size(size.width * fraction, trackHeight), cornerRadius = CornerRadius(trackHeight))
            },
    )
}

fun main(args: Array<String>) = application {
    var loaded by remember { mutableStateOf(args.getOrNull(0)?.let { load(File(it)) }) }
    var theme by remember { mutableStateOf(args.getOrNull(1)?.let { BraccatoTheme.fromCss(File(it).readText()).first } ?: BraccatoTheme.Default) }
    var stage by remember { mutableStateOf(false) }
    val clock = remember { DemoClock() }
    val state = rememberBraccatoLyricsState()

    Window(
        onCloseRequest = ::exitApplication,
        title = "braccato-kotlin demo" + (loaded?.let { " — ${it.name}" } ?: ""),
        state = rememberWindowState(width = 1000.dp, height = 820.dp),
    ) {
        LaunchedEffect(Unit) { while (true) withFrameNanos(clock::advance) }

        Column(Modifier.fillMaxSize().background(BACKGROUND)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
                val current = loaded
                if (current == null) {
                    BasicText("Open a lyric file to start.", Modifier.align(Alignment.Center), style = LABEL)
                } else if (stage) {
                    BraccatoStage(
                        lyrics = current.lyrics,
                        playback = { PlaybackSnapshot(clock.positionS, clock.playing) },
                        modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
                        theme = theme,
                    )
                } else {
                    BraccatoLyrics(
                        lyrics = current.lyrics,
                        playback = { PlaybackSnapshot(clock.positionS, clock.playing) },
                        modifier = Modifier.fillMaxSize(),
                        theme = theme,
                        state = state,
                        onSeek = { clock.positionS = it },
                    )
                    if (state.resumeAffordanceVisible) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                            Button("Resume autoscroll") { state.resumeAutoscroll() }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.3f)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(if (clock.playing) "Pause" else "Play") { clock.playing = !clock.playing }
                Button("-5 s") { clock.positionS = (clock.positionS - 5).coerceAtLeast(0.0) }
                Button("+5 s") { clock.positionS += 5 }
                BasicText(formatTime(clock.positionS) + " / " + formatTime(loaded?.durationS ?: 0.0), style = LABEL)
                SeekBar(clock, loaded?.durationS ?: 1.0, Modifier.weight(1f))
                Button(if (stage) "Scroll" else "Stage") { stage = !stage }
                Button("Open") {
                    pickFile("Open lyrics")?.let {
                        loaded = load(it)
                        clock.positionS = 0.0
                    }
                }
                Button("Theme") {
                    pickFile("Open a Better Lyrics theme (CSS)")?.let { theme = BraccatoTheme.fromCss(it.readText()).first }
                }
            }
        }
    }
}
