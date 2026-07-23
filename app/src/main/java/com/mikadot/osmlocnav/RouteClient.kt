package com.mikadot.osmlocnav

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RouteResult(val points: List<GeoPoint>, val distanceMeters: Double, val durationSeconds: Double)

class RouteClient(private val baseUrl: String) {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun route(start: GeoPoint, destination: GeoPoint, callback: (Result<RouteResult>) -> Unit) {
        val url = baseUrl.trimEnd('/') + "/route/v1/driving/${start.lon},${start.lat};${destination.lon},${destination.lat}?overview=full&geometries=geojson&steps=true"
        val req = Request.Builder().url(url).header("User-Agent", "OSMLocNav/0.4").build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    callback(runCatching {
                        if (!it.isSuccessful) error("OSRM HTTP ${it.code}")
                        val root = JSONObject(it.body?.string() ?: error("Пустой ответ OSRM"))
                        val routes = root.optJSONArray("routes") ?: error("OSRM не вернул routes")
                        if (routes.length() == 0) error("Маршрут не найден")
                        val route = routes.getJSONObject(0)
                        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                        val points = ArrayList<GeoPoint>(coords.length())
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            points += GeoPoint(c.getDouble(1), c.getDouble(0))
                        }
                        RouteResult(points, route.getDouble("distance"), route.getDouble("duration"))
                    })
                }
            }
        })
    }
}
