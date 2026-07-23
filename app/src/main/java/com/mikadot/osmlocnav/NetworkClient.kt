package com.mikadot.osmlocnav

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LocalizeResult(
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val confidence: Double,
    val backend: String,
    val processingMs: Long,
    val vehicleCount: Int,
    val accepted: Boolean,
    val message: String,
)

class NetworkClient(private val baseUrl: String, private val apiKey: String) {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun localize(frame: ByteArray, snapshot: MotionSnapshot, corridor: List<GeoPoint>, callback: (Result<LocalizeResult>) -> Unit) {
        val meta = JSONObject().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("prior_lat", snapshot.position.lat)
            put("prior_lon", snapshot.position.lon)
            put("route_progress_m", snapshot.progressMeters)
            put("speed_mps", snapshot.speedMps)
            put("heading_deg", snapshot.routeBearingDeg)
            put("yaw_delta_deg", snapshot.yawDeltaDeg)
            put("forward_accel_mps2", snapshot.forwardAccelMps2)
            put("stationary", snapshot.stationary)
            put("horizontal_fov_deg", 70.0)
            put("pitch_deg", 0.0)
            put("roll_deg", 0.0)
            put("route_corridor", JSONArray().apply {
                corridor.forEach { put(JSONArray().put(it.lat).put(it.lon)) }
            })
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", meta.toString())
            .addFormDataPart("frame", "frame.jpg", frame.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/localize")
            .header("X-API-Key", apiKey)
            .post(body).build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    callback(runCatching {
                        val text = it.body?.string() ?: ""
                        if (!it.isSuccessful) error("Сервер HTTP ${it.code}: $text")
                        val j = JSONObject(text)
                        LocalizeResult(
                            j.getDouble("lat"), j.getDouble("lon"), j.optDouble("heading_deg", snapshot.routeBearingDeg),
                            j.optDouble("confidence", 0.0), j.optString("backend", "unknown"),
                            j.optLong("processing_ms", 0), j.optInt("vehicle_count", 0),
                            j.optBoolean("accepted", true), j.optString("message", "")
                        )
                    })
                }
            }
        })
    }
}
