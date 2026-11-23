// Port of packages/core/src/stage.ts.
//
// The stage layout shows only what is being sung: the current line on the floor, the line before it
// above it while the two overlap, the next one waiting below. All of it is pure planning over times
// and measured sizes.
package braccato.core

import kotlin.math.max
import kotlin.math.min

public enum class StageItemKind { LINE, BLANK, INSTRUMENTAL, CREDITS }

public enum class StageRole { CURRENT, PREVIOUS, QUEUED, GONE }

public data class StageItem(
    val kind: StageItemKind,
    /** Seconds. */
    val start: Double,
    /** Seconds. The credits hold the stage until the song ends, so theirs is infinite. */
    val end: Double,
)

internal const val STAGE_LEAD_S = 0.5
internal const val STAGE_HANDOFF_S = 0.12
internal const val STAGE_OVERLAP_S = 0.15

internal fun overlapsPrevious(items: List<StageItem>, index: Int): Boolean {
    val previous = items.getOrNull(index - 1)
    return previous != null &&
        previous.kind == StageItemKind.LINE &&
        items[index].kind != StageItemKind.BLANK &&
        items[index].start < previous.end - STAGE_OVERLAP_S
}

internal fun stageEnterTimes(items: List<StageItem>): List<Double> {
    return items.mapIndexed { index, item ->
        val early = item.start - STAGE_LEAD_S
        val previous = items.getOrNull(index - 1)
        // An outro note would otherwise keep the credits off stage until the song ends.
        if (item.kind == StageItemKind.CREDITS && previous?.kind == StageItemKind.INSTRUMENTAL) {
            return@mapIndexed (previous.start + previous.end) / 2
        }
        if (previous?.kind != StageItemKind.LINE || overlapsPrevious(items, index)) return@mapIndexed early
        max(early, min(previous.end, item.start) - STAGE_HANDOFF_S)
    }
}

public fun planStage(items: List<StageItem>, timeS: Double): List<StageRole> {
    val enterTimes = stageEnterTimes(items)
    var current = -1
    enterTimes.forEachIndexed { index, enterTime ->
        if (enterTime <= timeS) current = index
    }
    val roles = items.indices.map { index ->
        if (index < current) StageRole.GONE else if (index > current) StageRole.QUEUED else StageRole.CURRENT
    }.toMutableList()
    if (current < 0) return roles

    val previous = items.getOrNull(current - 1)
    if (previous?.kind == StageItemKind.LINE && overlapsPrevious(items, current) && timeS < previous.end) {
        roles[current - 1] = StageRole.PREVIOUS
    }
    return roles
}

public data class StageMetrics(
    val height: Double,
    val left: Double,
    val width: Double,
    /** Where the item scales from horizontally: 0 left, 0.5 centre, 1 right. */
    val originX: Double,
)

public data class StageGeometry(
    val stageHeight: Double,
    val gap: Double,
    val activeScale: Double,
)

public data class StagePlacement(
    val y: Double,
    val visible: Boolean,
)

public data class StageBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

internal data class StageSpanRect(
    val x: Double,
    val width: Double,
)

internal data class StagePadding(val left: Double, val right: Double)

internal data class StageSpan(val left: Double, val width: Double)

/**
 * A wrapped `fit-content` box fills the width offered, so the plate follows the text instead. The rects
 * miss printed `::before` labels, so the span is widened to what the alignment guarantees.
 */
internal fun stageTextSpan(
    offsetLeft: Double,
    offsetWidth: Double,
    box: StageSpanRect,
    textRects: Iterable<StageSpanRect>,
    padding: StagePadding,
    originX: Double,
): StageSpan {
    val whole = StageSpan(left = offsetLeft, width = offsetWidth)
    if (box.width <= 0 || offsetWidth <= 0 || originX == 1.0) return whole

    val scale = box.width / offsetWidth
    var start = Double.POSITIVE_INFINITY
    var end = Double.NEGATIVE_INFINITY
    for (rect in textRects) {
        if (rect.width <= 0) continue
        start = min(start, (rect.x - box.x) / scale)
        end = max(end, (rect.x + rect.width - box.x) / scale)
    }
    if (start > end) return whole

    val contentLeft = padding.left
    val contentRight = offsetWidth - padding.right
    if (originX == 0.0) {
        start = contentLeft
    } else {
        val centre = (contentLeft + contentRight) / 2
        val half = max(end - centre, centre - start)
        start = centre - half
        end = centre + half
    }

    val left = max(0.0, start - padding.left)
    val right = min(offsetWidth, end + padding.right)
    return StageSpan(left = offsetLeft + left, width = right - left)
}

private fun unionBox(box: StageBox?, next: StageBox): StageBox {
    if (box == null) return next
    val x = min(box.x, next.x)
    val y = min(box.y, next.y)
    return StageBox(
        x = x,
        y = y,
        width = max(box.x + box.width, next.x + next.width) - x,
        height = max(box.y + box.height, next.y + next.height) - y,
    )
}

/** Where a line waits below the floor, and where it rises from as it enters. */
internal fun queuedStageY(stageHeight: Double, height: Double, gap: Double): Double {
    return stageHeight - height + gap
}

public data class StageLayout(val placements: List<StagePlacement>, val box: StageBox?)

public fun layoutStage(
    roles: List<StageRole>,
    items: List<StageItem>,
    metrics: List<StageMetrics>,
    previousY: List<Double?>,
    geometry: StageGeometry,
): StageLayout {
    val stageHeight = geometry.stageHeight
    val gap = geometry.gap
    val current = roles.indexOf(StageRole.CURRENT)
    val currentY = if (current >= 0) stageHeight - metrics[current].height else stageHeight

    val placements = roles.mapIndexed { index, role ->
        val height = metrics[index].height
        when (role) {
            StageRole.CURRENT -> StagePlacement(y = currentY, visible = true)
            StageRole.PREVIOUS -> StagePlacement(y = currentY - gap - height, visible = true)
            StageRole.QUEUED -> StagePlacement(y = queuedStageY(stageHeight, height, gap), visible = false)
            StageRole.GONE -> StagePlacement(y = previousY.getOrNull(index) ?: currentY, visible = false)
        }
    }

    var box: StageBox? = null
    roles.forEachIndexed { index, role ->
        val kind = items[index].kind
        if ((role != StageRole.CURRENT && role != StageRole.PREVIOUS) ||
            kind == StageItemKind.INSTRUMENTAL ||
            kind == StageItemKind.BLANK
        ) {
            return@forEachIndexed
        }
        val (height, left, width, originX) = metrics[index]
        val scale = geometry.activeScale
        box = unionBox(
            box,
            StageBox(
                x = left + width * originX * (1 - scale),
                y = placements[index].y + (height * (1 - scale)) / 2,
                width = width * scale,
                height = height * scale,
            ),
        )
    }
    return StageLayout(placements = placements, box = box)
}
