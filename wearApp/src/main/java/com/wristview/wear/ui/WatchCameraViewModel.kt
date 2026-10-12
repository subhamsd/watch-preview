package com.wristview.wear.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wristview.wear.network.NsdDiscovery
import com.wristview.wear.network.WearCameraClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

enum class ConnectionState {
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

class WatchCameraViewModel(application: Application) : AndroidViewModel(application), WearCameraClient.CameraClientListener {

    companion object {
        private const val TAG = "WatchCameraViewModel"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.SCANNING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _cameraHost = MutableStateFlow<String?>(null)
    val cameraHost: StateFlow<String?> = _cameraHost.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _battery = MutableStateFlow(100)
    val battery: StateFlow<Int> = _battery.asStateFlow()

    private val _storageMb = MutableStateFlow(0L)
    val storageMb: StateFlow<Long> = _storageMb.asStateFlow()

    private val _zoomLinear = MutableStateFlow(0f)
    val zoomLinear: StateFlow<Float> = _zoomLinear.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _lastCapturedThumb = MutableStateFlow<Bitmap?>(null)
    val lastCapturedThumb: StateFlow<Bitmap?> = _lastCapturedThumb.asStateFlow()

    private val _captureFlashActive = MutableStateFlow(false)
    val captureFlashActive: StateFlow<Boolean> = _captureFlashActive.asStateFlow()

    private val _statusMessage = MutableStateFlow("Searching for camera on Wi-Fi...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _exposureIndex = MutableStateFlow(0)
    val exposureIndex: StateFlow<Int> = _exposureIndex.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0) // 0 = off, 3 = 3s, 10 = 10s
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _countdownRemaining = MutableStateFlow(0)
    val countdownRemaining: StateFlow<Int> = _countdownRemaining.asStateFlow()

    private val _isGridEnabled = MutableStateFlow(false)
    val isGridEnabled: StateFlow<Boolean> = _isGridEnabled.asStateFlow()

    private val _isCameraSleeping = MutableStateFlow(false)
    val isCameraSleeping: StateFlow<Boolean> = _isCameraSleeping.asStateFlow()

    private val _focusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPoint: StateFlow<Pair<Float, Float>?> = _focusPoint.asStateFlow()

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    private val _captureHistory = MutableStateFlow<List<Bitmap>>(emptyList())
    val captureHistory: StateFlow<List<Bitmap>> = _captureHistory.asStateFlow()

    private val prefs = application.getSharedPreferences("wristview_prefs", android.content.Context.MODE_PRIVATE)
    private val _targetIp = MutableStateFlow(prefs.getString("last_ip", "") ?: "")
    val targetIp: StateFlow<String> = _targetIp.asStateFlow()

    private var client: WearCameraClient? = null
    private var nsdDiscovery: NsdDiscovery? = null

    init {
        startDiscovery()
        // In parallel, scan current Wi-Fi subnet so discovery works on any network without mDNS
        scanSubnetForCamera()
    }

    fun setTargetIp(ip: String) {
        if (ip.isBlank()) return
        _targetIp.value = ip
        prefs.edit().putString("last_ip", ip).apply()
    }

    fun connectToTargetIp() {
        val ip = _targetIp.value
        if (ip.isNotBlank()) {
            connectToHost(ip, 8888)
        }
    }

    private fun scanSubnetForCamera() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val localIp = getDeviceIpAddress() ?: return@launch
            val prefix = localIp.substringBeforeLast(".") + "."

            // Check host machine if inside an Android emulator
            if (localIp.startsWith("10.0.2.")) {
                if (testPort("10.0.2.2", 8888)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        setTargetIp("10.0.2.2")
                        connectToHost("10.0.2.2", 8888)
                    }
                    return@launch
                }
            }

            // Concurrently probe subnet IPs on port 8888
            for (i in 1..254) {
                val ip = prefix + i
                if (ip == localIp) continue
                launch {
                    if (testPort(ip, 8888)) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Log.i(TAG, "Dynamic subnet scan discovered camera at $ip:8888")
                            setTargetIp(ip)
                            connectToHost(ip, 8888)
                        }
                    }
                }
            }
        }
    }

    private fun testPort(ip: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun startDiscovery() {
        _connectionState.value = ConnectionState.SCANNING
        _statusMessage.value = "Searching for camera via mDNS..."

        nsdDiscovery?.stopDiscovery()
        nsdDiscovery = NsdDiscovery(getApplication()) { host, port ->
            Log.i(TAG, "Camera discovered via mDNS at $host:$port")
            _cameraHost.value = "$host:$port"
            setTargetIp(host)
            connectToHost(host, port)
        }.apply {
            startDiscovery()
        }
    }

    fun connectToHost(host: String, port: Int) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _statusMessage.value = "Connecting to $host:$port..."

            try {
                client?.close()
                val uri = URI("ws://$host:$port")
                client = WearCameraClient(uri, this@WatchCameraViewModel).apply {
                    connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "Connect error: ${e.message}"
            }
        }
    }

    fun takePhoto() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val timer = _timerSeconds.value
        if (timer > 0) {
            viewModelScope.launch {
                for (t in timer downTo 1) {
                    _countdownRemaining.value = t
                    delay(1000)
                }
                _countdownRemaining.value = 0
                triggerCaptureNow()
            }
        } else {
            triggerCaptureNow()
        }
    }

    private fun triggerCaptureNow() {
        viewModelScope.launch {
            _captureFlashActive.value = true
            client?.sendTakePhoto()
            delay(150)
            _captureFlashActive.value = false
        }
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 10
            else -> 0
        }
    }

    fun toggleGrid() {
        _isGridEnabled.value = !_isGridEnabled.value
    }

    fun toggleSettingsSheet() {
        _showSettingsSheet.value = !_showSettingsSheet.value
    }

    fun switchCamera() {
        client?.sendSwitchCamera()
    }

    fun setExposureIndex(ev: Int) {
        val clamped = ev.coerceIn(-4, 4)
        _exposureIndex.value = clamped
        client?.sendExposure(clamped)
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
        client?.sendZoomRatio(ratio)
    }

    fun cycleZoomRatio() {
        val current = _zoomRatio.value
        val next = when {
            current < 1.8f -> 2.0f
            current < 2.8f -> 3.0f
            current < 4.8f -> 5.0f
            else -> 1.0f
        }
        setZoomRatio(next)
    }

    fun standbyCamera() {
        _isCameraSleeping.value = true
        _currentFrame.value = null
        client?.sendStandbyCamera()
    }

    fun wakeCamera() {
        _isCameraSleeping.value = false
        client?.sendWakeCamera()
    }

    fun toggleStandby() {
        if (_isCameraSleeping.value) {
            wakeCamera()
        } else {
            standbyCamera()
        }
    }

    fun triggerFocus(normX: Float, normY: Float) {
        _focusPoint.value = Pair(normX, normY)
        client?.sendTapToFocus(normX, normY)
        viewModelScope.launch {
            delay(2200)
            if (_focusPoint.value == Pair(normX, normY)) {
                _focusPoint.value = null
            }
        }
    }

    fun stopHeadlessService() {
        client?.sendStopService()
        _connectionState.value = ConnectionState.DISCONNECTED
        _currentFrame.value = null
    }

    fun disconnectAndSleep() {
        try {
            client?.sendStandbyCamera()
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
        _isCameraSleeping.value = true
        _currentFrame.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun setZoomPreset(linear: Float) {
        setZoom(linear)
    }

    fun setZoom(linearZoom: Float) {
        val clamped = linearZoom.coerceIn(0f, 1f)
        _zoomLinear.value = clamped
        client?.sendZoom(clamped)
    }

    fun stepZoom(increment: Boolean) {
        val step = 0.1f
        val newZoom = if (increment) _zoomLinear.value + step else _zoomLinear.value - step
        setZoom(newZoom)
    }

    fun toggleTorch() {
        client?.sendToggleTorch()
    }

    fun dismissThumbPreview() {
        _lastCapturedThumb.value = null
    }

    // CameraClientListener overrides
    override fun onConnected() {
        _connectionState.value = ConnectionState.CONNECTED
        _statusMessage.value = "Live Preview"
        _isCameraSleeping.value = false
        nsdDiscovery?.stopDiscovery()
    }

    override fun onDisconnected() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Camera disconnected"
        _currentFrame.value = null
    }

    override fun onNewFrame(bitmap: Bitmap) {
        if (!_isCameraSleeping.value) {
            _currentFrame.value = bitmap
        }
    }

    override fun onTelemetry(battery: Int, storageMb: Long) {
        _battery.value = battery
        _storageMb.value = storageMb
    }

    override fun onState(currentZoom: Float, maxZoom: Float) {
        _zoomRatio.value = currentZoom
    }

    override fun onCameraStandbyChanged(isSleeping: Boolean) {
        _isCameraSleeping.value = isSleeping
        if (isSleeping) {
            _currentFrame.value = null
        }
    }

    override fun onPhotoCaptured(filePath: String, thumbnail: Bitmap?) {
        _lastCapturedThumb.value = thumbnail
        _statusMessage.value = "Saved on camera!"
        thumbnail?.let {
            val list = _captureHistory.value.toMutableList()
            list.add(0, it)
            if (list.size > 5) list.removeAt(list.size - 1)
            _captureHistory.value = list
        }
    }

    override fun onTorchChanged(isTorchOn: Boolean) {
        _isTorchOn.value = isTorchOn
    }

    override fun onCameraSwitched(isFront: Boolean) {
        _isFrontCamera.value = isFront
    }

    override fun onExposureChanged(evIndex: Int) {
        _exposureIndex.value = evIndex
    }

    override fun onError(message: String) {
        _statusMessage.value = message
    }

    override fun onCleared() {
        nsdDiscovery?.stopDiscovery()
        try {
            client?.close()
        } catch (_: Exception) {}
        super.onCleared()
    }
}
