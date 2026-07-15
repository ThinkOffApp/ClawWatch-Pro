package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device inference via a local Ollama server (default 127.0.0.1:11434).
 *
 * The phone hosts Ollama, so a big model (e.g. Bonsai 27B / 1B-active MoE)
 * runs FULLY on-device — offline, private, zero API cost. ClawWatch's watch
 * surface offloads inference to the phone; this class is the phone-side brain.
 *
 * Mirrors PhoneAgent's shape (query -> PhoneAgent.RouterResult) so it drops
 * into the same router with Gemma/LiteRT kept as the automatic fallback when
 * Ollama isn't running.
 *
 * Model selection: auto-discovers an installed tag containing MODEL_HINT
 * ("bonsai") via GET /api/tags, else the first model, unless overridden by the
 * "ollama_model" pref. Host/port overridable via "ollama_host" pref.
 */
class OllamaAgent(private val context: Context) {

    companion object {
        private const val TAG = "OllamaAgent"
        private const val DEFAULT_HOST = "http://127.0.0.1:11434"
        private const val MODEL_HINT = "bonsai"
        private const val PREF_HOST = "ollama_host"
        private const val PREF_MODEL = "ollama_model"
        private const val CONNECT_TIMEOUT_MS = 4_000
        // On-device generation of a full reply can take a while on a phone.
        private const val READ_TIMEOUT_MS = 120_000
    }

    private var resolvedModel: String? = null

    private fun host(): String =
        prefString(PREF_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

    private fun prefString(key: String): String? = try {
        // Best-effort read; never let a prefs hiccup break inference.
        context.getSharedPreferences("clawwatch_secure_prefs", Context.MODE_PRIVATE)
            .getString(key, null)
    } catch (_: Exception) { null }

    /** True when the Ollama server responds and at least one model is installed. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        resolveModel() != null
    }

    fun getModelInfo(): String =
        resolvedModel?.let { "Ollama ($it)" } ?: "Ollama (not connected)"

    /** Discover which model to use; caches the result for the session. */
    private suspend fun resolveModel(): String? = withContext(Dispatchers.IO) {
        resolvedModel?.let { return@withContext it }

        val override = prefString(PREF_MODEL)?.takeIf { it.isNotBlank() }
        if (override != null) { resolvedModel = override; return@withContext override }

        val tags = try { getJson("${host()}/api/tags") } catch (e: Exception) {
            Log.d(TAG, "Ollama not reachable: ${e.message}")
            return@withContext null
        }
        val models = tags?.optJSONArray("models") ?: return@withContext null
        val names = ArrayList<String>(models.length())
        for (i in 0 until models.length()) {
            models.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        if (names.isEmpty()) return@withContext null
        // Prefer a Bonsai tag; else the first installed model.
        val chosen = names.firstOrNull { it.contains(MODEL_HINT, ignoreCase = true) } ?: names.first()
        resolvedModel = chosen
        Log.i(TAG, "Ollama model: $chosen (installed: $names)")
        chosen
    }

    suspend fun query(prompt: String, systemPrompt: String? = null): PhoneAgent.RouterResult =
        withContext(Dispatchers.IO) {
            val model = resolveModel()
                ?: return@withContext PhoneAgent.RouterResult.Escalate("ollama_unavailable", "Ollama not running")
            try {
                val messages = JSONArray()
                if (!systemPrompt.isNullOrBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                messages.put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("stream", false)
                    .toString()

                val resp = postJson("${host()}/api/chat", body)
                    ?: return@withContext PhoneAgent.RouterResult.Escalate("ollama_no_response", prompt.take(80))
                val text = resp.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (text.isBlank()) {
                    PhoneAgent.RouterResult.Escalate("ollama_empty", prompt.take(80))
                } else {
                    PhoneAgent.RouterResult.Answer(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ollama inference failed: ${e.message}", e)
                PhoneAgent.RouterResult.Escalate("ollama_error", e.message?.take(80) ?: "unknown")
            }
        }

    private fun getJson(urlStr: String): JSONObject? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }

    private fun postJson(urlStr: String, body: String): JSONObject? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Ollama POST ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }
}
