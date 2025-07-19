package com.example.monitoringsystem.ml

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PersonDetection(
    val boundingBox: RectF,
    val confidence: Float,
    val trackingId: Int? = null
)

data class DetectionResult(
    val detections: List<PersonDetection>,
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int
)

class PersonDetectionEngine {
    private val objectDetector: ObjectDetector

    init {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE) // Optimized for video
            .enableClassification() // Enable classification to filter for people
            .enableMultipleObjects() // Detect multiple people
            .build()

        objectDetector = ObjectDetection.getClient(options)
    }

    suspend fun detectPersons(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val detections = processImage(inputImage)
            Log.d("InCarMonitoring", "Bitmap: ${bitmap.width}x${bitmap.height}, bytes: ${bitmap.byteCount}")

            // Log all detected objects to see what ML Kit is finding
            Log.d("InCarMonitoring", "Total detections: ${detections.size}")
            detections.forEachIndexed { index, detection ->
                Log.d("InCarMonitoring", "Detection $index: ${detection.labels.size}")
                detection.labels.forEach { label ->
                    Log.d("InCarMonitoring", "  Label: '${label.text}', Confidence: ${label.confidence}")
                }
            }

            // Filter for people only (ML Kit labels people as "Person")
            val personDetections = detections
                .filter { detection ->
                    detection.labels.any { label ->
                        label.text.equals("People", ignoreCase = true) &&
                                label.confidence > 0.5f // Confidence threshold
                    }
                }
                .map { detection ->
                    val confidence = detection.labels
                        .firstOrNull { it.text.equals("People", ignoreCase = true) }
                        ?.confidence ?: 0f

                    PersonDetection(
                        boundingBox = RectF(detection.boundingBox),
                        confidence = confidence,
                        trackingId = detection.trackingId
                    )
                }

            Log.d("InCarMonitoring", "detectedPersons: $personDetections")

            val inferenceTime = System.currentTimeMillis() - startTime

            DetectionResult(
                detections = personDetections,
                inferenceTimeMs = inferenceTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        } catch (e: Exception) {
            DetectionResult(
                detections = emptyList(),
                inferenceTimeMs = System.currentTimeMillis() - startTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        }
    }

    private suspend fun processImage(inputImage: InputImage): List<DetectedObject> =
        suspendCancellableCoroutine { continuation ->
            objectDetector.process(inputImage)
                .addOnSuccessListener { detectedObjects ->
                    continuation.resume(detectedObjects)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }

    fun close() {
        objectDetector.close()
    }
}