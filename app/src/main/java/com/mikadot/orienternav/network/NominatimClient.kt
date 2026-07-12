package com.mikadot.orienternav.network

import com.mikadot.orienternav.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class NominatimClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
) {
    data class Place(
        val title: String,
        val point: GeoPoint,
    )

    suspend fun search(query: String): List<Place> =
        withContext(Dispatchers.IO) {
            val url =
                (baseUrl.trimEnd('/') + "/search")
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("format", "jsonv2")
                    .addQueryParameter("limit", "5")
                    .addQueryParameter("addressdetails", "1")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "OrienterNav/0.1 (personal Android navigation; github.com/Mika-dot/OrienterNav)")
                    .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Геокодер: HTTP ${response.code}" }
                val array = JSONArray(response.body?.string().orEmpty())
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(Place(item.getString("display_name"), GeoPoint(item.getDouble("lat"), item.getDouble("lon"))))
                    }
                }
            }
        }

    companion object {
        fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
