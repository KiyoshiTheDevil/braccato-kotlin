// Port of packages/core/src/stage.selfcheck.ts.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.core.StageRole.CURRENT
import dev.kiyoshi.braccato.core.StageRole.GONE
import dev.kiyoshi.braccato.core.StageRole.PREVIOUS
import dev.kiyoshi.braccato.core.StageRole.QUEUED
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun line(start: Double, end: Double) = StageItem(StageItemKind.LINE, start, end)
private fun instrumental(start: Double, end: Double) = StageItem(StageItemKind.INSTRUMENTAL, start, end)
private fun credits(start: Double) = StageItem(StageItemKind.CREDITS, start, Double.POSITIVE_INFINITY)
private fun blank(start: Double, end: Double) = StageItem(StageItemKind.BLANK, start, end)

private fun metric(height: Double, left: Double = 100.0, width: Double = 300.0, originX: Double = 0.5) =
    StageMetrics(height = height, left = left, width = width, originX = originX)

private val GEOMETRY = StageGeometry(stageHeight = 500.0, gap = 10.0, activeScale = 1.0)

class StageTest {
    // -- Enter times --------------------------------------------

    @Test fun `enter times`() {
        val (first, second) = stageEnterTimes(listOf(line(10.0, 12.0), line(12.0, 14.0)))
        assertEquals(9.5, first, "the first item enters half a second early")
        assertEquals(11.88, second, "a back-to-back subtitle line hands off just before it starts")

        assertEquals(13.5, stageEnterTimes(listOf(line(10.0, 11.0), line(14.0, 16.0)))[1], "after a real gap the next line enters half a second early")

        assertTrue(
            abs(stageEnterTimes(listOf(line(10.0, 11.8), line(12.0, 14.0)))[1] - 11.68) < 1e-9,
            "a gap shorter than the lead hands off just after the previous line ends",
        )

        assertEquals(listOf(9.5), stageEnterTimes(listOf(line(10.0, 12.0))), "a single line enters half a second early")
        assertEquals(listOf(CURRENT), planStage(listOf(line(10.0, 12.0)), 11.0), "a single line holds the stage")
        assertEquals(listOf(CURRENT), planStage(listOf(line(10.0, 12.0)), 20.0), "and keeps it after it ends")

        assertEquals(11.88, stageEnterTimes(listOf(line(10.0, 12.0), instrumental(12.0, 14.0)))[1], "a note after a sung line is handed off to like a line")
    }

    // -- Overlap --------------------------------------------

    @Test fun overlap() {
        assertTrue(overlapsPrevious(listOf(line(0.0, 2.6), line(2.1, 4.5)), 1), "starting 0.5 s before the previous line ends is an overlap")
        assertFalse(overlapsPrevious(listOf(line(0.0, 2.0), line(1.9, 3.0)), 1), "a 0.1 s touch is not an overlap")
        assertFalse(overlapsPrevious(listOf(instrumental(0.0, 3.0), line(2.0, 4.0)), 1), "only sung lines overlap")
    }

    // -- Subtitle roles --------------------------------------------

    @Test fun `subtitle roles`() {
        val items = listOf(line(10.0, 12.0), line(12.0, 14.0))
        assertEquals(listOf(QUEUED, QUEUED), planStage(items, 5.0), "nothing is on stage before the lead")
        assertEquals(listOf(CURRENT, QUEUED), planStage(items, 10.5), "subtitle shows no next line")
        assertEquals(listOf(GONE, CURRENT), planStage(items, 11.9), "a back-to-back handoff never shows both lines")

        val overlapping = listOf(line(0.0, 2.6), line(2.1, 4.5), line(4.7, 7.0))
        assertEquals(listOf(PREVIOUS, CURRENT, QUEUED), planStage(overlapping, 2.3), "during a real overlap the old line stays while it is still sung")
        assertEquals(listOf(GONE, CURRENT, QUEUED), planStage(overlapping, 2.7), "and leaves once it ends")
    }

    // -- Instrumentals and credits --------------------------------------------

    @Test fun `instrumentals and credits`() {
        val items = listOf(instrumental(0.0, 3.0), line(3.0, 5.0), credits(5.0))
        assertEquals(listOf(CURRENT, QUEUED, QUEUED), planStage(items, 1.0), "the intro note takes the stage")
        assertEquals(listOf(GONE, GONE, CURRENT), planStage(items, 6.0), "the credits end the song")
        assertEquals(listOf(GONE, CURRENT, QUEUED), planStage(items, 2.8), "a note is never kept as the previous item")

        val lineThenCredits = listOf(line(0.0, 2.0), credits(2.0))
        assertEquals(listOf(CURRENT, QUEUED), planStage(lineThenCredits, 1.0), "credits wait while the last line is sung")
        assertEquals(listOf(GONE, CURRENT), planStage(lineThenCredits, 3.0), "credits are current after the last line, alone")

        val outro = listOf(line(0.0, 2.0), instrumental(2.0, 12.0), credits(12.0))
        assertEquals(listOf(GONE, CURRENT, QUEUED), planStage(outro, 6.9), "an outro note holds its first half")
        assertEquals(listOf(GONE, GONE, CURRENT), planStage(outro, 7.0), "regression: the credits take over halfway through an outro note")
    }

    // -- Invariants --------------------------------------------

    @Test fun invariants() {
        assertEquals(1, planStage(listOf(line(0.0, 5.0), line(1.0, 2.0)), 1.0).count { it == CURRENT }, "exactly one current item once anything entered")
        assertEquals(emptyList(), planStage(emptyList(), 3.0), "an empty song has an empty stage")
    }

    // -- Geometry --------------------------------------------

    @Test fun geometry() {
        run {
            val (placements, box) = layoutStage(
                listOf(CURRENT, QUEUED),
                listOf(line(0.0, 2.0), line(2.0, 4.0)),
                listOf(metric(40.0), metric(60.0)),
                listOf(null, null),
                GEOMETRY,
            )
            assertEquals(460.0, placements[0].y, "a lone current line sits on the stage floor")
            assertTrue(placements[0].visible)
            assertEquals(450.0, placements[1].y, "a queued subtitle line waits just below its own spot")
            assertFalse(placements[1].visible)
            assertEquals(StageBox(100.0, 460.0, 300.0, 40.0), box, "the box hugs the one sung line")
        }

        run {
            val (placements, box) = layoutStage(
                listOf(PREVIOUS, CURRENT),
                listOf(line(0.0, 2.6), line(2.1, 4.5)),
                listOf(metric(40.0, 50.0, 400.0), metric(40.0)),
                listOf(null, null),
                GEOMETRY,
            )
            assertEquals(460.0, placements[1].y)
            assertEquals(410.0, placements[0].y, "the overlapping line sits a gap above the current one")
            assertNotNull(box)
            assertTrue(box.y < 460 && box.y + box.height == 500.0, "an overlap grows the box upward")
            assertTrue(box.x < 100, "and widens it to the wider line")
            assertEquals(StageBox(50.0, 410.0, 400.0, 90.0), box, "regression: the overlapping line keeps its active size in the box")
        }

        run {
            val (_, box) = layoutStage(
                listOf(CURRENT, QUEUED),
                listOf(instrumental(0.0, 3.0), line(3.0, 5.0)),
                listOf(metric(40.0), metric(40.0)),
                listOf(null, null),
                GEOMETRY,
            )
            assertNull(box, "a note on its own gets no box")
        }

        run {
            val (_, box) = layoutStage(
                listOf(GONE, CURRENT),
                listOf(line(0.0, 2.0), credits(2.0)),
                listOf(metric(40.0), metric(60.0)),
                listOf(300.0, null),
                GEOMETRY,
            )
            assertEquals(StageBox(100.0, 440.0, 300.0, 60.0), box, "focused credits are plated")
        }

        run {
            val (placements, _) = layoutStage(
                listOf(GONE, CURRENT),
                listOf(line(0.0, 2.0), line(2.0, 4.0)),
                listOf(metric(40.0), metric(40.0)),
                listOf(460.0, null),
                GEOMETRY,
            )
            assertEquals(460.0, placements[0].y, "a leaving line fades where it was")
        }

        run {
            val items = listOf(line(0.0, 2.0), blank(2.0, 4.0))
            assertEquals(listOf(GONE, CURRENT), planStage(items, 3.0), "a blank line clears the line before it")
            val (_, box) = layoutStage(listOf(GONE, CURRENT), items, listOf(metric(40.0), metric(40.0)), listOf(460.0, null), GEOMETRY)
            assertNull(box, "regression: a blank line gets no box, so no empty backdrop shows")
            assertFalse(overlapsPrevious(listOf(blank(0.0, 3.0), line(2.0, 4.0)), 1), "a blank line is never held as the previous line")
            assertEquals(
                listOf(GONE, CURRENT),
                planStage(listOf(line(0.0, 3.0), blank(2.0, 4.0)), 2.5),
                "regression: a blank line that starts before the line ahead ends still clears it",
            )
        }
    }

    // -- Text span --------------------------------------------

    @Test fun `text span`() {
        val box = StageSpanRect(x = 0.0, width = 1400.0)
        val noPadding = StagePadding(0.0, 0.0)
        val centre = 0.5
        val left = 0.0
        val right = 1.0
        val wrapped = listOf(StageSpanRect(146.0, 1108.0), StageSpanRect(562.5, 275.0))

        assertEquals(StageSpan(166.0, 1108.0), stageTextSpan(20.0, 1400.0, box, wrapped, noPadding, centre), "regression: wrapped credits are measured by their widest line")
        assertEquals(StageSpan(270.0, 900.0), stageTextSpan(20.0, 1400.0, box, listOf(StageSpanRect(350.0, 800.0)), noPadding, centre), "regression: a centred line keeps its printed label")
        assertEquals(
            StageSpan(20.0, 910.0),
            stageTextSpan(20.0, 1400.0, box, listOf(StageSpanRect(110.0, 790.0)), StagePadding(10.0, 10.0), left),
            "a left-aligned line starts where its content does",
        )
        assertEquals(StageSpan(20.0, 1400.0), stageTextSpan(20.0, 1400.0, box, wrapped, noPadding, right), "a right-aligned line keeps its box")
        assertEquals(
            StageSpan(20.0, 430.0),
            stageTextSpan(20.0, 430.0, StageSpanRect(100.0, 430.0), listOf(StageSpanRect(115.0, 400.0)), StagePadding(15.0, 15.0), centre),
            "a line that fits keeps its own box",
        )
        assertEquals(StageSpan(20.0, 1400.0), stageTextSpan(20.0, 1400.0, box, emptyList(), noPadding, centre), "with no text to measure the box stands")
        assertEquals(StageSpan(20.0, 1400.0), stageTextSpan(20.0, 1400.0, box, listOf(StageSpanRect(300.0, 0.0)), noPadding, centre), "empty text rects are ignored")
        assertEquals(StageSpan(20.0, 1400.0), stageTextSpan(20.0, 1400.0, StageSpanRect(0.0, 0.0), wrapped, noPadding, centre), "an unrendered box keeps its offsets")

        val scaled = stageTextSpan(20.0, 1400.0, StageSpanRect(0.0, 1400 * 1.1), listOf(StageSpanRect(146 * 1.1, 1108 * 1.1)), noPadding, centre)
        assertTrue(abs(scaled.left - 166) < 1e-6 && abs(scaled.width - 1108) < 1e-6, "a scaled line is measured in its unscaled pixels")

        assertEquals(
            StageSpan(20.0, 430.0),
            stageTextSpan(20.0, 430.0, StageSpanRect(100.0, 430.0), listOf(StageSpanRect(90.0, 460.0)), noPadding, centre),
            "the span never grows past the box it came from",
        )
    }
}
