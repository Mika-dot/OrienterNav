package com.mikadot.orienternav.util

import android.content.Context
import com.mikadot.orienternav.BuildConfig

class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("orienter_nav", Context.MODE_PRIVATE)

    var mapStyleUrl: String
        get() = prefs.getString("map_style", BuildConfig.DEFAULT_MAP_STYLE)!!
        set(value) = prefs.edit().putString("map_style", value.trim()).apply()

    var routerUrl: String
        get() = prefs.getString("router_url", BuildConfig.DEFAULT_ROUTER_URL)!!
        set(value) = prefs.edit().putString("router_url", value.trimEnd('/')).apply()

    var geocoderUrl: String
        get() = prefs.getString("geocoder_url", BuildConfig.DEFAULT_GEOCODER_URL)!!
        set(value) = prefs.edit().putString("geocoder_url", value.trimEnd('/')).apply()

    var visualServiceUrl: String
        get() = prefs.getString("visual_url", "")!!
        set(value) = prefs.edit().putString("visual_url", value.trimEnd('/')).apply()

    var visualApiKey: String
        get() = prefs.getString("visual_key", "")!!
        set(value) = prefs.edit().putString("visual_key", value.trim()).apply()

    var visualEnabled: Boolean
        get() = prefs.getBoolean("visual_enabled", true)
        set(value) = prefs.edit().putBoolean("visual_enabled", value).apply()
}
