package me.pipi.easyshare.utils

import java.util.UUID

data class AdvertisedPeerIdentity(
    val address: String,
    val senderId: String,
    val name: String,
    val brandId: Int?,
    val observedAtMillis: Long,
    val nameIsTruncated: Boolean = false,
)

enum class PeerIdentitySource { REQUEST_ONLY, GATT_ADDRESS, SENDER_ID, JOINED_GROUP }

data class ResolvedPeerIdentity(
    val name: String,
    val brandId: Int?,
    val source: PeerIdentitySource = PeerIdentitySource.REQUEST_ONLY,
)

object IncomingPeerIdentity {
    const val EXTRA_GATT_PEER_ADDRESS = "gatt_peer_address"
    const val MAX_AGE_MILLIS = 30_000L
    private const val MAX_PEERS = 64
    private val advertisementUuid = Regex("0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb")
    private val addressPattern = Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val peers = LinkedHashMap<String, AdvertisedPeerIdentity>()
    private var collecting = false
    private var generation = 0L

    @Synchronized
    fun startCollection(): Long {
        peers.clear()
        collecting = true
        return ++generation
    }

    @Synchronized
    fun stopCollection(collectionId: Long) {
        if (collectionId != generation) return
        collecting = false
        peers.clear()
    }

    @Synchronized
    fun record(collectionId: Long, address: String, serviceData: Map<UUID, ByteArray>, nowMillis: Long) {
        if (!collecting || collectionId != generation) return
        val peer = parseAdvertisement(address, serviceData, nowMillis) ?: return
        peers.entries.removeAll { nowMillis - it.value.observedAtMillis !in 0..MAX_AGE_MILLIS }
        peers.remove(peer.address)
        peers[peer.address] = peer
        if (peers.size > MAX_PEERS) peers.remove(peers.keys.first())
    }

    @Synchronized
    fun resolve(
        requestName: String,
        requestBrandId: Int?,
        requestBrand: String?,
        peerAddress: String?,
        senderId: String?,
        nowMillis: Long,
        joinedOwnerName: String? = null,
    ): ResolvedPeerIdentity {
        peers.entries.removeAll { nowMillis - it.value.observedAtMillis !in 0..MAX_AGE_MILLIS }
        return resolveAdvertisements(
            requestName, requestBrandId, requestBrand, peerAddress, senderId,
            if (collecting) peers.values.toList() else emptyList(), nowMillis, joinedOwnerName,
        )
    }

    fun verifiedJoinedOwnerName(
        actualGroupName: String?,
        expectedGroupName: String?,
        groupIsOwner: Boolean,
        ownerName: String?,
    ): String? {
        if (groupIsOwner || actualGroupName.isNullOrBlank() || expectedGroupName.isNullOrBlank() ||
            actualGroupName != expectedGroupName
        ) return null
        return usableJoinedOwnerName(ownerName)
    }

    fun parseAdvertisement(
        address: String,
        serviceData: Map<UUID, ByteArray>,
        nowMillis: Long,
    ): AdvertisedPeerIdentity? {
        val normalizedAddress = normalizeAddress(address) ?: return null
        val protocolData = serviceData.filterKeys { advertisementUuid.matches(it.toString()) }
        val names = protocolData.values.filter { it.size == 27 }.mapNotNull { data ->
            val name = BleUtils.deviceDisplayNameFromAdvertisement(data) ?: return@mapNotNull null
            val id = BleUtils.senderIdFromAdvertisement(data) ?: return@mapNotNull null
            // Native OShare uses "..."; Easy Share displays its truncation marker as "…".
            Triple(id, name, name.endsWith("...") || name.endsWith("…"))
        }.distinct()
        val (id, name, truncated) = names.singleOrNull() ?: return null
        val brandId = protocolData.filterValues { it.size == 6 }.keys.map { uuid ->
            DeviceUtils.bleByteToBrandId((uuid.mostSignificantBits ushr 32).toByte())
        }.distinct().singleOrNull()?.takeIf { DeviceUtils.knownDeviceNameById(it) != null }
        return AdvertisedPeerIdentity(normalizedAddress, id, name, brandId, nowMillis, truncated)
    }

    // The caller must verify the joined remote group before supplying its public owner name.
    fun resolveAdvertisements(
        requestName: String,
        requestBrandId: Int?,
        requestBrand: String?,
        peerAddress: String?,
        senderId: String?,
        advertisements: List<AdvertisedPeerIdentity>,
        nowMillis: Long,
        joinedOwnerName: String? = null,
    ): ResolvedPeerIdentity {
        val fresh = advertisements.filter {
            nowMillis - it.observedAtMillis in 0..MAX_AGE_MILLIS &&
                normalizeAddress(it.address) != null && it.name.isNotBlank()
        }
        val address = normalizeAddress(peerAddress)
        val addressMatches = fresh.filter { address != null && normalizeAddress(it.address) == address }
        val peer = if (addressMatches.isNotEmpty()) {
            addressMatches.singleOrNull()
        } else {
            val id = senderId?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{4}")) }
            fresh.filter { id != null && it.senderId.equals(id, ignoreCase = true) }.singleOrNull()
        }
        val suppliedBrand = requestBrand?.trim()?.takeIf { it.isNotEmpty() }
        val suppliedBrandId = requestBrandId?.takeIf { it >= 0 }
        val hasSuppliedBrand = suppliedBrand != null || suppliedBrandId != null
        val fullPeerName = peer?.takeUnless { it.nameIsTruncated }?.name
        val ownerName = usableJoinedOwnerName(joinedOwnerName)
        val usesJoinedOwner = !hasSuppliedBrand && fullPeerName == null && ownerName != null
        val brandId = if (hasSuppliedBrand) {
            DeviceUtils.resolvePeerBrandId(suppliedBrandId, suppliedBrand)
        } else {
            peer?.brandId?.takeIf { DeviceUtils.knownDeviceNameById(it) != null }
                ?: ownerName?.takeIf { usesJoinedOwner }?.let(DeviceUtils::brandIdFromDeviceNamePrefix)
        }
        return ResolvedPeerIdentity(
            BleUtils.normalizeDeviceName(if (hasSuppliedBrand) requestName else fullPeerName ?: ownerName ?: requestName),
            brandId,
            when {
                usesJoinedOwner -> PeerIdentitySource.JOINED_GROUP
                peer == null -> PeerIdentitySource.REQUEST_ONLY
                addressMatches.isNotEmpty() -> PeerIdentitySource.GATT_ADDRESS
                else -> PeerIdentitySource.SENDER_ID
            },
        )
    }

    private fun usableJoinedOwnerName(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("Android", ignoreCase = true)
    }

    private fun normalizeAddress(value: String?): String? = value?.lowercase()?.takeIf {
        addressPattern.matches(it) && it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00"
    }
}
