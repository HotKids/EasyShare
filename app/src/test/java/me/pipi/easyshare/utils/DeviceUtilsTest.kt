package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUtilsTest {
    @Test
    fun p2pIdentityRejectsRedactedMalformedAndMulticastAddresses() {
        listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff", "03:12:34:56:78:90", "00:12:34:56:78",
            "00:12:34:56:78:gg").forEach {
            assertNull(DeviceUtils.usableP2pDeviceAddress(it))
        }
        assertEquals("02:ab:cd:12:34:56", DeviceUtils.usableP2pDeviceAddress("02:AB:CD:12:34:56"))
    }

    @Test
    fun pixelBrandRangeUsesTheGoogleArtwork() {
        assertEquals(me.pipi.easyshare.R.drawable.device_google, DeviceUtils.deviceIconById(130))
        assertEquals(me.pipi.easyshare.R.drawable.device_google, DeviceUtils.deviceIconById(139))
    }

    @Test
    fun automaticAndUnknownOptionsKeepTheAndroidArtwork() {
        listOf(null, -1, 0).forEach { id ->
            assertEquals(me.pipi.easyshare.R.drawable.device_default, DeviceUtils.deviceIconById(id))
        }
    }

    @Test
    fun randomNetworkCredentialsUseExpectedAlphabetAndVary() {
        val first = DeviceUtils.getRandomChars(32)
        val second = DeviceUtils.getRandomChars(32)

        assertEquals(32, first.length)
        assertTrue(first.all { it.isLetterOrDigit() })
        assertNotEquals(first, second)
    }

    @Test
    fun samsungPeersUseTheCompatibilityP2pBand() {
        assertTrue(DeviceUtils.requiresTwoGhzP2pCompatibility(70))
        assertTrue(DeviceUtils.requiresTwoGhzP2pCompatibility(75))
        assertFalse(DeviceUtils.requiresTwoGhzP2pCompatibility(76))
        assertFalse(DeviceUtils.requiresTwoGhzP2pCompatibility(null))
    }

    @Test
    fun selectableBrandsStayInRequiredOrder() {
        assertEquals(
            listOf(
                -1 to "Auto",
                130 to "Pixel",
                70 to "Samsung",
                30 to "Xiaomi",
                20 to "vivo",
                10 to "OPPO",
                42 to "OnePlus",
                140 to "Honor",
                100 to "Lenovo",
                110 to "Motorola",
                80 to "ZTE",
                60 to "Nubia",
                50 to "Meizu",
                161 to "ASUS",
                160 to "ROG",
            ),
            DeviceUtils.getBrandList(),
        )
    }

    @Test
    fun everySelectableBrandHasDedicatedIcon() {
        DeviceUtils.getBrandList()
            .filter { (id, _) -> id >= 0 }
            .forEach { (id, name) ->
                assertNotEquals(
                    "$name uses the default icon",
                    me.pipi.easyshare.R.drawable.device_default,
                    DeviceUtils.deviceIconById(id),
                )
            }
    }

    @Test
    fun extendedAllianceBrandRangesAreRecognized() {
        assertEquals("Xiaomi", DeviceUtils.deviceNameById(31))
        assertEquals("Nubia", DeviceUtils.deviceNameById(60))
        assertEquals("RedMagic", DeviceUtils.deviceNameById(66))
        assertEquals("ZTE", DeviceUtils.deviceNameById(89))
        assertEquals("Motorola", DeviceUtils.deviceNameById(110))
        assertEquals("Motorola", DeviceUtils.deviceNameById(119))
        assertEquals("Honor", DeviceUtils.deviceNameById(140))
        assertEquals("Honor", DeviceUtils.deviceNameById(149))
        assertEquals("ROG", DeviceUtils.deviceNameById(160))
        assertEquals("ASUS", DeviceUtils.deviceNameById(161))
        assertEquals("ASUS", DeviceUtils.deviceNameById(169))
    }

    @Test
    fun signedBleByteIsNormalizedToUnsignedBrandId() {
        assertEquals(170, DeviceUtils.bleByteToBrandId((-86).toByte()))
        assertEquals("Unknown", DeviceUtils.deviceNameById(255))
    }

    @Test
    fun pixelNineProPropertiesAreDetectedAsGooglePixel() {
        val brandId = DeviceUtils.detectBrandId(
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 9 Pro",
        )

        assertEquals(130, brandId)
        assertEquals("Pixel", DeviceUtils.knownDeviceNameById(brandId))
        assertNotEquals(
            me.pipi.easyshare.R.drawable.device_default,
            DeviceUtils.deviceIconById(brandId),
        )
    }
}
