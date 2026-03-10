package com.thinkoff.clawwatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class LocalModelResponse(
    val content: String,
    val model: String
)

class LocalModelClient {
    companion object {
        private const val TAG = "LocalModelClient"
    }

    suspend fun chat(
        baseUrl: String,
        model: String,
        transcript: List<LocalMessage>,
        systemPrompt: String
    ): Result<LocalModelResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(normalizeBaseUrl(baseUrl) + "/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
            }

            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.7)
                    put("max_tokens", 220)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        transcript.forEach { message ->
                            put(JSONObject().apply {
                                put("role", if (message.isUser) "user" else "assistant")
                                put("content", message.body)
                            })
                        }
                    })
                }.toString()

                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                val responseCode = conn.responseCode
                val responseBody = readBody(conn)
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Local Qwen failed ($responseCode): ${responseBody.take(220)}")
                }

                val json = JSONObject(responseBody)
                val choices = json.optJSONArray("choices") ?: JSONArray()
                val content = choices.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()
                if (content.isBlank()) {
                    throw IllegalStateException("Local Qwen returned an empty reply")
                }

                val responseModel = json.optString("model", model)
                Log.i(TAG, "Local model reply from $responseModel")
                LocalModelResponse(content = content, model = responseModel)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/').ifBlank { "http://127.0.0.1:8080" }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }
}
