package me.pipi.easyshare.ui.theme

import androidx.compose.material3.ColorScheme
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

    @Test
    fun fallbackSurfacesAndOutlinesStayNeutralInBothThemes() {
        for (scheme in listOf(LightColorScheme, DarkColorScheme)) {
            for (color in neutralSurfaces(scheme) + listOf(scheme.outline, scheme.outlineVariant)) {
                val chroma = maxOf(color.red, color.green, color.blue) -
                    minOf(color.red, color.green, color.blue)
                assertTrue("Expected a neutral fallback color, got $color", chroma <= 5f / 255f)
            }
        }
    }

    @Test
    fun darkThemeContentAndPrimaryActionsHaveSufficientContrast() {
        assertReadable(DarkColorScheme.onPrimary, DarkColorScheme.primary)
        assertReadable(DarkColorScheme.onPrimaryContainer, DarkColorScheme.primaryContainer)
        for (background in neutralSurfaces(DarkColorScheme)) {
            assertReadable(DarkColorScheme.onSurface, background)
            assertReadable(DarkColorScheme.onSurfaceVariant, background)
            assertReadable(DarkColorScheme.primary, background)
        }
    }

    @Test
    fun lightThemeContentHasSufficientContrastAcrossSurfaceHierarchy() {
        assertReadable(LightColorScheme.onPrimaryContainer, LightColorScheme.primaryContainer)
        for (background in neutralSurfaces(LightColorScheme)) {
            assertReadable(LightColorScheme.onSurface, background)
            assertReadable(LightColorScheme.onSurfaceVariant, background)
        }
    }

    @Test
    fun surfaceContainersPreserveLightAndDarkHierarchy() {
        for ((scheme, light) in listOf(LightColorScheme to true, DarkColorScheme to false)) {
            val luminance = listOf(scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
                scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
                .map(Color::luminance)
            assertTrue("Expected ordered container luminance: $luminance", luminance.zipWithNext().all {
                (lower, higher) -> if (light) lower > higher else lower < higher
            })
        }
    }

    @Test
    fun progressTrackUsesTheSupportingBluePaletteInBothThemes() {
        for (scheme in listOf(LightColorScheme, DarkColorScheme)) {
            assertTrue("Expected a blue supporting container", scheme.secondaryContainer.blue >= scheme.secondaryContainer.red)
            assertTrue("Expected a blue supporting container", scheme.secondaryContainer.green >= scheme.secondaryContainer.red)
            assertReadable(scheme.onSecondaryContainer, scheme.secondaryContainer)
        }
    }

    private fun neutralSurfaces(scheme: ColorScheme): List<Color> = listOf(
        scheme.background, scheme.surface, scheme.surfaceVariant,
        scheme.surfaceDim, scheme.surfaceBright,
        scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
        scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
    )

    private fun assertReadable(foreground: Color, background: Color) {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val contrast = (max(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("Expected at least 4.5:1 contrast, got $contrast", contrast >= 4.5f)
    }
}
