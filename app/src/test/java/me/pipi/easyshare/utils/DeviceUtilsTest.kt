package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceUtilsTest {
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
