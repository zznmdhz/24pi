package com.twentyfourpi.lifelog.collector

import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.util.wgs84ToGcj02
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class GeocodeResult(val name: String, val address: String, val provider: String)

class AmapGeocoder(private val settings: SettingsStore) {
    fun enabled() = settings.amapConsent && settings.amapKey.isNotBlank()

    fun reverse(latitude: Double, longitude: Double): GeocodeResult? {
        if (!enabled()) return null
        val gcj = wgs84ToGcj02(latitude, longitude)
        val location = URLEncoder.encode("${gcj.longitude},${gcj.latitude}", "UTF-8")
        val key = URLEncoder.encode(settings.amapKey, "UTF-8")
        val connection = URL("https://restapi.amap.com/v3/geocode/regeo?key=$key&location=$location&extensions=base&radius=500")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("status") != "1") return null
            val regeocode = json.optJSONObject("regeocode") ?: return null
            val address = regeocode.optString("formatted_address")
            val component = regeocode.optJSONObject("addressComponent")
            val neighborhood = component?.optJSONObject("neighborhood")?.optString("name").orEmpty()
            val township = component?.optString("township").orEmpty()
            GeocodeResult(neighborhood.ifBlank { township }.ifBlank { "未命名地点" }, address, "amap")
        } finally { connection.disconnect() }
    }
}
