package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Watch-side relay that sends queries to the phone's Gemma model
 * and waits for the response. Uses Wearable MessageAPI over Bluetooth.
 *
 * Paths:
 *   /clawwatch/gemma-query    watch → phone (user text)
 *   /clawwatch/gemma-response phone → watch (Gemma result)
 */
class PhoneGemmaRelay(private val context: Context) : MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "PhoneGemmaRelay"
        const val PATH_GEMMA_QUERY = "/clawwatch/gemma-query"
        const val PATH_GEMMA_RESPONSE = "/clawwatch/gemma-response"
        const val PATH_HISTORY_REQUEST = "/clawwatch/history-request"
        const val PATH_HISTORY_RESPONSE = "/clawwatch/history-response"
        private const val TIMEOUT_MS = 30_000L
    }

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private var pendingResponse: CompletableDeferred<String>? = null

    fun start() {
        messageClient.addListener(this)
    }

    fun stop() {
        messageClient.removeListener(this)
        pendingResponse?.cancel()
    }

    /**
     * Check if the phone companion with Gemma is reachable.
     */
    suspend fun isPhoneReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            nodeClient.connectedNodes.await().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Send a query to the phone's Gemma and wait for the response.
     * Returns the response text or throws on timeout/failure.
     */
    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            return@withContext Result.failure(RuntimeException("Phone not reachable: ${e.message}"))
        }
        if (nodes.isEmpty()) {
            return@withContext Result.failure(RuntimeException("No phone connected"))
        }

        val deferred = CompletableDeferred<String>()
        pendingResponse = deferred

        val payload = prompt.toByteArray(Charsets.UTF_8)
        var sent = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, PATH_GEMMA_QUERY, payload).await()
                sent = true
                break
            } catch (_: Exception) { }
        }

        if (!sent) {
            pendingResponse = null
            return@withContext Result.failure(RuntimeException("Failed to send to phone"))
        }

        try {
            val response = withTimeout(TIMEOUT_MS) { deferred.await() }
            Result.success(response)
        } catch (_: TimeoutCancellationException) {
            pendingResponse = null
            Result.failure(RuntimeException("Phone Gemma timed out (${TIMEOUT_MS}ms)"))
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_GEMMA_RESPONSE) {
            val response = String(event.data, Charsets.UTF_8)
            Log.d(TAG, "Gemma response received: ${response.take(80)}")
            pendingResponse?.complete(response)
            pendingResponse = null
        }
    }
}
