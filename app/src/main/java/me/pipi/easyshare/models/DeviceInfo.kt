package me.pipi.easyshare.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val state: Int,
    val key: String?,
    val mac: String,
    @SerialName("catShare")
    val easyShare: Int? = null,
    @SerialName("catShareCrypto")
    val cryptoVersion: Int? = null,
)
