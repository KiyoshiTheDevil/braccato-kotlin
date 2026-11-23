// Port of packages/core/src/instrumental.ts, its two wave shapes. The rest of that file builds the
// note icon as SVG elements; a Compose renderer draws it from these paths instead.
package braccato.core

import braccato.types.internal.InternalBraccatoApi

/** The surface of the fill inside the note, at the top of its ripple. SVG path data, 24 by 24 box. */
@InternalBraccatoApi public const val INSTRUMENTAL_WAVE_PATH_HIGH: String = "M -4 3 Q 1 2 5 3 Q 10 4 14 3 Q 18 2 22 3 Q 26 4 30 3 L 30 4 L -4 4 Z"

/** The same surface at the bottom of its ripple. Same commands as the high one, so the two morph. */
@InternalBraccatoApi public const val INSTRUMENTAL_WAVE_PATH_LOW: String = "M -4 3 Q 1 4 5 3 Q 10 2 14 3 Q 18 4 22 3 Q 26 2 30 3 L 30 4 L -4 4 Z"

/** The block under the wave that fills the rest of the note. */
@InternalBraccatoApi public const val INSTRUMENTAL_WAVE_RECT_PATH: String = "M -4 3.9 L 30 3.9 L 30 30 L -4 30 Z"

/** The note itself, drawn once as the empty background and once as the fill the wave clips. */
@InternalBraccatoApi public const val INSTRUMENTAL_NOTE_PATH: String =
    "M10 21q-1.65 0-2.825-1.175T6 17t1.175-2.825T10 13q.575 0 1.063.138t.937.412V4q0-.425.288-.712T13 3h4q.425 0 .713.288T18 4v2q0 .425-.288.713T17 7h-3v10q0 1.65-1.175 2.825T10 21"
