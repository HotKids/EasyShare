package me.pipi.easyshare.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveHomeLayoutTest {
    @Test
    fun phoneAndMediumWindowsKeepSinglePane() {
        assertFalse(usesTwoPaneHome(427f, 952f))
        assertFalse(usesTwoPaneHome(600f, 1024f))
        assertFalse(usesTwoPaneHome(839f, 1024f))
    }

    @Test
    fun expandedWindowUsesTwoPanesAtTheBoundary() {
        assertTrue(usesTwoPaneHome(840f, 480f))
        assertTrue(usesTwoPaneHome(1280f, 800f))
    }

    @Test
    fun shortWindowsRemainScrollableSinglePane() {
        assertFalse(usesTwoPaneHome(840f, 479f))
        assertFalse(usesTwoPaneHome(1280f, 320f))
    }

    @Test
    fun portraitArtworkKeepsItsEstablishedSizeAndNarrowWidthScaling() {
        assertEquals(1f, homeHeroArtworkScale(379f, 800f), 0.001f)
        assertEquals(0.5f, homeHeroArtworkScale(132f, 800f), 0.001f)
    }

    @Test
    fun shortWindowArtworkLeavesSpaceForReceiveControls() {
        listOf(240f, 320f, 479f).forEach { height ->
            assertTrue(312f * homeHeroArtworkScale(1280f, height) <= height / 3f + 0.001f)
        }
    }
}
