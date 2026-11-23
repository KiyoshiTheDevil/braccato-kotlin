// Not a port of one selfcheck. engine.selfcheck.ts checks these decisions through a fake DOM and
// `tickView`; these check the same rules on LyricsTimeline directly.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.Lyric
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LINE_HEIGHT = 100.0
private val NO_OFFSETS = TimingOffsets(richsync = 0.0, line = 0.0, scroll = 0.0)

/** Lines a second or more apart, each 2 s long, one under the other. */
private fun song(vararg startsS: Double) = buildLyrics(
    startsS.map { start -> Lyric(startTimeMs = start * 1000, words = "line at $start", durationMs = 2000.0) },
)

private fun viewport(lineCount: Int, scrollTop: Double = 0.0, credits: ScrollItem? = null) = ScrollViewport(
    height = 400.0,
    scrollTop = scrollTop,
    maxScrollTop = 100000.0,
    linePositions = List(lineCount) { it * LINE_HEIGHT },
    lineHeights = List(lineCount) { LINE_HEIGHT },
    credits = credits,
    targetScrollRatio = 0.25,
)

private val PLAYING = TickOptions(isPlaying = true)

/** A wall clock reading well past zero, as a real one is: autoscroll resumes once the clock passes a pause. */
private const val NOW = 1_000_000.0

class TimelineTest {
    @AfterTest fun resetTheme() {
        setThemeSettings(emptyMap())
    }

    @Test fun `a line is active from just before it starts until the next one does`() {
        val timeline = LyricsTimeline(song(10.0, 13.0, 16.0), offsets = NO_OFFSETS)

        assertEquals(emptyList(), timeline.tick(9.0, PLAYING, 0.0).activeLines)
        assertEquals(listOf(0), timeline.tick(9.5, PLAYING, 0.0).activeLines, "the lookahead takes a line in 0.54 s early")
        assertEquals(listOf(0), timeline.tick(12.4, PLAYING, 0.0).activeLines, "a line stays active past its end until the next starts")
        assertEquals(listOf(0, 1), timeline.tick(12.5, PLAYING, 0.0).activeLines, "the next line's lookahead overlaps it")
        assertEquals(listOf(1), timeline.tick(13.2, PLAYING, 0.0).activeLines)
    }

    @Test fun `a line animates from its start to the next line's`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)

        assertEquals(emptyList(), timeline.tick(9.9, PLAYING, 0.0).animatingLines)
        assertEquals(listOf(0), timeline.tick(10.0, PLAYING, 0.0).animatingLines)
        assertEquals(listOf(1), timeline.tick(13.0, PLAYING, 0.0).animatingLines)
        assertEquals(listOf(1), timeline.tick(30.0, PLAYING, 0.0).animatingLines, "the last line keeps animating, since no line follows it")
    }

    @Test fun `the theme's offsets and the user's move the lyric clock`() {
        val timeline = LyricsTimeline(song(10.0), offsets = TimingOffsets(line = 0.115, scroll = 0.5))
        val frame = timeline.tick(10.0, PLAYING.copy(lyricOffset = 0.2), 0.0)

        assertEquals(10.0 + 0.115 - 0.2, frame.lyricTime, 1e-9)
        assertEquals(frame.lyricTime + 0.5, frame.scrollTime, 1e-9)
    }

    @Test fun `tells a seek from playback`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)

        timeline.tick(10.0, PLAYING, 0.0)
        assertFalse(timeline.tick(10.1, PLAYING, 0.0).clock!!.timeJumped, "a small step is playback")
        assertTrue(timeline.tick(30.0, PLAYING, 0.0).clock!!.timeJumped, "a big one is a seek")
        assertTrue(timeline.tick(29.97, PLAYING, 0.0).clock!!.mediaSteppedBack.not(), "a step back within the resync threshold is not a loop")
        assertTrue(timeline.tick(29.8, PLAYING, 0.0).clock!!.mediaSteppedBack, "a media clock stepping back while playing is a loop")

        val paused = TickOptions(isPlaying = false)
        timeline.tick(29.8, paused, 0.0)
        assertFalse(timeline.tick(29.9, paused, 0.0).clock!!.pausedSeek, "a paused scrub moves nothing unless the host asks (1.18.0)")
        val following = TickOptions(isPlaying = false, followPausedSeeks = true)
        assertTrue(timeline.tick(30.0, following, 0.0).clock!!.pausedSeek, "a paused clock that moves was scrubbed, for a host that follows")
    }

    @Test fun `extrapolates a player snapshot to now`() {
        val timeline = LyricsTimeline(song(10.0), offsets = NO_OFFSETS)
        val frame = timeline.tick(10.0, PLAYING.copy(eventCreationTime = 1000.0), now = 1250.0)

        assertEquals(10.25, frame.lyricTime, 1e-9)
    }

    @Test fun `does nothing paused at zero`() {
        val timeline = LyricsTimeline(song(10.0), offsets = NO_OFFSETS)
        assertEquals(FrameStatus.SKIPPED, timeline.tick(0.0, TickOptions(isPlaying = false), 0.0).status)
    }

    @Test fun `scrolls once per new line, to put it at the target`() {
        val timeline = LyricsTimeline(song(10.0, 13.0, 16.0), offsets = NO_OFFSETS)

        val first = timeline.tick(10.2, PLAYING, NOW, viewport(3)).scroll
        assertNotNull(first)
        assertEquals(ScrollAction.COMMIT, first.action, "the first line scrolls into place")
        assertEquals(0.0, first.targetScrollTop, "clamped at the top")

        val held = timeline.tick(10.4, PLAYING, NOW, viewport(3)).scroll
        assertEquals(ScrollAction.NONE, held?.action, "the same line does not scroll again")

        val second = timeline.tick(13.2, PLAYING, NOW, viewport(3)).scroll
        assertEquals(ScrollAction.COMMIT, second?.action)
        assertEquals(100.0 + LINE_HEIGHT / 2 - 100.0, second?.targetScrollTop, "the second line's centre sits at the target, a quarter down")
        assertEquals(1, second?.anchorIndex)
    }

    @Test fun `prepares the scroll just before a line starts`() {
        val timeline = LyricsTimeline(song(10.0, 13.0, 16.0), offsets = NO_OFFSETS)
        // Played through rather than jumped to, since a jump scrolls at once. The view sits where the
        // timeline put the second line.
        for (tenths in 132..158) timeline.tick(tenths / 10.0, PLAYING, NOW, viewport(3, scrollTop = 50.0))
        // The third line is 100 ms off.
        val frame = timeline.tick(15.9, PLAYING, NOW, viewport(3, scrollTop = 50.0))

        assertEquals(ScrollAction.PREPARE, frame.scroll?.action)
        assertEquals(2, frame.scroll?.lastActiveIndex)
    }

    @Test fun `a user scroll holds the view and offers the way back`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        timeline.tick(10.2, PLAYING, NOW, viewport(2))

        timeline.noteUserScroll(now = 1000.0)
        val held = timeline.tick(13.2, PLAYING, 2000.0, viewport(2, scrollTop = 300.0))
        assertNull(held.scroll, "autoscroll waits while the user reads")
        assertTrue(held.resumeAffordanceVisible)

        val resumed = timeline.tick(13.4, PLAYING, 1000.0 + USER_SCROLL_RESUME_DELAY_MS + 1, viewport(2, scrollTop = 300.0))
        assertEquals(ScrollAction.COMMIT, resumed.scroll?.action, "once the pause is over the view goes back to the line")
        assertFalse(resumed.resumeAffordanceVisible)
        assertFalse(resumed.scroll!!.naturalAdvance, "a scroll back from the user's position is not a natural advance")
    }

    @Test fun `resuming by hand scrolls at once`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        timeline.tick(10.2, PLAYING, NOW, viewport(2))
        timeline.noteUserScroll(now = 1000.0)
        timeline.resumeAutoscroll()

        assertEquals(ScrollAction.COMMIT, timeline.tick(10.3, PLAYING, 2000.0, viewport(2, scrollTop = 300.0)).scroll?.action)
    }

    @Test fun `the credits take the focus once the last line is sung`() {
        val lyrics = buildLyrics(
            listOf(Lyric(startTimeMs = 10000.0, words = "last", durationMs = 2000.0)),
            SetLyricsOptions(songwriters = listOf("A")),
        )
        val timeline = LyricsTimeline(lyrics, offsets = NO_OFFSETS)
        val credits = ScrollItem(position = 300.0, height = 80.0)

        assertFalse(timeline.tick(11.0, PLAYING, NOW, viewport(1, credits = credits)).creditsFocused)
        val frame = timeline.tick(12.1, PLAYING, NOW, viewport(1, credits = credits))
        assertTrue(frame.creditsFocused)
        assertEquals(ScrollAction.COMMIT, frame.scroll?.action, "focusing the credits scrolls")
        assertEquals(0.0, frame.scroll?.targetScrollTop, "but never so far the last line leaves the view")
    }

    @Test fun `the stage layout plans roles instead of scrolling`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), layout = LyricsLayout.STAGE, offsets = NO_OFFSETS)
        val frame = timeline.tick(10.5, PLAYING, NOW, viewport(2))

        assertEquals(listOf(StageRole.CURRENT, StageRole.QUEUED), frame.stageRoles)
        assertNull(frame.scroll)
    }

    @Test fun `unsynced lyrics drift down at a steady pace while playing`() {
        val lyrics = buildLyrics(List(4) { Lyric(startTimeMs = 0.0, words = "line $it", durationMs = 0.0) })
        val timeline = LyricsTimeline(lyrics, offsets = NO_OFFSETS)
        val options = PLAYING.copy(passiveScrollEnabled = true)
        val view = viewport(4).copy(maxScrollTop = 140.0)

        assertEquals(0.0, timeline.tick(5.0, options, 1000.0, view).passiveScrollTop, "the first frame only starts the clock")
        val frame = timeline.tick(5.0, options, 1500.0, view)
        assertEquals(FrameStatus.UNSYNCED, frame.status)
        // 4 lines at 3.5 s each take 14 s to cross 140 px.
        assertEquals(5.0, frame.passiveScrollTop!!, 1e-9)
    }
}

/**
 * A source like Media3's `ExoPlayer.currentPosition`, which changes about ten times a second, read at
 * 120 frames a second, as a phone may draw.
 */
class HeldReadingClockTest {
    private val frameMs = 1000.0 / 120

    /** The reading a 10 Hz source gives at a true time. */
    private fun coarse(trueS: Double) = kotlin.math.floor(trueS * 10) / 10

    @Test fun `a source that changes ten times a second still moves the swipe every frame`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        val shown = mutableListOf<Double>()
        val truths = mutableListOf<Double>()
        for (frame in 0 until 240) {
            val now = NOW + frame * frameMs
            val trueS = 10.0 + frame * frameMs / 1000
            shown += timeline.tick(coarse(trueS), PLAYING, now).lyricTime
            truths += trueS
        }

        val steps = shown.drop(12).zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it >= 0 }, "the clock never runs backwards")
        assertTrue(steps.max() < 0.03, "no frame jumps by anything like a 100 ms step (largest ${steps.max()})")
        assertTrue(steps.count { it > 0 } > steps.size * 0.8, "the clock moves on nearly every frame")
        assertTrue(
            shown.zip(truths).drop(12).all { (s, t) -> s - t in -0.11..MAX_HELD_LEAD_S },
            "it stays with the song, never further ahead than the cap",
        )
    }

    @Test fun `a seek back is taken at once`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        for (frame in 0 until 60) timeline.tick(coarse(20.0 + frame * frameMs / 1000), PLAYING, NOW + frame * frameMs)

        val after = timeline.tick(12.0, PLAYING, NOW + 60 * frameMs).lyricTime
        assertEquals(12.0, after, 1e-9)
    }

    @Test fun `a stalled source is carried no further than the cap`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        var last = 0.0
        for (frame in 0 until 120) last = timeline.tick(15.0, PLAYING, NOW + frame * frameMs).lyricTime

        assertEquals(15.0 + MAX_HELD_LEAD_S, last, 1e-9)
    }

    @Test fun `a paused reading is shown as it is`() {
        val timeline = LyricsTimeline(song(10.0, 13.0), offsets = NO_OFFSETS)
        val paused = TickOptions(isPlaying = false)
        timeline.tick(15.0, PLAYING, NOW)
        timeline.tick(15.0, PLAYING, NOW + 100)

        assertEquals(15.0, timeline.tick(15.0, paused, NOW + 200).lyricTime, 1e-9)
    }
}
