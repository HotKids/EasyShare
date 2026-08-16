package me.pipi.easyshare.utils

import android.os.Build
import androidx.annotation.DrawableRes
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import java.util.Random

data class BrandConfig(
    val idRange: IntRange,
    val name: String,
    val searchKeys: List<String> = emptyList(),
    val canonicalId: Int = idRange.first,
    val isPrimary: Boolean = true,
    @param:DrawableRes val iconRes: Int = R.drawable.device_default
)

object DeviceUtils {
    // Single Source of Truth for Brand Mappings
    private val BRAND_REGISTRY = listOf(
        BrandConfig(114514..114514, "Easy Share"),
        BrandConfig(11..11, "realme", listOf("realme"), iconRes = R.drawable.device_realme),
        BrandConfig(10..19, "OPPO", listOf("oppo"), iconRes = R.drawable.device_oppo),
        BrandConfig(20..29, "vivo", listOf("vivo"), iconRes = R.drawable.device_vivo),
        BrandConfig(32..32, "Black Shark", listOf("blackshark"), iconRes = R.drawable.device_heisai),
        BrandConfig(30..39, "Xiaomi", listOf("xiaomi", "redmi"), iconRes = R.drawable.device_xiaomi),
        BrandConfig(41..45, "OnePlus", listOf("oneplus"), canonicalId = 42, iconRes = R.drawable.device_oneplus),
        BrandConfig(50..59, "Meizu", listOf("meizu"), iconRes = R.drawable.device_meizu),
        BrandConfig(66..66, "RedMagic", listOf("redmagic", "red magic"), iconRes = R.drawable.device_redmagic),
        BrandConfig(60..69, "Nubia", listOf("nubia"), iconRes = R.drawable.device_nubia),
        BrandConfig(70..75, "Samsung", listOf("samsung"), iconRes = R.drawable.device_samsung),
        BrandConfig(80..89, "ZTE", listOf("zte"), iconRes = R.drawable.device_zte),
        BrandConfig(90..95, "Smartisan", listOf("smartisan", "jianguo")),
        BrandConfig(100..109, "Lenovo", listOf("lenovo"), iconRes = R.drawable.device_lenovo),
        BrandConfig(110..119, "Motorola", listOf("motorola", "moto"), iconRes = R.drawable.device_motorola),
        BrandConfig(120..129, "NIO", listOf("nio")),
        BrandConfig(
            130..139,
            "Pixel",
            listOf("google", "pixel"),
            iconRes = R.drawable.device_google,
        ),
        BrandConfig(140..149, "Honor", listOf("honor"), iconRes = R.drawable.device_honor),
        BrandConfig(160..160, "ROG", listOf("rog"), iconRes = R.drawable.device_rog),
        BrandConfig(161..169, "ASUS", listOf("asus"), iconRes = R.drawable.device_asus),
        BrandConfig(170..179, "Hisense", listOf("hisense")),
        //OPPO车机？ BrandConfig(200..200, "T"),
        /* ColorOS系的果子互传ID
        BrandConfig(800..800, "iPhone", listOf("iphone"), false),
        BrandConfig(801..801, "iPad", listOf("ipad"), false),
        BrandConfig(802..802, "Mac", listOf("macintosh", "macbook"), false)
         */
    )

    fun getLocalBrandId(): Int {
        val settings = AppSettings(MyApplication.getInstance())
        
        // Manual Selection Priority
        if (settings.brandId != -1) {
            return settings.brandId
        }
        
        // Automatic Detection (Single Pass through Registry)
        return detectBrandId(Build.BRAND, Build.MANUFACTURER, Build.MODEL)
    }

    fun detectBrandId(brand: String, manufacturer: String, model: String): Int {
        val normalizedBrand = brand.lowercase()
        val normalizedManufacturer = manufacturer.lowercase()
        val normalizedModel = model.lowercase()

        return BRAND_REGISTRY.firstOrNull { config ->
            config.searchKeys.any { key -> 
                normalizedBrand.contains(key) ||
                    normalizedManufacturer.contains(key) ||
                    normalizedModel.contains(key)
            }
        }?.canonicalId ?: 0
    }

    fun knownDeviceNameById(id: Int): String? =
        BRAND_REGISTRY.firstOrNull { id in it.idRange }?.name

    fun deviceNameById(id: Int): String = knownDeviceNameById(id) ?: "Unknown"

    @DrawableRes
    fun deviceIconById(id: Int?): Int {
        return BRAND_REGISTRY.firstOrNull { id != null && id in it.idRange }?.iconRes
            ?: R.drawable.device_default
    }

    fun getBrandList(): List<Pair<Int, String>> {
        return listOf(
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
        )
    }

    fun bleByteToBrandId(byte: Byte): Int {
        val id = byte.toInt() and 0xFF
        if (id == 114) return 114514
        if (id == 32) {
            // Collision: Black Shark (32) and iPhone (800)
            // Return 32 for now, as it's the more common case for OShare
            return 32
        }
        return id
    }

    fun getRandomChars(len: Int): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray()
        val sb = StringBuilder()
        val rand = Random()
        repeat(len) {
            sb.append(alphabet[rand.nextInt(alphabet.size)])
        }
        return sb.toString()
    }
}
