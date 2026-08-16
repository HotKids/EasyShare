package me.pipi.easyshare.services

import android.util.Log
import me.pipi.easyshare.IMacAddressService
import me.pipi.easyshare.utils.TAG
import java.net.NetworkInterface
import kotlin.system.exitProcess

class MacAddressService: IMacAddressService.Stub() {
    override fun destroy() {
        exit()
    }

    override fun exit() {
        Log.i(TAG, "Exit")
        exitProcess(0)
    }

    override fun getP2pMacAddress(): String? {
        return getMacAddressByName("p2p0")
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun getMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }
}
