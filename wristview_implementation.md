# WristView: Android Phone Camera + Wear OS Remote Viewfinder

## 1. Project goal

Build a native Android + Wear OS app that lets a user:

-   View a near-real-time preview from the Android phone camera on a
    Wear OS watch.
-   Adjust camera zoom from the watch.
-   Trigger full-resolution photo capture on the phone.
-   See connection and capture status.
-   Add supported camera controls such as exposure compensation, flash,
    camera switching, focus, and manual settings as time permits.

**Recommended first demo:** live preview + zoom + shutter. Make these
reliable before adding advanced controls.

## 2. Architecture

Use two apps/modules:

-   `phoneApp`: Android phone app; owns camera permissions, camera
    session, photo capture, signaling endpoint, and video publishing.
-   `watchApp`: Wear OS app; connects to the phone, renders incoming
    video, sends commands, and displays status.

Suggested data paths:

1.  **Video:** phone camera → WebRTC video track → local Wi-Fi → watch
    WebRTC receiver → `SurfaceViewRenderer` embedded in Compose.
2.  **Control:** watch → authenticated WebSocket over local Wi-Fi →
    phone command handler → camera controller.
3.  **Status:** phone → WebSocket → watch, including applied zoom,
    capture result, errors, and connection state.

WebRTC is the recommended production-oriented video transport because it
supports real-time media and adapts to network conditions. A simpler
MJPEG-over-HTTP prototype can be used to validate the concept, but it is
less bandwidth-efficient and generally less suitable for a polished live
preview.

**Important:** WebRTC requires signaling. Being on the same Wi-Fi does
not automatically connect the apps. For a hackathon prototype, show the
phone's local IP/port and let the watch connect to it, or implement
local device discovery later. Some guest Wi-Fi networks isolate clients
and prevent device-to-device connections.

## 3. Suggested project structure

``` text
WristView/
├── phoneApp/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/.../phone/
│           ├── PhoneCameraActivity.kt
│           ├── camera/CameraController.kt
│           ├── streaming/WebRtcPublisher.kt
│           ├── signaling/SignalingServer.kt
│           ├── control/CameraCommandHandler.kt
│           └── ui/PhoneCameraScreen.kt
└── watchApp/
    └── src/main/
        ├── AndroidManifest.xml
        └── java/.../watch/
            ├── WatchActivity.kt
            ├── streaming/WebRtcViewer.kt
            ├── signaling/PhoneConnection.kt
            ├── control/CameraRemoteClient.kt
            └── ui/CameraScreen.kt
```

Keep WebRTC objects and camera sessions in lifecycle-aware managers, not
directly inside composable functions. Use a `ViewModel` or state holder
to expose UI state such as `isConnected`, `zoomRatio`, and
`captureStatus`.

## 4. Dependencies and platform choices

Use Kotlin, Jetpack Compose, and a compatible, maintained Android WebRTC
distribution. Pin a specific version; do not use a floating dependency
such as `1.0.+`. Confirm current artifact availability and compatibility
with your compile SDK before implementation.

Phone camera options:

-   **CameraX**: easier lifecycle integration, preview, and still-image
    capture.
-   **Camera2**: offers more direct access to advanced capture controls,
    but requires more camera-session and device-capability handling.
-   **WebRTC camera capturer**: can be the simplest way to publish a
    camera video track. If using it, decide whether WebRTC owns camera
    capture or whether you need CameraX-specific processing. Avoid
    having CameraX and WebRTC independently open the same camera at
    once.

A practical design is to define a `CameraController` interface and
implement it with CameraX first. Add Camera2-specific functionality only
when a required setting is not conveniently supported.

## 5. Permissions and networking

### Phone manifest

Declare the permissions required for the selected implementation,
typically:

``` xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Request camera permission at runtime before opening the camera. Add
microphone permission only if audio is actually required.

### Watch manifest

For network communication:

``` xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

If the implementation uses a local HTTP or WebSocket endpoint without
TLS, Android network security policy may need configuration. Prefer
authenticated and encrypted signaling for anything beyond a trusted demo
network. Never expose an unauthenticated camera-control server to an
untrusted network.

For an initial demo, keep the phone app in the foreground while
streaming. Background camera use is subject to Android
foreground-service and camera access restrictions, which should be
designed deliberately rather than assumed to work.

## 6. Video streaming flow

1.  Phone opens the camera after permission is granted.
2.  Phone creates a WebRTC `PeerConnectionFactory` and video
    source/track.
3.  Watch connects to the phone's signaling endpoint.
4.  Phone and watch exchange SDP offer/answer messages.
5.  Both exchange ICE candidates.
6.  WebRTC establishes the media path.
7.  Watch attaches the incoming remote video track to a video renderer.
8.  UI displays connected/disconnected state and reconnect options.

For the first version, start around **640 × 480 at 15--24 FPS** and
measure on the actual devices. Tune resolution, frame rate, and bitrate
for the watch display, network conditions, heat, and battery life.

### Signaling messages

The signaling channel needs message types for at least:

-   `offer`
-   `answer`
-   `iceCandidate`
-   `error`
-   `close` or session termination

Signaling is not the video stream itself; it only helps establish and
manage the WebRTC connection. For same-Wi-Fi operation, a local
signaling server is enough for a prototype, but you still need to handle
IP changes, reconnects, and Wi-Fi client isolation.

## 7. Remote camera command protocol

Use a small JSON command protocol over WebSocket. Include a request ID
so the watch can match a result to a command.

Example request:

``` json
{
  "id": "cmd-001",
  "type": "setZoom",
  "value": 2.0
}
```

Example response:

``` json
{
  "id": "cmd-001",
  "type": "commandResult",
  "ok": true,
  "appliedValue": 2.0
}
```

Other useful command types:

-   `capturePhoto`
-   `setZoom`
-   `switchCamera`
-   `setExposureCompensation`
-   `setFlashMode`
-   `focusAt`
-   `getCapabilities`
-   `getCameraState`

Validate every command on the phone. Return an explicit error when a
value is outside the supported range or the requested feature is
unavailable. The watch should display the **actual applied value**
reported by the phone rather than assuming the requested value
succeeded.

## 8. Camera controller responsibilities

Define a camera abstraction that exposes operations such as:

``` kotlin
interface CameraController {
    suspend fun setZoom(ratio: Float): Result<Float>
    suspend fun capturePhoto(): Result<String>
    suspend fun switchCamera(): Result<Unit>
    suspend fun setExposureCompensation(index: Int): Result<Int>
    suspend fun getCapabilities(): CameraCapabilities
}
```

This is an illustrative interface, not a complete drop-in
implementation. Adapt return types and data models to the app's
architecture.

### Zoom

-   Read the device's supported zoom range.
-   Clamp requested values to that range.
-   Apply zoom using the selected CameraX or Camera2 implementation.
-   Send the actual applied ratio back to the watch.
-   Debounce rapid slider updates to avoid overwhelming the command
    channel.

### Photo capture

-   Capture on the phone using CameraX `ImageCapture` or the Camera2
    still-capture pipeline.
-   Save the full-resolution image on the phone.
-   Send a success/failure response to the watch.
-   Optionally send a small thumbnail to the watch after the capture
    succeeds.

Do not use a frame from the watch's compressed live preview as the final
photo.

### Focus, flash, exposure, and manual controls

-   Implement autofocus and flash only where supported.
-   Use CameraX APIs or Camera2 interop for exposure compensation when
    available.
-   Manual ISO, shutter speed, and white balance depend on hardware
    capability and camera configuration. Check supported modes and
    ranges before showing controls.
-   Provide `getCapabilities` so the watch can hide or disable
    unsupported settings.
-   Test front and rear cameras separately because their capabilities
    can differ.

## 9. Wear OS UI plan

Keep the main screen simple and legible:

-   Main area: live preview.
-   Primary control: shutter button.
-   Zoom: compact slider or plus/minus controls.
-   Secondary screen: flash, exposure, camera switching, and other
    settings.
-   Status: connecting, live, reconnecting, capture succeeded, or error.

Use Compose for layout and `AndroidView` to host WebRTC's
`SurfaceViewRenderer`. Create and initialize the renderer with the
WebRTC EGL context; attach the remote video track as a sink after it
arrives. Detach the sink and release renderer/EGL resources with the
appropriate lifecycle.

Illustrative Compose wrapper:

``` kotlin
@Composable
fun LiveCameraView(
    renderer: SurfaceViewRenderer,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}
```

This only embeds an already initialized renderer. It does not create the
WebRTC session or manage resource cleanup by itself.

## 10. Build milestones

### Milestone 1 --- Camera capture on phone

-   Request permission.
-   Open camera.
-   Capture and save a full-resolution image.
-   Confirm camera lifecycle works on the target phone.

### Milestone 2 --- Device connectivity

-   Put phone and watch on the same non-isolated Wi-Fi.
-   Show the phone's local address or provide a manual connection field
    on the watch.
-   Establish signaling and test reconnect behavior.

### Milestone 3 --- Live preview

-   Establish WebRTC offer/answer and ICE exchange.
-   Receive video on the watch.
-   Render it in Compose.
-   Tune resolution and frame rate.

### Milestone 4 --- Remote shutter

-   Send `capturePhoto`.
-   Save image on the phone.
-   Report success/failure to the watch.
-   Test repeated captures and disconnects.

### Milestone 5 --- Zoom

-   Read supported zoom range.
-   Add watch controls.
-   Send `setZoom` commands.
-   Update UI with the phone's applied zoom value.

### Milestone 6 --- Polish and optional features

-   Add camera switching, exposure compensation, flash, and focus if
    supported.
-   Improve reconnection and error messages.
-   Add session authentication.
-   Test battery, heat, and long-running stability.
-   Prepare a fallback recording or demo if venue Wi-Fi is unreliable.

## 11. Testing checklist

-   [ ] Camera permission denied and later granted.
-   [ ] Phone and watch connect on the target Wi-Fi.
-   [ ] Connection fails gracefully when devices are on different
    networks.
-   [ ] Video appears and remains reasonably responsive.
-   [ ] Zoom updates the phone camera and watch UI.
-   [ ] Captured photo is saved at the expected resolution.
-   [ ] Unsupported settings are hidden or reported as unavailable.
-   [ ] Screen rotation, app pause/resume, and reconnects do not leak
    camera sessions.
-   [ ] Phone app visibly indicates when camera streaming is active.
-   [ ] Commands are rejected when unauthenticated or malformed.
-   [ ] Battery and device temperature remain acceptable during a demo.

## 12. Hackathon MVP priorities

**Must have** 1. Reliable live preview. 2. Remote shutter saving a
full-resolution photo on the phone. 3. Responsive zoom. 4. Clear
connection state and graceful failure handling.

**Nice to have** - Front/rear camera switching. - Exposure compensation
and flash. - Focus control. - Capture timer and framing guides. -
Thumbnail transfer to the watch.

**Defer unless the core works** - Full manual ISO/shutter controls
across many phone models. - Internet streaming between distant
networks. - Background camera streaming. - Complex device discovery.

## 13. Demo narrative

1.  Place the phone on a tripod or stand.
2.  Open WristView on the phone and start the camera session.
3.  Connect the watch over the same Wi-Fi.
4.  Show the live preview on the watch.
5.  Zoom in and out from the watch.
6.  Trigger the shutter.
7.  Show the saved full-resolution photo on the phone.
8.  Demonstrate a supported secondary setting if time permits.

**Core product promise:** frame it, control it, and capture it---without
touching the phone.

## 14. Implementation references

Consult current official documentation before coding, especially for
version-specific APIs:

-   CameraX: https://developer.android.com/media/camera/camerax
-   Camera2: https://developer.android.com/media/camera/camera2
-   Android permissions:
    https://developer.android.com/training/permissions/requesting
-   Android foreground services:
    https://developer.android.com/develop/background-work/services/foreground-services
-   Jetpack Compose interoperability (`AndroidView`):
    https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose
-   WebRTC project and documentation: https://webrtc.org/

## 15. Definition of done

The MVP is ready when a real Android phone and Wear OS watch can connect
over the target Wi-Fi, display a live phone-camera preview on the watch,
change zoom from the watch, and trigger a full-resolution photo capture
saved on the phone---with clear status and recoverable errors.
