package com.app.hackaton2026.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class CameraServer(
    port: Int,
    private val listener: CommandListener
) : WebSocketServer(InetSocketAddress(port)) {

    interface CommandListener {
        fun onClientConnected(count: Int)
        fun onClientDisconnected(count: Int)
        fun onCommand(command: String, payload: JSONObject)
    }

    companion object {
        private const val TAG = "CameraServer"
    }

    init {
        isReuseAddr = true
    }

    override fun onStart() {
        Log.i(TAG, "CameraServer started on port $port")
        connectionLostTimeout = 10
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")
        listener.onClientConnected(connections.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Client disconnected: ${conn.remoteSocketAddress}, code=$code, reason=$reason")
        listener.onClientDisconnected(connections.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val command = json.optString("command", "")
            listener.onCommand(command, json)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed command message: $message", e)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        // Binary commands from client not expected, but handled if needed
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error: ${ex.message}", ex)
    }

    private var frameCount = 0L

    fun broadcastVideoFrame(jpegBytes: ByteArray) {
        if (connections.isEmpty()) return
        frameCount++
        if (frameCount % 60 == 0L) {
            Log.i(TAG, "Streaming video: frame #$frameCount (${jpegBytes.size} bytes) to ${connections.size} clients")
        }
        val buffer = ByteBuffer.wrap(jpegBytes)
        for (client in connections) {
            if (client.isOpen) {
                try {
                    client.send(buffer.duplicate())
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending frame", e)
                }
            }
        }
    }

    fun broadcastJson(json: JSONObject) {
        val payload = json.toString()
        for (client in connections) {
            if (client.isOpen) {
                try {
                    client.send(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending JSON", e)
                }
            }
        }
    }

    val connectedClientsCount: Int
        get() = connections.size
}
