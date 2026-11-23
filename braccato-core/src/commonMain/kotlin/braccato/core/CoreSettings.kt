// Not a port of one upstream file. These are the `registerThemeSetting` calls spread across
// engine.ts, inject.ts and imageHighlights.ts, gathered in one place so all of them are registered
// before a theme is applied (ThemeSettings.kt says why). Keys and defaults are upstream's, in
// upstream's order.
package braccato.core

/** Every theme setting braccato-core reads. */
public object CoreSettings {
    // -- engine.ts --------------------------------------------

    public val LYRIC_ENDING_THRESHOLD_S: Setting = registerThemeSetting("blyrics-lyric-ending-threshold-s", 0.5)
    public val EARLY_SCROLL_CONSIDER: Setting = registerThemeSetting("blyrics-early-scroll-consider-s", 0.54)
    public val SWIPE_LEAD_RATIO: Setting = registerThemeSetting("blyrics-swipe-lead-ratio", 0.1)
    public val SWIPE_DURATION_RATIO: Setting = registerThemeSetting("blyrics-swipe-duration-ratio", 1.6)

    public val ENABLE_DEBUG_RENDER: Setting = registerThemeSetting("blyrics-debug-renderer", false)
    public val ENABLE_ANIMATION_TIMING_LOGS: Setting = registerThemeSetting("blyrics-debug-animation-timing", false)

    // The per-line scroll stagger. Upstream hands these to CSS as custom properties, and their
    // defaults are CSS expressions, so they are kept as the strings a theme writes.
    private const val LINE_SCROLL_AFTER_FUNCTION =
        "calc(750ms + log(var(--blyrics-line-scroll-abs-relative-index) + 2, 2.71828) * 80ms + (var(--blyrics-line-scroll-abs-relative-index) + 1) * 20ms)"

    private fun registerLineScrollStyleSetting(property: String, defaultValue: String): Pair<String, Setting> =
        property to registerThemeSetting(property.substring(2), defaultValue)

    public val LINE_SCROLL_STYLE_SETTINGS: List<Pair<String, Setting>> = listOf(
        registerLineScrollStyleSetting("--blyrics-line-scroll-duration", LINE_SCROLL_AFTER_FUNCTION),
        registerLineScrollStyleSetting(
            "--blyrics-line-scroll-above-duration",
            "calc(750ms + min(var(--blyrics-line-scroll-abs-relative-index), 6) * 20ms)",
        ),
        registerLineScrollStyleSetting("--blyrics-line-scroll-active-duration", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-below-duration", LINE_SCROLL_AFTER_FUNCTION),
        registerLineScrollStyleSetting(
            "--blyrics-line-scroll-timing-function",
            "var(--blyrics-lyric-scroll-timing-function)",
        ),
        registerLineScrollStyleSetting("--blyrics-line-scroll-start-easing", "var(--blyrics-line-scroll-timing-function)"),
        registerLineScrollStyleSetting("--blyrics-line-scroll-end-easing", "linear"),
        registerLineScrollStyleSetting("--blyrics-line-scroll-above-start-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-active-start-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-below-start-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-above-end-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-active-end-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-below-end-easing", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-translate-y-start", "var(--blyrics-line-scroll-delta-px)"),
        registerLineScrollStyleSetting("--blyrics-line-scroll-translate-y-end", "0px"),
        registerLineScrollStyleSetting("--blyrics-line-scroll-above-translate-y-start", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-active-translate-y-start", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-below-translate-y-start", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-above-translate-y-end", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-active-translate-y-end", ""),
        registerLineScrollStyleSetting("--blyrics-line-scroll-below-translate-y-end", ""),
    )

    // 0.5 means the selected lyric will be in the middle of the screen, 0 means top, 1 means bottom
    public val SCROLL_POS_OFFSET_RATIO: Setting = registerThemeSetting("blyrics-target-scroll-pos-ratio", 0.37)

    public val PASSIVE_SCROLL_ENABLED: Setting = registerThemeSetting("blyrics-passive-scroll-enabled", true)
    public val PASSIVE_SECONDS_PER_LINE: Setting = registerThemeSetting("blyrics-passive-scroll-seconds-per-line", 3.5)
    public val PASSIVE_BOTTOM_PAUSE_S: Setting = registerThemeSetting("blyrics-passive-scroll-bottom-pause-s", 1.5)
    public val PASSIVE_RESET_DURATION_S: Setting = registerThemeSetting("blyrics-passive-scroll-reset-duration-s", 0.6)
    public val PASSIVE_TOP_PAUSE_S: Setting = registerThemeSetting("blyrics-passive-scroll-top-pause-s", 0.8)

    // -- imageHighlights.ts --------------------------------------------

    public val imageHighlights: Setting = registerThemeSetting("blyrics-image-highlights", false, true)

    // -- inject.ts --------------------------------------------

    public val disableRichsync: Setting = registerThemeSetting("blyrics-disable-richsync", false, true)
    public val lineSyncedAnimationDelay: Setting = registerThemeSetting("blyrics-line-synced-animation-delay", 50.0, true)
    public val longWordThreshold: Setting = registerThemeSetting("blyrics-long-word-threshold", 1500.0, true)
    public val longWordWrapThreshold: Setting = registerThemeSetting("blyrics-long-word-wrap-threshold", 10.0, true)
    public val letterWave: Setting = registerThemeSetting("blyrics-letter-wave", true, true)
    public val hideCredits: Setting = registerThemeSetting("blyrics-hide-credits", false, true)

    /** Touching the object is what registers everything above; this is the touch. */
    internal fun ensureRegistered() = Unit
}
