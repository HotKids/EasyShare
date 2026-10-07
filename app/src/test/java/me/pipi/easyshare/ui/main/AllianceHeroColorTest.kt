package me.pipi.easyshare.ui.main

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AllianceHeroColorTest {
    private val themeColor = 0xFF705682.toInt()

    @Test
    fun lightAnimationUsesThemeColor() {
        assertArrayEquals(
            floatArrayOf(112f, 86f, 130f, 255f),
            transform(heroAnimationColorMatrix(themeColor, false), 255f, 255f, 255f),
            0.0001f,
        )
    }

    @Test
    fun darkAnimationUsesItsOwnBaseTone() {
        assertArrayEquals(
            floatArrayOf(112f, 86f, 130f, 255f),
            transform(heroAnimationColorMatrix(themeColor, true), 42f, 45f, 46f),
            0.0001f,
        )
    }

    @Test
    fun animationDetailsStayDistinct() {
        assertArrayEquals(
            floatArrayOf(97f, 71f, 115f, 255f),
            transform(heroAnimationColorMatrix(themeColor, false), 240f, 240f, 240f),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(132f, 106f, 150f, 255f),
            transform(heroAnimationColorMatrix(themeColor, true), 62f, 65f, 66f),
            0.0001f,
        )
    }

    @Test
    fun illustrationAccentUsesThemeColor() {
        assertArrayEquals(
            floatArrayOf(112f, 86f, 130f, 255f),
            transform(heroIllustrationColorMatrix(themeColor), 244f, 103f, 88f),
            0.0001f,
        )
    }

    @Test
    fun neutralPhoneSurfacesStayUnchanged() {
        assertArrayEquals(
            floatArrayOf(219f, 219f, 223f, 255f),
            transform(heroIllustrationColorMatrix(themeColor), 219f, 219f, 223f),
            0.0001f,
        )
    }

    @Test
    fun bothFiltersKeepOriginalTransparency() {
        for (matrix in listOf(
            heroAnimationColorMatrix(themeColor, false),
            heroAnimationColorMatrix(themeColor, true),
            heroIllustrationColorMatrix(themeColor),
        )) {
            assertArrayEquals(
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
                matrix.copyOfRange(15, 20),
                0f,
            )
        }
    }

    private fun transform(matrix: FloatArray, red: Float, green: Float, blue: Float): FloatArray {
        val input = floatArrayOf(red, green, blue, 255f)
        return FloatArray(4) { row ->
            (input.indices.sumOf { column ->
                (input[column] * matrix[row * 5 + column]).toDouble()
            }.toFloat() + matrix[row * 5 + 4]).coerceIn(0f, 255f)
        }
    }
}
