package ru.screenmirror.receiver

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class TvSignalingServer(
    port: Int,
    private val token: String,
    private val onMessage: (String) -> Unit,
    private val onClientState: (Boolean) -> Unit
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    @Volatile private var client: WebSocket? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val supplied = handshake.resourceDescriptor.substringAfter("token=", "").substringBefore("&")
        if (supplied != token) {
            conn.close(1008, "Неверный код сопряжения")
            return
        }
        client?.close(1000, "Новое подключение")
        client = conn
        onClientState(true)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (conn === client) onMessage.invoke(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (conn === client) {
            client = null
            onClientState(false)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}
    override fun onStart() {}

    fun sendToPhone(message: String) {
        try { client?.send(message) } catch (_: Exception) {}
    }

    override fun stop() {
        try { super.stop(1000) } catch (_: Exception) {}
    }
}
