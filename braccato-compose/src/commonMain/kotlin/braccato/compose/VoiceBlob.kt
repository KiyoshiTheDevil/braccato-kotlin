// Who sings: a name at the foot of the lyrics, in a capsule that glides to the singer's side and takes
// their name's width, splits in two when two sing, and flows back together. Not upstream, which shows
// no names; Kodama asked for it (2026-11-07) in place of a row of badges under the lyrics.
package braccato.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Who sings each line, by the agent ids lines carry (v1, v2, …, v1000 for a group), and optionally a
 * colour for each, which tints their capsule. Shown only when two voices or more have a name (a singer and
 * their group count).
 */
class VoiceLabels(
    val names: Map<String, String>,
    val tint: ((agent: String) -> Color?)? = null,
    /** How far above the view's bottom edge it sits, clear of what the host lays over that edge. */
    val bottom: androidx.compose.ui.unit.Dp = 16.dp,
    /**
     * What lies behind the lyrics, for the capsule to blur too: over a video, its picture. The host
     * records it into this layer and tells where the layer stands on screen ([backdropOrigin], in root
     * coordinates). Without it the capsule blurs only the lyrics behind it.
     */
    val backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    val backdropOrigin: () -> Offset = { Offset.Zero },
    /**
     * Added to the capsule's own darkening (0.22), for a bright picture behind it. Above 0 it is laid
     * over the blurred backdrop instead of the light frost.
     */
    val darken: Float = 0f,
    /** The capsule's size against its own (13 sp names, 26 dp high): 1 leaves it. */
    val scale: Float = 1f,
) {
    val useful: Boolean get() = names.size >= 2

    /**
     * How far [BraccatoStage] raises its floor for the capsule's own line: the capsule, its distance
     * from the bottom, and a little air. A host drawing a second stage beside it (a shadow) without
     * voices raises that one by the same.
     */
    val stageRoom: androidx.compose.ui.unit.Dp get() = if (useful) 26.dp * scale + bottom + 10.dp else 0.dp
}

/**
 * What the lyrics behind the capsule need from it: its shape, to leave out of the sharp text, and the
 * text drawn into a layer of its own, for the capsule to show blurred. The shape is state, so the text
 * is drawn again as the capsule moves; it changes only when the capsule does.
 */
internal class VoiceGlass(val text: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    var shape by androidx.compose.runtime.mutableStateOf<Path?>(null)
    var bounds = Rect.Zero
}

/** The agents singing now. */
internal data class Singing(val agents: List<String>)

private class Pill(val label: String, val agent: String, val side: LineSide)

private fun sideOf(agent: String) = when (agent) {
    "v1000" -> LineSide.CENTER
    "v2", "v3" -> LineSide.RIGHT
    else -> LineSide.START
}

/** At most two capsules: a group of exactly two splits into its two singers, otherwise shows its own name. */
private fun VoiceLabels.pillsFor(agents: List<String>): List<Pill> {
    val persons = names.filterKeys { it != "v1000" }
    val out = mutableListOf<Pill>()
    for (a in agents) {
        if (a == "v1000") {
            if (persons.size == 2) persons.forEach { (id, n) -> out += Pill(n, id, LineSide.CENTER) }
            else names["v1000"]?.let { out += Pill(it, a, LineSide.CENTER) }
        } else {
            names[a]?.let { out += Pill(it, a, sideOf(a)) }
        }
    }
    return out.distinctBy { it.agent }.take(2)
}

private fun capsule(r: Rect) = Path().apply { addRoundRect(RoundRect(r, CornerRadius(r.height / 2))) }

/** Two capsules as one shape, with a waist between them: 1 full height, towards 0 where it lets go. */
private fun goo(a: Rect, b: Rect?, waist: Float): Path {
    val shape = capsule(a)
    if (b == null || b.width <= 0f) return shape
    val (l, r) = if (a.center.x <= b.center.x) a to b else b to a
    val joined = Path().apply { op(capsule(l), capsule(r), PathOperation.Union) }
    if (waist <= 0.02f || r.left <= l.right - l.height / 2) return joined
    val h = l.height
    val x0 = l.right - h * 0.35f
    val x1 = r.left + h * 0.35f
    val mid = (x0 + x1) / 2
    val pinch = h / 2 * (1f - 0.92f * waist.coerceIn(0f, 1f))
    val neck = Path().apply {
        moveTo(x0, l.top)
        quadraticTo(mid, l.top + pinch * 2, x1, l.top)
        lineTo(x1, l.bottom)
        quadraticTo(mid, l.bottom - pinch * 2, x0, l.bottom)
        close()
    }
    return Path().apply { op(joined, neck, PathOperation.Union) }
}

/** One capsule's place and words, each moving on its own spring. */
private class Slot {
    val left = Animatable(0f)
    val right = Animatable(0f)
    val alpha = Animatable(0f)
    var label = ""
    var previous = ""
    var agent = ""
    val fade = Animatable(1f)
    /** Its words alone: going back in, they leave first, before they could lie over the other's. */
    val words = Animatable(1f)
    fun rect(top: Float, h: Float) = Rect(left.value, top, right.value, top + h)
}

/**
 * In one place at the foot of the lyrics: over the line being sung it went away and came back with
 * every line (Kodama, 2026-11-07). Only sideways it moves, to whoever sings.
 */
@Composable
internal fun VoiceBlob(
    voices: VoiceLabels,
    singing: Singing?,
    theme: BraccatoTheme,
    glass: VoiceGlass,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.fillMaxSize()) {
    val blurred = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember(theme, voices.scale) { TextStyle(fontFamily = theme.fontFamily, fontSize = 13.sp * voices.scale, fontWeight = FontWeight.SemiBold, color = Color.White) }
    val width = with(density) { maxWidth.toPx() }
    val height = with(density) { maxHeight.toPx() }
    val bottom = with(density) { voices.bottom.toPx() }
    val h = with(density) { 26.dp.toPx() } * voices.scale
    val pad = with(density) { 12.dp.toPx() } * voices.scale
    val gap = with(density) { 8.dp.toPx() } * voices.scale
    val inset = with(density) { (theme.fontSize * 0.25f).toPx() }
    val slots = remember { listOf(Slot(), Slot()) }
    val shown = remember { Animatable(0f) }
    val waist = remember { Animatable(0f) }
    val merging = remember { booleanArrayOf(false) }

    val pills = remember(singing, voices) { singing?.let { voices.pillsFor(it.agents) }.orEmpty() }
    LaunchedEffect(pills.map { it.agent to it.label }, width) {
        if (pills.isEmpty()) { shown.animateTo(0f, tween(220)); return@LaunchedEffect }
        val widths = pills.map { measurer.measure(it.label, style).size.width + 2 * pad }
        // Where each goes: at its singer's side; a pair from one group side by side in the middle.
        val lefts = if (pills.size == 2 && pills.all { it.side == LineSide.CENTER }) {
            val start = (width - widths.sum() - gap) / 2
            listOf(start, start + widths[0] + gap)
        } else {
            pills.mapIndexed { i, p ->
                when (p.side) {
                    LineSide.START -> inset
                    LineSide.RIGHT -> width - inset - widths[i]
                    LineSide.CENTER -> (width - widths[i]) / 2
                }
            }
        }
        val first = shown.value == 0f
        // Fully damped: it glides into place without going past it and coming back (Kodama, 2026-11-16).
        val move = spring<Float>(dampingRatio = 1f, stiffness = 260f, visibilityThreshold = 0.3f)
        coroutineScope {
            launch { shown.animateTo(1f, tween(220)) }
            pills.forEachIndexed { i, p ->
                val s = slots[i]
                if (s.label != p.label) {
                    s.previous = s.label; s.label = p.label; s.agent = p.agent
                    launch { s.fade.snapTo(0f); s.fade.animateTo(1f, tween(260)) }
                }
                val appearing = s.alpha.value == 0f
                if (first || (appearing && i == 0)) {
                    s.left.snapTo(lefts[i]); s.right.snapTo(lefts[i] + widths[i])
                } else if (appearing) {
                    // The second comes out of the first, held to it by a waist that thins and lets go.
                    s.left.snapTo(slots[0].left.value); s.right.snapTo(slots[0].right.value)
                    merging[0] = false
                    launch { waist.snapTo(1f); waist.animateTo(0f, tween(380, delayMillis = 80, easing = FastOutSlowInEasing)) }
                }
                launch { s.alpha.animateTo(1f, tween(if (i == 0) 220 else 1)) }
                launch { s.words.animateTo(1f, tween(200)) }
                launch { s.left.animateTo(lefts[i], move) }
                launch { s.right.animateTo(lefts[i] + widths[i], move) }
            }
            // One fewer: the second flows back into the first, and is gone once inside.
            if (pills.size == 1 && slots[1].alpha.value > 0f) {
                merging[0] = true
                launch {
                    val s = slots[1]
                    coroutineScope {
                        launch { s.words.animateTo(0f, tween(120)) }
                        launch { s.left.animateTo(lefts[0], move) }
                        launch { s.right.animateTo(lefts[0] + widths[0], move) }
                    }
                    s.alpha.snapTo(0f); s.label = ""; merging[0] = false
                }
            }
        }
    }

    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val a = shown.value
        if (a <= 0f) {
            if (glass.shape != null) glass.shape = null
            return@Canvas
        }
        val top = height - bottom - h
        val r0 = slots[0].rect(top, h)
        val r1 = if (slots[1].alpha.value > 0f) slots[1].rect(top, h) else null
        val w = if (merging[0] && r1 != null) {
            val apart = maxOf(r1.left - r0.right, r0.left - r1.right)
            (1f - apart / gap).coerceIn(0f, 1f)
        } else waist.value
        val path = goo(r0, r1, w)
        // Told on only when it moved: the text behind draws again for each new one.
        val bounds = path.getBounds()
        if (bounds != glass.bounds) { glass.bounds = bounds; glass.shape = path }
        // Frosted: the lyrics behind it, blurred. Under that a little dark, where a blur cannot be had
        // (Android before 12) and the text would show through sharp.
        drawPath(path, Color.Black.copy(alpha = (0.22f + voices.darken) * a))
        val r = 14.dp.toPx()
        val area = Rect(bounds.left - r, bounds.top - r, bounds.right + r, bounds.bottom + r)
        blurred.renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal)
        blurred.record(androidx.compose.ui.unit.IntSize(area.width.toInt().coerceAtLeast(1), area.height.toInt().coerceAtLeast(1))) {
            // The picture behind first, where it stands against this canvas; the lyrics over it.
            voices.backdrop?.let { b -> val o = voices.backdropOrigin() - origin; translate(o.x - area.left, o.y - area.top) { drawLayer(b) } }
            translate(-area.left, -area.top) { drawLayer(glass.text) }
        }
        clipPath(path) { translate(area.left, area.top) { drawLayer(blurred) } }
        // The extra dark goes over the blur: under it, an opaque picture behind (a video) hid it.
        // Asked for, the dark replaces the light frost on top, which would take it back.
        if (voices.darken > 0f) drawPath(path, Color.Black.copy(alpha = (0.22f + voices.darken) * a))
        else drawPath(path, Color.White.copy(alpha = 0.12f * a))
        // The singers' colours, when asked for, lie over their own capsules.
        val tint = voices.tint
        listOfNotNull(slots[0] to r0, r1?.let { slots[1] to it }).forEach { (s, r) ->
            tint?.invoke(s.agent)?.let { c -> drawRoundRect(c.copy(alpha = 0.32f * a), r.topLeft, r.size, CornerRadius(h / 2)) }
            fun word(text: String, alpha: Float) {
                if (text.isEmpty() || alpha <= 0f) return
                val t = measurer.measure(text, style)
                // Narrower than its words while it moves: they stay centred and whole.
                drawText(t, color = Color.White.copy(alpha = alpha * a), topLeft = Offset(r.center.x - t.size.width / 2f, r.center.y - t.size.height / 2f))
            }
            word(s.previous, (1f - s.fade.value) * s.words.value)
            word(s.label, s.fade.value * s.words.value)
        }
    }
}
