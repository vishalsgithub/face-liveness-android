package com.xpertnest.liveness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera preview (CameraX) that checks every frame for a real face.
 *
 * The camera runs while the view is attached, [active] and the host Activity /
 * Fragment is resumed. Request the CAMERA permission before showing it, then call
 * [start] (or re-attach the view) once it is granted.
 *
 * ```kotlin
 * cameraView.onVerified = { result -> /* real face */ }
 * ```
 */
class FaceLivenessCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), LifecycleOwner {

    /** Called on the main thread for every processed frame. */
    var onResult: ((LivenessResult) -> Unit)? = null

    /** Called once each time a face becomes verified (live for [requiredLiveFrames] frames in a row). */
    var onVerified: ((LivenessResult) -> Unit)? = null

    /** Called when the camera or the engine can't be started. */
    var onError: ((LivenessError) -> Unit)? = null

    /** Faces with `livenessScore >= livenessThreshold` count as live. Default 0.915. */
    var livenessThreshold = FaceLiveness.DEFAULT_LIVENESS_THRESHOLD

    /** Consecutive live frames before [onVerified]. Default 8. */
    var requiredLiveFrames = 8
        set(value) { field = maxOf(1, value) }

    var lensFacing = LensFacing.FRONT
        set(value) {
            if (field == value) return
            field = value
            if (running) { stopCamera(); updateRunning() }
        }

    /** Draw a green / red box around the face. Default true. */
    var showFaceOverlay = true
        set(value) { field = value; overlay.invalidate() }

    /** Pause the camera without removing the view. Default true. */
    var active = true
        set(value) { field = value; updateRunning() }

    val isRunning: Boolean get() = running

    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val overlay = OverlayView(context)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var running = false
    private var attached = false
    private var hostResumed = true
    private var hostLifecycle: Lifecycle? = null

    // Touched on the analysis thread only.
    private var consecutiveLive = 0
    private var verifiedNotified = false

    private val hostObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { hostResumed = true; updateRunning() }
        override fun onPause(owner: LifecycleOwner) { hostResumed = false; updateRunning() }
        override fun onDestroy(owner: LifecycleOwner) { release() }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        context.obtainStyledAttributes(attrs, R.styleable.FaceLivenessCameraView).apply {
            try {
                livenessThreshold = getFloat(R.styleable.FaceLivenessCameraView_livenessThreshold, livenessThreshold)
                requiredLiveFrames = getInt(R.styleable.FaceLivenessCameraView_requiredLiveFrames, requiredLiveFrames)
                lensFacing = if (getInt(R.styleable.FaceLivenessCameraView_lensFacing, 0) == 1) LensFacing.BACK else LensFacing.FRONT
                showFaceOverlay = getBoolean(R.styleable.FaceLivenessCameraView_showFaceOverlay, true)
                active = getBoolean(R.styleable.FaceLivenessCameraView_autoStart, true)
            } finally {
                recycle()
            }
        }
    }

    /** Starts (or restarts) the camera, e.g. after the CAMERA permission was granted. */
    fun start() {
        active = true
        if (running) return
        updateRunning()
    }

    /** Stops the camera. */
    fun stop() {
        active = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        hostLifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also {
            hostResumed = it.currentState.isAtLeast(Lifecycle.State.RESUMED)
            it.addObserver(hostObserver)
        }
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        attached = false
        hostLifecycle?.removeObserver(hostObserver)
        hostLifecycle = null
        updateRunning()
        super.onDetachedFromWindow()
    }

    private fun release() {
        attached = false
        stopCamera()
    }

    private fun updateRunning() {
        val shouldRun = active && attached && hostResumed
        if (shouldRun && !running) startCamera() else if (!shouldRun && running) stopCamera()
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke(LivenessError(LivenessErrorType.PERMISSION_DENIED, "Camera permission not granted."))
            return
        }
        running = true
        consecutiveLive = 0
        verifiedNotified = false
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (e: Exception) {
                running = false
                onError?.invoke(LivenessError(LivenessErrorType.CAMERA_UNAVAILABLE, e.message ?: "Could not start the camera."))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val wantFront = lensFacing == LensFacing.FRONT
        val selector = when {
            wantFront && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA
        val rotation = display?.rotation ?: Surface.ROTATION_0

        // 4:3 for both so the analysed frame matches what the preview shows.
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val newPreview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // 640x480: enough for the 192px detector and the 80px liveness crops.
        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()

        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        newAnalysis.setAnalyzer(executor) { image -> analyze(image, mirrored) }

        provider.unbind(*listOfNotNull(preview, analysis).toTypedArray())
        provider.bindToLifecycle(this, selector, newPreview, newAnalysis)
        preview = newPreview
        analysis = newAnalysis
    }

    private fun stopCamera() {
        running = false
        val provider = cameraProvider
        val useCases = listOfNotNull(preview, analysis)
        if (provider != null && useCases.isNotEmpty()) provider.unbind(*useCases.toTypedArray())
        analysis?.clearAnalyzer()
        preview = null
        analysis = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        overlay.update(null, false)
    }

    private fun analyze(image: ImageProxy, mirrored: Boolean) {
        try {
            val engine = try {
                NativeEngine.shared(context)
            } catch (e: Exception) {
                post {
                    onError?.invoke(LivenessError(LivenessErrorType.ENGINE_ERROR, e.message ?: "Failed to load the models."))
                }
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            val planes = image.planes
            val begin = SystemClock.elapsedRealtimeNanos()
            val faces = engine.detectYuv420(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                image.width, image.height,
                planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                NativeEngine.orientationForRotation(rotation),
            )
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000f

            val swap = rotation == 90 || rotation == 270
            val uprightW = if (swap) image.height else image.width
            val uprightH = if (swap) image.width else image.height
            val face = faces.firstOrNull()?.toLivenessFace(livenessThreshold)
            val live = face?.isLive == true
            if (live) consecutiveLive++ else { consecutiveLive = 0; verifiedNotified = false }
            val verified = consecutiveLive >= requiredLiveFrames
            val notifyVerified = verified && !verifiedNotified
            if (notifyVerified) verifiedNotified = true
            val consecutive = consecutiveLive

            post {
                if (!running) return@post
                val frame = face?.let { toViewRect(it.bounds, uprightW, uprightH, mirrored) }
                overlay.update(frame, live)
                val result = LivenessResult(face, frame, uprightW, uprightH, elapsedMs, consecutive, verified)
                onResult?.invoke(result)
                if (notifyVerified) onVerified?.invoke(result)
            }
        } catch (e: Exception) {
            // A bad frame should not stop the stream.
        } finally {
            image.close()
        }
    }

    /** Maps an upright-image rect to view pixels for PreviewView's FILL_CENTER (+ mirroring). */
    private fun toViewRect(bounds: RectF, imageW: Int, imageH: Int, mirrored: Boolean): RectF {
        val scale = maxOf(width / imageW.toFloat(), height / imageH.toFloat())
        val dx = (width - imageW * scale) / 2f
        val dy = (height - imageH * scale) / 2f
        val left = if (mirrored) imageW - bounds.right else bounds.left
        val right = if (mirrored) imageW - bounds.left else bounds.right
        return RectF(left * scale + dx, bounds.top * scale + dy, right * scale + dx, bounds.bottom * scale + dy)
    }

    private inner class OverlayView(context: Context) : View(context) {
        private var rect: RectF? = null
        private var live = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }

        fun update(rect: RectF?, live: Boolean) {
            this.rect = rect
            this.live = live
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = rect ?: return
            if (!showFaceOverlay) return
            paint.color = if (live) Color.rgb(0x34, 0xC7, 0x59) else Color.rgb(0xFF, 0x3B, 0x30)
            val radius = r.width() / 12f
            canvas.drawRoundRect(r, radius, radius, paint)
        }
    }
}
