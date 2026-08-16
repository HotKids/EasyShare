package me.pipi.easyshare.utils

import android.util.Log
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import me.pipi.easyshare.models.WebSocketMessage

suspend fun WebSocketSession.sendStatus(id: Int, taskId: String, type: Int, reason: String) {
    val st = WebSocketMessage.makeStatus(id, taskId, type, reason)
    send(Frame.Text(st.toText()))
    flush()
}

suspend fun WebSocketSession.sendStatusIgnoreException(id: Int, taskId: String, type: Int, reason: String) {
    try {
        sendStatus(id, taskId, type, reason)
    } catch (error: CancellationException) {
        throw error
    } catch (e: Throwable) {
        Log.w("WsUtils", "Failed to send transfer status", e)
    }
}
