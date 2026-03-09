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
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

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
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                }

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
