package com.thinkoff.clawwatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AntFarmMessage(
    val id: String,
    val from: String,
    val body: String,
    val createdAt: String,
    val isHuman: Boolean = false
)

data class AntFarmRoomFeed(
    val roomSlug: String,
    val roomName: String,
    val messages: List<AntFarmMessage>
)

data class AntFarmDirectFeed(
    val target: String,
    val messages: List<AntFarmMessage>
)

data class AntFarmDirectThread(
    val target: String,
    val preview: String,
    val createdAt: String
)

class AntFarmClient(
    private val baseUrl: String = "https://antfarm.world"
) {
    companion object {
        private const val TAG = "AntFarmClient"
    }

    suspend fun fetchRoomMessages(room: String, apiKey: String, limit: Int = 30): Result<AntFarmRoomFeed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encodedRoom = URLEncoder.encode(room, "UTF-8")
                val url = URL("$baseUrl/api/v1/rooms/$encodedRoom/messages?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-API-Key", apiKey)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                try {
                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode != 200) {
                        throw IllegalStateException("Room fetch failed ($responseCode): ${responseBody.take(180)}")
                    }

                    val json = JSONObject(responseBody)
                    val roomName = json.optString("room_name", room)
                    val items = json.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val body = item.optString("body", "").trim()
                            if (body.isBlank()) continue
                            add(
                                AntFarmMessage(
                                    id = item.optString("id", "msg-$i"),
                                    from = item.optString("from", "unknown"),
                                    body = body,
                                    createdAt = item.optString("created_at", ""),
                                    isHuman = item.optBoolean("isHuman", false)
                                )
                            )
                        }
                    }.asReversed()

                    Log.i(TAG, "Fetched ${messages.size} messages for $room")
                    AntFarmRoomFeed(
                        roomSlug = json.optString("room", room),
                        roomName = roomName,
                        messages = messages
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun sendRoomMessage(room: String, apiKey: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encodedRoom = URLEncoder.encode(room, "UTF-8")
                val url = URL("$baseUrl/api/v1/rooms/$encodedRoom/messages")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-API-Key", apiKey)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                }

                try {
                    val payload = JSONObject().apply {
                        put("body", body)
                    }.toString()
                    OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Send failed ($responseCode): ${responseBody.take(180)}")
                    }

                    Log.i(TAG, "Sent room message to $room")
                    Unit
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun fetchDirectMessages(target: String, apiKey: String, limit: Int = 100): Result<AntFarmDirectFeed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedTarget = target.trim().removePrefix("@")
                val displayTarget = if (target.trim().startsWith("@")) target.trim() else "@$normalizedTarget"
                val url = URL("$baseUrl/api/v1/messages?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-API-Key", apiKey)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                try {
                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode != 200) {
                        throw IllegalStateException("Direct fetch failed ($responseCode): ${responseBody.take(180)}")
                    }

                    val json = JSONObject(responseBody)
                    val items = json.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            if (item.optString("type", "dm") != "dm") continue

                            val from = item.optString("from", "").removePrefix("@")
                            val to = item.optString("to", "").removePrefix("@")
                            if (from != normalizedTarget && to != normalizedTarget) continue

                            val body = item.optString("body", "").trim()
                            if (body.isBlank()) continue

                            add(
                                AntFarmMessage(
                                    id = item.optString("id", "dm-$i"),
                                    from = item.optString("from_name", "").ifBlank { item.optString("from", normalizedTarget) },
                                    body = body,
                                    createdAt = item.optString("created_at", ""),
                                    isHuman = true
                                )
                            )
                        }
                    }.asReversed()

                    Log.i(TAG, "Fetched ${messages.size} direct messages for $normalizedTarget")
                    AntFarmDirectFeed(
                        target = displayTarget,
                        messages = messages
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun fetchDirectMessagesAsHuman(target: String, googleIdToken: String, limit: Int = 100): Result<AntFarmDirectFeed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedTarget = target.trim().removePrefix("@")
                val displayTarget = if (target.trim().startsWith("@")) target.trim() else "@$normalizedTarget"
                val url = URL("$baseUrl/api/v1/messages?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Google-Id-Token", googleIdToken)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                try {
                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode != 200) {
                        throw IllegalStateException("Direct fetch failed ($responseCode): ${responseBody.take(180)}")
                    }

                    val json = JSONObject(responseBody)
                    val items = json.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            if (item.optString("type", "dm") != "dm") continue

                            val from = item.optString("from", "").removePrefix("@")
                            val to = item.optString("to", "").removePrefix("@")
                            if (from != normalizedTarget && to != normalizedTarget) continue

                            val body = item.optString("body", "").trim()
                            if (body.isBlank()) continue

                            add(
                                AntFarmMessage(
                                    id = item.optString("id", "dm-$i"),
                                    from = item.optString("from_name", "").ifBlank { item.optString("from", normalizedTarget) },
                                    body = body,
                                    createdAt = item.optString("created_at", ""),
                                    isHuman = item.optString("from", "").startsWith("@")
                                )
                            )
                        }
                    }.asReversed()

                    Log.i(TAG, "Fetched ${messages.size} human direct messages for $normalizedTarget")
                    AntFarmDirectFeed(
                        target = displayTarget,
                        messages = messages
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun fetchRecentDirectThreadsAsHuman(googleIdToken: String, limit: Int = 100): Result<List<AntFarmDirectThread>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("$baseUrl/api/v1/messages?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Google-Id-Token", googleIdToken)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                try {
                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode != 200) {
                        throw IllegalStateException("Recent DM fetch failed ($responseCode): ${responseBody.take(180)}")
                    }

                    val json = JSONObject(responseBody)
                    val yourHandle = json.optString("your_handle", "").trim().removePrefix("@")
                    val items = json.optJSONArray("messages") ?: JSONArray()
                    val threads = linkedMapOf<String, AntFarmDirectThread>()

                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        if (item.optString("type", "dm") != "dm") continue

                        val from = item.optString("from", "").trim()
                        val to = item.optString("to", "").trim()
                        val body = item.optString("body", "").trim()
                        if (body.isBlank()) continue

                        val counterpart = when {
                            yourHandle.isNotBlank() && from.removePrefix("@").equals(yourHandle, ignoreCase = true) -> to
                            yourHandle.isNotBlank() && to.removePrefix("@").equals(yourHandle, ignoreCase = true) -> from
                            from.equals(to, ignoreCase = true) -> continue
                            else -> continue
                        }.trim()

                        if (counterpart.isBlank()) continue
                        val slug = if (counterpart.startsWith("@")) counterpart else "@$counterpart"
                        val createdAt = item.optString("created_at", "")
                        val existing = threads[slug]
                        if (existing == null || createdAt > existing.createdAt) {
                            threads[slug] = AntFarmDirectThread(
                                target = slug,
                                preview = body.take(96),
                                createdAt = createdAt
                            )
                        }
                    }

                    threads.values.sortedByDescending { it.createdAt }
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun sendDirectMessage(target: String, apiKey: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedTarget = target.trim().removePrefix("@")
                val url = URL("$baseUrl/api/v1/messages")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-API-Key", apiKey)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                }

                try {
                    val payload = JSONObject().apply {
                        put("to", normalizedTarget)
                        put("body", body)
                    }.toString()
                    OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Direct send failed ($responseCode): ${responseBody.take(180)}")
                    }

                    Log.i(TAG, "Sent direct message to $normalizedTarget")
                    Unit
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun sendDirectMessageAsHuman(target: String, googleIdToken: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedTarget = target.trim().removePrefix("@")
                val url = URL("$baseUrl/api/v1/messages")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Google-Id-Token", googleIdToken)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                }

                try {
                    val payload = JSONObject().apply {
                        put("to", normalizedTarget)
                        put("body", body)
                    }.toString()
                    OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                    val responseCode = conn.responseCode
                    val responseBody = readBody(conn)
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Direct send failed ($responseCode): ${responseBody.take(180)}")
                    }

                    Log.i(TAG, "Sent human direct message to $normalizedTarget")
                    Unit
                } finally {
                    conn.disconnect()
                }
            }
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }
}
