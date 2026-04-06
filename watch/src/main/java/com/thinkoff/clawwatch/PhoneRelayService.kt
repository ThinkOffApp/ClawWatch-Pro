package com.thinkoff.clawwatch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives queries from the phone companion via Wearable MessageAPI,
 * runs them through ClawRunner, and sends the response back.
 *
 * Paths:
 *   /clawwatch/query    — phone → watch (user text)
 *   /clawwatch/response — watch → phone (ClawRunner result)
 *   /clawwatch/avatar-state — watch → phone (STATE|MOOD)
 */
class PhoneRelayService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneRelayService"
        const val PATH_QUERY = "/clawwatch/query"
        const val PATH_RESPONSE = "/clawwatch/response"
        const val PATH_AVATAR_STATE = "/clawwatch/avatar-state"
        const val PATH_HISTORY_REQUEST = "/clawwatch/history-request"
        const val PATH_HISTORY_RESPONSE = "/clawwatch/history-response"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_HISTORY_REQUEST) {
            // Phone is requesting conversation history
            val sourceNodeId = event.sourceNodeId
            serviceScope.launch {
                val runner = ClawRunner(applicationContext)
                val history = runner.getConversationHistory()
                // Format as JSON array: [{"role":"user","content":"..."},...]
                val json = org.json.JSONArray()
                history.forEach { (role, content) ->
                    json.put(org.json.JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                val payload = json.toString().toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(sourceNodeId, PATH_HISTORY_RESPONSE, payload)
            }
            return
        }
        if (event.path != PATH_QUERY) return

        val queryText = String(event.data, Charsets.UTF_8)
        val sourceNodeId = event.sourceNodeId
        Log.i(TAG, "Phone query received: ${queryText.take(80)}")

        // Send "thinking" state to phone
        sendAvatarState(sourceNodeId, "THINKING", "NEUTRAL")

        serviceScope.launch {
            val runner = ClawRunner(applicationContext)
            val result = runner.query(queryText)

            result
                .onSuccess { response ->
                    sendAvatarState(sourceNodeId, "SPEAKING", "CHEERFUL")
                    sendResponse(sourceNodeId, response)
                    // After a short delay, go back to idle
                    kotlinx.coroutines.delay(1500L)
                    sendAvatarState(sourceNodeId, "IDLE", "NEUTRAL")
                }
                .onFailure { error ->
                    sendAvatarState(sourceNodeId, "ERROR", "SERIOUS")
                    sendResponse(sourceNodeId, "Sorry, I couldn't process that: ${error.message ?: "unknown error"}")
                    kotlinx.coroutines.delay(1500L)
                    sendAvatarState(sourceNodeId, "IDLE", "NEUTRAL")
                }
        }
    }

    private fun sendResponse(nodeId: String, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(applicationContext)
            .sendMessage(nodeId, PATH_RESPONSE, payload)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send response to phone: ${e.message}")
            }
    }

    private fun sendAvatarState(nodeId: String, state: String, mood: String) {
        val payload = "$state|$mood".toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(applicationContext)
            .sendMessage(nodeId, PATH_AVATAR_STATE, payload)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send avatar state to phone: ${e.message}")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
