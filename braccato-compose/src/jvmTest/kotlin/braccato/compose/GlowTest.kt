package braccato.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.BlurEffect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

// Checked against Compose's own conversion, so a Compose release that changes it fails here.
@OptIn(InternalComposeUiApi::class)
class GlowTest {
    @Test fun `the glow blurs as much as upstream's CSS drop-shadow`() {
        // CSS: a drop-shadow's blur radius is twice the Gaussian's standard deviation.
        for (cssRadius in listOf(12.8f, 6f, 3f)) {
            val sigma = BlurEffect.convertRadiusToSigma(composeBlurRadiusFor(cssRadius))
            assertTrue(abs(sigma - cssRadius / 2) < 0.01f, "a $cssRadius px CSS glow blurs with sigma ${cssRadius / 2}, got $sigma")
        }
    }
}
