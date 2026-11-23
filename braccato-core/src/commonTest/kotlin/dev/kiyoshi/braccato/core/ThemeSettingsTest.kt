// Port of packages/core/src/themeSettings.selfcheck.ts and imageHighlights.selfcheck.ts.
package dev.kiyoshi.braccato.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeSettingsTest {
    @AfterTest fun resetTheme() {
        setThemeSettings(emptyMap())
    }

    // -- Reading a stylesheet --------------------------------------------

    @Test fun `takes every setting declared in one comment, spaces inside a value and all`() {
        val theme = """
/* Three settings, one comment.
   blyrics-target-scroll-pos-ratio = 0.5;
   blyrics-swipe-easing = cubic-bezier(0.4, 0, 0.2, 1);
   blyrics-disable-richsync = true;
*/
.blyrics--line { opacity: 1; }
"""
        assertEquals(
            listOf(
                "blyrics-target-scroll-pos-ratio" to "0.5",
                "blyrics-swipe-easing" to "cubic-bezier(0.4, 0, 0.2, 1)",
                "blyrics-disable-richsync" to "true",
            ),
            parseThemeConfig(theme).toList(),
        )
    }

    @Test fun `only what a browser ignores configures the module`() {
        val theme = """
.blyrics--line { --blyrics-target-scroll-pos-ratio: 0.9; }
blyrics-swipe-easing = ease;
/* blyrics-disable-richsync = true; */
"""
        assertEquals(listOf("blyrics-disable-richsync" to "true"), parseThemeConfig(theme).toList())
    }

    @Test fun `scans every comment and the last declaration stands`() {
        val theme = listOf(
            "/* blyrics-swipe-lead-ratio = 0.2; */",
            ".blyrics--word { color: red; }",
            "/* blyrics-swipe-lead-ratio = 0.4; blyrics-queue-scroll-ms = 40; */",
        ).joinToString("\n")

        assertEquals(
            listOf("blyrics-swipe-lead-ratio" to "0.4", "blyrics-queue-scroll-ms" to "40"),
            parseThemeConfig(theme).toList(),
        )
    }

    // -- Applying what was read --------------------------------------------

    @Test fun `applies, resets and reports reloads the way upstream does`() {
        val scrollRatioDefault = 0.37
        val easingDefault = "linear"

        val scrollRatio = registerThemeSetting("blyrics-fixture-scroll-ratio", scrollRatioDefault)
        val easing = registerThemeSetting("blyrics-fixture-easing", easingDefault)
        // The kind of setting the lines are built out of: everything built before it changed is wrong after.
        val disableRichsync = registerThemeSetting("blyrics-fixture-disable-richsync", false, true)

        assertFalse(
            setThemeSettings(parseThemeConfig("/* blyrics-fixture-scroll-ratio = 0.5; blyrics-fixture-easing = ease in out; */")),
            "a theme that touches nothing the lines are built out of leaves the lyrics on screen good",
        )
        assertEquals(
            listOf<Any>(0.5, "ease in out", false),
            listOf(scrollRatio.getNumberValue(), easing.getStringValue(), disableRichsync.getBooleanValue()),
        )

        assertTrue(
            setThemeSettings(parseThemeConfig("/* blyrics-fixture-disable-richsync = true; */")),
            "a theme that changes a setting the lines are built out of asks for them to be built again",
        )

        assertFalse(
            setThemeSettings(parseThemeConfig("/*blyrics-fixture-disable-richsync=true;*/")),
            "the same value declared in different words changes nothing",
        )

        assertEquals(
            listOf<Any>(scrollRatioDefault, easingDefault),
            listOf(scrollRatio.getNumberValue(), easing.getStringValue()),
            "settings a theme no longer declares go back to their defaults",
        )

        assertTrue(
            setThemeSettings(parseThemeConfig("")),
            "a build setting going back to its default asks for the lines to be built again",
        )
        assertFalse(disableRichsync.getBooleanValue())

        setThemeSettings(parseThemeConfig("/* blyrics-fixture-scroll-ratio = comfortable; */"))
        assertEquals(scrollRatioDefault, scrollRatio.getNumberValue(), "a number setting that is not a number keeps its default")
        assertFalse(scrollRatio.isManuallySet(), "and does not report itself as one the theme set")

        assertFalse(
            setThemeSettings(parseThemeConfig("/* blyrics-fixture-not-registered = 1; */")),
            "a key nothing registered is ignored",
        )
    }

    // -- Image highlights --------------------------------------------

    @Test fun `image highlights are opt-in and a build setting`() {
        assertEquals(false, CoreSettings.imageHighlights.getBooleanValue())
        assertTrue(setThemeSettings(parseThemeConfig("/* blyrics-image-highlights = true; */")))
        assertEquals(true, CoreSettings.imageHighlights.getBooleanValue())
        assertFalse(setThemeSettings(parseThemeConfig("/* blyrics-image-highlights = true; */")))
        assertTrue(setThemeSettings(emptyMap()))
        assertEquals(false, CoreSettings.imageHighlights.getBooleanValue())
    }

    @Test fun `reads a single CSS url and nothing else`() {
        assertEquals("data:image/jpeg;base64,AA==", imageURL("url(\"data:image/jpeg;base64,AA==\")"))
        assertEquals("https://example.test/white.jpg", imageURL("url('https://example.test/white.jpg')"))
        assertEquals("white.jpg", imageURL("url(white.jpg)"))
        assertNull(imageURL("none"))
        assertNull(imageURL("linear-gradient(white,black)"))
        assertNull(imageURL("url(a), url(b)"))
    }
}
