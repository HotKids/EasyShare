package me.pipi.easyshare.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackLightColorTest {
    @Test
    fun primaryButtonLabelHasSufficientContrast() {
        assertReadable(LightColorScheme.onPrimary, LightColorScheme.primary)
    }

    @Test
    fun primaryActionsHaveSufficientContrastOnHomeAndSheetSurfaces() {
        for (background in listOf(
            LightColorScheme.background,
            LightColorScheme.surface,
            LightColorScheme.surfaceContainerLow,
        )) {
            assertReadable(LightColorScheme.primary, background)
        }
    }

    @Test
    fun supportingTextHasSufficientContrastOnNeutralSurfaces() {
        for (background in listOf(
            LightColorScheme.background,
            LightColorScheme.surface,
            LightColorScheme.surfaceVariant,
            LightColorScheme.surfaceContainerLowest,
            LightColorScheme.surfaceContainerLow,
            LightColorScheme.surfaceContainer,
            LightColorScheme.surfaceContainerHigh,
            LightColorScheme.surfaceContainerHighest,
        )) {
            assertReadable(LightColorScheme.onSurfaceVariant, background)
        }
    }

    private fun assertReadable(foreground: Color, background: Color) {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val contrast = (max(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("Expected at least 4.5:1 contrast, got $contrast", contrast >= 4.5f)
    }
}
