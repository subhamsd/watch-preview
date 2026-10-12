package com.app.hackaton2026.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServiceLifecycleOwner : androidx.lifecycle.LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    init {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}

class HeadlessCameraController(
    private val context: Context,
    private val onFrameAvailable: (ByteArray) -> Unit,
    private val onCameraReady: (maxZoom: Float, currentZoom: Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "HeadlessCameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val serviceLifecycleOwner = ServiceLifecycleOwner()

    private var currentTorchState = false
    private var currentLinearZoom = 0f
    private var useFrontCamera = false
    private var currentExposureIndex = 0

    @Volatile
    private var currentZoomRatio = 1.0f

    @Volatile
    private var isSleeping = false

    val isPaused: Boolean get() = isSleeping

    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get ProcessCameraProvider", e)
                onError("Failed to initialize camera provider: ${e.message}")
            }
        }, mainExecutor)
    }

    fun pauseCamera() {
        if (isSleeping) return
        isSleeping = true
        Log.i(TAG, "Putting headless camera sensor into STANDBY/SLEEP mode to conserve battery and heat")
        mainExecutor.execute {
            try {
                currentTorchState = false
                serviceLifecycleOwner.pause()
                cameraProvider?.unbindAll()
                camera = null
                imageAnalysis = null
                imageCapture = null
                Log.i(TAG, "CameraX unbindAll complete & Lifecycle transitioned to CREATED - hardware sensor is completely OFF")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera during standby", e)
            }
        }
    }

    fun resumeCamera() {
        if (!isSleeping && camera != null) {
            Log.d(TAG, "Camera already active, resume skipped")
            return
        }
        isSleeping = false
        Log.i(TAG, "Waking headless camera sensor from STANDBY")
        mainExecutor.execute {
            serviceLifecycleOwner.resume()
            bindCameraUseCases()
        }
    }

    private fun bindCameraUseCases() {
        if (isSleeping) {
            Log.i(TAG, "Skipping bindCameraUseCases: camera is in sleep/standby mode")
            return
        }
        mainExecutor.execute {
            val provider = cameraProvider ?: return@execute

            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // 1. ImageAnalysis for live remote viewfinder frames
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processPreviewFrame(imageProxy)
                    }
                }

            // 2. ImageCapture for full-resolution photos
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            try {
                serviceLifecycleOwner.resume()
                provider.unbindAll()

                // Bind to custom ServiceLifecycleOwner so camera stays RESUMED 24/7 headlessly
                val activeCamera = provider.bindToLifecycle(
                    serviceLifecycleOwner,
                    cameraSelector,
                    imageAnalysis,
                    imageCapture
                )
                camera = activeCamera

                activeCamera.cameraInfo.zoomState.observe(serviceLifecycleOwner) { zoomState ->
                    currentLinearZoom = zoomState.linearZoom
                    onCameraReady(zoomState.maxZoomRatio, currentZoomRatio)
                }

                Log.i(TAG, "Headless camera use cases bound successfully (isFront=$useFrontCamera)")
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                onError("Use case binding failed: ${e.message}")
            }
        }
    }

    private fun processPreviewFrame(imageProxy: ImageProxy) {
        if (isSleeping) {
            imageProxy.close()
            return
        }
        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // If zoom > 1.0x, digitally crop the center so viewfinder zoom ALWAYS works visibly
            val zoom = currentZoomRatio
            val srcBitmap = if (zoom > 1.05f) {
                val cropW = (bitmap.width / zoom).toInt().coerceIn(10, bitmap.width)
                val cropH = (bitmap.height / zoom).toInt().coerceIn(10, bitmap.height)
                val startX = ((bitmap.width - cropW) / 2).coerceIn(0, bitmap.width - cropW)
                val startY = ((bitmap.height - cropH) / 2).coerceIn(0, bitmap.height - cropH)
                Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
            } else {
                bitmap
            }

            val finalBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(srcBitmap, 0, 0, srcBitmap.width, srcBitmap.height, matrix, true)
            } else {
                srcBitmap
            }

            val outStream = ByteArrayOutputStream()
            // Compress to JPEG with 65% quality for optimal bandwidth on Wi-Fi
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 65, outStream)
            val jpegBytes = outStream.toByteArray()

            onFrameAvailable(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    fun setLinearZoom(zoom: Float) {
        val clamped = zoom.coerceIn(0f, 1f)
        currentLinearZoom = clamped
        mainExecutor.execute {
            try {
                camera?.cameraControl?.setLinearZoom(clamped)
                Log.i(TAG, "setLinearZoom executed: $clamped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set linear zoom", e)
            }
        }
    }

    fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(1.0f, 10.0f)
        Log.i(TAG, "setZoomRatio updated currentZoomRatio to $currentZoomRatio")
        mainExecutor.execute {
            try {
                val activeCam = camera ?: run {
                    Log.w(TAG, "setZoomRatio hardware skipped: camera is not bound, digital zoom active ($currentZoomRatio)")
                    return@execute
                }
                val zoomState = activeCam.cameraInfo.zoomState.value
                val minRatio = zoomState?.minZoomRatio ?: 1f
                val maxRatio = zoomState?.maxZoomRatio ?: 8f
                val clamped = ratio.coerceIn(minRatio, maxRatio)
                Log.i(TAG, "Applying hardware zoom ratio $clamped (requested=$ratio, range=[$minRatio, $maxRatio])")
                val future = activeCam.cameraControl.setZoomRatio(clamped)
                future.addListener({
                    try {
                        future.get()
                        Log.i(TAG, "Hardware zoom ratio set successfully to $clamped")
                    } catch (ex: Exception) {
                        Log.w(TAG, "setZoomRatio threw exception, trying linear zoom fallback: ${ex.message}")
                        if (maxRatio > minRatio) {
                            val linear = ((clamped - minRatio) / (maxRatio - minRatio)).coerceIn(0f, 1f)
                            activeCam.cameraControl.setLinearZoom(linear)
                        }
                    }
                }, mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "Failed in setZoomRatio: $ratio", e)
            }
        }
    }

    fun triggerFocus(normalizedX: Float, normalizedY: Float) {
        val zoom = currentZoomRatio
        val (mappedX, mappedY) = if (zoom > 1.05f) {
            val offset = (1f - 1f / zoom) / 2f
            Pair(
                (offset + normalizedX / zoom).coerceIn(0f, 1f),
                (offset + normalizedY / zoom).coerceIn(0f, 1f)
            )
        } else {
            Pair(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
        }

        Log.i(TAG, "Triggering tap-to-focus: tapped=($normalizedX, $normalizedY), mapped=($mappedX, $mappedY), zoom=$zoom")
        mainExecutor.execute {
            try {
                val activeCam = camera ?: return@execute
                val analysis = imageAnalysis
                val factory = if (analysis != null) {
                    SurfaceOrientedMeteringPointFactory(1f, 1f, analysis)
                } else {
                    SurfaceOrientedMeteringPointFactory(1f, 1f)
                }
                val point = factory.createPoint(mappedX, mappedY)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
                )
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build()

                // Force lens to actively seek and lock onto the new tap target
                activeCam.cameraControl.cancelFocusAndMetering()
                val future = activeCam.cameraControl.startFocusAndMetering(action)
                future.addListener({
                    try {
                        val result = future.get()
                        Log.i(TAG, "Focus metering completed at ($mappedX, $mappedY): successful=${result.isFocusSuccessful}")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Focus listener error: ${ex.message}")
                    }
                }, mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "Failed in triggerFocus", e)
            }
        }
    }

    fun toggleTorch(enable: Boolean? = null): Boolean {
        val target = enable ?: !currentTorchState
        currentTorchState = target
        mainExecutor.execute {
            camera?.cameraControl?.enableTorch(target)
        }
        return target
    }

    fun switchCamera(): Boolean {
        useFrontCamera = !useFrontCamera
        bindCameraUseCases()
        return useFrontCamera
    }

    fun setExposureIndex(index: Int): Int {
        val exposureState = camera?.cameraInfo?.exposureState ?: return 0
        val clamped = index.coerceIn(exposureState.exposureCompensationRange.lower, exposureState.exposureCompensationRange.upper)
        currentExposureIndex = clamped
        mainExecutor.execute {
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        }
        return clamped
    }

    data class CaptureResult(
        val filePath: String,
        val thumbnailBase64: String
    )

    fun takePhoto(onResult: (Result<CaptureResult>) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(Result.failure(IllegalStateException("Camera not ready for capture")))
            return
        }

        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "WristView"
        ).apply { mkdirs() }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(outputDir, "WV_${timeStamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val zoom = currentZoomRatio
                        if (zoom > 1.05f) {
                            applyZoomCropToFile(photoFile, zoom)
                        }
                        val thumbnailBase64 = createThumbnailBase64(photoFile)
                        onResult(Result.success(CaptureResult(photoFile.absolutePath, thumbnailBase64)))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed creating capture thumbnail", e)
                        onResult(Result.success(CaptureResult(photoFile.absolutePath, "")))
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    onResult(Result.failure(exc))
                }
            }
        )
    }

    private fun applyZoomCropToFile(file: File, zoom: Float) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val fullW = bounds.outWidth
            val fullH = bounds.outHeight
            if (fullW <= 0 || fullH <= 0) return

            val cropW = (fullW / zoom).toInt().coerceIn(10, fullW)
            val cropH = (fullH / zoom).toInt().coerceIn(10, fullH)
            val startX = ((fullW - cropW) / 2).coerceIn(0, fullW - cropW)
            val startY = ((fullH - cropH) / 2).coerceIn(0, fullH - cropH)
            val cropRect = Rect(startX, startY, startX + cropW, startY + cropH)

            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttribute(android.media.ExifInterface.TAG_ORIENTATION)

            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.inputStream())
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.absolutePath, false)
            }

            val croppedBitmap = decoder?.decodeRegion(cropRect, BitmapFactory.Options()) ?: return
            FileOutputStream(file).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (orientation != null) {
                val newExif = android.media.ExifInterface(file.absolutePath)
                newExif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation)
                newExif.saveAttributes()
            }
            Log.i(TAG, "Successfully cropped captured photo to match ${zoom}x zoom ($cropW x $cropH)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying zoom crop to photo", e)
        }
    }

    private fun createThumbnailBase64(file: File): String {
        // Decode scaled thumbnail for the watch screen
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

        val targetSize = 240
        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= targetSize &&
            boundsOptions.outHeight / (sampleSize * 2) >= targetSize
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val sampledBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return ""

        // Correct orientation using EXIF metadata
        val rotatedBitmap = try {
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
            } else {
                sampledBitmap
            }
        } catch (e: Exception) {
            sampledBitmap
        }

        val stream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val thumbBytes = stream.toByteArray()
        return Base64.encodeToString(thumbBytes, Base64.NO_WRAP)
    }

    fun stop() {
        try {
            serviceLifecycleOwner.destroy()
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }
}
