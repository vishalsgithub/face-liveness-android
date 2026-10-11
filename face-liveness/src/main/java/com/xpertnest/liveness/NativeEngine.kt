package com.xpertnest.liveness

import android.content.Context
import android.graphics.RectF
import java.io.File
import java.nio.ByteBuffer

/** A detected face before the threshold is applied. Bounds are in pixels of the upright image. */
internal data class DetectedFace(
  val bounds: RectF,
  val detectionConfidence: Float,
  val livenessScore: Float,
)

/**
 * Face detection + liveness engine (JNI to the shared C++ core).
 *
 * The native engine is not thread-safe, so every call is serialized. Use [shared]
 * to get the process-wide instance.
 */
internal class NativeEngine private constructor(private var handle: Long) {

  @Synchronized
  fun detectYuv420(
    y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
    width: Int, height: Int, yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
    orientation: Int, allFaces: Boolean = false,
  ): List<DetectedFace> = parse(
    nativeDetectYuv420(handle, y, u, v, width, height, yRowStride, uvRowStride, uvPixelStride,
      orientation, allFaces)
  )

  /** [pixels] must be a direct buffer of RGBA bytes. */
  @Synchronized
  fun detectRgba(
    pixels: ByteBuffer, width: Int, height: Int, bytesPerRow: Int,
    orientation: Int, allFaces: Boolean = false,
  ): List<DetectedFace> = parse(
    nativeDetectRgba(handle, pixels, width, height, bytesPerRow, orientation, allFaces)
  )

  private fun parse(values: FloatArray?): List<DetectedFace> {
    if (values == null || values.isEmpty()) throw LivenessException(-1, "Detection failed.")
    val count = values[0].toInt()
    if (count < 0) throw LivenessException(count, messageFor(count))
    return List(count) { i ->
      val o = 1 + i * 6
      DetectedFace(
        bounds = RectF(values[o], values[o + 1], values[o + 2] + 1, values[o + 3] + 1),
        detectionConfidence = values[o + 4],
        livenessScore = values[o + 5],
      )
    }
  }

  companion object {
    const val DEFAULT_LIVENESS_THRESHOLD = 0.915f

    // Bump when the bundled models change, so stale copies are replaced.
    private const val MODELS_VERSION = "v1"
    private val MODEL_FILES = listOf(
      "detection/detection.param",
      "detection/detection.bin",
      "live/config.json",
      "live/model_1.param",
      "live/model_1.bin",
      "live/model_2.param",
      "live/model_2.bin",
    )

    @Volatile private var instance: NativeEngine? = null

    init {
      System.loadLibrary("face_liveness_android")
    }

    /** Process-wide engine, created (and models unpacked) on first use. */
    fun shared(context: Context): NativeEngine {
      instance?.let { return it }
      synchronized(this) {
        instance?.let { return it }
        val dir = prepareModels(context.applicationContext)
        val handle = nativeCreate()
        val ret = nativeLoad(handle, dir.absolutePath)
        if (ret != 0) {
          nativeDestroy(handle)
          throw LivenessException(ret, messageFor(ret))
        }
        return NativeEngine(handle).also { instance = it }
      }
    }

    /** Copies the models from the package assets to app storage (once). */
    private fun prepareModels(context: Context): File {
      val dir = File(context.filesDir, "face_liveness_models_$MODELS_VERSION")
      // Assets may be compressed (no file descriptor / length), so a marker records a full copy.
      val complete = File(dir, ".complete")
      if (complete.exists()) return dir
      for (name in MODEL_FILES) {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        context.assets.open("face_liveness/$name").use { input ->
          tmp.outputStream().use { input.copyTo(it) }
        }
        if (!tmp.renameTo(file)) throw LivenessException(-1, "Could not unpack $name.")
      }
      complete.createNewFile()
      return dir
    }

    fun messageFor(code: Int): String = when (code) {
      -100 -> "Models are not loaded."
      -101 -> "Invalid image."
      -102 -> "Invalid liveness model config."
      -103 -> "Failed to load the face detection model."
      -104 -> "Failed to load the liveness models."
      else -> "Engine error $code."
    }

    /** EXIF orientation for a frame that must be rotated clockwise by [degrees]. */
    fun orientationForRotation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
      90 -> 6
      180 -> 3
      270 -> 8
      else -> 1
    }

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeLoad(handle: Long, modelDir: String): Int
    @JvmStatic private external fun nativeDetectYuv420(
      handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
      width: Int, height: Int, yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
      orientation: Int, allFaces: Boolean,
    ): FloatArray?
    @JvmStatic private external fun nativeDetectRgba(
      handle: Long, pixels: ByteBuffer, width: Int, height: Int, bytesPerRow: Int,
      orientation: Int, allFaces: Boolean,
    ): FloatArray?
  }
}
