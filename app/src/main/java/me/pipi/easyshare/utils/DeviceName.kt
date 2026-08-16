package me.pipi.easyshare.utils

import android.os.Build

object DeviceName {

    fun get(): String {
        return getMarketName()
            ?: getBluetoothDefaultName()
            ?: getCodename()
            ?: getBrandModel()
            ?: Build.MODEL
    }

    private fun getMarketName(): String? =
        getProp("ro.product.marketname")
            ?: getProp("ro.product.odm.marketname")
            ?: getProp("ro.product.vendor.marketname")
            ?: getProp("ro.config.marketing_name")
            ?: getProp("ro.vendor.oplus.market.name")
            ?: getProp("ro.vendor.vivo.market.name")
            ?: getProp("ro.vivo.market.name")
            ?: getProp("ro.oppo.market.enname")
            ?: getProp("ro.vendor.oplus.market.enname")

    private fun getBluetoothDefaultName(): String? =
        getProp("bluetooth.device.default_name")

    private fun getBrandModel(): String? {
        val brand = Build.BRAND.takeUnless { it.isBlank() } ?: return null
        val model = Build.MODEL.takeUnless { it.isBlank() } ?: return null
        return if (model.startsWith(brand, ignoreCase = true)) model
        else "$brand $model"
    }

    private fun getCodename(): String? =
        getProp("ro.product.device")

    private fun getProp(propName: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", propName))
            process.inputStream.bufferedReader().readLine()
                ?.takeUnless { it.isBlank() }
        } catch (e: Exception) {
            null
        }
    }
}