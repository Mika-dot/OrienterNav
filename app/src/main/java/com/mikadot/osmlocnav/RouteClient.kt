package com.mikadot.osmlocnav

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RouteStep(
    val point: GeoPoint,
    val instruction: String,
    val distanceMeters: Double,
)

data class RouteResult(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

class RouteClient(private val baseUrl: String) {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun route(
        start: GeoPoint,
        destination: GeoPoint,
        initialBearing: Double? = null,
        callback: (Result<RouteResult>) -> Unit,
    ) {
        val coordinates = "${start.lon},${start.lat};${destination.lon},${destination.lat}"
        val builder = (baseUrl.trimEnd('/') + "/route/v1/driving/$coordinates")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .addQueryParameter("alternatives", "false")
        if (initialBearing != null) {
            builder.addQueryParameter("bearings", "${initialBearing.toInt()},90;")
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "OrienterNav/0.5")
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result.failure(e))

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    callback(runCatching {
                        if (!it.isSuccessful) error("OSRM HTTP ${it.code}")
                        val root = JSONObject(it.body?.string() ?: error("Пустой ответ OSRM"))
                        check(root.optString("code") == "Ok") { root.optString("message", "Маршрут не найден") }
                        val routes = root.getJSONArray("routes")
                        if (routes.length() == 0) error("Маршрут не найден")
                        val route = routes.getJSONObject(0)
                        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                        val points = ArrayList<GeoPoint>(coords.length())
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            points += GeoPoint(c.getDouble(1), c.getDouble(0))
                        }
                        val steps = buildList {
                            val legs = route.optJSONArray("legs") ?: return@buildList
                            for (legIndex in 0 until legs.length()) {
                                val rawSteps = legs.getJSONObject(legIndex).optJSONArray("steps") ?: continue
                                for (stepIndex in 0 until rawSteps.length()) {
                                    val step = rawSteps.getJSONObject(stepIndex)
                                    val maneuver = step.getJSONObject("maneuver")
                                    val location = maneuver.getJSONArray("location")
                                    add(
                                        RouteStep(
                                            point = GeoPoint(location.getDouble(1), location.getDouble(0)),
                                            instruction = instructionFor(maneuver, step.optString("name")),
                                            distanceMeters = step.optDouble("distance", 0.0),
                                        ),
                                    )
                                }
                            }
                        }
                        RouteResult(points, steps, route.getDouble("distance"), route.getDouble("duration"))
                    })
                }
            }
        })
    }

    private fun instructionFor(maneuver: JSONObject, street: String): String {
        val type = maneuver.optString("type")
        val modifier = maneuver.optString("modifier")
        val action = when (type) {
            "depart" -> "Начните движение"
            "arrive" -> "Вы прибыли"
            "roundabout", "rotary" -> maneuver.optInt("exit", 0).let {
                if (it > 0) "На круге выберите съезд $it" else "Въезжайте на круг"
            }
            "merge" -> "Перестройтесь ${direction(modifier)}"
            "fork" -> "На развилке держитесь ${direction(modifier)}"
            "on ramp" -> "Сверните на въезд ${direction(modifier)}"
            "off ramp" -> "Сверните на съезд ${direction(modifier)}"
            "end of road" -> "В конце дороги поверните ${direction(modifier)}"
            "continue", "new name", "notification" -> "Продолжайте движение"
            "turn" -> "Поверните ${direction(modifier)}"
            else -> "Двигайтесь ${direction(modifier)}"
        }
        return if (street.isBlank() || type == "arrive") action else "$action на $street"
    }

    private fun direction(modifier: String): String = when (modifier) {
        "left", "sharp left" -> "налево"
        "slight left" -> "левее"
        "right", "sharp right" -> "направо"
        "slight right" -> "правее"
        "uturn" -> "в обратном направлении"
        else -> "прямо"
    }
}
