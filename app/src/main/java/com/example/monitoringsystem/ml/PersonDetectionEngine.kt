package com.example.monitoringsystem.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

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

class PersonDetectionEngine(private val context: Context) {

    // Model configuration
    private val modelInputSize = 300
    private val maxDetections = 10
    private val confidenceThreshold = 0.6f
    private val nmsThreshold = 0.6f
    private val personClassId = 0 // Person class in COCO dataset

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var inputBuffer: ByteBuffer? = null

    // Output arrays
    private var outputBoxes: Array<Array<FloatArray>>? = null
    private var outputClasses: Array<FloatArray>? = null
    private var outputScores: Array<FloatArray>? = null
    private var outputCount: FloatArray? = null

    init {
        setupModel()
    }

    private fun setupModel() {
        try {
            Log.d("InCarMonitoring", "Loading TensorFlow Lite model...")

            // Load model
            val modelBuffer = FileUtil.loadMappedFile(context, "models/ssd_mobilenet_v1.tflite")

            // Setup interpreter options
            val options = Interpreter.Options().apply {
                numThreads = 4

                // Try to use GPU if available
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate!!)
                    Log.d("InCarMonitoring", "GPU delegate enabled")
                } else {
                    Log.d("InCarMonitoring", "GPU delegate not supported, using CPU")
                }
            }

            interpreter = Interpreter(modelBuffer, options)

            // Log input tensor info
            val inputTensor = interpreter!!.getInputTensor(0)
            Log.d("InCarMonitoring", "Input tensor shape: ${inputTensor.shape().contentToString()}")
            Log.d("InCarMonitoring", "Input tensor type: ${inputTensor.dataType()}")

            // Check ALL output tensors
            for (i in 0 until interpreter!!.outputTensorCount) {
                val outputTensor = interpreter!!.getOutputTensor(i)
                Log.d("InCarMonitoring", "Output tensor $i shape: ${outputTensor.shape().contentToString()}")
                Log.d("InCarMonitoring", "Output tensor $i type: ${outputTensor.dataType()}")
            }

            // FIXED: Calculate buffer size based on actual tensor type
            val inputShape = inputTensor.shape() // Should be [1, 300, 300, 3]
            val inputTensorSize = when (inputTensor.dataType()) {
                org.tensorflow.lite.DataType.UINT8 -> {
                    // UInt8: 1 byte per value
                    inputShape[1] * inputShape[2] * inputShape[3] // 300 * 300 * 3 = 270,000
                }
                org.tensorflow.lite.DataType.FLOAT32 -> {
                    // Float32: 4 bytes per value
                    inputShape[1] * inputShape[2] * inputShape[3] * 4 // 300 * 300 * 3 * 4 = 1,080,000
                }
                else -> {
                    Log.e("InCarMonitoring", "Unsupported input tensor type: ${inputTensor.dataType()}")
                    300 * 300 * 3 // Default fallback
                }
            }

            Log.d("InCarMonitoring", "Creating ByteBuffer of size: $inputTensorSize bytes")

            inputBuffer = ByteBuffer.allocateDirect(inputTensorSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // FIXED: Initialize output arrays based on actual tensor shapes
            val outputTensor0 = interpreter!!.getOutputTensor(0)
            val outputTensor1 = interpreter!!.getOutputTensor(1)
            val outputTensor2 = interpreter!!.getOutputTensor(2)
            val outputTensor3 = interpreter!!.getOutputTensor(3)

            // Initialize output arrays
            outputBoxes = Array(outputTensor0.shape()[0]) { Array(outputTensor0.shape()[1]) { FloatArray(outputTensor0.shape()[2]) } }
            outputClasses = Array(outputTensor1.shape()[0]) { FloatArray(outputTensor1.shape()[1]) }
            outputScores = Array(outputTensor2.shape()[0]) { FloatArray(outputTensor2.shape()[1]) }
            outputCount = FloatArray(outputTensor3.shape()[0])

            Log.d("InCarMonitoring", "TensorFlow Lite model loaded successfully")

        } catch (e: Exception) {
            Log.e("InCarMonitoring", "Failed to load TensorFlow Lite model: ${e.message}", e)
        }
    }

    suspend fun detectPersons(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val interpreter = this@PersonDetectionEngine.interpreter
            if (interpreter == null) {
                Log.e("InCarMonitoring", "Interpreter not initialized")
                return@withContext DetectionResult(
                    detections = emptyList(),
                    inferenceTimeMs = System.currentTimeMillis() - startTime,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }

            // Preprocess image
            val preprocessedBitmap = preprocessImage(bitmap)
            convertBitmapToByteBuffer(preprocessedBitmap)

            // FIXED: Use Map for outputs
            val inputsMap = arrayOf(inputBuffer)
            val outputsMap = mapOf(
                0 to outputBoxes,
                1 to outputClasses,
                2 to outputScores,
                3 to outputCount
            )

            interpreter.runForMultipleInputsOutputs(inputsMap, outputsMap)

            Log.d("InCarMonitoring", "Inference completed, checking outputs...")

            // Debug the actual output values
            Log.d("InCarMonitoring", "Output count: ${outputCount?.get(0)}")
            Log.d("InCarMonitoring", "First few scores: ${outputScores?.get(0)?.take(5)?.toString()}")
            Log.d("InCarMonitoring", "First few classes: ${outputClasses?.get(0)?.take(5)?.toString()}")

            // Post-process results
            val detections = postProcessResults(bitmap.width, bitmap.height)

            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d("InCarMonitoring", "TensorFlow Lite inference completed in ${inferenceTime}ms, found ${detections.size} people")

            DetectionResult(
                detections = detections,
                inferenceTimeMs = inferenceTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

        } catch (e: Exception) {
            Log.e("InCarMonitoring", "TensorFlow Lite inference error: ${e.message}", e)
            DetectionResult(
                detections = emptyList(),
                inferenceTimeMs = System.currentTimeMillis() - startTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // Resize bitmap to model input size
        return Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer?.let { buffer ->
            buffer.rewind()

            val pixels = IntArray(modelInputSize * modelInputSize)
            bitmap.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

            // Check if model expects UInt8 or Float32
            val inputTensor = interpreter?.getInputTensor(0)

            when (inputTensor?.dataType()) {
                org.tensorflow.lite.DataType.UINT8 -> {
                    // UInt8 input: values 0-255, no normalization
                    Log.d("InCarMonitoring", "Converting to UInt8 format")
                    for (pixel in pixels) {
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        buffer.put(r.toByte())
                        buffer.put(g.toByte())
                        buffer.put(b.toByte())
                    }
                }
                org.tensorflow.lite.DataType.FLOAT32 -> {
                    // Float32 input: normalized values
                    Log.d("InCarMonitoring", "Converting to Float32 format")
                    for (pixel in pixels) {
                        val r = ((pixel shr 16) and 0xFF) / 127.5f - 1.0f
                        val g = ((pixel shr 8) and 0xFF) / 127.5f - 1.0f
                        val b = (pixel and 0xFF) / 127.5f - 1.0f

                        buffer.putFloat(r)
                        buffer.putFloat(g)
                        buffer.putFloat(b)
                    }
                }
                else -> {
                    Log.e("InCarMonitoring", "Unknown tensor data type: ${inputTensor?.dataType()}")
                }
            }

            Log.d("InCarMonitoring", "ByteBuffer position after conversion: ${buffer.position()}")
        }
    }

    private fun postProcessResults(originalWidth: Int, originalHeight: Int): List<PersonDetection> {
        val detections = mutableListOf<PersonDetection>()

        val boxes = outputBoxes?.get(0) ?: return emptyList()
        val classes = outputClasses?.get(0) ?: return emptyList()
        val scores = outputScores?.get(0) ?: return emptyList()
        val numDetections = outputCount?.get(0)?.toInt() ?: 0

        Log.d("InCarMonitoring", "=== RAW MODEL OUTPUTS ===")
        Log.d("InCarMonitoring", "Number of detections: $numDetections")

        // Debug first few raw outputs
        for (i in 0 until minOf(5, maxDetections)) {
            Log.d("InCarMonitoring", "Detection $i:")
            Log.d("InCarMonitoring", "  Raw score: ${scores[i]}")
            Log.d("InCarMonitoring", "  Raw class: ${classes[i]}")
            Log.d("InCarMonitoring", "  Raw box: [${boxes[i][0]}, ${boxes[i][1]}, ${boxes[i][2]}, ${boxes[i][3]}]")
        }

        // Check if we have any scores above a very low threshold
        val allScores = scores.take(maxDetections)
        val maxScore = allScores.maxOrNull() ?: 0f
        val scoresAbove01 = allScores.count { it > 0.1f }
        val scoresAbove05 = allScores.count { it > 0.5f }

        Log.d("InCarMonitoring", "Max score: $maxScore")
        Log.d("InCarMonitoring", "Scores > 0.1: $scoresAbove01")
        Log.d("InCarMonitoring", "Scores > 0.5: $scoresAbove05")

        for (i in 0 until min(numDetections, maxDetections)) {
            val confidence = scores[i]
            val classId = classes[i].toInt()

            // Filter for person class and confidence threshold
            if (classId == personClassId && confidence >= confidenceThreshold) {
                val box = boxes[i]

                // Convert normalized coordinates to actual coordinates
                // COCO format: [y_min, x_min, y_max, x_max] normalized to [0, 1]
                val yMin = box[0] * originalHeight
                val xMin = box[1] * originalWidth
                val yMax = box[2] * originalHeight
                val xMax = box[3] * originalWidth

                val boundingBox = RectF(
                    max(0f, xMin),
                    max(0f, yMin),
                    min(originalWidth.toFloat(), xMax),
                    min(originalHeight.toFloat(), yMax)
                )

                // Validate bounding box
                if (boundingBox.width() > 10 && boundingBox.height() > 10) {
                    detections.add(
                        PersonDetection(
                            boundingBox = boundingBox,
                            confidence = confidence,
                            trackingId = null
                        )
                    )

                    Log.d("InCarMonitoring", "Person detected: confidence=${confidence}, box=${boundingBox}")
                }
            }
        }

        // Apply Non-Maximum Suppression to remove overlapping detections
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<PersonDetection>): List<PersonDetection> {
        if (detections.size <= 1) return detections

        val sortedDetections = detections.sortedByDescending { it.confidence }
        val finalDetections = mutableListOf<PersonDetection>()

        for (i in sortedDetections.indices) {
            val currentDetection = sortedDetections[i]
            var shouldKeep = true

            for (j in finalDetections.indices) {
                val existingDetection = finalDetections[j]
                val iou = calculateIoU(currentDetection.boundingBox, existingDetection.boundingBox)

                if (iou > nmsThreshold) {
                    shouldKeep = false
                    break
                }
            }

            if (shouldKeep) {
                finalDetections.add(currentDetection)
            }
        }

        Log.d("InCarMonitoring", "NMS: ${detections.size} -> ${finalDetections.size} detections")
        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            Log.d("InCarMonitoring", "TensorFlow Lite resources released")
        } catch (e: Exception) {
            Log.e("InCarMonitoring", "Error closing TensorFlow Lite resources: ${e.message}")
        }
    }
}