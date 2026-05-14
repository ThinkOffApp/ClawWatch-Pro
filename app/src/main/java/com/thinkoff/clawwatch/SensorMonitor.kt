package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SensorMonitor — opt-in continuous ambient listening for ClawWatch on the phone.
 *
 * Reuses VoiceEngine's on-device Vosk recognizer (already initialised for the
 * watch-trigger flow) for offline STT. Each final utterance is POSTed to a
 * configured GroupMind room (default: "petrus-ambient") with metadata tagging
 * the source as ambient sensor capture.
 *
 * Battery: Vosk is ~1-2 %/h on a modern phone. We let VoiceEngine manage the
 * mic lifecycle; SensorMonitor just owns the result handler + HTTP publish.
 *
 * **Not wired into MainActivity yet** — see start()/stop() below for entry
 * points. Caller should:
 *   1. Confirm RECORD_AUDIO permission
 *   2. Confirm voiceEngine.initVosk has succeeded
 *   3. Call SensorMonitor(context, voiceEngine).start()
 *
 * Stop on app background / explicit user toggle / when battery < 20 %.
 *
 * Prefs (SecurePrefs.watch):
 *   - groupmind_api_key: required, the agent's posting key
 *   - groupmind_sensor_room: optional, default "petrus-ambient"
 *   - groupmind_base_url: optional, default https://groupmind.one/api/v1
 */
class SensorMonitor(
    private val context: Context,
    private val voiceEngine: VoiceEngine,
) {
    companion object {
        private const val TAG = "SensorMonitor"
        private const val DEFAULT_BASE = "https://groupmind.one/api/v1"
        private const val DEFAULT_SENSOR_ROOM = "petrus-ambient"
        private const val PREF_API_KEY = "groupmind_api_key"
        private const val PREF_SENSOR_ROOM = "groupmind_sensor_room"
        private const val PREF_BASE_URL = "groupmind_base_url"
        // Minimum gap between published utterances. Avoids spamming the room
        // when Vosk fires several short results in quick succession.
        private const val MIN_PUBLISH_GAP_MS = 3_000L
        // Drop utterances shorter than this many characters (likely noise /
        // half-words / hallucinated tokens from the offline model).
        private const val MIN_UTTERANCE_LEN = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPublishMs: Long = 0L
    private var lastPublishedText: String? = null

    @Volatile
    private var running: Boolean = false

    fun isRunning(): Boolean = running

    /**
     * Start continuous mic capture. Each final utterance is published to the
     * configured GroupMind sensor room. Idempotent if already running.
     */
    fun start() {
        if (running) {
            Log.i(TAG, "start() called but already running")
            return
        }
        running = true
        Log.i(TAG, "starting ambient listening")
        voiceEngine.startListening(
            onResult = { text -> handleFinalUtterance(text) },
            onPartial = { /* drop partials; only publish stable finals */ },
        )
    }

    /** Stop listening + cancel any in-flight publishes. */
    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "stopping ambient listening")
        voiceEngine.stopListening()
    }

    private fun handleFinalUtterance(rawText: String) {
        val text = rawText.trim()
        if (text.length < MIN_UTTERANCE_LEN) return
        if (text.equals(lastPublishedText, ignoreCase = true)) return // dedup repeats

        val now = System.currentTimeMillis()
        if (now - lastPublishMs < MIN_PUBLISH_GAP_MS) {
            Log.i(TAG, "rate-limited: '$text' too soon after last publish")
            return
        }
        lastPublishMs = now
        lastPublishedText = text

        scope.launch { publish(text) }
    }

    private suspend fun publish(text: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = SecurePrefs.watch(context)
        val apiKey = prefs.getString(PREF_API_KEY, null)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "groupmind_api_key missing - cannot publish utterance")
            return@withContext false
        }
        val base = prefs.getString(PREF_BASE_URL, DEFAULT_BASE) ?: DEFAULT_BASE
        val room = prefs.getString(PREF_SENSOR_ROOM, DEFAULT_SENSOR_ROOM) ?: DEFAULT_SENSOR_ROOM

        val metadata = JSONObject().apply {
            put("source", "clawwatch-sensor/phone")
            put("kind", "ambient_audio_transcript")
            put("stt_engine", "vosk-android")
            put("visibility", "agents-only")
        }
        val payload = JSONObject().apply {
            put("room", room)
            put("body", "[ambient] $text")
            put("metadata", metadata)
        }

        return@withContext httpPostJson("$base/messages", apiKey, payload.toString())
    }

    private fun httpPostJson(url: String, apiKey: String, jsonBody: String): Boolean {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", apiKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "open conn failed: ${e.message}")
            return false
        }
        return try {
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "utterance published (HTTP $code)")
                true
            } else {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "publish failed: HTTP $code body=${err?.take(200)}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish exception: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    /** Tear down the coroutine scope. Call when the host activity finalises. */
    fun destroy() {
        stop()
        scope.cancel()
    }
}
