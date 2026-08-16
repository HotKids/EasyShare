package me.pipi.easyshare.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EasyShareBlueDark,
    onPrimary = FlymeDarkBackground,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004D63),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFB9EBF8),
    background = FlymeDarkBackground,
    onBackground = FlymeDarkOnSurface,
    surface = FlymeDarkBackground,
    onSurface = FlymeDarkOnSurface,
    surfaceVariant = FlymeDarkSurface,
    onSurfaceVariant = FlymeDarkOnSurfaceVariant,
    outlineVariant = FlymeDarkOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = EasyShareBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFC4F0FB),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF003642),
    background = FlymeLightBackground,
    onBackground = FlymeLightOnSurface,
    surface = FlymeLightBackground,
    onSurface = FlymeLightOnSurface,
    surfaceVariant = FlymeLightSurface,
    onSurfaceVariant = FlymeLightOnSurfaceVariant,
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
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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
