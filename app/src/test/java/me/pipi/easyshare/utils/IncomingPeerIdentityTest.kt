package me.pipi.easyshare.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingPeerIdentityTest {
    private val now = 50_000L
    private val oppo = AdvertisedPeerIdentity("d1:22:33:44:55:66", "ab12", "OPPO Find N6", 10, now)
    private val other = AdvertisedPeerIdentity("d2:22:33:44:55:66", "cd34", "Other device", 70, now)

    private fun resolve(
        address: String? = oppo.address,
        senderId: String? = null,
        brandId: Int? = null,
        brand: String? = null,
        peers: List<AdvertisedPeerIdentity> = listOf(oppo, other),
        atMillis: Long = now,
        joinedOwnerName: String? = null,
    ) = IncomingPeerIdentity.resolveAdvertisements(
        "Sender nickname", brandId, brand, address, senderId, peers, atMillis, joinedOwnerName,
    )

    @Test
    fun exactGattPeerProvidesAdvertisedDeviceNameAndMissingBrand() {
        assertEquals(ResolvedPeerIdentity("OPPO Find N6", 10, PeerIdentitySource.GATT_ADDRESS), resolve(address = oppo.address.uppercase()))
    }

    @Test
    fun exactUniqueSenderIdCanMatchWhenGattAddressWasNotAdvertised() {
        assertEquals(ResolvedPeerIdentity("OPPO Find N6", 10, PeerIdentitySource.SENDER_ID), resolve(address = null, senderId = "AB12"))
        assertEquals(ResolvedPeerIdentity("OPPO Find N6", 10, PeerIdentitySource.SENDER_ID), resolve(address = "d3:22:33:44:55:66", senderId = "ab12"))
    }

    @Test
    fun nearbyDeviceCannotFillMissingIdentity() {
        assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(address = "d3:22:33:44:55:66"))
    }

    @Test
    fun senderIdCollisionDoesNotChooseOneOfTwoDevices() {
        assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(
            address = null, senderId = oppo.senderId, peers = listOf(oppo, other.copy(senderId = oppo.senderId)),
        ))
    }

    @Test
    fun exactAddressWinsOverAnotherDevicesSenderId() {
        assertEquals(ResolvedPeerIdentity("OPPO Find N6", 10, PeerIdentitySource.GATT_ADDRESS), resolve(senderId = other.senderId))
    }

    @Test
    fun conflictingAddressClaimsDoNotFallBackToSenderId() {
        assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(
            senderId = oppo.senderId,
            peers = listOf(oppo, other.copy(address = oppo.address)),
        ))
    }

    @Test
    fun truncatedAdvertisementPreservesRequestNameWhileFillingBrand() {
        listOf("Long device...", "Long device…").forEach { name ->
            assertEquals(ResolvedPeerIdentity("Sender nickname", 10, PeerIdentitySource.GATT_ADDRESS), resolve(
                peers = listOf(oppo.copy(name = name, nameIsTruncated = true)),
            ))
        }
        listOf("Long device...", "Long device\t").forEach { name ->
            val data = advertisement() + (UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb") to nameData(name))
            assertEquals(true, IncomingPeerIdentity.parseAdvertisement(oppo.address, data, now)?.nameIsTruncated)
        }
    }

    @Test
    fun expiredAndFutureObservationsCannotIdentifyPeer() {
        assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(atMillis = now + IncomingPeerIdentity.MAX_AGE_MILLIS + 1))
        assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(atMillis = now - 1))
    }

    @Test
    fun explicitRequestBrandIsPreferredAndUnknownBrandIsNotInvented() {
        assertEquals(42, resolve(brandId = 70, brand = "OnePlus").brandId)
        assertEquals(70, resolve(brandId = 70).brandId)
        assertNull(resolve(brand = "Unknown").brandId)
        assertNull(resolve(brandId = 255).brandId)
    }

    @Test
    fun verifiedJoinedOwnerProvidesNameAndRegistryBrandWithoutBleMatch() {
        listOf("OPPO Find N6" to 10, "Xiaomi 15" to 30, "Google Pixel 10" to 130).forEach { (name, brandId) ->
            assertEquals(ResolvedPeerIdentity(name, brandId, PeerIdentitySource.JOINED_GROUP), resolve(
                peers = emptyList(), joinedOwnerName = name,
            ))
        }
    }

    @Test
    fun joinedOwnerCustomNamesRemainNamesWithoutInventedBrandOrModel() {
        listOf("Joey's phone", "Find N6", "My OPPO", "OPPOFan phone").forEach { name ->
            assertEquals(ResolvedPeerIdentity(name, null, PeerIdentitySource.JOINED_GROUP), resolve(
                peers = emptyList(), joinedOwnerName = name,
            ))
        }
    }

    @Test
    fun blankAndGenericJoinedOwnerCannotReplaceRequestName() {
        listOf(null, "", "  ", "Android", " android ").forEach { name ->
            assertEquals(ResolvedPeerIdentity("Sender nickname", null), resolve(
                peers = emptyList(), joinedOwnerName = name,
            ))
        }
    }

    @Test
    fun fullMatchedBleNameWinsOverVerifiedJoinedOwnerName() {
        assertEquals(ResolvedPeerIdentity(oppo.name, 10, PeerIdentitySource.GATT_ADDRESS), resolve(
            joinedOwnerName = "Different owner name",
        ))
    }

    @Test
    fun joinedOwnerFillsTruncatedBleNameAndPreservesAdvertisedBrand() {
        assertEquals(ResolvedPeerIdentity("Full device name", 10, PeerIdentitySource.JOINED_GROUP), resolve(
            peers = listOf(oppo.copy(name = "Long device…", nameIsTruncated = true)),
            joinedOwnerName = "Full device name",
        ))
    }

    @Test
    fun explicitRequestBrandPreservesRequestNameDespiteBothAlternateNames() {
        listOf(70 to null, null to "Unknown", 255 to null).forEach { (brandId, brand) ->
            assertEquals(ResolvedPeerIdentity(
                "Sender nickname", DeviceUtils.resolvePeerBrandId(brandId, brand), PeerIdentitySource.GATT_ADDRESS,
            ), resolve(brandId = brandId, brand = brand, joinedOwnerName = "Xiaomi 15"))
        }
    }

    @Test
    fun joinedOwnerDoesNotDependOnAnActiveBleCollection() {
        val collectionId = IncomingPeerIdentity.startCollection()
        IncomingPeerIdentity.stopCollection(collectionId)
        assertEquals(ResolvedPeerIdentity("Pixel 10", 130, PeerIdentitySource.JOINED_GROUP), IncomingPeerIdentity.resolve(
            "Sender nickname", null, null, null, null, now, joinedOwnerName = "Pixel 10",
        ))
    }

    @Test
    fun mismatchedJoinedGroupCannotProvideOwnerName() {
        assertNull(IncomingPeerIdentity.verifiedJoinedOwnerName("DIRECT-other", "DIRECT-expected", false, "OPPO Find N6"))
    }

    @Test
    fun blankJoinedGroupNamesCannotProvideOwnerName() {
        listOf(null, "", " ").forEach { groupName ->
            assertNull(IncomingPeerIdentity.verifiedJoinedOwnerName(groupName, "DIRECT-expected", false, "OPPO Find N6"))
            assertNull(IncomingPeerIdentity.verifiedJoinedOwnerName("DIRECT-expected", groupName, false, "OPPO Find N6"))
        }
    }

    @Test
    fun localGroupOwnerCannotBeUsedAsRemoteSenderName() {
        assertNull(IncomingPeerIdentity.verifiedJoinedOwnerName("DIRECT-expected", "DIRECT-expected", true, "Pixel 10"))
    }

    @Test
    fun exactJoinedRemoteGroupProvidesItsNonGenericOwnerName() {
        assertEquals("OPPO Find N6", IncomingPeerIdentity.verifiedJoinedOwnerName(
            "DIRECT-expected", "DIRECT-expected", false, " OPPO Find N6 ",
        ))
        listOf(null, "", " ", "Android", " android ").forEach { name ->
            assertNull(IncomingPeerIdentity.verifiedJoinedOwnerName("DIRECT-expected", "DIRECT-expected", false, name))
        }
    }

    @Test
    fun protocolAdvertisementParsesNameAndBrandWithoutRejectingRandomBleAddress() {
        val peer = IncomingPeerIdentity.parseAdvertisement(oppo.address.uppercase(), advertisement(), now)
        assertEquals(oppo, peer)
        assertNull(IncomingPeerIdentity.parseAdvertisement("02:00:00:00:00:00", advertisement(), now))
    }

    @Test
    fun unrelatedServiceDataAndConflictingNamesAreRejected() {
        assertNull(IncomingPeerIdentity.parseAdvertisement(oppo.address, mapOf(UUID.randomUUID() to nameData()), now))
        assertNull(IncomingPeerIdentity.parseAdvertisement(oppo.address, advertisement() + (
            UUID.fromString("00001234-0000-1000-8000-00805f9b34fb") to nameData("Different device")
        ), now))
    }

    @Test
    fun receiverCollectionIsBoundedByItsLifetimeAndObservationAge() {
        val collectionId = IncomingPeerIdentity.startCollection()
        try {
            IncomingPeerIdentity.record(collectionId, oppo.address, advertisement(), now)
            assertEquals(10, IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null, now).brandId)
            assertNull(IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null,
                now + IncomingPeerIdentity.MAX_AGE_MILLIS + 1).brandId)
            IncomingPeerIdentity.stopCollection(collectionId)
            assertNull(IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null, now).brandId)
            IncomingPeerIdentity.record(collectionId, oppo.address, advertisement(), now)
            assertNull(IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null, now).brandId)
        } finally {
            IncomingPeerIdentity.stopCollection(collectionId)
        }
    }

    @Test
    fun oldCollectionCannotWriteIntoOrStopItsReplacement() {
        val oldId = IncomingPeerIdentity.startCollection()
        IncomingPeerIdentity.stopCollection(oldId)
        val currentId = IncomingPeerIdentity.startCollection()
        try {
            IncomingPeerIdentity.record(oldId, oppo.address, advertisement(), now)
            assertNull(IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null, now).brandId)
            IncomingPeerIdentity.record(currentId, oppo.address, advertisement(), now)
            IncomingPeerIdentity.stopCollection(oldId)
            assertEquals(10, IncomingPeerIdentity.resolve("Nickname", null, null, oppo.address, null, now).brandId)
        } finally {
            IncomingPeerIdentity.stopCollection(currentId)
        }
    }

    @Test
    fun receiverCollectionEvictsTheOldestPeerWhenFull() {
        val collectionId = IncomingPeerIdentity.startCollection()
        try {
            repeat(65) { index ->
                IncomingPeerIdentity.record(collectionId, "d1:22:33:44:55:%02x".format(index), advertisement(), now)
            }
            assertNull(IncomingPeerIdentity.resolve("Nickname", null, null, "d1:22:33:44:55:00", null, now).brandId)
            assertEquals(10, IncomingPeerIdentity.resolve("Nickname", null, null, "d1:22:33:44:55:40", null, now).brandId)
        } finally {
            IncomingPeerIdentity.stopCollection(collectionId)
        }
    }

    private fun nameData(name: String = oppo.name) = ByteArray(27).apply {
        this[8] = 0xab.toByte()
        this[9] = 0x12
        name.toByteArray().copyInto(this, destinationOffset = 10)
    }

    private fun advertisement() = mapOf(
        UUID.fromString("0000010a-0000-1000-8000-00805f9b34fb") to ByteArray(6),
        UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb") to nameData(),
    )
}
