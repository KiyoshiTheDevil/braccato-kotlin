// Port of packages/core/src/util.ts, the part that is not the DOM. `isGecko`, `reflow` and the
// layout reads are browser questions with no counterpart here.
package dev.kiyoshi.braccato.core

import dev.kiyoshi.braccato.types.internal.InternalBraccatoApi

import dev.kiyoshi.braccato.types.internal.jsParseFloat
import dev.kiyoshi.braccato.types.internal.jsRound
import kotlin.math.max
import kotlin.math.min

internal fun clamp(value: Double, min: Double, max: Double): Double {
    return max(min, min(max, value))
}

internal fun positiveModulo(value: Double, divisor: Double): Double {
    return ((value % divisor) + divisor) % divisor
}

internal fun roundedMs(value: Double): Double {
    return jsRound(value * 10) / 10
}

/**
 * Converts CSS duration value to milliseconds.
 *
 * @return Duration in milliseconds
 *
 * @suppress Internal to the braccato modules.
 */
@InternalBraccatoApi public fun toMs(cssDuration: String): Double {
    if (cssDuration.isEmpty()) return 0.0
    if (cssDuration.endsWith("ms")) {
        return jsParseFloat(cssDuration.dropLast(2))
    } else if (cssDuration.endsWith("s")) {
        return jsParseFloat(cssDuration.dropLast(1)) * 1000
    }
    return 0.0
}
