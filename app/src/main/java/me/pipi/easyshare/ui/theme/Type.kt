package me.pipi.easyshare.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val MaterialTypography = Typography()

// Chinese text uses system glyphs without the default Latin tracking.
val Typography = Typography(
    headlineSmall = MaterialTypography.headlineSmall.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
    titleMedium = MaterialTypography.titleMedium.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
    titleSmall = MaterialTypography.titleSmall.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
    bodyLarge = MaterialTypography.bodyLarge.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
    bodyMedium = MaterialTypography.bodyMedium.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
    labelLarge = MaterialTypography.labelLarge.copy(
        fontFamily = FontFamily.Default,
        letterSpacing = 0.sp,
    ),
)
