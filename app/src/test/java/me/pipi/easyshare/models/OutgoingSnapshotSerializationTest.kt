package me.pipi.easyshare.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.pipi.easyshare.utils.LiveStage
import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingSnapshotSerializationTest {
    @Test
    fun pendingCancellationSurvivesSnapshotsAndDefaultsOffForOlderSnapshots() {
        val state = TransferUiState(1, "peer", TransferUiStatus.SENDING, 40,
            LiveStage.TRANSFERRING, cancelRequested = true)
        assertEquals(state, Json.decodeFromString<TransferUiState>(Json.encodeToString(state)))
        assertEquals(false, Json.decodeFromString<TransferUiState>(
            """{"taskId":1,"deviceId":"peer","status":"SENDING"}""").cancelRequested)
    }

    @Test
    fun missingNameProvenanceSurvivesThePresentationSnapshot() {
        val presentation = OutgoingTransferPresentation(1, "peer", "Device", 130,
            "shared_file", "application/octet-stream", 1, 50, false, nameIsFallback = true)
        assertEquals(presentation, Json.decodeFromString<OutgoingTransferPresentation>(
            Json.encodeToString(presentation)))
        assertEquals(false, Json.decodeFromString<OutgoingTransferPresentation>(
            """{"taskId":1,"deviceId":"peer","deviceName":"Device","brandId":130,"fileName":"shared_file","mimeType":"application/octet-stream","fileCount":1,"totalSize":50,"isText":false}""").nameIsFallback)
    }
    @Test
    fun outgoingMetadataAndEveryTerminalOutcomeRoundTripWithoutFileUris() {
        val presentation = OutgoingTransferPresentation(1, "peer", "Device", 130,
            "file.jpg", "image/jpeg", 1, 50, false)
        assertEquals(presentation, Json.decodeFromString<OutgoingTransferPresentation>(
            Json.encodeToString(presentation)))
        for (status in listOf(TransferUiStatus.SUCCESS, TransferUiStatus.PARTIAL,
            TransferUiStatus.FAILED, TransferUiStatus.CANCELED, TransferUiStatus.REJECTED,
            TransferUiStatus.TIMEOUT)) {
            val state = TransferUiState(1, "peer", status, 100, LiveStage.COMPLETED, "result")
            assertEquals(state, Json.decodeFromString<TransferUiState>(Json.encodeToString(state)))
        }
    }
}
