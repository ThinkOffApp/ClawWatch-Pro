package com.thinkoff.clawwatch

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
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
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private var responseCallback: ((String) -> Unit)? = null
    private var avatarStateCallback: ((state: String, mood: String) -> Unit)? = null
    private var phoneAgent: PhoneAgent? = null
    private val gemmaScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    fun setPhoneAgent(agent: PhoneAgent) {
        phoneAgent = agent
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
            PATH_AVATAR_STATE -> {
                // Format: "STATE|MOOD" e.g. "SPEAKING|CHEERFUL"
                val parts = data.split("|", limit = 2)
                val state = parts.getOrElse(0) { "IDLE" }
                val mood = parts.getOrElse(1) { "NEUTRAL" }
                avatarStateCallback?.invoke(state, mood)
            }
            PATH_GEMMA_QUERY -> {
                // Watch asked phone to run Gemma locally
                val sourceNodeId = event.sourceNodeId
                gemmaScope.launch {
                    val agent = phoneAgent
                    if (agent == null || !agent.isAvailable()) {
                        agent?.initialize()
                    }
                    val response = try {
                        val result = agent?.query(data)
                        when (result) {
                            is PhoneAgent.RouterResult.Answer -> result.text
                            is PhoneAgent.RouterResult.Escalate -> {
                                // Force Gemma to answer anyway (watch explicitly asked for Gemma)
                                "I'm not sure about that. Try asking with Opus mode for a better answer."
                            }
                            null -> "Gemma not available on this phone."
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
