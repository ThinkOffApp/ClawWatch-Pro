package com.thinkoff.clawwatch

import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREF_ANTFARM_KEY = "antfarm_api_key"
        private const val PREF_ANTFARM_ROOMS = "antfarm_rooms"
        private const val DEFAULT_ROOM = "ant-farm-management"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val antFarmClient = AntFarmClient()
    private val roomMessages = mutableListOf<LocalMessage>()
    private var activeRoomSlug: String = DEFAULT_ROOM
    private var activeRoomName: String = "Your ClawWatch room"

    private enum class Tab(
        val title: String,
        val subtitle: String
    ) {
        ROOM(
            title = "Your ClawWatch room",
            subtitle = "Private room first. Rooms and watch controls stay one tap away."
        ),
        ROOMS(
            title = "Rooms and invites",
            subtitle = "Your private room stays pinned, with joined rooms and discovery underneath."
        ),
        WATCH(
            title = "Watch dashboard",
            subtitle = "Pairing, sync, avatar, voice, diagnostics, and future billing live here."
        )
    }

    private data class LocalMessage(
        val author: String,
        val body: String,
        val isUser: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs.companion(this)

        binding.tabRoom.setOnClickListener { showTab(Tab.ROOM) }
        binding.tabRooms.setOnClickListener { showTab(Tab.ROOMS) }
        binding.tabWatch.setOnClickListener { showTab(Tab.WATCH) }
        binding.sendButton.setOnClickListener { sendComposerMessage() }
        binding.refreshRoomButton.setOnClickListener { refreshRoom() }
        binding.saveWatchSettingsButton.setOnClickListener { saveWatchSettings() }
        binding.loadRoomNowButton.setOnClickListener { refreshRoom(switchToRoom = true) }

        binding.composerInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

        binding.actionCheckWatch.setOnClickListener { runQuickAction("Check on watch") }
        binding.actionSummarizeFamily.setOnClickListener { runQuickAction("Summarize family") }
        binding.actionOpenScratchpad.setOnClickListener { runQuickAction("Open scratchpad") }
        binding.actionInviteSomeone.setOnClickListener { runQuickAction("Invite someone") }

        loadSavedSettings()
        seedRoomMessagesIfUnconfigured()
        renderRoomMessages()
        showTab(Tab.ROOM)

        if (hasRoomConfig()) {
            refreshRoom()
        }
    }

    private fun showTab(tab: Tab) {
        binding.headerTitle.text = if (tab == Tab.ROOM) activeRoomName else tab.title
        binding.headerSubtitle.text = tab.subtitle

        binding.roomPanel.alpha = if (tab == Tab.ROOM) 1f else 0f
        binding.roomsPanel.alpha = if (tab == Tab.ROOMS) 1f else 0f
        binding.watchPanel.alpha = if (tab == Tab.WATCH) 1f else 0f

        binding.roomPanel.visibility = if (tab == Tab.ROOM) View.VISIBLE else View.GONE
        binding.roomsPanel.visibility = if (tab == Tab.ROOMS) View.VISIBLE else View.GONE
        binding.watchPanel.visibility = if (tab == Tab.WATCH) View.VISIBLE else View.GONE

        styleTab(binding.tabRoom, active = tab == Tab.ROOM)
        styleTab(binding.tabRooms, active = tab == Tab.ROOMS)
        styleTab(binding.tabWatch, active = tab == Tab.WATCH)
    }

    private fun styleTab(button: Button, active: Boolean) {
        val background = if (active) R.color.tab_active else R.color.tab_inactive
        val foreground = if (active) R.color.tab_active_text else R.color.tab_inactive_text
        button.backgroundTintList = ContextCompat.getColorStateList(this, background)
        button.setTextColor(ContextCompat.getColor(this, foreground))
    }

    private fun loadSavedSettings() {
        binding.antFarmKeyInput.setText(getAntFarmKey().orEmpty())
        val room = getConfiguredRoom()
        binding.antFarmRoomInput.setText(room)
        activeRoomSlug = room
    }

    private fun saveWatchSettings() {
        prefs.edit()
            .putString(PREF_ANTFARM_KEY, binding.antFarmKeyInput.text?.toString()?.trim().orEmpty())
            .putString(PREF_ANTFARM_ROOMS, binding.antFarmRoomInput.text?.toString()?.trim().orEmpty())
            .apply()

        activeRoomSlug = getConfiguredRoom()
        binding.roomStatus.text = "Settings saved • ready to load $activeRoomSlug"
        binding.roomPresenceChip.text = "Saved"
        binding.roomHint.text = "Saved Ant Farm connection details. Load the room to verify live messaging."
    }

    private fun seedRoomMessagesIfUnconfigured() {
        if (roomMessages.isNotEmpty()) return
        if (hasRoomConfig()) return

        roomMessages += LocalMessage(
            author = "ClawWatch",
            body = "Add your Ant Farm agent key and room in the Watch tab, then load the room here.",
            isUser = false
        )
        binding.roomHint.text = "Room connection not configured yet."
        binding.roomStatus.text = "Setup needed • add key + room in Watch"
        binding.roomPresenceChip.text = "Setup"
    }

    private fun runQuickAction(label: String) {
        binding.composerInput.setText(
            when (label) {
                "Check on watch" -> "How is the watch doing right now?"
                "Summarize family" -> "What is going on with the family?"
                "Open scratchpad" -> "Open the room scratchpad and summarize the latest plan."
                "Invite someone" -> "Invite someone into this room and explain what it is for."
                else -> ""
            }
        )
        binding.composerInput.setSelection(binding.composerInput.text.length)
        sendComposerMessage()
    }

    private fun refreshRoom(switchToRoom: Boolean = false) {
        val apiKey = getAntFarmKey()
        val room = getConfiguredRoom()

        if (switchToRoom) showTab(Tab.ROOM)

        if (apiKey.isNullOrBlank()) {
            roomMessages.clear()
            seedRoomMessagesIfUnconfigured()
            renderRoomMessages()
            return
        }

        setRoomLoadingState(loading = true, message = "Syncing $room…")
        lifecycleScope.launch {
            antFarmClient.fetchRoomMessages(room, apiKey)
                .onSuccess { feed ->
                    activeRoomSlug = feed.roomSlug
                    activeRoomName = feed.roomName
                    binding.headerTitle.text = activeRoomName
                    binding.roomStatus.text = "Connected • ${feed.roomSlug} • ${feed.messages.size} messages"
                    binding.roomPresenceChip.text = "Live"
                    binding.roomHint.text = "Real Ant Farm room timeline loaded through the agent-key API."

                    roomMessages.clear()
                    roomMessages += feed.messages.map {
                        LocalMessage(
                            author = it.from,
                            body = it.body,
                            isUser = false
                        )
                    }
                    if (roomMessages.isEmpty()) {
                        roomMessages += LocalMessage(
                            author = "ClawWatch",
                            body = "No messages yet in ${feed.roomSlug}. Send one to start the test.",
                            isUser = false
                        )
                    }
                    renderRoomMessages()
                    setRoomLoadingState(loading = false, message = "Room live")
                }
                .onFailure { error ->
                    roomMessages.clear()
                    roomMessages += LocalMessage(
                        author = "ClawWatch",
                        body = "Room load failed: ${error.message ?: "unknown error"}",
                        isUser = false
                    )
                    binding.roomStatus.text = "Connection failed for $room"
                    binding.roomPresenceChip.text = "Error"
                    binding.roomHint.text = "Check the Ant Farm key and room slug in the Watch tab."
                    renderRoomMessages()
                    setRoomLoadingState(loading = false, message = "Load failed")
                }
        }
    }

    private fun sendComposerMessage() {
        val message = binding.composerInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return

        val apiKey = getAntFarmKey()
        val room = getConfiguredRoom()
        if (apiKey.isNullOrBlank()) {
            roomMessages += LocalMessage("ClawWatch", "Set the Ant Farm key first in the Watch tab.", false)
            renderRoomMessages()
            return
        }

        roomMessages += LocalMessage(author = "You", body = message, isUser = true)
        binding.composerInput.text?.clear()
        renderRoomMessages()
        setRoomLoadingState(loading = true, message = "Sending…")

        lifecycleScope.launch {
            antFarmClient.sendRoomMessage(room, apiKey, message)
                .onSuccess {
                    refreshRoom()
                }
                .onFailure { error ->
                    roomMessages += LocalMessage(
                        author = "ClawWatch",
                        body = "Send failed: ${error.message ?: "unknown error"}",
                        isUser = false
                    )
                    setRoomLoadingState(loading = false, message = "Send failed")
                    renderRoomMessages()
                }
        }
    }

    private fun renderRoomMessages() {
        binding.messagesContainer.removeAllViews()
        roomMessages.forEachIndexed { index, message ->
            binding.messagesContainer.addView(buildMessageBubble(message, index))
        }
        binding.roomPanel.post {
            binding.roomPanel.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setRoomLoadingState(loading: Boolean, message: String) {
        binding.refreshRoomButton.isEnabled = !loading
        binding.sendButton.isEnabled = !loading
        binding.loadRoomNowButton.isEnabled = !loading
        binding.roomStatus.text = message
        if (loading) {
            binding.roomPresenceChip.text = "Syncing"
        }
    }

    private fun buildMessageBubble(message: LocalMessage, index: Int): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = if (index == 0) 0 else dp(12)
            layoutParams = params
            gravity = if (message.isUser) Gravity.END else Gravity.START
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (message.isUser) R.color.user_bubble else R.color.agent_bubble
                    )
                )
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.78f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val authorView = TextView(this).apply {
            text = message.author
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (message.isUser) R.color.user_author else R.color.agent_author
                )
            )
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        val bodyView = TextView(this).apply {
            text = message.body
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.message_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.18f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            layoutParams = params
        }

        bubble.addView(authorView)
        bubble.addView(bodyView)
        wrapper.addView(bubble)
        return wrapper
    }

    private fun hasRoomConfig(): Boolean = !getAntFarmKey().isNullOrBlank()

    private fun getAntFarmKey(): String? =
        prefs.getString(PREF_ANTFARM_KEY, null)?.takeIf { it.isNotBlank() }

    private fun getConfiguredRoom(): String =
        (prefs.getString(PREF_ANTFARM_ROOMS, DEFAULT_ROOM) ?: DEFAULT_ROOM)
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: DEFAULT_ROOM

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
