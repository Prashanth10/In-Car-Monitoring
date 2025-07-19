package com.example.monitoringsystem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monitoringsystem.ml.DetectionResult
import com.example.monitoringsystem.ml.PersonDetection

@Composable
fun BoundingBoxOverlay(
    detectionResult: DetectionResult?,
    videoWidth: Int,
    videoHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        detectionResult?.let { result ->
            drawDetections(
                detections = result.detections,
                originalWidth = result.imageWidth,
                originalHeight = result.imageHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight
            )
        }
    }
}

private fun DrawScope.drawDetections(
    detections: List<PersonDetection>,
    originalWidth: Int,
    originalHeight: Int,
    viewWidth: Int,
    viewHeight: Int
) {
    if (originalWidth <= 0 || originalHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

    // Calculate scaling factors to map detection coordinates to view coordinates
    val scaleX = size.width / originalWidth
    val scaleY = size.height / originalHeight

    val paint = Paint().apply {
        color = Color.Red
        strokeWidth = 3.dp.toPx()
        style = PaintingStyle.Stroke
    }

    val textPaint = android.graphics.Paint().apply {
        color = Color.White.toArgb()
        textSize = 16.sp.toPx()
        isAntiAlias = true
    }

    val backgroundPaint = android.graphics.Paint().apply {
        color = Color.Red.copy(alpha = 0.7f).toArgb()
        style = android.graphics.Paint.Style.FILL
    }

    detections.forEach { detection ->
        val box = detection.boundingBox

        // Scale bounding box to view coordinates
        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY

        // Draw bounding box
        drawRect(
            color = Color.Red,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        )

        // Draw confidence label
        val confidence = (detection.confidence * 100).toInt()
        val label = "Person ${confidence}%"

        drawIntoCanvas { canvas ->
            // Draw background for text
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val textBackgroundLeft = left
            val textBackgroundTop = top - textBounds.height() - 8.dp.toPx()
            val textBackgroundRight = left + textBounds.width() + 16.dp.toPx()
            val textBackgroundBottom = top

            canvas.nativeCanvas.drawRect(
                textBackgroundLeft,
                textBackgroundTop,
                textBackgroundRight,
                textBackgroundBottom,
                backgroundPaint
            )

            // Draw text
            canvas.nativeCanvas.drawText(
                label,
                left + 8.dp.toPx(),
                top - 8.dp.toPx(),
                textPaint
            )
        }

        // Draw tracking ID if available
        detection.trackingId?.let { trackingId ->
            val trackingLabel = "ID: $trackingId"
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    trackingLabel,
                    left + 8.dp.toPx(),
                    bottom + 20.sp.toPx(),
                    textPaint
                )
            }
        }
    }
}

// Extension function to get view dimensions
@Composable
fun getViewDimensions(): Pair<Int, Int> {
    // This would be passed from the parent composable
    // For now, return default values
    return Pair(640, 480)
}