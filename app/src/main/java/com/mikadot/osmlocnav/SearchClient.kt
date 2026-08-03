package com.mikadot.osmlocnav

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class SearchPlace(val title: String, val point: GeoPoint)

class SearchClient(
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
) {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun search(query: String, callback: (Result<List<SearchPlace>>) -> Unit) {
        val url = (baseUrl.trimEnd('/') + "/search")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "5")
            .addQueryParameter("accept-language", "ru")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OrienterNav/0.5 (https://github.com/Mika-dot/OrienterNav)")
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result.failure(e))

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    callback(runCatching {
                        check(it.isSuccessful) { "Поиск адреса: HTTP ${it.code}" }
                        val array = JSONArray(it.body?.string().orEmpty())
                        buildList {
                            for (index in 0 until array.length()) {
                                val item = array.getJSONObject(index)
                                add(
                                    SearchPlace(
                                        title = item.optString("display_name", "Результат поиска"),
                                        point = GeoPoint(item.getString("lat").toDouble(), item.getString("lon").toDouble()),
                                    ),
                                )
                            }
                        }
                    })
                }
            }
        })
    }
}
