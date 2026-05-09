package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Polls the user's own Instagram stories and forwards new ones to
 * GroupMind as petrus's "intent state".
 *
 * Phase 1 implementation: simple coroutine loop on Dispatchers.IO,
 * 10-minute interval. Survives app lifecycle if launched from a
 * foreground service or from PhoneAgent's app-scoped supervisor.
 *
 * Phase 2 (optional, future): Instagram Graph API webhooks pushed to
 * a public HTTPS endpoint. Eliminates polling but needs server infra.
 *
 * The "intent state" mapping:
 *  - Latest story image (best available media URL) + caption become
 *    the user's current intent payload.
 *  - GroupMindIntentBridge owns the actual GroupMind shape; this
 *    watcher just hands it raw IG fields.
 */
class InstagramStoryWatcher(
    private val context: Context,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    companion object {
        private const val TAG = "IGStoryWatcher"
        const val DEFAULT_POLL_INTERVAL_MS = 10L * 60 * 1000   // 10 min
        private const val PREF_LAST_STORY_ID = "ig_last_story_id"
    }

    private var loopJob: Job? = null

    /** Start the periodic poll loop. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    InstagramAuth.refreshIfNeeded(context)
                    pollOnce()
                } catch (e: Exception) {
                    Log.w(TAG, "poll cycle failed: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        }
        Log.i(TAG, "started, interval=${pollIntervalMs}ms")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Single poll cycle — fetch /me/stories, find any IDs we haven't
     * seen since last cycle, fetch their full media payload, forward
     * to the GroupMind bridge.
     */
    private suspend fun pollOnce() = withContext(Dispatchers.IO) {
        val token = InstagramAuth.currentAccessToken(context) ?: run {
            Log.d(TAG, "no IG access token yet, skipping")
            return@withContext
        }
        val userId = InstagramAuth.currentUserId(context) ?: "me"
        val storiesUrl = "https://graph.instagram.com/$userId/stories?fields=id,media_type,media_url,permalink,caption,timestamp&access_token=${URLEncoder.encode(token, "UTF-8")}"
        val response = httpGet(storiesUrl) ?: return@withContext
        val data = response.optJSONArray("data") ?: return@withContext
        if (data.length() == 0) return@withContext

        val prefs = SecurePrefs.watch(context)
        val lastId = prefs.getString(PREF_LAST_STORY_ID, null)
        var newestId: String? = null

        // Stories are returned newest first. Walk until we hit the
        // last-seen id (or the end if first poll).
        for (i in 0 until data.length()) {
            val story = data.getJSONObject(i)
            val id = story.optString("id")
            if (id.isBlank()) continue
            if (newestId == null) newestId = id
            if (id == lastId) break    // already-seen, stop here
            // First-poll case (no lastId): only forward the single
            // newest story to avoid blasting GroupMind with backlog.
            if (lastId == null && i > 0) break

            val payload = StoryPayload(
                id = id,
                mediaType = story.optString("media_type", "IMAGE"),
                mediaUrl = story.optString("media_url", null),
                permalink = story.optString("permalink", null),
                caption = story.optString("caption", null),
                timestampIso = story.optString("timestamp", null),
            )
            try {
                GroupMindIntentBridge.publishIntentFromStory(context, payload)
            } catch (e: Exception) {
                Log.w(TAG, "publish failed for story $id: ${e.message}")
            }
        }

        if (newestId != null && newestId != lastId) {
            prefs.edit().putString(PREF_LAST_STORY_ID, newestId).apply()
        }
    }

    private fun httpGet(url: String): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val text = (conn.inputStream ?: conn.errorStream).bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET $url failed: ${e.message}")
        null
    }

    data class StoryPayload(
        val id: String,
        val mediaType: String,
        val mediaUrl: String?,
        val permalink: String?,
        val caption: String?,
        val timestampIso: String?,
    )
}
