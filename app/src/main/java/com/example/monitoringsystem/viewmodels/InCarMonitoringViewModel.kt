package com.example.monitoringsystem.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    val selectedVideoUri: Uri? = null
)

class InCarMonitoringViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(InCarMonitoringUiState())
    val uiState: StateFlow<InCarMonitoringUiState> = _uiState.asStateFlow()

    private var videoPickerLauncher: ActivityResultLauncher<Intent>? = null

    fun setVideoPickerLauncher(launcher: ActivityResultLauncher<Intent>) {
        videoPickerLauncher = launcher
    }

    fun initializePlayer(context: Context) {
        val player = ExoPlayer.Builder(context).build()

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

//        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
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
            summary = ""
        )

        _uiState.value.player?.play()

        // Simulate frame processing
        viewModelScope.launch {
            var frameCount = 0
            while (_uiState.value.isProcessing) {
                delay(100) // Simulate processing delay
                frameCount++
                _uiState.value = _uiState.value.copy(frameCount = frameCount)

                // Simulate AI summary generation every 50 frames
                if (frameCount % 50 == 0) {
                    generateAISummary()
                }
            }
        }
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(isProcessing = false)
        _uiState.value.player?.pause()
    }

    private fun generateAISummary() {
        _uiState.value = _uiState.value.copy(isGeneratingSummary = true)

        viewModelScope.launch {
            delay(2000) // Simulate AI processing time

            val currentFrames = _uiState.value.frameCount
            val videoSource = _uiState.value.videoSource
            val newSummary = buildString {
                append("🚗 In-Car Analysis Report\n\n")
                append("📊 Frames Processed: $currentFrames\n")
                append("⏱️ Processing Time: ${currentFrames * 0.1f} seconds\n")
                append("🎥 Video Source: $videoSource\n\n")
                append("🎯 Key Observations:\n")
                append("• Driver appears alert and focused\n")
                append("• Proper seating position maintained\n")
                append("• No signs of distraction detected\n")
                append("• Road conditions: Clear visibility\n")
                append("• Rearview mirror perspective: Optimal\n\n")
                append("📈 Safety Score: ${(85..98).random()}/100\n\n")
                append("🔍 Recommendations:\n")
                append("• Continue monitoring for fatigue signs\n")
                append("• Maintain current driving behavior\n")
                append("• System functioning optimally\n")
                append("• Consider break if score drops below 80\n\n")
                append("📱 Device Processing Status: Active\n")
                append("Last updated: Frame $currentFrames")
            }

            _uiState.value = _uiState.value.copy(
                summary = newSummary,
                isGeneratingSummary = false
            )
        }
    }

    fun releasePlayer() {
        _uiState.value.player?.release()
        _uiState.value = _uiState.value.copy(player = null)
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }
}