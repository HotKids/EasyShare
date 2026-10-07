package me.pipi.easyshare.ui.main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.android.material.color.MaterialColors
import me.pipi.easyshare.utils.DeviceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandCardColorTest {
    @Test
    fun detectedPixelUsesGoogleBlueInsteadOfAutoFallback() {
        val effectiveBrand = DeviceUtils.detectBrandId("google", "Google", "Pixel 11 Pro")
        val expected = MaterialColors.getColorRoles(0xFF3086FF.toInt(), true)

        assertEquals(expected.accentContainer, brandCardColorRoles(effectiveBrand, false).accentContainer)
        assertNotEquals(brandCardColorRoles(0, false).accentContainer, expected.accentContainer)
    }

    @Test
    fun brandBackgroundUsesItsSourcePalette() {
        val pixel = brandCardColorRoles(130, false).accentContainer
        val samsung = brandCardColorRoles(70, false).accentContainer
        val xiaomi = brandCardColorRoles(30, false).accentContainer

        assertEquals(MaterialColors.getColorRoles(0xFF3086FF.toInt(), true).accentContainer, pixel)
        assertEquals(MaterialColors.getColorRoles(0xFF1F7FFB.toInt(), true).accentContainer, samsung)
        assertNotEquals(samsung, xiaomi)
        assertEquals(
            MaterialColors.getColorRoles(0xFFFF8847.toInt(), true).accentContainer,
            xiaomi,
        )
    }

    @Test
    fun subBrandsRetainTheirOwnSourceColor() {
        assertEquals(0xFFFFC933.toInt(), DeviceUtils.devicePrimaryColorById(11))
        assertEquals(0xFF000000.toInt(), DeviceUtils.devicePrimaryColorById(10))
        assertEquals(0xFF43D077.toInt(), DeviceUtils.devicePrimaryColorById(32))
        assertEquals(0xFFFF8847.toInt(), DeviceUtils.devicePrimaryColorById(39))
        assertEquals(0xFFD42C1F.toInt(), DeviceUtils.devicePrimaryColorById(66))
        assertEquals(0xFFFF3332.toInt(), DeviceUtils.devicePrimaryColorById(69))
    }

    @Test
    fun unknownBrandUsesExistingAndroidIconColor() {
        for (brandId in listOf(null, -1, 0, 999)) {
            assertEquals(0xFF43D077.toInt(), DeviceUtils.devicePrimaryColorById(brandId))
        }
        for (brandId in 130..139) {
            assertEquals(0xFF3086FF.toInt(), DeviceUtils.devicePrimaryColorById(brandId))
        }
    }

    @Test
    fun allSelectableBrandsHaveReadableTextInBothThemes() {
        val brandIds = DeviceUtils.getBrandList().map { it.first } + listOf(0, 11, 32, 66)
        for (brandId in brandIds) {
            for (darkTheme in listOf(false, true)) {
                val roles = brandCardColorRoles(brandId, darkTheme)
                val background = Color(roles.accentContainer).luminance()
                val foreground = Color(roles.onAccentContainer).luminance()
                val contrast = (maxOf(background, foreground) + 0.05f) /
                    (minOf(background, foreground) + 0.05f)

                assertTrue("Brand $brandId dark=$darkTheme contrast=$contrast", contrast >= 4.5f)
            }
        }
    }

    @Test
    fun darkModeUsesDifferentBackgroundAndForeground() {
        for (brandId in listOf(0, 130, 70, 30, 10)) {
            val light = brandCardColorRoles(brandId, false)
            val dark = brandCardColorRoles(brandId, true)

            assertNotEquals(light.accentContainer, dark.accentContainer)
            assertNotEquals(light.onAccentContainer, dark.onAccentContainer)
        }
    }
}
