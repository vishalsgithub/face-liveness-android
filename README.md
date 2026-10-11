# Face Liveness for Android

[![](https://jitpack.io/v/vishalsgithub/face-liveness-android.svg)](https://jitpack.io/#vishalsgithub/face-liveness-android)

On-device **face liveness (anti-spoofing) detection** for Android. It checks whether the face in front of the camera is real or a presentation attack (printed photo, screen replay, mask) from ordinary camera frames. The user doesn't have to blink or turn their head, and nothing leaves the device.

- `FaceLivenessCameraView`: a ready-made CameraX view with a live verdict, usable from XML or code
- `FaceLiveness.detect()`: checks photos (`Bitmap` or `Uri`)
- Kotlin and Java, Android 7.0+ (API 24)
- Models and native libraries are included; apps don't need the NDK

## Installation

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency to your app module:

```kotlin
dependencies {
    implementation("com.github.vishalsgithub:face-liveness-android:1.0.0")
}
```

The library declares the `CAMERA` permission; your app requests it at runtime (see below).

## Usage

### Camera view

```xml
<com.xpertnest.liveness.FaceLivenessCameraView
    android:id="@+id/camera"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:livenessThreshold="0.915"
    app:requiredLiveFrames="8"
    app:lensFacing="front" />
```

```kotlin
val camera = findViewById<FaceLivenessCameraView>(R.id.camera)

camera.onVerified = { result ->
    // A real face was seen for 8 consecutive frames.
    Log.d("Liveness", "Real face ${result.face?.livenessScore}")
}
camera.onResult = { result ->
    // Every processed frame: result.face is null when no face is visible.
}
camera.onError = { error -> Log.w("Liveness", "${error.type}: ${error.message}") }

// Ask for the camera permission, then start the camera.
val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) camera.start()
}
requestCamera.launch(Manifest.permission.CAMERA)
```

The view follows the lifecycle of its Activity or Fragment: the camera pauses in the background and resumes automatically.

| Property / XML attribute | Default | Meaning |
| --- | --- | --- |
| `livenessThreshold` | `0.915` | Minimum score for a face to count as live |
| `requiredLiveFrames` | `8` | Consecutive live frames before `onVerified` is called |
| `lensFacing` | `FRONT` | `FRONT` or `BACK` |
| `showFaceOverlay` | `true` | Draw a green or red box around the face |
| `autoStart` (XML) / `active` | `true` | Start the camera automatically; `stop()` / `start()` to control it |

Each `LivenessResult` contains:
- `face`: `bounds`, `livenessScore`, `detectionConfidence` and `isLive`, or `null` when no face is visible.
- `faceFrameInView`, `isVerified`, `consecutiveLiveFrames`, `imageWidth`, `imageHeight` and `processingTimeMs`.

**Jetpack Compose:** wrap the view in `AndroidView`:

```kotlin
AndroidView(factory = { FaceLivenessCameraView(it).apply { onVerified = { /* ... */ } } })
```

### Photos

```kotlin
val faces = withContext(Dispatchers.Default) {
    FaceLiveness.detect(context, uri)        // or FaceLiveness.detect(context, bitmap)
}
faces.firstOrNull()?.let { face ->
    Log.d("Liveness", "${face.isLive} ${face.livenessScore} ${face.bounds}")
}
```

`detect` runs on the calling thread, so call it from a background thread. The EXIF orientation of `Uri` images is applied. Optional arguments: `livenessThreshold` and `allFaces`.

A single frame is noisy. For a verification flow, rely on `onVerified`, which requires several consecutive live frames.

## How it works

1. **Face detection**: an SSD-style detector finds faces in a 192 px version of the frame.
2. **Liveness**: two MiniFASNet models look at the face at 2.7× and 4.0× context (resized to 80×80). Each outputs a "real face" probability, and the two are averaged.

A shared C++ engine runs both models on [ncnn](https://github.com/Tencent/ncnn), called through JNI. Camera frames are analysed on a background thread at 640×480.

## Example app

The [example](example) module has a camera screen and a photo self-test:

```sh
./gradlew :example:installRelease
```

Building the library from source downloads the prebuilt ncnn engine (about 21 MB) into `~/.face_liveness`, and needs NDK 28.2.

## Related packages

- iOS (Swift Package Manager): [vishalsgithub/face-liveness](https://github.com/vishalsgithub/face-liveness)
- Flutter: [face_liveness](https://pub.dev/packages/face_liveness)
- React Native: [@solankivishal/react-native-face-liveness](https://www.npmjs.com/package/@solankivishal/react-native-face-liveness)

## License

Apache-2.0. See [LICENSE](LICENSE). ncnn is distributed under BSD-3-Clause.
