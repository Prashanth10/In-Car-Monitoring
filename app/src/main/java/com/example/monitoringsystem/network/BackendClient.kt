package com.example.monitoringsystem.network

import android.util.Log
import com.example.monitoringsystem.service.BackendApiService
import com.example.monitoringsystem.service.MonitoringMetadata
import com.example.monitoringsystem.service.SummaryLogRequest
import com.example.monitoringsystem.viewmodels.DetectionStats
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.*

class BackendClient {

    // For local development: "http://10.0.2.2:8000/" (Android emulator)
    // For device: "http://YOUR_COMPUTER_IP:8000/"
    private val baseUrl = "http://10.0.2.2:8000/"

    private val retrofit: Retrofit by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService: BackendApiService by lazy {
        retrofit.create(BackendApiService::class.java)
    }

    suspend fun sendSummaryToBackend(
        sessionId: String,
        summary: String,
        frameCount: Int,
        detectionStats: DetectionStats,
        videoSource: String,
        processingTimeSeconds: Float
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            Log.d("BackendClient", "Sending summary to backend...")

            val metadata = MonitoringMetadata(
                framesProcessed = frameCount,
                peopleDetected = detectionStats.peopleDetectedCount,
                processingTimeSeconds = processingTimeSeconds,
                videoSource = videoSource,
                inferenceTimeMs = detectionStats.averageInferenceTime,
                totalDetections = detectionStats.totalDetections
            )

            val request = SummaryLogRequest(
                sessionId = sessionId,
                summary = summary,
                metadata = metadata,
                timestamp = getCurrentTimestamp()
            )

            val response = apiService.logSummary(request)

            if (response.isSuccessful) {
                val responseBody = response.body()
                Log.d("BackendClient", "Successfully sent summary to backend: ${responseBody?.logId}")
                return@withContext true
            } else {
                Log.e("BackendClient", "Backend error: ${response.code()} - ${response.message()}")
                Log.e("BackendClient", "Error body: ${response.errorBody()?.string()}")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e("BackendClient", "Exception sending summary to backend: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("BackendClient", "Checking backend health...")

            val response = apiService.healthCheck()

            if (response.isSuccessful) {
                val health = response.body()
                Log.d("BackendClient", "Backend is healthy: ${health?.status}")
                return@withContext true
            } else {
                Log.e("BackendClient", "Backend health check failed: ${response.code()}")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e("BackendClient", "Backend health check exception: ${e.message}")
            return@withContext false
        }
    }

//    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
//        try {
//            Log.d("BackendClient", "Testing connection to: $baseUrl")
//
//            val response = apiService.healthCheck()
//
//            if (response.isSuccessful) {
//                val health = response.body()
//                return@withContext " Connected successfully: ${health?.status}"
//            } else {
//                return@withContext " Connection failed: ${response.code()}"
//            }
//        } catch (e: Exception) {
//            return@withContext " Network error: ${e.message}"
//        }
//    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}