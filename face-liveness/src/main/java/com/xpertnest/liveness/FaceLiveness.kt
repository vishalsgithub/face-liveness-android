package com.xpertnest.liveness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Still-image face liveness detection.
 *
 * All methods run inference synchronously: call them from a background thread
 * (e.g. `withContext(Dispatchers.Default)`).
 */
object FaceLiveness {

    /** Default liveness threshold, same as the original Android sample. */
    const val DEFAULT_LIVENESS_THRESHOLD = NativeEngine.DEFAULT_LIVENESS_THRESHOLD

    /** Loads the models ahead of time so the first detection is fast. */
    @WorkerThread
    fun preload(context: Context) {
        NativeEngine.shared(context)
    }

    /**
     * Detects faces in [bitmap]. Returns the largest face first; only the largest
     * face is evaluated unless [allFaces].
     */
    @WorkerThread
    @JvmStatic
    @JvmOverloads
    fun detect(
        context: Context,
        bitmap: Bitmap,
        livenessThreshold: Float = DEFAULT_LIVENESS_THRESHOLD,
        allFaces: Boolean = false,
    ): List<LivenessFace> = detect(context, bitmap, ExifInterface.ORIENTATION_NORMAL, livenessThreshold, allFaces)

    /**
     * Detects faces in an image file or content URI (JPEG, PNG, HEIF, ...).
     * The EXIF orientation is applied.
     */
    @WorkerThread
    @JvmStatic
    @JvmOverloads
    fun detect(
        context: Context,
        uri: Uri,
        livenessThreshold: Float = DEFAULT_LIVENESS_THRESHOLD,
        allFaces: Boolean = false,
    ): List<LivenessFace> {
        val orientation = open(context, uri).use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .coerceIn(1, 8)
        val bitmap = open(context, uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw LivenessException(-101, "Could not decode the image at $uri.")
        try {
            return detect(context, bitmap, orientation, livenessThreshold, allFaces)
        } finally {
            bitmap.recycle()
        }
    }

    private fun detect(
        context: Context,
        bitmap: Bitmap,
        orientation: Int,
        livenessThreshold: Float,
        allFaces: Boolean,
    ): List<LivenessFace> {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw LivenessException(-101, "Unsupported bitmap.")
        try {
            // ARGB_8888 bitmaps are RGBA in memory.
            val pixels = ByteBuffer.allocateDirect(argb.rowBytes * argb.height)
            argb.copyPixelsToBuffer(pixels)
            return NativeEngine.shared(context)
                .detectRgba(pixels, argb.width, argb.height, argb.rowBytes, orientation, allFaces)
                .map { it.toLivenessFace(livenessThreshold) }
        } finally {
            if (argb !== bitmap) argb.recycle()
        }
    }

    private fun open(context: Context, uri: Uri): InputStream = when (uri.scheme) {
        null -> File(uri.toString()).inputStream()
        else -> context.contentResolver.openInputStream(uri)
            ?: throw LivenessException(-101, "Could not open $uri.")
    }
}

internal fun DetectedFace.toLivenessFace(threshold: Float) = LivenessFace(
    bounds = bounds,
    detectionConfidence = detectionConfidence,
    livenessScore = livenessScore,
    isLive = livenessScore >= threshold,
)
