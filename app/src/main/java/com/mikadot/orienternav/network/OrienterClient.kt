package com.mikadot.orienternav.network

import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.VisualEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OrienterClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

    suspend fun localize(
        jpeg: ByteArray,
        prior: GeoPoint,
        heading: Double?,
        searchRadiusMeters: Int,
    ): VisualEstimate =
        withContext(Dispatchers.IO) {
            val body =
                MultipartBody
                    .Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                    .addFormDataPart("prior_lat", prior.latitude.toString())
                    .addFormDataPart("prior_lon", prior.longitude.toString())
                    .addFormDataPart("search_radius_m", searchRadiusMeters.toString())
                    .apply { heading?.let { addFormDataPart("prior_heading_deg", it.toString()) } }
                    .build()
            val request =
                Request
                    .Builder()
                    .url(baseUrl.trimEnd('/') + "/v1/localize")
                    .post(body)
                    .apply { if (apiKey.isNotBlank()) header("X-API-Key", apiKey) }
                    .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "OrienterNet: HTTP ${response.code}: ${text.take(200)}" }
                val json = JSONObject(text)
                VisualEstimate(
                    point = GeoPoint(json.getDouble("latitude"), json.getDouble("longitude")),
                    yawDegrees = json.getDouble("yaw_degrees"),
                    confidence = json.getDouble("confidence"),
                    sigmaMeters = json.getDouble("sigma_meters"),
                    timestampMillis = System.currentTimeMillis(),
                )
            }
        }
}
