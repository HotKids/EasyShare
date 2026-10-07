package me.pipi.easyshare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val DarkColorScheme = darkColorScheme(
    primary = EasyShareBlueDark,
    onPrimary = FlymeDarkBackground,
    primaryContainer = Color(0xFF004D63),
    onPrimaryContainer = Color(0xFFB9EBF8),
    secondary = Color(0xFFAFCCD6),
    onSecondary = Color(0xFF18323C),
    secondaryContainer = Color(0xFF2C454F),
    onSecondaryContainer = Color(0xFFDAE6EB),
    background = FlymeDarkBackground,
    onBackground = FlymeDarkOnSurface,
    surface = FlymeDarkBackground,
    onSurface = FlymeDarkOnSurface,
    surfaceVariant = FlymeDarkSurface,
    onSurfaceVariant = FlymeDarkOnSurfaceVariant,
    surfaceTint = EasyShareBlueDark,
    surfaceDim = FlymeDarkBackground,
    surfaceBright = Color(0xFF343438),
    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF171719),
    surfaceContainerHigh = FlymeDarkSurface,
    surfaceContainerHighest = Color(0xFF27272A),
    outline = Color(0xFF858589),
    outlineVariant = FlymeDarkOutline,
)

internal val LightColorScheme = lightColorScheme(
    primary = EasyShareBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4F0FB),
    onPrimaryContainer = Color(0xFF003642),
    secondary = Color(0xFF4A626A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE6EB),
    onSecondaryContainer = Color(0xFF263B43),
    background = FlymeLightBackground,
    onBackground = FlymeLightOnSurface,
    surface = FlymeLightBackground,
    onSurface = FlymeLightOnSurface,
    surfaceVariant = FlymeLightSurface,
    onSurfaceVariant = FlymeLightOnSurfaceVariant,
    surfaceTint = EasyShareBlue,
    surfaceDim = Color(0xFFE4E4E7),
    surfaceBright = FlymeLightSurface,
    surfaceContainerLowest = FlymeLightSurface,
    surfaceContainerLow = Color(0xFFF3F3F5),
    surfaceContainer = Color(0xFFF0F0F2),
    surfaceContainerHigh = Color(0xFFEAEAEC),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    outline = Color(0xFF747478),
    outlineVariant = FlymeLightOutline,
)

@Composable
fun EasyShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
