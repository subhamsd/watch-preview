package com.wristview.wear.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*

// Pro Camera Colors
val ProAccentCyan = Color(0xFF00E5FF)
val ProAccentEmerald = Color(0xFF00E676)
val ProShutterRed = Color(0xFFFF1744)
val ProAmber = Color(0xFFFFB300)

@Composable
fun WatchCameraScreen(viewModel: WatchCameraViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val frameBitmap by viewModel.currentFrame.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val storageMb by viewModel.storageMb.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val lastCapturedThumb by viewModel.lastCapturedThumb.collectAsState()
    val isFlashActive by viewModel.captureFlashActive.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val targetIp by viewModel.targetIp.collectAsState()

    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val exposureIndex by viewModel.exposureIndex.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val countdownRemaining by viewModel.countdownRemaining.collectAsState()
    val isGridEnabled by viewModel.isGridEnabled.collectAsState()
    val isCameraSleeping by viewModel.isCameraSleeping.collectAsState()
    val focusPoint by viewModel.focusPoint.collectAsState()
    val showSettingsSheet by viewModel.showSettingsSheet.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (connectionState) {
            ConnectionState.SCANNING, ConnectionState.CONNECTING -> {
                ScanningView(
                    statusMsg = statusMsg,
                    targetIp = targetIp,
                    onConnectTargetIp = { viewModel.connectToTargetIp() },
                    onRetry = { viewModel.startDiscovery() }
                )
            }
            ConnectionState.CONNECTED -> {
                LiveProViewfinderView(
                    frameBitmap = frameBitmap,
                    battery = battery,
                    storageMb = storageMb,
                    zoomRatio = zoomRatio,
                    isTorchOn = isTorchOn,
                    isFrontCamera = isFrontCamera,
                    exposureIndex = exposureIndex,
                    timerSeconds = timerSeconds,
                    countdownRemaining = countdownRemaining,
                    isGridEnabled = isGridEnabled,
                    isFlashActive = isFlashActive,
                    isCameraSleeping = isCameraSleeping,
                    focusPoint = focusPoint,
                    onShutterClick = { viewModel.takePhoto() },
                    onZoomCycle = { viewModel.cycleZoomRatio() },
                    onOpenSettings = { viewModel.toggleSettingsSheet() },
                    onToggleStandby = { viewModel.toggleStandby() },
                    onTapToFocus = { x, y -> viewModel.triggerFocus(x, y) }
                )
            }
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                DisconnectedView(
                    message = statusMsg,
                    targetIp = targetIp,
                    onConnectTargetIp = { viewModel.connectToTargetIp() },
                    onReconnect = { viewModel.startDiscovery() }
                )
            }
        }

        // Pro Settings Overlay
        AnimatedVisibility(
            visible = showSettingsSheet,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            ProSettingsDrawer(
                isFrontCamera = isFrontCamera,
                timerSeconds = timerSeconds,
                isTorchOn = isTorchOn,
                isGridEnabled = isGridEnabled,
                isCameraSleeping = isCameraSleeping,
                exposureIndex = exposureIndex,
                onSwitchCamera = { viewModel.switchCamera() },
                onCycleTimer = { viewModel.cycleTimer() },
                onToggleTorch = { viewModel.toggleTorch() },
                onToggleGrid = { viewModel.toggleGrid() },
                onExposureAdjust = { delta -> viewModel.setExposureIndex(exposureIndex + delta) },
                onToggleStandby = { viewModel.toggleStandby() },
                onStopService = { viewModel.stopHeadlessService() },
                onClose = { viewModel.toggleSettingsSheet() }
            )
        }

        // Captured Photo Review Overlay
        AnimatedVisibility(
            visible = lastCapturedThumb != null && !showSettingsSheet,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut()
        ) {
            lastCapturedThumb?.let { thumb ->
                ThumbnailPreviewDialog(
                    thumbnail = thumb,
                    onDismiss = { viewModel.dismissThumbPreview() }
                )
            }
        }
    }
}

@Composable
fun LiveProViewfinderView(
    frameBitmap: Bitmap?,
    battery: Int,
    storageMb: Long,
    zoomRatio: Float,
    isTorchOn: Boolean,
    isFrontCamera: Boolean,
    exposureIndex: Int,
    timerSeconds: Int,
    countdownRemaining: Int,
    isGridEnabled: Boolean,
    isFlashActive: Boolean,
    isCameraSleeping: Boolean,
    focusPoint: Pair<Float, Float>?,
    onShutterClick: () -> Unit,
    onZoomCycle: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleStandby: () -> Unit,
    onTapToFocus: (Float, Float) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live Camera Stream Canvas OR Standby Saver Canvas
        if (isCameraSleeping) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0F14)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(text = "💤", fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Camera Sleeping",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Sensor off • Battery saved",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(ProAccentCyan)
                            .clickable { onToggleStandby() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡ Wake Camera",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (frameBitmap != null) {
            val haptic = LocalHapticFeedback.current
            Image(
                bitmap = frameBitmap.asImageBitmap(),
                contentDescription = "Live Viewfinder",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height).coerceIn(0f, 1f)
                            onTapToFocus(normX, normY)
                        }
                    },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0C)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    indicatorColor = ProAccentCyan,
                    strokeWidth = 3.dp
                )
            }
        }

        // 2. Rule-of-Thirds Grid
        if (!isCameraSleeping && isGridEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridColor = Color.White.copy(alpha = 0.28f)
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1f)
                drawLine(gridColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = 1f)
            }
        }

        // 3. Center Reticle Target (when not focusing on specific point)
        if (!isCameraSleeping && focusPoint == null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val crossColor = ProAccentCyan.copy(alpha = 0.45f)
                val arm = 10f
                drawLine(crossColor, Offset(cx - arm, cy), Offset(cx - 3f, cy), strokeWidth = 1.5f)
                drawLine(crossColor, Offset(cx + 3f, cy), Offset(cx + arm, cy), strokeWidth = 1.5f)
                drawLine(crossColor, Offset(cx, cy - arm), Offset(cx, cy - 3f), strokeWidth = 1.5f)
                drawLine(crossColor, Offset(cx, cy + 3f), Offset(cx, cy + arm), strokeWidth = 1.5f)
            }
        }

        // 4. Authentic Smartphone Tap-to-Focus Indicator (Golden Ring + Sun Icon + Snappy Spring Pop)
        if (!isCameraSleeping && focusPoint != null) {
            val pt = focusPoint
            val animScale = remember(pt) { Animatable(1.42f) }

            LaunchedEffect(pt) {
                // Snappy spring contraction from 1.42x to 1.0x (overshoot pop)
                animScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val fx = pt.first * size.width
                val fy = pt.second * size.height
                val radius = 28.dp.toPx() * animScale.value
                val yellowFocus = Color(0xFFFFD600)
                val shadowColor = Color.Black.copy(alpha = 0.65f)

                // High-contrast outer outline
                drawCircle(
                    color = shadowColor,
                    radius = radius + 1.5f,
                    center = Offset(fx, fy),
                    style = Stroke(width = 3.5f)
                )

                // Main Golden Focus Ring (Galaxy/iPhone camera style)
                drawCircle(
                    color = yellowFocus,
                    radius = radius,
                    center = Offset(fx, fy),
                    style = Stroke(width = 2.4f)
                )

                // 4 Precision Ticks at 12, 3, 6, 9 o'clock
                val tick = 6.dp.toPx()
                // Top
                drawLine(shadowColor, Offset(fx, fy - radius - tick - 1f), Offset(fx, fy - radius + 2f), strokeWidth = 3.5f)
                drawLine(yellowFocus, Offset(fx, fy - radius - tick), Offset(fx, fy - radius + 1f), strokeWidth = 2.4f)
                // Bottom
                drawLine(shadowColor, Offset(fx, fy + radius - 2f), Offset(fx, fy + radius + tick + 1f), strokeWidth = 3.5f)
                drawLine(yellowFocus, Offset(fx, fy + radius - 1f), Offset(fx, fy + radius + tick), strokeWidth = 2.4f)
                // Left
                drawLine(shadowColor, Offset(fx - radius - tick - 1f, fy), Offset(fx - radius + 2f, fy), strokeWidth = 3.5f)
                drawLine(yellowFocus, Offset(fx - radius - tick, fy), Offset(fx - radius + 1f, fy), strokeWidth = 2.4f)
                // Right
                drawLine(shadowColor, Offset(fx + radius - 2f, fy), Offset(fx + radius + tick + 1f, fy), strokeWidth = 3.5f)
                drawLine(yellowFocus, Offset(fx + radius - 1f, fy), Offset(fx + radius + tick, fy), strokeWidth = 2.4f)

                // Center Micro Focus Dot
                drawCircle(color = shadowColor, radius = 3.5.dp.toPx(), center = Offset(fx, fy))
                drawCircle(color = yellowFocus, radius = 2.2.dp.toPx(), center = Offset(fx, fy))

                // Exposure Sun Icon (Positioned at 3 o'clock next to the ring)
                val sunOffset = Offset(fx + radius + 15.dp.toPx(), fy)
                val sunR = 3.8.dp.toPx()
                val rayLen = 3.2.dp.toPx()
                drawCircle(color = shadowColor, radius = sunR + 1.2f, center = sunOffset)
                drawCircle(color = yellowFocus, radius = sunR, center = sunOffset)
                for (angle in 0 until 8) {
                    val rad = Math.toRadians((angle * 45).toDouble())
                    val cos = Math.cos(rad).toFloat()
                    val sin = Math.sin(rad).toFloat()
                    val r1 = sunR + 2.2.dp.toPx()
                    val r2 = r1 + rayLen
                    val p1 = Offset(sunOffset.x + cos * r1, sunOffset.y + sin * r1)
                    val p2 = Offset(sunOffset.x + cos * r2, sunOffset.y + sin * r2)
                    drawLine(shadowColor, p1, p2, strokeWidth = 2.5f)
                    drawLine(yellowFocus, p1, p2, strokeWidth = 1.6f)
                }
            }
        }

        // Shutter Screen Flash
        if (isFlashActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }

        // 4. Large Pulsing Countdown Overlay
        if (countdownRemaining > 0) {
            val infiniteTransition = rememberInfiniteTransition()
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .scale(scale)
                        .background(ProShutterRed.copy(alpha = 0.85f), CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$countdownRemaining",
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // 5. Circular Top HUD Capsule (Safely indented below circular bezel)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Live status dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isCameraSleeping) ProAmber else ProAccentEmerald, CircleShape)
                )
                Spacer(modifier = Modifier.width(5.dp))

                // Lens badge
                Text(
                    text = if (isCameraSleeping) "SLEEP" else if (isFrontCamera) "FRONT" else "BACK",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Timer badge if active
                if (timerSeconds > 0 && !isCameraSleeping) {
                    Text(
                        text = " • ⏱${timerSeconds}s",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ProAmber
                    )
                }

                // Torch badge if active
                if (isTorchOn && !isCameraSleeping) {
                    Text(text = " • ⚡", fontSize = 9.sp)
                }

                // EV badge if non-zero
                if (exposureIndex != 0 && !isCameraSleeping) {
                    Text(
                        text = " • " + (if (exposureIndex > 0) "+$exposureIndex" else "$exposureIndex"),
                        fontSize = 9.sp,
                        color = ProAccentCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Battery %
                Text(
                    text = " • $battery%",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (battery < 20) Color(0xFFFF5252) else Color.White
                )
            }
        }

        // Shooting controls (Only visible when camera is awake)
        if (!isCameraSleeping) {
            // 6. Floating Zoom Cycler Pill (Centered above Shutter, completely separated)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .clickable { onZoomCycle() }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // 7. Tactical Central Shutter Button (Bottom Center)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .size(54.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(ProShutterRed)
                    .clickable { onShutterClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(Color.White.copy(alpha = 0.5f), CircleShape)
                )
            }

            // 8. One-touch Standby Sleep Shortcut (9 o'clock waist - widest circular point)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.70f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable { onToggleStandby() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "💤", fontSize = 14.sp)
            }
        }

        // 9. Settings Gear Pill (3 o'clock waist - widest circular point)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.70f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .clickable { onOpenSettings() },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "⚙️", fontSize = 15.sp)
        }
    }
}

@Composable
fun ProSettingsDrawer(
    isFrontCamera: Boolean,
    timerSeconds: Int,
    isTorchOn: Boolean,
    isGridEnabled: Boolean,
    isCameraSleeping: Boolean,
    exposureIndex: Int,
    onSwitchCamera: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleGrid: () -> Unit,
    onExposureAdjust: (Int) -> Unit,
    onToggleStandby: () -> Unit,
    onStopService: () -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C10))
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 28.dp, bottom = 42.dp, start = 14.dp, end = 14.dp),
            autoCentering = AutoCenteringParams(itemIndex = 1),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Camera Settings",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ProAccentCyan,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // 1. Lens Selection Chip
            item {
                Chip(
                    onClick = onSwitchCamera,
                    label = { Text("Lens: ${if (isFrontCamera) "Front Selfie" else "Rear Main"}", fontSize = 11.sp) },
                    icon = { Text(if (isFrontCamera) "🤳" else "📷") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 2. Standby / Wake Camera Sensor
            item {
                Chip(
                    onClick = {
                        onToggleStandby()
                        onClose()
                    },
                    label = {
                        Text(
                            text = if (isCameraSleeping) "Wake Camera Sensor" else "Put Camera to Sleep",
                            fontSize = 11.sp
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (isCameraSleeping) "Turn on sensor" else "Save battery & heat",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    },
                    icon = { Text(if (isCameraSleeping) "⚡" else "💤") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 3. Timer Chip
            item {
                Chip(
                    onClick = onCycleTimer,
                    label = { Text("Timer: ${if (timerSeconds == 0) "Off" else "${timerSeconds}s"}", fontSize = 11.sp) },
                    icon = { Text("⏱️") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 4. Torch Toggle
            item {
                ToggleChip(
                    checked = isTorchOn,
                    onCheckedChange = { onToggleTorch() },
                    label = { Text("Torch / Flash", fontSize = 11.sp) },
                    toggleControl = { Switch(checked = isTorchOn) },
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 5. Framing Grid Toggle
            item {
                ToggleChip(
                    checked = isGridEnabled,
                    onCheckedChange = { onToggleGrid() },
                    label = { Text("Framing 3x3 Grid", fontSize = 11.sp) },
                    toggleControl = { Switch(checked = isGridEnabled) },
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 6. Exposure EV Controls
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactButton(
                        onClick = { onExposureAdjust(-1) },
                        modifier = Modifier.size(34.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "EV: ${if (exposureIndex > 0) "+$exposureIndex" else "$exposureIndex"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ProAccentCyan
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    CompactButton(
                        onClick = { onExposureAdjust(1) },
                        modifier = Modifier.size(34.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 7. Stop Remote Service Chip
            item {
                Chip(
                    onClick = {
                        onStopService()
                        onClose()
                    },
                    label = { Text("Stop Remote Service", fontSize = 11.sp) },
                    icon = { Text("🛑") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }

            // 8. Close / Done Button
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    onClick = onClose,
                    label = {
                        Text(
                            text = "Done",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.65f)
                )
            }
        }
    }
}

@Composable
fun ScanningView(
    statusMsg: String,
    targetIp: String,
    onConnectTargetIp: () -> Unit,
    onRetry: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        autoCentering = AutoCenteringParams(itemIndex = 1),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
                indicatorColor = ProAccentCyan
            )
        }
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "WristView Radar",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        item {
            Text(
                text = statusMsg,
                fontSize = 10.sp,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            if (targetIp.isNotBlank()) {
                Chip(
                    onClick = onConnectTargetIp,
                    label = {
                        Text(
                            text = "Connect ($targetIp)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            } else {
                Chip(
                    onClick = onRetry,
                    label = {
                        Text(
                            text = "Scan Wi-Fi",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            }
        }
    }
}

@Composable
fun DisconnectedView(
    message: String,
    targetIp: String,
    onConnectTargetIp: () -> Unit,
    onReconnect: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        autoCentering = AutoCenteringParams(itemIndex = 1),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(text = "⚠️", fontSize = 22.sp)
        }
        item {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Camera Offline",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        item {
            Text(
                text = message,
                fontSize = 10.sp,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        item {
            Spacer(modifier = Modifier.height(6.dp))
            if (targetIp.isNotBlank()) {
                Chip(
                    onClick = onConnectTargetIp,
                    label = {
                        Text(
                            text = "Reconnect ($targetIp)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Chip(
                onClick = onReconnect,
                label = {
                    Text(
                        text = "Scan for Camera",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
    }
}

@Composable
fun ThumbnailPreviewDialog(thumbnail: Bitmap, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.90f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "✓ Saved on Camera",
                color = ProAccentEmerald,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "Captured photo preview",
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tap to dismiss",
                color = Color(0xFF90A4AE),
                fontSize = 9.sp
            )
        }
    }
}
