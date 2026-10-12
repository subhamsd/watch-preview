package com.wristview.wear.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

class WearCameraClient(
    serverUri: URI,
    private val listener: CameraClientListener
) : WebSocketClient(serverUri) {

    interface CameraClientListener {
        fun onConnected()
        fun onDisconnected()
        fun onNewFrame(bitmap: Bitmap)
        fun onTelemetry(battery: Int, storageMb: Long)
        fun onState(currentZoom: Float, maxZoom: Float)
        fun onCameraStandbyChanged(isSleeping: Boolean)
        fun onPhotoCaptured(filePath: String, thumbnail: Bitmap?)
        fun onTorchChanged(isTorchOn: Boolean)
        fun onCameraSwitched(isFront: Boolean)
        fun onExposureChanged(evIndex: Int)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "WearCameraClient"
    }

    override fun onOpen(handshakedata: ServerHandshake) {
        Log.i(TAG, "Connected to headless camera server!")
        listener.onConnected()
        sendCommand("GET_STATE", JSONObject())
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Connection closed: code=$code, reason=$reason")
        listener.onDisconnected()
    }

    private var rxFrameCount = 0L

    override fun onMessage(bytes: ByteBuffer) {
        val byteArray = ByteArray(bytes.remaining())
        bytes.get(byteArray)
        val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        if (bitmap != null) {
            rxFrameCount++
            if (rxFrameCount % 60 == 0L) {
                Log.i(TAG, "Watch received frame #$rxFrameCount (${bitmap.width}x${bitmap.height})")
            }
            listener.onNewFrame(bitmap)
        } else {
            Log.e(TAG, "Failed to decode frame bitmap! bytes=${byteArray.size}")
        }
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "TELEMETRY" -> {
                    val battery = json.optInt("battery", 100)
                    val storageMb = json.optLong("storageMb", 0)
                    listener.onTelemetry(battery, storageMb)
                }
                "STATE" -> {
                    val currentZoom = json.optDouble("currentZoom", 1.0).toFloat()
                    val maxZoom = json.optDouble("maxZoom", 1.0).toFloat()
                    val battery = json.optInt("battery", 100)
                    val storageMb = json.optLong("storageMb", 0)
                    val isSleeping = json.optBoolean("isSleeping", false)
                    listener.onState(currentZoom, maxZoom)
                    listener.onTelemetry(battery, storageMb)
                    listener.onCameraStandbyChanged(isSleeping)
                }
                "CAMERA_STANDBY" -> {
                    val isSleeping = json.optBoolean("isSleeping", true)
                    listener.onCameraStandbyChanged(isSleeping)
                }
                "PHOTO_CAPTURED" -> {
                    val filePath = json.optString("filePath", "")
                    val thumbBase64 = json.optString("thumbnail", "")
                    val thumbBitmap = if (thumbBase64.isNotEmpty()) {
                        val thumbBytes = Base64.decode(thumbBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                    } else null
                    listener.onPhotoCaptured(filePath, thumbBitmap)
                }
                "TORCH_STATE" -> {
                    listener.onTorchChanged(json.optBoolean("torch", false))
                }
                "CAMERA_SWITCHED" -> {
                    listener.onCameraSwitched(json.optBoolean("isFront", false))
                }
                "EXPOSURE_STATE" -> {
                    listener.onExposureChanged(json.optInt("evIndex", 0))
                }
                "ERROR" -> {
                    listener.onError(json.optString("message", "Unknown error"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $message", e)
        }
    }

    override fun onError(ex: Exception) {
        Log.e(TAG, "WebSocket error: ${ex.message}", ex)
        listener.onError(ex.message ?: "Connection error")
    }

    fun sendTakePhoto() {
        sendCommand("TAKE_PHOTO", JSONObject())
    }

    fun sendStandbyCamera() {
        sendCommand("STANDBY_CAMERA", JSONObject())
    }

    fun sendWakeCamera() {
        sendCommand("WAKE_CAMERA", JSONObject())
    }

    fun sendStopService() {
        sendCommand("STOP_SERVICE", JSONObject())
    }

    fun sendZoom(zoomLinear: Float) {
        val payload = JSONObject().apply {
            put("zoom", zoomLinear.toDouble())
        }
        sendCommand("SET_ZOOM", payload)
    }

    fun sendZoomRatio(ratio: Float) {
        val payload = JSONObject().apply {
            put("ratio", ratio.toDouble())
        }
        sendCommand("SET_ZOOM", payload)
    }

    fun sendToggleTorch() {
        sendCommand("TOGGLE_TORCH", JSONObject())
    }

    fun sendSwitchCamera() {
        sendCommand("SWITCH_CAMERA", JSONObject())
    }

    fun sendExposure(evIndex: Int) {
        val payload = JSONObject().apply {
            put("evIndex", evIndex)
        }
        sendCommand("SET_EXPOSURE", payload)
    }

    fun sendTapToFocus(normX: Float, normY: Float) {
        val payload = JSONObject().apply {
            put("x", normX.toDouble())
            put("y", normY.toDouble())
        }
        sendCommand("TAP_TO_FOCUS", payload)
    }

    private fun sendCommand(command: String, payload: JSONObject) {
        if (!isOpen) return
        try {
            payload.put("command", command)
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command $command", e)
        }
    }
}
