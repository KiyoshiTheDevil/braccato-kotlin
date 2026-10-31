// Not a port of one selfcheck. engine.selfcheck.ts checks these decisions through a fake DOM and
// `tickView`; these check the same rules on LyricsTimeline directly.
package braccato.core

import braccato.types.Lyric
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
        assertTrue(timeline.tick(29.9, paused, 0.0).clock!!.pausedSeek, "a paused clock that moves was scrubbed")
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
