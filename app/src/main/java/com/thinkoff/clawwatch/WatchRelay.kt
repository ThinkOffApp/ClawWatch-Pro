package com.thinkoff.clawwatch

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
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
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private var responseCallback: ((String) -> Unit)? = null
    private var avatarStateCallback: ((state: String, mood: String) -> Unit)? = null

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
        }
    }
}
