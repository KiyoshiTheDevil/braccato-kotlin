// Not a port of one upstream file. These are the `registerThemeSetting` calls spread across
// engine.ts, inject.ts and imageHighlights.ts, gathered in one place so all of them are registered
// before a theme is applied (ThemeSettings.kt says why). Keys and defaults are upstream's, in
// upstream's order.
package dev.kiyoshi.braccato.core

/**
 * Every theme setting braccato-core reads, under upstream's keys and with upstream's defaults. A theme
 * sets them through [setThemeSettings]; a renderer reads them with [Setting.getNumberValue] and its kin.
 */
public object CoreSettings {
    // -- engine.ts --------------------------------------------

    /** A line this close to its end, in seconds, no longer holds the scroll, unless it is the last one sung (`blyrics-lyric-ending-threshold-s`). */
    public val LYRIC_ENDING_THRESHOLD_S: Setting = registerThemeSetting("blyrics-lyric-ending-threshold-s", 0.5)
    /** How long before it starts a line joins the scroll's active set, in seconds (`blyrics-early-scroll-consider-s`). */
    public val EARLY_SCROLL_CONSIDER: Setting = registerThemeSetting("blyrics-early-scroll-consider-s", 0.54)
    /** How far the word swipe starts ahead of the word, as a share of its duration (`blyrics-swipe-lead-ratio`). */
    public val SWIPE_LEAD_RATIO: Setting = registerThemeSetting("blyrics-swipe-lead-ratio", 0.1)
    /** How long the word swipe takes, as a multiple of the word's duration (`blyrics-swipe-duration-ratio`). */
    public val SWIPE_DURATION_RATIO: Setting = registerThemeSetting("blyrics-swipe-duration-ratio", 1.6)

    /** Upstream's renderer debugging (`blyrics-debug-renderer`). Read, not acted on here. */
    public val ENABLE_DEBUG_RENDER: Setting = registerThemeSetting("blyrics-debug-renderer", false)
    /** Upstream's animation timing logs (`blyrics-debug-animation-timing`). Read, not acted on here. */
    public val ENABLE_ANIMATION_TIMING_LOGS: Setting = registerThemeSetting("blyrics-debug-animation-timing", false)

    // The per-line scroll stagger. Upstream hands these to CSS as custom properties, and their
    // defaults are CSS expressions, so they are kept as the strings a theme writes.
    private const val LINE_SCROLL_AFTER_FUNCTION =
        "calc(750ms + log(var(--blyrics-line-scroll-abs-relative-index) + 2, 2.71828) * 80ms + (var(--blyrics-line-scroll-abs-relative-index) + 1) * 20ms)"

    private fun registerLineScrollStyleSetting(property: String, defaultValue: String): Pair<String, Setting> =
        property to registerThemeSetting(property.substring(2), defaultValue)

    /** The per-line scroll stagger, as the custom properties a theme sets and their CSS values. A renderer reads them to animate each line's scroll. */
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
    /** Where the active line sits in the view: 0 the top, 0.5 the middle, 1 the bottom (`blyrics-target-scroll-pos-ratio`). */
    public val SCROLL_POS_OFFSET_RATIO: Setting = registerThemeSetting("blyrics-target-scroll-pos-ratio", 0.37)

    /** Whether unsynced lyrics drift down on their own (`blyrics-passive-scroll-enabled`). */
    public val PASSIVE_SCROLL_ENABLED: Setting = registerThemeSetting("blyrics-passive-scroll-enabled", true)
    /** How long the drift spends on a line, in seconds (`blyrics-passive-scroll-seconds-per-line`). */
    public val PASSIVE_SECONDS_PER_LINE: Setting = registerThemeSetting("blyrics-passive-scroll-seconds-per-line", 3.5)
    /** How long the drift waits at the bottom, in seconds (`blyrics-passive-scroll-bottom-pause-s`). */
    public val PASSIVE_BOTTOM_PAUSE_S: Setting = registerThemeSetting("blyrics-passive-scroll-bottom-pause-s", 1.5)
    /** How long the drift takes back to the top, in seconds (`blyrics-passive-scroll-reset-duration-s`). */
    public val PASSIVE_RESET_DURATION_S: Setting = registerThemeSetting("blyrics-passive-scroll-reset-duration-s", 0.6)
    /** How long the drift waits at the top, in seconds (`blyrics-passive-scroll-top-pause-s`). */
    public val PASSIVE_TOP_PAUSE_S: Setting = registerThemeSetting("blyrics-passive-scroll-top-pause-s", 0.8)

    // -- imageHighlights.ts --------------------------------------------

    /** Upstream's highlighted images in lyrics (`blyrics-image-highlights`). Changing it rebuilds the lyrics. */
    public val imageHighlights: Setting = registerThemeSetting("blyrics-image-highlights", false, true)

    // -- inject.ts --------------------------------------------

    /** Treat word synced lyrics as line synced (`blyrics-disable-richsync`). Changing it rebuilds the lyrics. */
    public val disableRichsync: Setting = registerThemeSetting("blyrics-disable-richsync", false, true)
    /** In line synced lyrics, how much later each word fades in than the one before it, in milliseconds (`blyrics-line-synced-animation-delay`). */
    public val lineSyncedAnimationDelay: Setting = registerThemeSetting("blyrics-line-synced-animation-delay", 50.0, true)
    /** A word held longer than this, in milliseconds, counts as long, which themes style differently (`blyrics-long-word-threshold`). */
    public val longWordThreshold: Setting = registerThemeSetting("blyrics-long-word-threshold", 1500.0, true)
    /** A word longer than this many characters may wrap inside (`blyrics-long-word-wrap-threshold`). */
    public val longWordWrapThreshold: Setting = registerThemeSetting("blyrics-long-word-wrap-threshold", 10.0, true)
    /** Whether long words wave and sweep letter by letter (`blyrics-letter-wave`). Changing it rebuilds the lyrics. */
    public val letterWave: Setting = registerThemeSetting("blyrics-letter-wave", true, true)
    /** Whether the songwriters' credits after the last line are left out (`blyrics-hide-credits`). */
    public val hideCredits: Setting = registerThemeSetting("blyrics-hide-credits", false, true)

    /** Touching the object is what registers everything above; this is the touch. */
    internal fun ensureRegistered() = Unit
}
