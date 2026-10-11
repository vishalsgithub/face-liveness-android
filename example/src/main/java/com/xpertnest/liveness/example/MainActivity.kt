package com.xpertnest.liveness.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xpertnest.liveness.FaceLiveness
import com.xpertnest.liveness.FaceLivenessCameraView
import com.xpertnest.liveness.LivenessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var camera: FaceLivenessCameraView
    private lateinit var title: TextView
    private lateinit var score: TextView
    private lateinit var caption: TextView
    private lateinit var progress: ProgressBar

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.start() else showError("Camera permission denied.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        camera = findViewById(R.id.camera)
        title = findViewById(R.id.title)
        score = findViewById(R.id.score)
        caption = findViewById(R.id.caption)
        progress = findViewById(R.id.progress)
        progress.max = camera.requiredLiveFrames

        camera.onResult = ::render
        camera.onVerified = { result ->
            Toast.makeText(this, "Verified real face (%.3f)".format(result.face!!.livenessScore), Toast.LENGTH_SHORT).show()
        }
        camera.onError = { error -> showError(error.message) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
        }

        runPhotoSelfTest()
    }

    private fun render(result: LivenessResult) {
        val face = result.face
        val (text, color) = when {
            face == null -> "Position your face in the frame" to Color.rgb(0x8E, 0x8E, 0x93)
            result.isVerified -> "✓ Real face" to Color.rgb(0x34, 0xC7, 0x59)
            face.isLive -> "Checking…" to Color.rgb(0x0A, 0x84, 0xFF)
            else -> "Spoof detected" to Color.rgb(0xFF, 0x3B, 0x30)
        }
        title.text = text
        title.setTextColor(color)
        score.text = face?.let { "%.3f".format(it.livenessScore) } ?: ""
        progress.progress = minOf(result.consecutiveLiveFrames, progress.max)
        caption.text = "Threshold %.3f · %d ms / frame".format(camera.livenessThreshold, result.processingTimeMs.toInt())
    }

    private fun showError(message: String) {
        title.text = message
        title.setTextColor(Color.rgb(0xFF, 0x95, 0x00))
    }

    /** Checks the bundled sample photo with the still-image API. */
    private fun runPhotoSelfTest() {
        val selfTest = findViewById<TextView>(R.id.selfTest)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    val bitmap = assets.open("face.jpg").use { BitmapFactory.decodeStream(it) }
                    val face = FaceLiveness.detect(this@MainActivity, bitmap).firstOrNull()
                    if (face == null) "Sample photo: no face"
                    else "Sample photo: ${if (face.isLive) "real" else "spoof"} %.3f".format(face.livenessScore)
                } catch (e: Exception) {
                    "Sample photo: ${e.message}"
                }
            }
            Log.i("FaceLivenessExample", text)
            selfTest.text = text
        }
    }
}
