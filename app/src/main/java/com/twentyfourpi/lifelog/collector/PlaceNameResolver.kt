package com.twentyfourpi.lifelog.collector

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.twentyfourpi.lifelog.BuildConfig
import com.twentyfourpi.lifelog.data.LifeLogDao
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 地点命名与坐标采集相互独立。用户同意后依次尝试：
 * 1. Android 设备自带 Geocoder（无需应用 Key）；
 * 2. 已配置时使用高德；
 * 3. OpenStreetMap Nominatim 公共实例作为免 Key 备用。
 *
 * Nominatim 只处理已经确认的新地点，结果直接缓存在 Room 的 places 表中；全局串行且
 * 至少间隔 15 秒，满足公共实例对周期任务 4 次/分钟和缓存结果的要求。
 */
class PlaceNameResolver(
    private val context: Context,
    private val settings: SettingsStore,
    private val amap: AmapGeocoder = AmapGeocoder(settings),
) {
    fun enabled(): Boolean = settings.placeNameConsent

    suspend fun reverse(latitude: Double, longitude: Double): GeocodeResult? {
        if (!enabled()) return null
        val device = withTimeoutOrNull(8_000) { reverseWithDevice(latitude, longitude) }
        if (device != null) return device
        return withContext(Dispatchers.IO) {
            if (amap.enabled()) {
                val amapResult = runCatching { amap.reverse(latitude, longitude) }.getOrNull()
                if (amapResult != null) return@withContext amapResult
            }
            reverseWithOpenStreetMap(latitude, longitude)
        }
    }

    suspend fun backfillMissingAddresses(dao: LifeLogDao, limit: Int = 12): BackfillResult {
        if (!enabled()) return BackfillResult()
        var updated = 0
        var attempted = 0
        var failed = 0
        val missing = dao.activePlaces().filter { it.address.isBlank() }
        missing.take(limit).forEach { place ->
            attempted++
            val result = runCatching { reverse(place.latitude, place.longitude) }
                .onFailure { DiagnosticLog.error("location", "reverse_geocode_backfill_failed", it, mapOf("placeId" to place.id)) }
                .getOrNull()
            if (result != null) {
                dao.updatePlace(
                    place.copy(
                        name = if (place.name == "未命名地点") result.name else place.name,
                        address = result.address,
                    ),
                )
                updated++
                DiagnosticLog.event("location", "reverse_geocode_backfilled", mapOf("placeId" to place.id, "provider" to result.provider))
            } else failed++
        }
        return BackfillResult(attempted, updated, failed, (missing.size - limit).coerceAtLeast(0))
    }

    fun scheduleBackfill() = PlaceNameBackfillScheduler.enqueue(context)

    private suspend fun reverseWithDevice(latitude: Double, longitude: Double): GeocodeResult? {
        if (!Geocoder.isPresent()) return null
        return suspendCancellableCoroutine { continuation ->
            val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
            geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    val address = addresses.firstOrNull()
                    continuation.takeIf { it.isActive }?.resume(address?.toResult("android"))
                }

                override fun onError(errorMessage: String?) {
                    DiagnosticLog.event("location", "device_geocoder_failed", mapOf("error" to errorMessage))
                    continuation.takeIf { it.isActive }?.resume(null)
                }
            })
        }
    }

    private suspend fun reverseWithOpenStreetMap(latitude: Double, longitude: Double): GeocodeResult? =
        osmMutex.withLock {
            val waitMs = (lastOsmRequestAt + OSM_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            lastOsmRequestAt = System.currentTimeMillis()
            val lat = URLEncoder.encode("%.6f".format(Locale.US, latitude), "UTF-8")
            val lon = URLEncoder.encode("%.6f".format(Locale.US, longitude), "UTF-8")
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=18&addressdetails=1&accept-language=zh-CN"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "User-Agent",
                    // OpenStreetMap 要求 UA 能识别应用；对外分发版本不带开发者个人标识。
                    "24pi-life-log/${BuildConfig.VERSION_NAME} (Android app)",
                )
                if (connection.responseCode !in 200..299) {
                    DiagnosticLog.event("location", "openstreetmap_geocoder_failed", mapOf("httpStatus" to connection.responseCode))
                    return@withLock null
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val address = json.optJSONObject("address")
                val display = json.optString("display_name")
                val name = json.optString("name").ifBlank {
                    listOf("building", "amenity", "office", "shop", "tourism", "leisure", "neighbourhood", "suburb", "road")
                        .firstNotNullOfOrNull { key -> address?.optString(key)?.takeIf(String::isNotBlank) }
                        .orEmpty()
                }.ifBlank { "未命名地点" }
                display.takeIf(String::isNotBlank)?.let { GeocodeResult(name, it, "openstreetmap") }
            } finally {
                connection.disconnect()
            }
        }

    private fun Address.toResult(provider: String): GeocodeResult? {
        val full = getAddressLine(0).orEmpty()
        val name = listOf(featureName, premises, subLocality, thoroughfare, locality, subAdminArea)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
            .ifBlank { "未命名地点" }
        return full.takeIf(String::isNotBlank)?.let { GeocodeResult(name, it, provider) }
    }

    companion object {
        private const val OSM_INTERVAL_MS = 15_500L
        private val osmMutex = Mutex()
        @Volatile private var lastOsmRequestAt = 0L
    }
}

data class BackfillResult(
    val attempted: Int = 0,
    val updated: Int = 0,
    val failed: Int = 0,
    val remaining: Int = 0,
)
