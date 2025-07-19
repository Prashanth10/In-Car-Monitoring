package com.example.monitoringsystem.ml

import android.graphics.Bitmap
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class VideoFrameExtractor {

    @OptIn(UnstableApi::class)
    suspend fun extractVideoFrameFromPlayerView(playerView: PlayerView): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val surfaceView = playerView.videoSurfaceView

            if (surfaceView == null) {
                Log.e("InCarMonitoring", "No video surface view found")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val width = surfaceView.width
            val height = surfaceView.height

            if (width <= 0 || height <= 0) {
                Log.e("InCarMonitoring", "Invalid video surface dimensions")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            fun checkBitmapPixels(bitmap: Bitmap) {
                try {
                    val sampleSize = 10
                    val pixels = IntArray(sampleSize * sampleSize)
                    bitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
                    val nonZeroPixels = pixels.count { it != 0 }
                    Log.d(
                        "InCarMonitoring",
                        "Extract Pixel sample from top-left ${sampleSize}x${sampleSize}"
                    )
                    Log.d(
                        "InCarMonitoring",
                        "Extract Non-zero pixels: $nonZeroPixels / ${pixels.size}"
                    )
                    Log.d("InCarMonitoring", "Extract First few pixels: ${
                        pixels.take(5).joinToString { "0x${it.toUInt().toString(16)}" }
                    }")
                } catch (e: Exception) {
                    Log.e("InCarMonitoring", "Pixel check failed: ${e.message}")
                }
            }

            try {
                when (surfaceView) {
                    is SurfaceView -> {
                        PixelCopy.request(
                            surfaceView,
                            bitmap,
                            { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    Log.d("InCarMonitoring", "Surfaceview PixelCopy success")
                                    checkBitmapPixels(bitmap)
                                    continuation.resume(bitmap)
                                } else {
                                    Log.e(
                                        "InCarMonitoring",
                                        "PixelCopy failed for SurfaceView: $result"
                                    )
                                    continuation.resume(null)
                                }
                            },
                            handler
                        )
                    }

                    is android.view.TextureView -> {
                        val surfaceTexture = surfaceView.surfaceTexture
                        if (surfaceTexture == null) {
                            Log.e("InCarMonitoring", "TextureView has no surface texture")
                            checkBitmapPixels(bitmap)
                            continuation.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        Log.d("InCarMonitoring", "TextureView PixelCopy success")

                        val surface = android.view.Surface(surfaceTexture)
                        PixelCopy.request(
                            surface,
                            bitmap,
                            { result ->
                                surface.release()
                                if (result == PixelCopy.SUCCESS) {
                                    continuation.resume(bitmap)
                                } else {
                                    Log.e(
                                        "InCarMonitoring",
                                        "PixelCopy failed for Surface: $result"
                                    )
                                    continuation.resume(null)
                                }
                            },
                            handler
                        )
                    }

                    else -> {
                        Log.e(
                            "InCarMonitoring",
                            "Unsupported video surface type: ${surfaceView::class}"
                        )
                        continuation.resume(null)
                    }
                }
            } catch (e: Exception) {
                Log.e("InCarMonitoring", "PixelCopy exception: ${e.message}")
                continuation.resume(null)
            }
        }

//    /**
//     * Extract frame from PlayerView using View.draw method
//     * This is the most reliable method for getting current frame
//     */
//    suspend fun extractFrameFromPlayerView(playerView: PlayerView): Bitmap? =
//        withContext(Dispatchers.Main) {
//            try {
//                val width = playerView.width
//                val height = playerView.height
//
//                Log.d("InCarMonitoring", "Extract PlayerView dimensions: ${width}x${height}")
//
//                if (width <= 0 || height <= 0) {
//                    Log.e("InCarMonitoring", "Extract Invalid PlayerView dimensions")
//                    return@withContext null
//                }
//
//                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//                val canvas = Canvas(bitmap)
//                playerView.draw(canvas)
//
//                Log.d("InCarMonitoring", "Extract Extracted bitmap: ${bitmap.width}x${bitmap.height}")
//                Log.d("InCarMonitoring", "Extract Bitmap config: ${bitmap.config}")
//                Log.d("InCarMonitoring", "Extract Bitmap is recycled: ${bitmap.isRecycled}")
//
//                // Check if bitmap has actual content (not just black/empty)
//                val pixels = IntArray(100) // Sample 100 pixels
//                bitmap.getPixels(pixels, 0, 10, 0, 0, 10, 10)
//                val nonZeroPixels = pixels.count { it != 0 }
//                Log.d("InCarMonitoring", "Extract Non-zero pixels in sample: $nonZeroPixels/100")
//
//                bitmap
//            } catch (e: Exception) {
//                Log.e("InCarMonitoring", "Extract Frame extraction error: ${e.message}", e)
//                null
//            }
//        }

//    /**
//     * Alternative method using PixelCopy (Android 7.0+)
//     * More efficient but requires API 24+
//     */
//    suspend fun extractFrameUsingPixelCopy(view: View): Bitmap? =
//        suspendCancellableCoroutine { continuation ->
//            try {
//                val width = view.width
//                val height = view.height
//
//                if (width <= 0 || height <= 0) {
//                    continuation.resume(null)
//                    return@suspendCancellableCoroutine
//                }
//
//                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//
//                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//                    PixelCopy.request(
//                        view as android.view.SurfaceView,
//                        bitmap,
//                        { result ->
//                            if (result == PixelCopy.SUCCESS) {
//                                continuation.resume(bitmap)
//                            } else {
//                                continuation.resume(null)
//                            }
//                        },
//                        android.os.Handler(android.os.Looper.getMainLooper())
//                    )
//                } else {
//                    continuation.resume(null)
//                }
//            } catch (e: Exception) {
//                continuation.resume(null)
//            }
//        }

    /**
     * Scale bitmap to optimize for ML processing
     * Maintains aspect ratio while reducing size for faster inference
     */
    fun scaleBitmapForML(bitmap: Bitmap, maxSize: Int = 640): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}