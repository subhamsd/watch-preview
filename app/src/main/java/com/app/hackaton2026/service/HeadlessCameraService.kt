package com.app.hackaton2026.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import com.app.hackaton2026.camera.HeadlessCameraController
import com.app.hackaton2026.network.CameraServer
import com.app.hackaton2026.network.NsdAdvertiser
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class HeadlessCameraService : Service(), CameraServer.CommandListener {

    companion object {
        private const val TAG = "HeadlessCameraService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wristview_camera_channel"
        const val SERVER_PORT = 8888

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        var isRunning = false
            private set
    }

    private var nsdAdvertiser: NsdAdvertiser? = null
    private var cameraServer: CameraServer? = null
    private var cameraController: HeadlessCameraController? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HeadlessCameraService onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceInternal()

        if (isRunning) {
            Log.i(TAG, "HeadlessCameraService already running, ignoring duplicate start")
            return START_STICKY
        }
        isRunning = true
        startComponents()

        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val notification = createNotification("WristView Camera is active and discoverable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startComponents() {
        // 1. Start WebSocket server
        cameraServer = CameraServer(SERVER_PORT, this).apply {
            start()
        }

        // 2. Start mDNS advertiser
        nsdAdvertiser = NsdAdvertiser(this).apply {
            startAdvertising(SERVER_PORT)
        }

        // 3. Start Headless CameraX
        cameraController = HeadlessCameraController(
            context = this,
            onFrameAvailable = { jpegBytes ->
                cameraServer?.broadcastVideoFrame(jpegBytes)
            },
            onCameraReady = { maxZoom, currentZoom ->
                broadcastState(maxZoom = maxZoom, currentZoom = currentZoom)
            },
            onError = { errMsg ->
                Log.e(TAG, "Camera error: $errMsg")
                val errJson = JSONObject().apply {
                    put("type", "ERROR")
                    put("message", errMsg)
                }
                cameraServer?.broadcastJson(errJson)
            }
        ).apply {
            start()
        }

        // 4. Start periodic telemetry broadcast (battery, storage)
        startTelemetryLoop()
    }

    private fun startTelemetryLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(4000)
                sendTelemetry()
            }
        }
    }

    private fun sendTelemetry() {
        val batteryLevel = getBatteryPercentage()
        val freeStorageMb = getFreeStorageMb()

        val json = JSONObject().apply {
            put("type", "TELEMETRY")
            put("battery", batteryLevel)
            put("storageMb", freeStorageMb)
            put("clients", cameraServer?.connectedClientsCount ?: 0)
        }
        cameraServer?.broadcastJson(json)
    }

    private fun broadcastState(maxZoom: Float = 1.0f, currentZoom: Float = 1.0f) {
        val json = JSONObject().apply {
            put("type", "STATE")
            put("maxZoom", maxZoom)
            put("currentZoom", currentZoom)
            put("battery", getBatteryPercentage())
            put("storageMb", getFreeStorageMb())
            put("isSleeping", cameraController?.isPaused ?: false)
        }
        cameraServer?.broadcastJson(json)
    }

    private fun getBatteryPercentage(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100
    }

    private fun getFreeStorageMb(): Long {
        return try {
            val stat = StatFs(filesDir.path)
            (stat.availableBytes / (1024 * 1024))
        } catch (e: Exception) {
            0L
        }
    }

    override fun onClientConnected(count: Int) {
        Log.i(TAG, "Wear OS watch connected! Total: $count. Resuming camera.")
        cameraController?.resumeCamera()
        broadcastState()
    }

    override fun onClientDisconnected(count: Int) {
        Log.i(TAG, "Wear OS watch disconnected. Total: $count")
        if (count == 0) {
            Log.i(TAG, "All watch clients disconnected -> Putting camera sensor to standby immediately to prevent battery drain & heat")
            cameraController?.pauseCamera()
        }
    }

    override fun onCommand(command: String, payload: JSONObject) {
        Log.i(TAG, "Command received: $command")
        when (command) {
            "STANDBY_CAMERA", "SLEEP_CAMERA" -> {
                Log.i(TAG, "Standby camera command received from watch")
                cameraController?.pauseCamera()
                val resp = JSONObject().apply {
                    put("type", "CAMERA_STANDBY")
                    put("isSleeping", true)
                }
                cameraServer?.broadcastJson(resp)
            }
            "WAKE_CAMERA", "RESUME_CAMERA" -> {
                Log.i(TAG, "Wake camera command received from watch")
                cameraController?.resumeCamera()
                val resp = JSONObject().apply {
                    put("type", "CAMERA_STANDBY")
                    put("isSleeping", false)
                }
                cameraServer?.broadcastJson(resp)
            }
            "STOP_SERVICE" -> {
                Log.i(TAG, "Stop service command received from watch")
                cameraController?.pauseCamera()
                stopSelf()
            }
            "TAKE_PHOTO" -> {
                cameraController?.takePhoto { result ->
                    result.onSuccess { capture ->
                        val resp = JSONObject().apply {
                            put("type", "PHOTO_CAPTURED")
                            put("filePath", capture.filePath)
                            put("thumbnail", capture.thumbnailBase64)
                        }
                        cameraServer?.broadcastJson(resp)
                    }.onFailure { ex ->
                        val resp = JSONObject().apply {
                            put("type", "ERROR")
                            put("message", "Capture failed: ${ex.message}")
                        }
                        cameraServer?.broadcastJson(resp)
                    }
                }
            }
            "SET_ZOOM" -> {
                val ratio = payload.optDouble("ratio", 0.0).toFloat()
                val linear = payload.optDouble("zoom", -1.0).toFloat()
                if (ratio > 0f) {
                    cameraController?.setZoomRatio(ratio)
                } else if (linear >= 0f) {
                    cameraController?.setLinearZoom(linear)
                }
            }
            "TOGGLE_TORCH" -> {
                val newState = cameraController?.toggleTorch() ?: false
                val resp = JSONObject().apply {
                    put("type", "TORCH_STATE")
                    put("torch", newState)
                }
                cameraServer?.broadcastJson(resp)
            }
            "SWITCH_CAMERA" -> {
                val isFront = cameraController?.switchCamera() ?: false
                val resp = JSONObject().apply {
                    put("type", "CAMERA_SWITCHED")
                    put("isFront", isFront)
                }
                cameraServer?.broadcastJson(resp)
            }
            "SET_EXPOSURE" -> {
                val evIndex = payload.optInt("evIndex", 0)
                val applied = cameraController?.setExposureIndex(evIndex) ?: 0
                val resp = JSONObject().apply {
                    put("type", "EXPOSURE_STATE")
                    put("evIndex", applied)
                }
                cameraServer?.broadcastJson(resp)
            }
            "TAP_TO_FOCUS" -> {
                val x = payload.optDouble("x", 0.5).toFloat()
                val y = payload.optDouble("y", 0.5).toFloat()
                cameraController?.triggerFocus(x, y)
            }
            "GET_STATE" -> {
                broadcastState()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WristView Headless Camera Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the headless camera server for remote Wear OS control"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WristView Camera Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "HeadlessCameraService onDestroy")
        isRunning = false
        serviceScope.cancel()
        nsdAdvertiser?.stopAdvertising()
        try {
            cameraServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        cameraController?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
