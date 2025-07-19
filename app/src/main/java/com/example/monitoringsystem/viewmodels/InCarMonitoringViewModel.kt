package com.example.monitoringsystem.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.monitoringsystem.ml.DetectionResult
import com.example.monitoringsystem.ml.PersonDetectionEngine
import com.example.monitoringsystem.ml.VideoFrameExtractor
import com.example.monitoringsystem.network.AISummaryGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InCarMonitoringUiState(
    val player: ExoPlayer? = null,
    val isProcessing: Boolean = false,
    val isGeneratingSummary: Boolean = false,
    val frameCount: Int = 0,
    val summary: String = "",
    val videoSource: String = "Sample Video",
    val selectedVideoUri: Uri? = null,
    val hasPartialAccess: Boolean = false,
    val accessType: String = "None",
    val detectionResult: DetectionResult? = null,
    val detectionStats: DetectionStats = DetectionStats()
)

data class DetectionStats(
    val totalDetections: Int = 0,
    val averageInferenceTime: Float = 0f,
    val peopleDetectedCount: Int = 0,
    val lastDetectionTime: Long = 0L
)

class InCarMonitoringViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(InCarMonitoringUiState())
    val uiState: StateFlow<InCarMonitoringUiState> = _uiState.asStateFlow()

    private var videoPickerLauncher: ActivityResultLauncher<Intent>? = null
    private var personDetectionEngine: PersonDetectionEngine? = null
    private var videoFrameExtractor: VideoFrameExtractor? = null
    private var aiSummaryGenerator: AISummaryGenerator? = null
    private var playerView: PlayerView? = null

    fun setVideoPickerLauncher(launcher: ActivityResultLauncher<Intent>) {
        videoPickerLauncher = launcher
    }

    fun setPlayerView(view: PlayerView) {
        playerView = view
    }

    fun initializePlayer(context: Context) {
        val player = ExoPlayer.Builder(context)
            .build()

        // Initialize ML components
        personDetectionEngine = PersonDetectionEngine(context)
        videoFrameExtractor = VideoFrameExtractor()

        // Initialize AI Summary Generator
        aiSummaryGenerator = AISummaryGenerator()

        // Load default video or selected video
        val uri = _uiState.value.selectedVideoUri
            ?: Uri.parse("android.resource://${context.packageName}/raw/in_car_sample_video")

        loadVideo(player, uri, context)
        player.repeatMode = Player.REPEAT_MODE_ALL

        _uiState.value = _uiState.value.copy(player = player)
    }


    fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        videoPickerLauncher?.launch(intent)
    }

    fun handleVideoSelection(uri: Uri?, context: Context) {
        uri?.let {
            _uiState.value = _uiState.value.copy(
                selectedVideoUri = it,
                videoSource = "Selected Video"
            )
            _uiState.value.player?.let { player ->
                loadVideo(player, it, context)
            }
        }
    }

    private fun loadVideo(player: ExoPlayer, uri: Uri, context: Context) {
        try {
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
        } catch (e: Exception) {
            // Fallback to default video if loading fails
            val fallbackUri = Uri.parse("android.resource://${context.packageName}/raw/in_car_sample_video")
            val mediaItem = MediaItem.fromUri(fallbackUri)
            player.setMediaItem(mediaItem)
            player.prepare()
            _uiState.value = _uiState.value.copy(
                videoSource = "Sample Video (Fallback)"
            )
        }
    }

    fun startMonitoring() {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            frameCount = 0,
            summary = "",
            detectionResult = null,
            detectionStats = DetectionStats()
        )

        _uiState.value.player?.play()

        // Start frame processing with TensorFlow Lite detection
        viewModelScope.launch {
            var frameCount = 0
            while (_uiState.value.isProcessing) {
                delay(100) // Process every 100ms (10 FPS for frame counting)
                frameCount++
                _uiState.value = _uiState.value.copy(frameCount = frameCount)

                // Generate AI summary every 50 frames
                if (frameCount % 50 == 0) {
                    generateAISummary()
                }
            }
        }

        // Start TensorFlow Lite person detection processing (separate coroutine)
        startPersonDetection()
    }

    private fun startPersonDetection() {
        viewModelScope.launch {
            while (_uiState.value.isProcessing) {
                processFrameForDetection()
                delay(300) // Process TensorFlow Lite detection every 300ms (~3 FPS for ML)
            }
        }
    }

    private suspend fun processFrameForDetection() {
        playerView?.let { view ->
            videoFrameExtractor?.let { extractor ->
                personDetectionEngine?.let { detector ->
                    try {
                        // Extract frame from video
//                        val bitmap = extractor.extractFrameFromPlayerView(view)
                        val bitmap = extractor.extractVideoFrameFromPlayerView(view)
                        bitmap?.let { frame ->
                            // Scale for optimal TensorFlow Lite performance
                            val scaledFrame = extractor.scaleBitmapForML(frame, 640)

                            // Perform TensorFlow Lite detection
                            val result = detector.detectPersons(scaledFrame)

                            // Update UI with results
                            updateDetectionResults(result)

                            // Clean up bitmaps
                            if (scaledFrame != frame) {
                                scaledFrame.recycle()
                            }
                            frame.recycle()
                        }
                    } catch (e: Exception) {
                        // Handle detection errors gracefully
                    }
                }
            }
        }
    }

    private fun updateDetectionResults(result: DetectionResult) {
        val currentStats = _uiState.value.detectionStats
        val newStats = currentStats.copy(
            totalDetections = currentStats.totalDetections + 1,
            averageInferenceTime = if (currentStats.totalDetections == 0) {
                result.inferenceTimeMs.toFloat()
            } else {
                (currentStats.averageInferenceTime * currentStats.totalDetections + result.inferenceTimeMs) / (currentStats.totalDetections + 1)
            },
            peopleDetectedCount = if (result.detections.isNotEmpty())
                currentStats.peopleDetectedCount + 1 else currentStats.peopleDetectedCount,
            lastDetectionTime = System.currentTimeMillis()
        )

        _uiState.value = _uiState.value.copy(
            detectionResult = result,
            detectionStats = newStats
        )
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(
            isProcessing = false
        )
        _uiState.value.player?.pause()
    }

    private fun generateAISummary() {
        _uiState.value = _uiState.value.copy(isGeneratingSummary = true)

        viewModelScope.launch {
//            delay(2000) // Simulate AI processing time
//
//            val currentFrames = _uiState.value.frameCount
//            val videoSource = _uiState.value.videoSource
//            val accessType = _uiState.value.accessType
//            val stats = _uiState.value.detectionStats
//            val currentDetections = _uiState.value.detectionResult?.detections?.size ?: 0
//
//            val newSummary = buildString {
//                append("🚗 In-Car Analysis Report\n\n")
//                append("📊 Frames Processed: $currentFrames\n")
//                append("⏱️ Processing Time: ${currentFrames * 0.1f} seconds\n")
//                append("🎥 Video Source: $videoSource\n")
//                append("🔐 Access Type: $accessType\n\n")
//
//                append("🤖 TensorFlow Lite Person Detection: ACTIVE\n")
//                append("👥 Total ML Detections: ${stats.totalDetections}\n")
//                append("🎯 People Detected: ${stats.peopleDetectedCount}\n")
//                append("👤 Current People in Frame: $currentDetections\n")
//                append("⚡ Avg Inference Time: ${stats.averageInferenceTime.toInt()}ms\n")
//                append("📅 Last Detection: ${if (stats.lastDetectionTime > 0) "Active" else "None"}\n\n")
//
//                append("🎯 Key Observations:\n")
//                if (stats.peopleDetectedCount > 0) {
//                    append("• ${stats.peopleDetectedCount} person detection(s) recorded\n")
//                    if (currentDetections > 0) {
//                        append("• $currentDetections person(s) currently visible\n")
//                    }
//                    append("• Real-time TensorFlow Lite inference active\n")
//                    append("• COCO MobileNet SSD model running on-device\n")
//                    append("• Bounding boxes displayed with confidence scores\n")
//                } else {
//                    append("• TensorFlow Lite detection running, no people detected yet\n")
//                    append("• COCO-trained MobileNet SSD ready to detect occupants\n")
//                }
//                append("• Monitoring system functioning optimally\n")
//                append("• Road conditions: Clear visibility\n")
//                append("• Rearview mirror perspective: Optimal\n\n")
//
//                append("📈 Safety Score: ${(85..98).random()}/100\n\n")
//                append("🔍 Recommendations:\n")
//                append("• Continue monitoring for fatigue signs\n")
//                append("• Maintain current driving behavior\n")
//                append("• TensorFlow Lite person detection enhancing safety monitoring\n")
//                append("• System functioning optimally\n")
//                append("• Consider break if score drops below 80\n\n")
//                append("📱 Device Processing Status: Active\n")
//                append("🔒 Privacy: ${if (_uiState.value.hasPartialAccess) "Selected access only" else "Full media access"}\n")
//                append("🧠 ML Processing: TensorFlow Lite COCO MobileNet SSD with real-time bounding boxes\n")
//                append("Last updated: Frame $currentFrames")
//            }
//
//            _uiState.value = _uiState.value.copy(
//                summary = newSummary,
//                isGeneratingSummary = false
//            )
            try {
                val currentFrames = _uiState.value.frameCount
                val videoSource = _uiState.value.videoSource
                val stats = _uiState.value.detectionStats
                val currentDetections = _uiState.value.detectionResult?.detections?.size ?: 0
                val processingTime = currentFrames * 0.1f

                // Generate AI summary using Gemini API
                val aiSummary = aiSummaryGenerator?.generateAISummary(
                    frameCount = currentFrames,
                    detectionStats = stats,
                    videoSource = videoSource,
                    processingTimeSeconds = processingTime,
                    currentDetections = currentDetections
                ) ?: "AI summary service not available"

                _uiState.value = _uiState.value.copy(
                    summary = aiSummary,
                    isGeneratingSummary = false
                )

            } catch (e: Exception) {
                Log.e("InCarMonitoring", "Error generating AI summary: ${e.message}")

                // Fallback to basic summary
                val fallbackSummary = "🚗 Basic monitoring report: ${_uiState.value.frameCount} frames processed, " +
                        "${_uiState.value.detectionStats.peopleDetectedCount} people detected. " +
                        "AI summary temporarily unavailable."

                _uiState.value = _uiState.value.copy(
                    summary = fallbackSummary,
                    isGeneratingSummary = false
                )
            }
        }
    }

    fun releasePlayer() {
        personDetectionEngine?.close()
        _uiState.value.player?.release()
        _uiState.value = _uiState.value.copy(player = null)
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }
}