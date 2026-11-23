// Port of packages/core/src/themeSettings.ts.
//
// The renderer declares the theme settings its own code reads, so a setting and the code that
// consumes it stay together. Applying settings reports whether the lyrics need reloading; the host
// decides what to do about it.
//
// What arrives here is compiled CSS rather than theme source. Better Lyrics themes are written in
// RICS and compiled with the `rics` package first, which is the consumer's dependency and not this
// module's.
//
// One difference from upstream: there, every module registers its settings as it is imported, so all
// of them exist before a theme can be applied. Kotlin initialises a file's top-level values on first
// use instead, which would leave a setting that is first read after the theme was applied at its
// default. So the module's own settings all live in CoreSettings.kt, and touching the registry
// registers them.
package braccato.core

import braccato.types.internal.WS
import braccato.types.internal.jsParseFloat
import braccato.types.internal.jsTrim

private val keyToSettingMap: MutableMap<String, Setting> = LinkedHashMap()

// -- Setting --------------------------------------------

/** What a [Setting] holds: a `Double`, a `Boolean` or a `String`. */
public enum class SettingType { NUMBER, BOOLEAN, STRING }

/**
 * One theme setting, as [CoreSettings] lists them. Its value is shared by every view in the process, as
 * upstream's is.
 */
public class Setting internal constructor(
    /** What the value is. */
    public val type: SettingType,
    value: Any,
    /** The value when no theme sets one. */
    public val defaultValue: Any,
    /** Whether lyrics already built must be built again for a change to show. */
    public val requiresLyricReload: Boolean,
) {
    /** A `Double`, `Boolean` or `String`, matching [type]. */
    public var value: Any = value
        internal set
    private var manuallySet = false

    /** The value of a [SettingType.NUMBER] setting. */
    public fun getNumberValue(): Double = value as Double

    /** The value of a [SettingType.BOOLEAN] setting. */
    public fun getBooleanValue(): Boolean = value as Boolean

    /** The value of a [SettingType.STRING] setting. */
    public fun getStringValue(): String = value as String

    /** Whether the host set the value itself, rather than a theme; a theme then leaves it alone. */
    public fun isManuallySet(): Boolean = manuallySet

    /** Marks the value as the host's own, see [isManuallySet]. */
    public fun setManuallySet(manuallySet: Boolean) {
        this.manuallySet = manuallySet
    }
}

// -- Parsing a theme --------------------------------------------

// A theme is a stylesheet, and it configures the renderer through comments inside it, in the form
// `blyrics-some-key = value;`. Only inside comments: everything outside one is CSS a browser would
// read, and a stylesheet must not be able to configure the module by accident.
private val THEME_COMMENT_PATTERN = Regex("""/\*([\s\S]*?)\*/""")
private val THEME_SETTING_PATTERN = Regex("""(blyrics-[\w-]+)$WS*=$WS*([^;]+);""")

/**
 * The `blyrics-*` configuration a compiled stylesheet declares, in declaration order, last one
 * winning. Depends on nothing, so a consumer that compiles a theme somewhere the renderer does not
 * run can read the settings out of it on its own.
 */
public fun parseThemeConfig(css: String): Map<String, String> {
    val config = LinkedHashMap<String, String>()

    for (comment in THEME_COMMENT_PATTERN.findAll(css)) {
        for (setting in THEME_SETTING_PATTERN.findAll(comment.groupValues[1])) {
            val key = setting.groupValues[1]
            // A Map keeps a key's first position when it is set again, which is what upstream's does.
            config[key] = setting.groupValues[2].jsTrim()
        }
    }

    return config
}

// -- Registry --------------------------------------------

// Module scope, as upstream: a setting's value is shared by every view in the process rather than
// held per instance.
private fun register(key: String, type: SettingType, defaultValue: Any, requiresLyricReload: Boolean): Setting {
    val setting = Setting(type, defaultValue, defaultValue, requiresLyricReload)
    keyToSettingMap[key] = setting
    return setting
}

internal fun registerThemeSetting(key: String, defaultValue: Double, requiresLyricReload: Boolean = false): Setting =
    register(key, SettingType.NUMBER, defaultValue, requiresLyricReload)

internal fun registerThemeSetting(key: String, defaultValue: Boolean, requiresLyricReload: Boolean = false): Setting =
    register(key, SettingType.BOOLEAN, defaultValue, requiresLyricReload)

internal fun registerThemeSetting(key: String, defaultValue: String, requiresLyricReload: Boolean = false): Setting =
    register(key, SettingType.STRING, defaultValue, requiresLyricReload)

/**
 * Applies a theme's `blyrics-*` settings, as [parseThemeConfig] reads them; settings it leaves out go back
 * to their defaults, and those the host set itself stay.
 *
 * @return Whether a setting that [Setting.requiresLyricReload] changed, so the lyrics should be built again.
 */
public fun setThemeSettings(map: Map<String, String>): Boolean {
    CoreSettings.ensureRegistered()
    var needsLyricReload = false

    map.forEach { (key, value) ->
        val setting = keyToSettingMap[key] ?: return@forEach
        val lastValue = setting.value
        when (setting.type) {
            SettingType.NUMBER -> {
                val parsed = jsParseFloat(value)
                if (parsed.isNaN()) {
                    setting.value = setting.defaultValue
                    setting.setManuallySet(false)
                } else {
                    setting.value = parsed
                    setting.setManuallySet(true)
                }
            }
            SettingType.BOOLEAN -> {
                setting.value = value.lowercase() == "true"
                setting.setManuallySet(true)
            }
            SettingType.STRING -> {
                setting.value = value
                setting.setManuallySet(true)
            }
        }

        if (setting.requiresLyricReload && lastValue != setting.value) {
            needsLyricReload = true
        }
    }

    // second pass reset undefined values to their default values
    for ((key, setting) in keyToSettingMap) {
        if (!map.containsKey(key) && setting.value != setting.defaultValue) {
            setting.value = setting.defaultValue
            setting.setManuallySet(false)
            if (setting.requiresLyricReload) {
                needsLyricReload = true
            }
        } else if (!map.containsKey(key)) {
            setting.setManuallySet(false)
        }
    }

    return needsLyricReload
}

/**
 * The values of every setting that changes how lines are built, as one comparable string. Two builds
 * that answer the same here built their lines the same way, so a line built under one can stand in
 * for the same line built under the other.
 */
internal fun lyricBuildSettingsKey(): String {
    CoreSettings.ensureRegistered()
    val values = mutableListOf<String>()
    for ((key, setting) in keyToSettingMap) {
        if (setting.requiresLyricReload) values += "$key=${jsString(setting.value)}"
    }
    return values.joinToString(";")
}

/** `String(value)` for a setting value: a whole number prints without a fraction, as in JavaScript. */
internal fun jsString(value: Any): String =
    if (value is Double && value == kotlin.math.floor(value) && !value.isInfinite()) value.toLong().toString()
    else value.toString()
