package com.example.monitoringsystem.service

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Data classes for backend API
data class MonitoringMetadata(
    val framesProcessed: Int,
    val peopleDetected: Int,
    val processingTimeSeconds: Float,
    val videoSource: String,
    val inferenceTimeMs: Float,
    val totalDetections: Int
)

data class SummaryLogRequest(
    val sessionId: String,
    val summary: String,
    val metadata: MonitoringMetadata,
    val timestamp: String? = null
)

data class SummaryLogResponse(
    val status: String,
    val message: String,
    val logId: String,
    val timestamp: String
)

data class HealthResponse(
    val status: String,
    val timestamp: String,
    val logsDirectory: String,
    val currentLogFile: String
)

interface BackendApiService {
    @GET("health")
    suspend fun healthCheck(): Response<HealthResponse>

    @POST("api/log-summary")
    suspend fun logSummary(@Body request: SummaryLogRequest): Response<SummaryLogResponse>
}