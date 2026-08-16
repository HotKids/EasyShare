package me.pipi.easyshare.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class P2pInfo(
    val id: String?,
    val ssid: String,
    val psk: String,
    val mac: String,
    val port: Int,
    val key: String? = null,
    @SerialName("catShare")
    val easyShare: Int? = null,
    @SerialName("catShareCrypto")
    val cryptoVersion: Int? = null,
    @SerialName("catShareToken")
    val authToken: String? = null,
    @SerialName("catShareCert")
    val certificateSha256: String? = null,
) : Parcelable
