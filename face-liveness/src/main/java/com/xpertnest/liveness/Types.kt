package com.xpertnest.liveness

import android.graphics.RectF

/** A detected face and its liveness result. */
data class LivenessFace(
    /** Face bounds in pixels of the upright image. */
    val bounds: RectF,
    /** Face detector confidence in [0, 1]. */
    val detectionConfidence: Float,
    /** Probability that the face is real (not a photo, screen or mask) in [0, 1]. */
    val livenessScore: Float,
    /** `livenessScore >= livenessThreshold`. */
    val isLive: Boolean,
)

/** Result of one analysed camera frame. */
data class LivenessResult(
    /** Largest face in the frame, or null when no face was found. */
    val face: LivenessFace?,
    /** Face rectangle in the camera view's coordinates (pixels), null when no face. */
    val faceFrameInView: RectF?,
    /** Size of the upright camera frame that [LivenessFace.bounds] refers to. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Inference time for this frame. */
    val processingTimeMs: Float,
    /** Number of consecutive frames (including this one) with a live face. */
    val consecutiveLiveFrames: Int,
    /** True once a face has been live for `requiredLiveFrames` consecutive frames. */
    val isVerified: Boolean,
)

/** Why the camera view could not run. */
enum class LivenessErrorType { PERMISSION_DENIED, CAMERA_UNAVAILABLE, ENGINE_ERROR }

data class LivenessError(val type: LivenessErrorType, val message: String)

/** Thrown when the native engine reports an error. */
class LivenessException(val code: Int, message: String) : Exception(message)

enum class LensFacing { FRONT, BACK }
