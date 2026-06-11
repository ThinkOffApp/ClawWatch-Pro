package com.thinkoff.clawwatch

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recovery context for the watch agent. CodeWatch's phone app publishes
 * Oura-derived sleep / HRV / resting-HR from Health Connect into the
 * user-intent state (device slot "phone"); this fetches it back so Claude's
 * system prompt knows how rested the user actually is.
 *
 * Usage: fire [refresh] (suspend, cached 30 min, never throws) at query
 * start, read [summaryOrNull] synchronously when building the prompt.
 */
class HealthContext(private val prefs: SharedPreferences) {
    companion object {
        private const val TAG = "HealthContext"
        private const val CACHE_MS = 30 * 60 * 1000L
        // Same pref keys WatchIntentAdapter uses for its publishing side.
        private const val PREF_INTENT_BASE_URL = "intent_base_url"
        private const val PREF_INTENT_USER_ID = "intent_user_id"
        private const val PREF_ANTFARM_API_KEY = "antfarm_api_key"
        private const val DEFAULT_BASE_URL = "https://groupmind.one/api/v1"
    }

    @Volatile private var cachedSummary: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    fun summaryOrNull(): String? = cachedSummary

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - cachedAtMs < CACHE_MS) return@withContext
        val baseUrl = prefs.getString(PREF_INTENT_BASE_URL, null) ?: DEFAULT_BASE_URL
        val userId = prefs.getString(PREF_INTENT_USER_ID, null) ?: return@withContext
        val apiKey = prefs.getString(PREF_ANTFARM_API_KEY, null) ?: return@withContext
        try {
            val url = URL("${baseUrl.trimEnd('/')}/intent/$userId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-API-Key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (conn.responseCode != 200) return@withContext
                val body = conn.inputStream.bufferedReader().readText()
                val phone = JSONObject(body).optJSONObject("devices")?.optJSONObject("phone")
                    ?: return@withContext
                cachedSummary = formatSummary(phone)
                cachedAtMs = System.currentTimeMillis()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Health context is best-effort garnish; never block or fail a query.
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    private fun formatSummary(phone: JSONObject): String? {
        val sleepMin = phone.optLong("health_sleep_minutes", -1L)
        val hrvMs = phone.optDouble("health_hrv_ms", -1.0)
        val restingHr = phone.optLong("health_resting_hr", -1L)
        val parts = buildList {
            if (sleepMin > 0) add("slept ${sleepMin / 60}h${sleepMin % 60}m last night")
            if (hrvMs > 0) add("HRV ${hrvMs.toInt()}ms")
            if (restingHr > 0) add("resting HR $restingHr bpm")
        }
        if (parts.isEmpty()) return null
        return "User's recovery data (from their Oura ring): ${parts.joinToString(", ")}. " +
            "Factor this in when relevant (energy, training, scheduling advice); don't recite it unprompted."
    }
}
