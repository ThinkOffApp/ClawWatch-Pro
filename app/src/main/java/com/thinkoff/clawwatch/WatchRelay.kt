package com.thinkoff.clawwatch

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.thinkoff.clawwatch.billing.QueryQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Thin relay between the phone companion and the ClawWatch on wrist.
 * Phone sends user text on /clawwatch/query, watch runs ClawRunner and
 * responds on /clawwatch/response. Avatar state updates arrive on
 * /clawwatch/avatar-state.
 */
class WatchRelay(private val context: Context) : MessageClient.OnMessageReceivedListener {

    companion object {
        const val PATH_QUERY = "/clawwatch/query"
        const val PATH_RESPONSE = "/clawwatch/response"
        const val PATH_AVATAR_STATE = "/clawwatch/avatar-state"
        const val PATH_GEMMA_QUERY = "/clawwatch/gemma-query"
        const val PATH_GEMMA_RESPONSE = "/clawwatch/gemma-response"
        const val PATH_HISTORY_REQUEST = "/clawwatch/history-request"
        const val PATH_HISTORY_RESPONSE = "/clawwatch/history-response"
        const val PATH_BYOK_STATUS = "/clawwatch/byok-status"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private var responseCallback: ((String) -> Unit)? = null
    private var avatarStateCallback: ((state: String, mood: String) -> Unit)? = null
    private var historyCallback: ((List<Pair<String, String>>) -> Unit)? = null
    private var phoneAgent: PhoneAgent? = null
    private val gemmaScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    fun setPhoneAgent(agent: PhoneAgent) {
        phoneAgent = agent
    }

    fun setHistoryListener(callback: (List<Pair<String, String>>) -> Unit) {
        historyCallback = callback
    }

    /**
     * Request conversation history from the watch.
     * Response arrives asynchronously via historyCallback.
     */
    suspend fun requestHistory() = withContext(Dispatchers.IO) {
        val nodes = try { nodeClient.connectedNodes.await() } catch (_: Exception) { return@withContext }
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, PATH_HISTORY_REQUEST, ByteArray(0)).await()
                break
            } catch (_: Exception) { }
        }
    }

    fun start() {
        messageClient.addListener(this)
    }

    fun stop() {
        messageClient.removeListener(this)
    }

    fun setResponseListener(callback: (String) -> Unit) {
        responseCallback = callback
    }

    fun setAvatarStateListener(callback: (state: String, mood: String) -> Unit) {
        avatarStateCallback = callback
    }

    /**
     * Send a user query to the watch. Returns true if a connected watch node
     * was found and the message was dispatched.
     */
    suspend fun sendQuery(text: String): Boolean = withContext(Dispatchers.IO) {
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (_: Exception) {
            return@withContext false
        }
        if (nodes.isEmpty()) return@withContext false

        val payload = text.toByteArray(Charsets.UTF_8)
        var sent = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, PATH_QUERY, payload).await()
                sent = true
                break // send to first available watch
            } catch (_: Exception) {
                // try next node
            }
        }
        sent
    }

    /**
     * Check whether at least one watch node is reachable right now.
     */
    suspend fun isWatchConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            nodeClient.connectedNodes.await().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val data = String(event.data, Charsets.UTF_8)
        when (event.path) {
            PATH_RESPONSE -> responseCallback?.invoke(data)
            PATH_BYOK_STATUS -> {
                // Watch reports whether IT holds an Anthropic key (status
                // only, never the key). Cached here so QueryQuota can gate
                // on the merged cross-device view — the key usually lives
                // on the watch, not in this app's store.
                SecurePrefs.watch(context).edit()
                    .putBoolean(QueryQuota.PREF_WATCH_BYOK, data == "1")
                    .apply()
            }
            PATH_AVATAR_STATE -> {
                // Format: "STATE|MOOD" e.g. "SPEAKING|CHEERFUL"
                val parts = data.split("|", limit = 2)
                val state = parts.getOrElse(0) { "IDLE" }
                val mood = parts.getOrElse(1) { "NEUTRAL" }
                avatarStateCallback?.invoke(state, mood)
            }
            PATH_HISTORY_RESPONSE -> {
                // Watch sent conversation history
                try {
                    val jsonArray = org.json.JSONArray(data)
                    val history = mutableListOf<Pair<String, String>>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        history += obj.getString("role") to obj.getString("content")
                    }
                    historyCallback?.invoke(history)
                } catch (e: Exception) {
                    android.util.Log.e("WatchRelay", "Failed to parse history: ${e.message}")
                }
            }
            PATH_GEMMA_QUERY -> {
                // Watch explicitly asked for Gemma -- run inference directly
                val sourceNodeId = event.sourceNodeId
                gemmaScope.launch {
                    val agent = phoneAgent
                    if (agent == null || !agent.isAvailable()) {
                        agent?.initialize()
                    }
                    val response = try {
                        if (agent == null || !agent.isAvailable()) {
                            "Gemma not available. Download Gemma 4 E2B in Google AI Edge Gallery."
                        } else {
                            val result = agent.query(data)
                            when (result) {
                                is PhoneAgent.RouterResult.Answer -> result.text
                                is PhoneAgent.RouterResult.Escalate -> {
                                    // Gemma failed to produce an answer
                                    "Gemma could not answer: ${result.reason}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        "Gemma error: ${e.message}"
                    }
                    val payload = response.toByteArray(Charsets.UTF_8)
                    messageClient.sendMessage(sourceNodeId, PATH_GEMMA_RESPONSE, payload)
                }
            }
        }
    }
}
