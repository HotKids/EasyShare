package me.pipi.easyshare.utils

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import me.pipi.easyshare.models.WebSocketMessage

suspend fun WebSocketSession.sendStatus(id: Int, taskId: String, type: Int, reason: String) {
    val st = WebSocketMessage.makeStatus(id, taskId, type, reason)
    send(Frame.Text(st.toText()))
    flush()
}

suspend fun WebSocketSession.sendStatusIgnoreException(id: Int, taskId: String, type: Int, reason: String) {
    try {
        sendStatus(id, taskId, type, reason)
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}