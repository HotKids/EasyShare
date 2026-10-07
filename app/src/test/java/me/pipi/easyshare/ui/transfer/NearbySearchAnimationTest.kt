package me.pipi.easyshare.ui.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySearchAnimationTest {
    @Test
    fun pulseFadesInHoldsAndFadesOutAtReferenceKeyframes() {
        assertEquals(0f, searchPulseAlpha(-1f), 0f)
        assertEquals(0f, searchPulseAlpha(0f), 0f)
        assertEquals(0.25f, searchPulseAlpha(15f), 0.0001f)
        assertEquals(0.5f, searchPulseAlpha(30f), 0f)
        assertEquals(0.5f, searchPulseAlpha(46f), 0f)
        assertEquals(0.25f, searchPulseAlpha(69f), 0.0001f)
        assertEquals(0f, searchPulseAlpha(92f), 0f)
        assertEquals(0f, searchPulseAlpha(150f), 0f)
    }

    @Test
    fun pulseExpandsLinearlyFromReferenceDiameter() {
        assertEquals(88.4f / 224f, searchPulseDiameterFraction(0f), 0.0001f)
        assertEquals(156.4f / 224f, searchPulseDiameterFraction(46f), 0.0001f)
        assertEquals(224.4f / 224f, searchPulseDiameterFraction(92f), 0.0001f)
        assertEquals(searchPulseDiameterFraction(0f), searchPulseDiameterFraction(-50f), 0f)
        assertEquals(searchPulseDiameterFraction(92f), searchPulseDiameterFraction(150f), 0f)
    }

    @Test
    fun pulseAlwaysStaysWithinReferenceOpacityAndExpandsOutward() {
        var previousDiameter = 0f
        for (frame in -1..100) {
            val alpha = searchPulseAlpha(frame.toFloat())
            val diameter = searchPulseDiameterFraction(frame.toFloat())
            assertTrue(alpha in 0f..0.5f)
            assertTrue(diameter >= previousDiameter)
            previousDiameter = diameter
        }
    }
}
