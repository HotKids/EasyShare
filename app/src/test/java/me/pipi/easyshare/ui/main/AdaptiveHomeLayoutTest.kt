package me.pipi.easyshare.ui.main

import org.junit.Assert.assertFalse
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
}
