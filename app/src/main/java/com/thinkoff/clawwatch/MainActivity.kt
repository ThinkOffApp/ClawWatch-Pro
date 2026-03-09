package com.thinkoff.clawwatch

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

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
    private lateinit var messageAdapter: RoomMessageAdapter
    private var activeRoomSlug: String = DEFAULT_ROOM
    private var activeRoomName: String = "Room"
    private val localTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isoTimeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs.companion(this)
        messageAdapter = RoomMessageAdapter(roomMessages)

        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = messageAdapter

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
            timestamp = nowTime(),
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
        binding.composerInput.text?.let { binding.composerInput.setSelection(it.length) }
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
                            timestamp = formatTimestamp(it.createdAt),
                            isUser = false
                        )
                    }
                    if (roomMessages.isEmpty()) {
                        roomMessages += LocalMessage(
                            author = "ClawWatch",
                            body = "No messages yet in ${feed.roomSlug}. Send one to start the test.",
                            timestamp = nowTime(),
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
                        timestamp = nowTime(),
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
            roomMessages += LocalMessage("ClawWatch", "Set the Ant Farm key first in the Watch tab.", nowTime(), false)
            renderRoomMessages()
            return
        }

        roomMessages += LocalMessage(author = "You", body = message, timestamp = nowTime(), isUser = true)
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
                        timestamp = nowTime(),
                        isUser = false
                    )
                    setRoomLoadingState(loading = false, message = "Send failed")
                    renderRoomMessages()
                }
        }
    }

    private fun renderRoomMessages() {
        messageAdapter.notifyDataSetChanged()
        if (roomMessages.isNotEmpty()) {
            binding.recyclerView.post {
                binding.recyclerView.scrollToPosition(roomMessages.size - 1)
            }
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

    private fun hasRoomConfig(): Boolean = !getAntFarmKey().isNullOrBlank()

    private fun getAntFarmKey(): String? =
        prefs.getString(PREF_ANTFARM_KEY, null)?.takeIf { it.isNotBlank() }

    private fun getConfiguredRoom(): String =
        (prefs.getString(PREF_ANTFARM_ROOMS, DEFAULT_ROOM) ?: DEFAULT_ROOM)
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: DEFAULT_ROOM

    private fun nowTime(): String = localTimeFormat.format(Date())

    private fun formatTimestamp(value: String): String {
        if (value.isBlank()) return nowTime()
        return runCatching { isoTimeFormat.format(Instant.parse(value)) }.getOrElse { value.take(16) }
    }
}
