package com.mikadot.orienternav.network

import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.RoutePlan
import com.mikadot.orienternav.model.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class OsrmClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
) {
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
    ): RoutePlan =
        withContext(Dispatchers.IO) {
            val coordinates = "${from.longitude},${from.latitude};${to.longitude},${to.latitude}"
            val url =
                (baseUrl.trimEnd('/') + "/route/v1/driving/$coordinates")
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("overview", "full")
                    .addQueryParameter("geometries", "geojson")
                    .addQueryParameter("steps", "true")
                    .addQueryParameter("alternatives", "false")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "OrienterNav/0.1")
                    .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Маршрутизатор: HTTP ${response.code}" }
                val root = JSONObject(response.body?.string().orEmpty())
                check(root.optString("code") == "Ok") { root.optString("message", "Маршрут не найден") }
                val route = root.getJSONArray("routes").getJSONObject(0)
                val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                val geometry =
                    buildList {
                        for (i in 0 until coords.length()) {
                            val pair = coords.getJSONArray(i)
                            add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                        }
                    }
                val steps =
                    buildList {
                        val legs = route.getJSONArray("legs")
                        for (l in 0 until legs.length()) {
                            val rawSteps = legs.getJSONObject(l).getJSONArray("steps")
                            for (i in 0 until rawSteps.length()) {
                                val raw = rawSteps.getJSONObject(i)
                                val maneuver = raw.getJSONObject("maneuver")
                                val p = maneuver.getJSONArray("location")
                                add(
                                    RouteStep(
                                        GeoPoint(p.getDouble(1), p.getDouble(0)),
                                        instructionFor(maneuver, raw.optString("name")),
                                        raw.optDouble("distance", 0.0),
                                    ),
                                )
                            }
                        }
                    }
                RoutePlan(geometry, steps, route.getDouble("distance"), route.getDouble("duration"))
            }
        }

    private fun instructionFor(
        maneuver: JSONObject,
        street: String,
    ): String {
        val type = maneuver.optString("type")
        val modifier = maneuver.optString("modifier")
        val action =
            when (type) {
                "depart" -> "Начните движение"
                "arrive" -> "Вы прибыли"
                "roundabout", "rotary" -> {
                    val exit = maneuver.optInt("exit", 0)
                    if (exit > 0) "На круге выберите съезд $exit" else "Въезжайте на круг"
                }
                "merge" -> "Перестройтесь ${direction(modifier)}"
                "fork" -> "На развилке держитесь ${direction(modifier)}"
                "on ramp" -> "Сверните на въезд ${direction(modifier)}"
                "off ramp" -> "Сверните на съезд ${direction(modifier)}"
                "end of road" -> "В конце дороги поверните ${direction(modifier)}"
                "continue", "new name", "notification" -> "Продолжайте движение"
                else -> "Поверните ${direction(modifier)}"
            }
        return if (street.isBlank() || type == "arrive") action else "$action на $street"
    }

    private fun direction(modifier: String): String =
        when (modifier) {
            "left", "sharp left" -> "налево"
            "slight left" -> "левее"
            "right", "sharp right" -> "направо"
            "slight right" -> "правее"
            "uturn" -> "в обратном направлении"
            else -> "прямо"
        }
}
