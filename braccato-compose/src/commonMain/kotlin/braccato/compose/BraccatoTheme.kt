// The values upstream's renderer reads from CSS: styles/variables.css, lyrics.css and
// instrumental.css for the defaults, and a theme's `--blyrics-*` overrides on top. Lengths are in
// dp, with upstream's `px` read as dp and its `rem` as 16 dp.
package braccato.compose

import braccato.types.internal.InternalBraccatoApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import braccato.core.CoreSettings
import braccato.core.SwipeRamp
import braccato.core.TimingOffsets
import braccato.core.parseThemeConfig
import braccato.core.setThemeSettings
import kotlin.math.max

/** Which effects run. `--blyrics-animate-*` upstream, each 1 or 0. */
@Immutable
public data class BraccatoAnimations(
    /** Lines grow as they are sung (`--blyrics-animate-line-scale`). */
    val lineScale: Boolean = true,
    /** Word groups nudge as they start (`--blyrics-animate-word-wobble`). */
    val wordWobble: Boolean = true,
    /** Words fill with a swipe as they are sung (`--blyrics-animate-highlight-swipe`). */
    val highlightSwipe: Boolean = true,
    /** Sung words glow (`--blyrics-animate-highlight-glow`). */
    val highlightGlow: Boolean = true,
    /** Highlights fade in and out (`--blyrics-animate-highlight-fade`). */
    val highlightFade: Boolean = true,
    /** The view scrolls smoothly, line after line (`--blyrics-animate-scroll`). */
    val scroll: Boolean = true,
    /** The instrumental note fills and waves (`--blyrics-animate-instrumental`). */
    val instrumental: Boolean = true,
    /** Translations and romanizations slide in and out (`--blyrics-animate-decoration-entry`). */
    val decorationEntry: Boolean = true,
) {
    /** `prefers-reduced-motion: reduce`: no zoom, no wobble, no moving fill. Fades and the swipe stay. */
    public fun reducedMotion(): BraccatoAnimations = copy(lineScale = false, wordWobble = false, instrumental = false, decorationEntry = false)
}

/**
 * How the lyrics look and move. The defaults are upstream's stylesheet; most values stand for one of its
 * `--blyrics-*` custom properties, and [fromCss] applies a Better Lyrics theme on top. Times are in
 * milliseconds, sizes in `em` are shares of [fontSize].
 */
@Immutable
public data class BraccatoTheme(
    // -- Colors --
    /** `--blyrics-text-color`. Lines are drawn in it, the inactive ones at [inactiveOpacity]. */
    val textColor: Color = Color.White,
    /** How opaque the line being sung is, against [textColor] (`--blyrics-active-opacity`). */
    val activeOpacity: Float = 1f,
    /** How opaque the other lines are, against [textColor] (`--blyrics-inactive-opacity`). */
    val inactiveOpacity: Float = 0.3f,
    /** `--blyrics-glow-color`, the highlight's drop shadow. */
    val glowColor: Color = Color.White.copy(alpha = 0.5f),
    /** The colour of translations and romanizations under a line. */
    val translatedColor: Color = Color.White.copy(alpha = 0.6f),
    /** `--blyrics-lyric-active-color`: the line being sung, in place of [textColor] at [activeOpacity]. */
    val lyricActiveColor: Color? = null,
    /** `--blyrics-lyric-inactive-color`: the other lines, in place of [textColor] at [inactiveOpacity]. */
    val lyricInactiveColor: Color? = null,

    // -- Typography --
    /** The lyrics' font family. Null: the platform's default. */
    val fontFamily: FontFamily? = null,
    /** `--blyrics-font-size`: 3rem. */
    val fontSize: Dp = 48.dp,
    /** The weight of sung lines. */
    val fontWeight: FontWeight = FontWeight.Bold,
    /** The line height, as a multiple of [fontSize]. */
    val lineHeight: Float = 1.333f,
    /**
     * The family for right-to-left lines (Hebrew, Arabic). The system's by default: a family of the app's
     * own seldom holds those scripts, and the platform's fallback for them comes at regular weight, thin
     * beside the rest. Null keeps [fontFamily].
     */
    val rtlFontFamily: FontFamily? = FontFamily.Default,
    /** The family of translations and romanizations. Null: [fontFamily]. */
    val translatedFontFamily: FontFamily? = null,
    /** The size of translations. */
    val translatedFontSize: Dp = 32.dp,
    /** The weight of translations and romanizations. */
    val translatedFontWeight: FontWeight = FontWeight.SemiBold,
    /** The romanization's own size. Null: as upstream, the translation's over 1.25. Not upstream. */
    val romanizedFontSize: Dp? = null,

    // -- Credits --
    /** What stands before the songwriters' names after the last line. */
    val creditsLabel: String = "Written by",
    /** How opaque the credits are while the song still runs. */
    val creditsOpacity: Float = 0.2f,
    /** How opaque the credits are once the last line is sung. */
    val creditsFocusedOpacity: Float = 0.85f,

    // -- Layout --
    /** `--blyrics-padding`, above and below every line. */
    val linePadding: Dp = 32.dp,
    /** `--blyrics-scale`, the size of a line that is not being sung. */
    val scale: Float = 0.95f,
    /** `--blyrics-active-scale`. */
    val activeScale: Float = 1f,

    // -- Animations --
    /** Which effects run. */
    val animations: BraccatoAnimations = BraccatoAnimations(),
    /** How long a line takes to grow to [activeScale] and back. */
    val scaleTransitionMs: Double = 166.0,
    /** The easing of a line growing as it starts. */
    val lineEnterEasing: Easing = CssEasing.Ease,
    /** The easing of a line shrinking as it ends. */
    val lineExitEasing: Easing = CssEasing.Ease,

    /** How long a line synced word takes to fade in. */
    val highlightFadeInMs: Double = 330.0,
    /** How long a word's highlight takes to fade out after its line. */
    val highlightFadeOutMs: Double = 500.0,
    /** The easing of the fade in. */
    val highlightFadeInEasing: Easing = CssEasing.Ease,
    /** The easing of the fade out. */
    val highlightFadeOutEasing: Easing = CssEasing.Ease,
    /** The easing of the swipe across a word. */
    val swipeEasing: Easing = LinearEasing,
    /** The swipe's easing as the theme wrote it, which the letter wave's planning reads. */
    val swipeEasingName: String = "linear",
    /** Where the swipe's leading edge starts, as a share of the word's width. */
    val swipeStartFrom: Double = -0.2,
    /** Where the swipe's trailing edge starts, as a share of the word's width. */
    val swipeEndFrom: Double = -0.1,
    /** Where the swipe's leading edge ends, as a share of the word's width. */
    val swipeStartTo: Double = 1.4,
    /** Where the swipe's trailing edge ends, as a share of the word's width. */
    val swipeEndTo: Double = 1.5,
    /** The glow's radius as a word starts. */
    val glowRadiusFrom: Dp = 12.8.dp,
    /** The glow's radius as it has died down. */
    val glowRadiusTo: Dp = 0.dp,
    /** How long the glow takes to die down, as a multiple of the word's duration. */
    val glowDurationRatio: Double = 1.2,
    /** The shortest the glow lasts, however short the word. */
    val glowMinDurationMs: Double = 1200.0,
    /** The easing of the glow dying down. */
    val glowEasing: Easing = CssEasing.Ease,

    /** How long the nudge of a word group lasts as it starts. */
    val wobbleDurationMs: Double = 1000.0,
    /** Peak of the wobble: `translateX(0.05em) scaleX(1.025)`. */
    val wobblePeakTranslateEm: Float = 0.05f,
    /** How wide the group grows at the nudge's peak. */
    val wobblePeakScaleX: Float = 1.025f,
    /** When the nudge peaks, as a share of its duration. */
    val wobblePeakOffset: Float = 0.125f,
    /** When the nudge settles, as a share of its duration. */
    val wobbleSettleOffset: Float = 0.75f,
    /** The easing into the nudge's peak. */
    val wobbleEasing: Easing = CssEasing.Ease,
    /** The easing from the peak to the settle. */
    val wobblePeakEasing: Easing = CssEasing.EaseInOut,
    /** The easing from the settle to rest. */
    val wobbleEndEasing: Easing = CssEasing.EaseOut,

    /** `--blyrics-letter-wave-transform: translateY(-0.05em)`. */
    val letterWaveRiseEm: Float = -0.05f,
    /** `--blyrics-letter-wave-settle: translateY(-0.02em)`. */
    val letterWaveSettleEm: Float = -0.02f,
    /** How large a letter grows as the wave passes it. */
    val letterWaveEmphasisScale: Float = 1.11f,
    /** How long the wave takes over one letter. */
    val letterWaveDurationMs: Double = 900.0,
    /** The easing of a letter rising. */
    val letterWaveRiseEasing: Easing = CssEasing.EaseInOut,
    /** The easing of a letter falling back. */
    val letterWaveFallEasing: Easing = CssEasing.EaseOut,

    /** How long the instrumental note's fill takes to fade in and out. */
    val instrumentalFillFadeMs: Double = 150.0,
    /** Where the fill's surface starts and ends, as a share of the note's height from the top. */
    val instrumentalFillFrom: Float = 0.78f,
    /** Where the fill's surface ends, as a share of the note's height from the top. */
    val instrumentalFillTo: Float = -0.04f,
    /** The wave on the fill's surface as the break starts, as a multiple of its height. */
    val instrumentalWaveFrom: Float = 1.2f,
    /** The wave on the fill's surface as the break ends. */
    val instrumentalWaveTo: Float = 0f,
    /** The easing of the wave settling. */
    val instrumentalWaveEasing: Easing = CssEasing.EaseIn,
    /** How long one swing of the wave takes. */
    val instrumentalWaveOscillationMs: Double = 1250.0,
    /** The easing of each swing. */
    val instrumentalWaveOscillationEasing: Easing = CssEasing.EaseInOut,

    /** `--blyrics-lyric-scroll-timing-function`, which the per-line scroll stagger moves with. */
    val lyricScrollEasing: Easing = CubicBezierEasing(0.86f, 0f, 0.2f, 1f),
    /** How long a translation or romanization takes to come in or go. */
    val decorationEntryMs: Double = 350.0,
    /** The easing of a translation or romanization coming in or going. */
    val decorationEntryEasing: Easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),

    /** `--blyrics-*-timing-offset`, in seconds. */
    val timingOffsets: TimingOffsets = TimingOffsets(),
) {
    /** The colour of the line being sung: [lyricActiveColor], or [textColor] at [activeOpacity]. */
    val activeColor: Color get() = lyricActiveColor ?: textColor.copy(alpha = textColor.alpha * activeOpacity)
    /** The colour of the other lines: [lyricInactiveColor], or [textColor] at [inactiveOpacity]. */
    val inactiveColor: Color get() = lyricInactiveColor ?: textColor.copy(alpha = textColor.alpha * inactiveOpacity)

    /**
     * The swipe as the core's letter planning reads it.
     *
     * @suppress Internal to the braccato modules.
     */
    @InternalBraccatoApi
    val swipeRamp: SwipeRamp
        get() = SwipeRamp(
            easing = swipeEasingName,
            startFrom = swipeStartFrom.toString(),
            startTo = swipeStartTo.toString(),
            endFrom = swipeEndFrom.toString(),
            endTo = swipeEndTo.toString(),
        )

    /** `--blyrics-credits-font-size: max(0.4em, 12px)`. */
    val creditsFontSize: Dp get() = max(fontSize.value * 0.4f, 12f).dp

    /** `--blyrics-target-scroll-pos-ratio`, which upstream reads as a theme setting. */
    val targetScrollRatio: Double get() = CoreSettings.SCROLL_POS_OFFSET_RATIO.getNumberValue()

    /** The default theme and the way to a Better Lyrics one. */
    public companion object {
        /** Upstream's look, unchanged. */
        public val Default: BraccatoTheme = BraccatoTheme()

        /**
         * Applies a compiled Better Lyrics theme: its `blyrics-*` comment settings go to the core's
         * registry (which is process wide, as upstream's is), and its `--blyrics-*` custom properties
         * override the defaults here where they are literal values this renderer reads.
         *
         * @return The theme, and whether a setting the lines are built from changed, so the caller can
         *   build them again.
         */
        public fun fromCss(css: String, base: BraccatoTheme = Default): Pair<BraccatoTheme, Boolean> {
            val needsRebuild = setThemeSettings(parseThemeConfig(css))
            return base.withCustomProperties(readCustomProperties(css)) to needsRebuild
        }
    }

    /** This theme with whatever `--blyrics-*` properties it can read applied. */
    public fun withCustomProperties(properties: Map<String, String>): BraccatoTheme {
        var t = this
        val em = fontSize.value.toDouble()
        fun prop(name: String) = properties["--blyrics-$name"]
        fun color(name: String, apply: (Color) -> BraccatoTheme) { prop(name)?.let(::parseCssColor)?.let { t = apply(it) } }
        fun number(name: String, apply: (Double) -> BraccatoTheme) { prop(name)?.let(::parseCssNumber)?.let { t = apply(it) } }
        fun ms(name: String, apply: (Double) -> BraccatoTheme) { prop(name)?.let(::parseCssDurationMs)?.let { t = apply(it) } }
        fun px(name: String, apply: (Double) -> BraccatoTheme) { prop(name)?.let { parseCssLengthPx(it, em) }?.let { t = apply(it) } }
        fun easing(name: String, apply: (Easing) -> BraccatoTheme) { prop(name)?.let(::parseCssEasing)?.let { t = apply(it) } }
        fun flag(name: String) = prop("animate-$name")?.let(::parseCssNumber)?.let { it != 0.0 }

        color("text-color") { t.copy(textColor = it) }
        color("lyric-active-color") { t.copy(lyricActiveColor = it) }
        color("lyric-inactive-color") { t.copy(lyricInactiveColor = it) }
        color("highlight-color") { t.copy(glowColor = it) }
        color("glow-color") { t.copy(glowColor = it) }
        color("translated-color") { t.copy(translatedColor = it) }
        number("active-opacity") { t.copy(activeOpacity = it.toFloat()) }
        number("inactive-opacity") { t.copy(inactiveOpacity = it.toFloat()) }
        number("translated-opacity") { t.copy(translatedColor = t.translatedColor.copy(alpha = it.toFloat())) }

        px("font-size") { t.copy(fontSize = it.dp) }
        number("font-weight") { t.copy(fontWeight = FontWeight(it.toInt().coerceIn(1, 1000))) }
        number("line-height") { t.copy(lineHeight = it.toFloat()) }
        px("translated-font-size") { t.copy(translatedFontSize = it.dp) }
        number("translated-font-weight") { t.copy(translatedFontWeight = FontWeight(it.toInt().coerceIn(1, 1000))) }

        prop("credits-label")?.let { label -> t = t.copy(creditsLabel = label.trim('"', '\'')) }
        number("credits-opacity") { t.copy(creditsOpacity = it.toFloat()) }
        number("credits-focused-opacity") { t.copy(creditsFocusedOpacity = it.toFloat()) }

        px("padding") { t.copy(linePadding = it.dp) }
        number("scale") { t.copy(scale = it.toFloat()) }
        number("active-scale") { t.copy(activeScale = it.toFloat()) }

        t = t.copy(
            animations = t.animations.copy(
                lineScale = flag("line-scale") ?: t.animations.lineScale,
                wordWobble = flag("word-wobble") ?: t.animations.wordWobble,
                highlightSwipe = flag("highlight-swipe") ?: t.animations.highlightSwipe,
                highlightGlow = flag("highlight-glow") ?: t.animations.highlightGlow,
                highlightFade = flag("highlight-fade") ?: t.animations.highlightFade,
                scroll = flag("scroll") ?: t.animations.scroll,
                instrumental = flag("instrumental") ?: t.animations.instrumental,
                decorationEntry = flag("decoration-entry") ?: t.animations.decorationEntry,
            ),
        )

        ms("scale-transition-duration") { t.copy(scaleTransitionMs = it) }
        ms("transition-duration") { t.copy(scaleTransitionMs = it) }
        easing("line-enter-easing") { t.copy(lineEnterEasing = it) }
        easing("line-exit-easing") { t.copy(lineExitEasing = it) }
        ms("lyric-highlight-fade-in-duration") { t.copy(highlightFadeInMs = it) }
        ms("lyric-highlight-fade-out-duration") { t.copy(highlightFadeOutMs = it) }
        easing("lyric-highlight-fade-in-easing") { t.copy(highlightFadeInEasing = it) }
        easing("lyric-highlight-fade-out-easing") { t.copy(highlightFadeOutEasing = it) }
        prop("highlight-swipe-easing")?.let { name ->
            parseCssEasing(name)?.let { t = t.copy(swipeEasing = it, swipeEasingName = name.trim()) }
        }
        number("highlight-swipe-start-from") { t.copy(swipeStartFrom = it) }
        number("highlight-swipe-end-from") { t.copy(swipeEndFrom = it) }
        number("highlight-swipe-start-to") { t.copy(swipeStartTo = it) }
        number("highlight-swipe-end-to") { t.copy(swipeEndTo = it) }
        px("highlight-glow-radius-from") { t.copy(glowRadiusFrom = it.dp) }
        px("highlight-glow-radius-to") { t.copy(glowRadiusTo = it.dp) }
        number("highlight-glow-duration-ratio") { t.copy(glowDurationRatio = it) }
        ms("highlight-glow-min-duration") { t.copy(glowMinDurationMs = it) }
        easing("highlight-glow-easing") { t.copy(glowEasing = it) }

        ms("wobble-duration") { t.copy(wobbleDurationMs = it) }
        number("word-wobble-peak-offset") { t.copy(wobblePeakOffset = it.toFloat()) }
        number("word-wobble-settle-offset") { t.copy(wobbleSettleOffset = it.toFloat()) }
        easing("word-wobble-easing") { t.copy(wobbleEasing = it) }
        easing("word-wobble-peak-easing") { t.copy(wobblePeakEasing = it) }
        easing("word-wobble-end-easing") { t.copy(wobbleEndEasing = it) }

        number("letter-wave-emphasis-scale") { t.copy(letterWaveEmphasisScale = it.toFloat()) }
        ms("letter-wave-duration") { t.copy(letterWaveDurationMs = it) }
        easing("letter-wave-rise-easing") { t.copy(letterWaveRiseEasing = it) }
        easing("letter-wave-fall-easing") { t.copy(letterWaveFallEasing = it) }

        ms("instrumental-fill-fade-duration") { t.copy(instrumentalFillFadeMs = it) }
        ms("instrumental-wave-oscillation-duration") { t.copy(instrumentalWaveOscillationMs = it) }
        easing("instrumental-wave-easing") { t.copy(instrumentalWaveEasing = it) }
        easing("instrumental-wave-oscillation-easing") { t.copy(instrumentalWaveOscillationEasing = it) }

        easing("lyric-scroll-timing-function") { t.copy(lyricScrollEasing = it) }
        easing("lyric-transition-timing-function") { t.copy(lyricScrollEasing = it) }

        ms("richsync-timing-offset") { t.copy(timingOffsets = t.timingOffsets.copy(richsync = it / 1000)) }
        ms("timing-offset") { t.copy(timingOffsets = t.timingOffsets.copy(line = it / 1000)) }
        ms("scroll-timing-offset") { t.copy(timingOffsets = t.timingOffsets.copy(scroll = it / 1000)) }

        return t
    }
}

/** How large a romanization is drawn. */
internal val BraccatoTheme.romanizedSize: Dp get() = romanizedFontSize ?: (translatedFontSize / 1.25f)
