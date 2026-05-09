package com.thinkoff.clawwatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Instagram Graph API OAuth flow + long-lived token management.
 *
 * Setup checklist (one-time, by petrus):
 *  1. Switch @petruspennanen to Creator account in IG app:
 *     Settings → Account → Switch to Professional Account → Creator.
 *  2. Register a Meta app at https://developers.facebook.com/apps
 *     → Create App → "Other" type → "Business" use case
 *     → Add product "Instagram Graph API"
 *     → Settings → Basic → copy App ID and App Secret.
 *  3. Add a redirect URI to the Meta app config:
 *       clawwatch://ig-oauth-callback
 *  4. Store the App ID + Secret in this app's SecurePrefs under keys
 *     "ig_app_id" and "ig_app_secret" (UI to do this is a TODO; for
 *     now they can be set programmatically or via a dev shortcut).
 *  5. Open MainActivity → Connect Instagram → consent in browser.
 *
 * After consent we exchange the short-lived code for a 60-day
 * long-lived token and persist it. The token auto-refreshes if used
 * within its TTL — a periodic refresh hook keeps it valid.
 *
 * Reference docs:
 *   https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/getting-started
 */
object InstagramAuth {
    private const val TAG = "IGAuth"

    const val REDIRECT_URI = "clawwatch://ig-oauth-callback"
    private const val OAUTH_BASE = "https://api.instagram.com/oauth/authorize"
    private const val TOKEN_BASE = "https://api.instagram.com/oauth/access_token"
    private const val LONG_LIVED_BASE = "https://graph.instagram.com/access_token"
    private const val REFRESH_BASE = "https://graph.instagram.com/refresh_access_token"

    // Scopes we need: read user's own profile + own media + own stories.
    private const val SCOPES = "instagram_business_basic,instagram_business_content_publish,instagram_business_manage_messages"

    private const val PREF_APP_ID = "ig_app_id"
    private const val PREF_APP_SECRET = "ig_app_secret"
    private const val PREF_ACCESS_TOKEN = "ig_access_token"
    private const val PREF_TOKEN_EXPIRY_MS = "ig_token_expiry_ms"
    private const val PREF_USER_ID = "ig_user_id"

    /** Build the consent URL the user opens in a browser. */
    fun buildAuthorizeUrl(context: Context): String? {
        val appId = SecurePrefs.watch(context).getString(PREF_APP_ID, null)
        if (appId.isNullOrBlank()) {
            Log.w(TAG, "ig_app_id not configured in SecurePrefs")
            return null
        }
        val params = listOf(
            "client_id" to appId,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "response_type" to "code",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$OAUTH_BASE?$params"
    }

    /** Open the consent URL in the user's default browser. */
    fun startAuthorization(context: Context): Boolean {
        val url = buildAuthorizeUrl(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not open IG OAuth: ${e.message}")
            false
        }
    }

    /**
     * Called from the MainActivity onNewIntent path when the
     * `clawwatch://ig-oauth-callback?code=...` deep link fires.
     * Exchanges short-lived code → long-lived 60-day token.
     */
    suspend fun handleCallback(context: Context, callbackUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val code = callbackUri.getQueryParameter("code")
            ?: return@withContext Result.failure(IllegalArgumentException("no code in callback"))
        val prefs = SecurePrefs.watch(context)
        val appId = prefs.getString(PREF_APP_ID, null)
            ?: return@withContext Result.failure(IllegalStateException("ig_app_id missing"))
        val appSecret = prefs.getString(PREF_APP_SECRET, null)
            ?: return@withContext Result.failure(IllegalStateException("ig_app_secret missing"))

        val shortLived = exchangeCodeForShortToken(appId, appSecret, code).getOrElse {
            return@withContext Result.failure(it)
        }
        val longLived = exchangeShortForLongToken(appSecret, shortLived.token).getOrElse {
            return@withContext Result.failure(it)
        }
        prefs.edit()
            .putString(PREF_ACCESS_TOKEN, longLived.token)
            .putLong(PREF_TOKEN_EXPIRY_MS, System.currentTimeMillis() + longLived.expiresInSec * 1000L)
            .putString(PREF_USER_ID, shortLived.userId)
            .apply()
        Log.i(TAG, "IG long-lived token stored (expires in ${longLived.expiresInSec / 86_400} days)")
        Result.success(Unit)
    }

    /** Refresh a still-valid long-lived token before it expires. */
    suspend fun refreshIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = SecurePrefs.watch(context)
        val token = prefs.getString(PREF_ACCESS_TOKEN, null) ?: return@withContext false
        val expiry = prefs.getLong(PREF_TOKEN_EXPIRY_MS, 0L)
        // Refresh inside a 7-day window before expiry.
        if (expiry - System.currentTimeMillis() > 7L * 24 * 3600 * 1000) return@withContext true
        val refreshed = refreshToken(token).getOrNull() ?: return@withContext false
        prefs.edit()
            .putString(PREF_ACCESS_TOKEN, refreshed.token)
            .putLong(PREF_TOKEN_EXPIRY_MS, System.currentTimeMillis() + refreshed.expiresInSec * 1000L)
            .apply()
        Log.i(TAG, "IG token refreshed (now expires in ${refreshed.expiresInSec / 86_400} days)")
        true
    }

    fun currentAccessToken(context: Context): String? =
        SecurePrefs.watch(context).getString(PREF_ACCESS_TOKEN, null)

    fun currentUserId(context: Context): String? =
        SecurePrefs.watch(context).getString(PREF_USER_ID, null)

    // ── Internals ────────────────────────────────────────────────────────────

    private data class ShortToken(val token: String, val userId: String)
    private data class LongToken(val token: String, val expiresInSec: Long)

    private fun exchangeCodeForShortToken(appId: String, appSecret: String, code: String): Result<ShortToken> {
        val body = listOf(
            "client_id" to appId,
            "client_secret" to appSecret,
            "grant_type" to "authorization_code",
            "redirect_uri" to REDIRECT_URI,
            "code" to code,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return postForm(TOKEN_BASE, body).mapCatching { json ->
            ShortToken(
                token = json.getString("access_token"),
                userId = json.get("user_id").toString(),
            )
        }
    }

    private fun exchangeShortForLongToken(appSecret: String, shortToken: String): Result<LongToken> {
        val url = "$LONG_LIVED_BASE?grant_type=ig_exchange_token&client_secret=${URLEncoder.encode(appSecret, "UTF-8")}&access_token=${URLEncoder.encode(shortToken, "UTF-8")}"
        return get(url).mapCatching { json ->
            LongToken(
                token = json.getString("access_token"),
                expiresInSec = json.getLong("expires_in"),
            )
        }
    }

    private fun refreshToken(currentToken: String): Result<LongToken> {
        val url = "$REFRESH_BASE?grant_type=ig_refresh_token&access_token=${URLEncoder.encode(currentToken, "UTF-8")}"
        return get(url).mapCatching { json ->
            LongToken(
                token = json.getString("access_token"),
                expiresInSec = json.getLong("expires_in"),
            )
        }
    }

    private fun get(url: String): Result<JSONObject> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, body: String): Result<JSONObject> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = (conn.inputStream ?: conn.errorStream).bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
