package com.twentyfourpi.lifelog.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var collectionEnabled: Boolean
        get() = prefs.getBoolean("collection_enabled", false)
        set(value) = prefs.edit().putBoolean("collection_enabled", value).apply()
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()
    var startedAt: Long
        get() = prefs.getLong("started_at", 0)
        set(value) = prefs.edit().putLong("started_at", value).apply()
    var amapConsent: Boolean
        get() = prefs.getBoolean("amap_consent", false)
        set(value) = prefs.edit().putBoolean("amap_consent", value).apply()
    var amapKey: String
        get() = prefs.getString("amap_key", "") ?: ""
        set(value) = prefs.edit().putString("amap_key", value.trim()).apply()
    var placeNameConsent: Boolean
        get() = prefs.getBoolean("place_name_consent", prefs.getBoolean("amap_consent", false))
        set(value) = prefs.edit().putBoolean("place_name_consent", value).apply()
    var notificationContentEnabled: Boolean
        get() = prefs.getBoolean("notification_content_enabled", false)
        set(value) = prefs.edit().putBoolean("notification_content_enabled", value).apply()
    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    fun sourceEnabled(source: SourceId): Boolean = prefs.getBoolean("source_${source.name}", true)
    fun setSourceEnabled(source: SourceId, enabled: Boolean) =
        prefs.edit().putBoolean("source_${source.name}", enabled).apply()

    fun getLong(key: String, default: Long = 0) = prefs.getLong(key, default)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getBoolean(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getString(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun remove(vararg keys: String) = prefs.edit().also { e -> keys.forEach(e::remove) }.apply()

    fun export(): Map<String, *> = prefs.all
    fun replace(values: Map<String, String>) {
        prefs.edit().clear().also { editor ->
            values.forEach { (key, encoded) ->
                val split = encoded.indexOf(':')
                if (split > 0) when (encoded.substring(0, split)) {
                    "b" -> editor.putBoolean(key, encoded.substring(split + 1).toBoolean())
                    "l" -> editor.putLong(key, encoded.substring(split + 1).toLong())
                    "i" -> editor.putInt(key, encoded.substring(split + 1).toInt())
                    "f" -> editor.putFloat(key, encoded.substring(split + 1).toFloat())
                    else -> editor.putString(key, encoded.substring(split + 1))
                }
            }
        }.commit()
    }

    companion object { const val NAME = "life_log_settings" }
}
