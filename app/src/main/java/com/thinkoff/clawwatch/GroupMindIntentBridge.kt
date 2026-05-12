package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts an "intent state" update to GroupMind whenever a new IG story
 * is detected by InstagramStoryWatcher.
 *
 * The exact GroupMind shape for "user current intent" is TBD —
 * petrus needs to confirm whether it's:
 *   a) a profile field (PUT /api/v1/users/<id>/status)
 *   b) a special "intents" room post
 *   c) a dedicated /api/v1/intents endpoint
 *
 * Until then this writes to the same GroupMind room used by AntFarm
 * (room slug from SecurePrefs key "groupmind_room", default "thinkoff-development")
 * with a tagged body so it's identifiable but doesn't break anything.
 * The exact endpoint is wrapped behind one method call so we can swap
 * it without touching the watcher.
 */
object GroupMindIntentBridge {
    private const val TAG = "IGIntentBridge"

    private const val DEFAULT_BASE = "https://groupmind.one/api/v1"
    private const val DEFAULT_ROOM = "thinkoff-development"
    private const val PREF_API_KEY = "groupmind_api_key"
    private const val PREF_ROOM = "groupmind_room"
    private const val PREF_BASE_URL = "groupmind_base_url"

    /**
     * Forward a new IG story to GroupMind as an intent-state update.
     * Returns true on HTTP 200/201 success.
     */
    suspend fun publishIntentFromStory(
        context: Context,
        story: InstagramStoryWatcher.StoryPayload,
    ): Boolean = withContext(Dispatchers.IO) {
        val prefs = SecurePrefs.watch(context)
        val apiKey = prefs.getString(PREF_API_KEY, null)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "groupmind_api_key missing - cannot publish intent")
            return@withContext false
        }
        val base = prefs.getString(PREF_BASE_URL, DEFAULT_BASE) ?: DEFAULT_BASE
        val room = prefs.getString(PREF_ROOM, DEFAULT_ROOM) ?: DEFAULT_ROOM

        val body = buildIntentBody(story)
        val payload = JSONObject().apply {
            put("room", room)
            put("body", body)
            // include the IG image URL so consumers can render the
            // story thumbnail without re-fetching from IG.
            story.mediaUrl?.let { put("image_url", it) }
        }
        return@withContext httpPostJson("$base/messages", apiKey, payload.toString())
    }

    /**
     * Compose the human-readable intent body. Format:
     *   "[ig-story] {caption|"(no caption)"}  ↗ {permalink}"
     * Caption-first so the LLM-side state can read it as the current
     * activity description.
     */
    private fun buildIntentBody(story: InstagramStoryWatcher.StoryPayload): String {
        val caption = story.caption?.trim()?.takeIf { it.isNotBlank() } ?: "(no caption)"
        val link = story.permalink?.let { " ↗ $it" } ?: ""
        return "[ig-story] $caption$link"
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
                Log.i(TAG, "intent published (HTTP $code)")
                true
            } else {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "intent publish failed: HTTP $code body=${err?.take(200)}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish exception: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }
}
