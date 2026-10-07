package me.pipi.easyshare.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingSnapshotSerializationTest {
    @Before
    @After
    fun clearRequests() = IncomingTransferUiCoordinator.clearAll()

    @Test
    fun capturedDirectorySurvivesAcceptanceProgressAndResultSnapshot() {
        val directory = "content://documents/tree/download-A"
        val request = Json.decodeFromString<IncomingTransferUiState>(
            """{"taskId":7,"senderName":"peer","brandId":null,"fileName":"files.zip", "fileCount":2,"totalSize":50,"status":"REQUESTED","receiveDirectoryUri":"$directory"}""",
        )
        assertTrue(IncomingTransferUiCoordinator.beginRequest(request, 5_000L, "timeout"))
        assertTrue(IncomingTransferUiCoordinator.decide(7, IncomingRequestDecision.ACCEPTED, 100L))
        IncomingTransferUiCoordinator.markReceiving(7, 37)
        assertDirectory(directory, requireNotNull(IncomingTransferUiCoordinator.get(7)))
        IncomingTransferUiCoordinator.complete(7, emptyList(), partial = true, receivedFilesToken = "a".repeat(64))
        val result = requireNotNull(IncomingTransferUiCoordinator.get(7))
        assertEquals(IncomingTransferUiStatus.PARTIAL, result.status)
        assertDirectory(directory, Json.decodeFromString(Json.encodeToString(result)))
    }

    @Test
    fun olderSnapshotWithoutDirectoryStillDecodes() {
        val request = Json.decodeFromString<IncomingTransferUiState>(
            """{"taskId":7,"senderName":"peer","brandId":null,"fileName":"files.zip","fileCount":2,"totalSize":50,"status":"REQUESTED"}""",
        )
        assertNull(request.receiveDirectoryUri)
    }

    private fun assertDirectory(expected: String, state: IncomingTransferUiState) {
        assertEquals(expected, Json.parseToJsonElement(Json.encodeToString(state))
            .jsonObject["receiveDirectoryUri"]?.jsonPrimitive?.content)
    }
}
