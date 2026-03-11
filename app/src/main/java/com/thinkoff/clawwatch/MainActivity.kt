package com.thinkoff.clawwatch

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
        private const val PREF_LOCAL_MODEL_BASE_URL = "local_model_base_url"
        private const val PREF_LOCAL_MODEL_NAME = "local_model_name"
        private const val PREF_HUMAN_EMAIL = "human_email"
        private const val PREF_HUMAN_DISPLAY_NAME = "human_display_name"
        private const val PREF_HUMAN_AVATAR_URL = "human_avatar_url"
        private const val DEFAULT_ROOM = "ant-farm-management"
        private const val DEFAULT_TEST_ANTFARM_KEY = "antfarm_67efac6733223e873ab305e1e48e9c6a3f573eab38d07b00c8eabffd718d0b2b"
        private const val DEFAULT_LOCAL_URL = "http://127.0.0.1:8080"
        private const val DEFAULT_LOCAL_MODEL = "qwen2.5-1.5b-instruct"
        private const val LOCAL_SYSTEM_PROMPT =
            "You are ClawWatch local Qwen. Keep replies short, direct, and useful for a smartwatch companion."
    }

    private enum class Tab(
        val title: String,
        val subtitle: String
    ) {
        ROOM(
            title = "Room",
            subtitle = "Chat with the selected conversation target."
        ),
        ROOMS(
            title = "Rooms",
            subtitle = "Switch between your live Ant Farm room and a direct local Qwen chat."
        ),
        WATCH(
            title = "Watch dashboard",
            subtitle = "Configure the live room and local model endpoint for testing."
        )
    }

    private enum class RoomMode(
        val title: String,
        val subtitle: String,
        val hint: String,
        val chip: String
    ) {
        ANT_FARM(
            title = "Family room",
            subtitle = "Live Ant Farm room timeline and messaging.",
            hint = "Connected room state and message timeline appear here.",
            chip = "Live room"
        ),
        LOCAL_QWEN(
            title = "1:1 with Qwen",
            subtitle = "Direct local chat for the phone-hosted model experiment.",
            hint = "This mode talks to a local OpenAI-compatible endpoint served from the phone.",
            chip = "Local model"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var googleSignInManager: GoogleSignInManager
    private val antFarmClient = AntFarmClient()
    private val localModelClient = LocalModelClient()
    private val roomMessages = mutableListOf<LocalMessage>()
    private lateinit var messageAdapter: RoomMessageAdapter
    private lateinit var roomLayoutManager: LinearLayoutManager
    private var autoScrollEnabled = true
    private var activeTab: Tab = Tab.ROOM
    private var activeRoomMode: RoomMode = RoomMode.ANT_FARM
    private var activeRoomSlug: String = DEFAULT_ROOM
    private var activeRoomName: String = RoomMode.ANT_FARM.title
    private val localTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isoTimeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            googleSignInManager.handleSignInResult(result.data)
                .onSuccess { user ->
                    persistSignedInUser(user)
                    binding.roomStatus.text = "Signed in as ${getCurrentNickname() ?: user.displayName ?: user.email}"
                    binding.roomPresenceChip.text = "Owner"
                    binding.roomHint.text = "Owner setup complete. The linked ClawWatch agent can now load the room."
                    if (isHumanReady()) {
                        refreshActiveConversation()
                    }
                }
                .onFailure { error ->
                    binding.roomStatus.text = "Google sign-in failed"
                    binding.roomPresenceChip.text = "Error"
                    binding.roomHint.text = error.message ?: "Google sign-in did not complete."
                    updateOwnerUi()
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs.companion(this)
        googleSignInManager = GoogleSignInManager(this)
        messageAdapter = RoomMessageAdapter(roomMessages)

        roomLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.layoutManager = roomLayoutManager
        binding.recyclerView.adapter = messageAdapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                autoScrollEnabled = isNearBottom()
                updateScrollToBottomFab()
            }
        })
        binding.scrollToBottomFab.setOnClickListener {
            autoScrollEnabled = true
            scrollToBottom(smooth = true)
        }

        binding.tabRoom.setOnClickListener { showTab(Tab.ROOM) }
        binding.tabRooms.setOnClickListener { showTab(Tab.ROOMS) }
        binding.tabWatch.setOnClickListener { showTab(Tab.WATCH) }
        binding.sendButton.setOnClickListener { sendComposerMessage() }
        binding.refreshRoomButton.setOnClickListener { refreshActiveConversation() }
        binding.saveWatchSettingsButton.setOnClickListener { saveWatchSettings() }
        binding.googleSignInButton.setOnClickListener {
            googleSignInLauncher.launch(googleSignInManager.getSignInIntent())
        }
        binding.googleSignOutButton.setOnClickListener { signOutOwner() }
        binding.saveNicknameButton.setOnClickListener { saveNickname() }
        binding.loadRoomNowButton.setOnClickListener {
            activeRoomMode = RoomMode.ANT_FARM
            refreshActiveConversation(switchToRoom = true)
        }
        binding.openAntFarmRoomButton.setOnClickListener {
            activeRoomMode = RoomMode.ANT_FARM
            refreshActiveConversation(switchToRoom = true)
        }
        binding.openLocalQwenButton.setOnClickListener {
            activeRoomMode = RoomMode.LOCAL_QWEN
            refreshActiveConversation(switchToRoom = true)
        }

        binding.composerInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

        binding.actionCheckWatch.setOnClickListener { runQuickAction("Check on watch") }
        binding.actionSummarizeFamily.setOnClickListener { runQuickAction("Summarize family") }
        binding.actionOpenScratchpad.setOnClickListener { runQuickAction("Open scratchpad") }
        binding.actionInviteSomeone.setOnClickListener { runQuickAction("Invite someone") }

        loadSavedSettings()
        seedInitialMessages()
        renderRoomMessages(forceScroll = true)
        showTab(Tab.ROOM)

        if (hasRoomConfig()) {
            refreshAntFarmRoom()
        }
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        applyHeader()

        binding.roomPanel.alpha = if (tab == Tab.ROOM) 1f else 0f
        binding.roomsPanel.alpha = if (tab == Tab.ROOMS) 1f else 0f
        binding.watchPanel.alpha = if (tab == Tab.WATCH) 1f else 0f

        binding.roomPanel.visibility = if (tab == Tab.ROOM) View.VISIBLE else View.GONE
        binding.roomsPanel.visibility = if (tab == Tab.ROOMS) View.VISIBLE else View.GONE
        binding.watchPanel.visibility = if (tab == Tab.WATCH) View.VISIBLE else View.GONE

        styleTab(binding.tabRoom, active = tab == Tab.ROOM)
        styleTab(binding.tabRooms, active = tab == Tab.ROOMS)
        styleTab(binding.tabWatch, active = tab == Tab.WATCH)
        styleModeButtons()
    }

    private fun applyHeader() {
        when (activeTab) {
            Tab.ROOM -> {
                binding.headerTitle.text = activeRoomName
                binding.headerSubtitle.text = activeRoomMode.subtitle
            }
            else -> {
                binding.headerTitle.text = activeTab.title
                binding.headerSubtitle.text = activeTab.subtitle
            }
        }
    }

    private fun styleTab(button: Button, active: Boolean) {
        val background = if (active) R.color.tab_active else R.color.tab_inactive
        val foreground = if (active) R.color.tab_active_text else R.color.tab_inactive_text
        button.backgroundTintList = ContextCompat.getColorStateList(this, background)
        button.setTextColor(ContextCompat.getColor(this, foreground))
    }

    private fun styleModeButtons() {
        styleModeButton(binding.openAntFarmRoomButton, activeRoomMode == RoomMode.ANT_FARM)
        styleModeButton(binding.openLocalQwenButton, activeRoomMode == RoomMode.LOCAL_QWEN)
    }

    private fun styleModeButton(button: Button, active: Boolean) {
        val background = if (active) R.color.tab_active else R.color.tab_inactive
        val foreground = if (active) R.color.tab_active_text else R.color.tab_inactive_text
        button.backgroundTintList = ContextCompat.getColorStateList(this, background)
        button.setTextColor(ContextCompat.getColor(this, foreground))
    }

    private fun loadSavedSettings() {
        ensureInternalTestDefaults()
        binding.antFarmRoomInput.setText(getConfiguredRoom())
        binding.localModelBaseUrlInput.setText(getLocalModelBaseUrl())
        binding.localModelNameInput.setText(getLocalModelName())
        activeRoomSlug = getConfiguredRoom()
        googleSignInManager.getCurrentUser()?.let { persistSignedInUser(it, updateUi = false) }
        updateOwnerUi()
    }

    private fun ensureInternalTestDefaults() {
        val currentKey = prefs.getString(PREF_ANTFARM_KEY, null)?.trim().orEmpty()
        val currentRooms = prefs.getString(PREF_ANTFARM_ROOMS, null)?.trim().orEmpty()
        if (currentKey.isNotBlank() && currentRooms.isNotBlank()) return

        prefs.edit().apply {
            if (currentKey.isBlank()) {
                putString(PREF_ANTFARM_KEY, DEFAULT_TEST_ANTFARM_KEY)
            }
            if (currentRooms.isBlank()) {
                putString(PREF_ANTFARM_ROOMS, DEFAULT_ROOM)
            }
        }.apply()
    }

    private fun saveWatchSettings() {
        prefs.edit()
            .putString(PREF_ANTFARM_ROOMS, binding.antFarmRoomInput.text?.toString()?.trim().orEmpty())
            .putString(PREF_LOCAL_MODEL_BASE_URL, binding.localModelBaseUrlInput.text?.toString()?.trim().orEmpty())
            .putString(PREF_LOCAL_MODEL_NAME, binding.localModelNameInput.text?.toString()?.trim().orEmpty())
            .apply()

        activeRoomSlug = getConfiguredRoom()
        binding.roomStatus.text = if (isHumanReady()) "Settings saved" else "Finish owner setup first"
        binding.roomPresenceChip.text = "Saved"
        binding.roomHint.text =
            if (isHumanReady()) {
                "Owner setup complete. The room and local-Qwen targets are ready to load from the Rooms tab."
            } else {
                "Save the Google owner account and nickname before loading rooms."
            }
        updateOwnerUi()
    }

    private fun seedInitialMessages() {
        if (roomMessages.isNotEmpty()) return

        roomMessages += LocalMessage(
            author = "ClawWatch",
            body = "Open Rooms to switch between the live Ant Farm room and a direct 1:1 Qwen chat.",
            timestamp = nowTime(),
            isUser = false
        )
        binding.roomHint.text = activeRoomMode.hint
        binding.roomStatus.text = "Choose a room target to begin"
        binding.roomPresenceChip.text = activeRoomMode.chip
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

    private fun refreshActiveConversation(switchToRoom: Boolean = false) {
        if (switchToRoom) showTab(Tab.ROOM)
        when (activeRoomMode) {
            RoomMode.ANT_FARM -> refreshAntFarmRoom()
            RoomMode.LOCAL_QWEN -> refreshLocalRoom()
        }
    }

    private fun refreshAntFarmRoom() {
        val apiKey = getAntFarmKey()
        val room = getConfiguredRoom()

        if (!isSignedInWithGoogle()) {
            activeRoomName = RoomMode.ANT_FARM.title
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "ClawWatch",
                body = "Sign in with Google in the Watch tab before loading your ClawWatch room.",
                timestamp = nowTime(),
                isUser = false
            )
            binding.roomHint.text = "Google sign-in is required before the room can load."
            binding.roomStatus.text = "Owner setup needed"
            binding.roomPresenceChip.text = "Setup"
            applyHeader()
            renderRoomMessages(forceScroll = true)
            return
        }

        if (!hasNickname()) {
            activeRoomName = RoomMode.ANT_FARM.title
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "ClawWatch",
                body = "Set your nickname in the Watch tab before loading the family room.",
                timestamp = nowTime(),
                isUser = false
            )
            binding.roomHint.text = "Finish nickname setup to continue as this room owner."
            binding.roomStatus.text = "Nickname needed"
            binding.roomPresenceChip.text = "Setup"
            applyHeader()
            renderRoomMessages(forceScroll = true)
            return
        }

        if (apiKey.isNullOrBlank()) {
            activeRoomName = RoomMode.ANT_FARM.title
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "ClawWatch",
                body = "The internal ClawWatch room transport is missing. Reload the app and try again.",
                timestamp = nowTime(),
                isUser = false
            )
            binding.roomHint.text = "The hidden ClawWatch agent transport is not configured."
            binding.roomStatus.text = "Transport missing"
            binding.roomPresenceChip.text = "Setup"
            applyHeader()
            renderRoomMessages(forceScroll = true)
            return
        }

        setRoomLoadingState(loading = true, message = "Syncing $room…")
        lifecycleScope.launch {
            antFarmClient.fetchRoomMessages(room, apiKey)
                .onSuccess { feed ->
                    activeRoomSlug = feed.roomSlug
                    activeRoomName = feed.roomName
                    binding.roomStatus.text = "Connected • ${feed.roomSlug} • ${feed.messages.size} messages"
                    binding.roomPresenceChip.text = "Live"
                    binding.roomHint.text =
                        "Signed in as ${getCurrentNickname()} • the linked ClawWatch agent is carrying room traffic underneath."

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
                    applyHeader()
                    renderRoomMessages(forceScroll = true)
                    setRoomLoadingState(loading = false, message = "Room live")
                }
                .onFailure { error ->
                    activeRoomName = RoomMode.ANT_FARM.title
                    roomMessages.clear()
                    roomMessages += LocalMessage(
                        author = "ClawWatch",
                        body = "Room load failed: ${error.message ?: "unknown error"}",
                        timestamp = nowTime(),
                        isUser = false
                    )
                    binding.roomStatus.text = "Connection failed for $room"
                    binding.roomPresenceChip.text = "Error"
                    binding.roomHint.text = "Check the saved room slug and try again."
                    applyHeader()
                    renderRoomMessages(forceScroll = true)
                    setRoomLoadingState(loading = false, message = "Load failed")
                }
        }
    }

    private fun refreshLocalRoom() {
        activeRoomName = RoomMode.LOCAL_QWEN.title
        binding.roomStatus.text = "Ready • ${getLocalModelName()} @ ${getLocalModelBaseUrl()}"
        binding.roomPresenceChip.text = "Local"
        binding.roomHint.text = RoomMode.LOCAL_QWEN.hint
        if (roomMessages.isEmpty() || roomMessages.firstOrNull()?.author != "ClawWatch") {
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "ClawWatch",
                body = "This is the local 1:1 Qwen chat. Messages here go to the configured local endpoint instead of Ant Farm.",
                timestamp = nowTime(),
                isUser = false
            )
        }
        applyHeader()
        renderRoomMessages(forceScroll = true)
        setRoomLoadingState(loading = false, message = "Local room ready")
    }

    private fun sendComposerMessage() {
        val message = binding.composerInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return

        when (activeRoomMode) {
            RoomMode.ANT_FARM -> sendAntFarmMessage(message)
            RoomMode.LOCAL_QWEN -> sendLocalModelMessage(message)
        }
    }

    private fun sendAntFarmMessage(message: String) {
        val apiKey = getAntFarmKey()
        val room = getConfiguredRoom()
        if (!isHumanReady()) {
            roomMessages += LocalMessage(
                "ClawWatch",
                "Finish Google sign-in and nickname setup in the Watch tab before sending room messages.",
                nowTime(),
                false
            )
            renderRoomMessages(forceScroll = true)
            return
        }
        if (apiKey.isNullOrBlank()) {
            roomMessages += LocalMessage("ClawWatch", "The internal ClawWatch transport is missing. Reload the app and try again.", nowTime(), false)
            renderRoomMessages(forceScroll = true)
            return
        }

        autoScrollEnabled = true
        roomMessages += LocalMessage(
            author = getCurrentNickname() ?: "You",
            body = message,
            timestamp = nowTime(),
            isUser = true
        )
        binding.composerInput.text?.clear()
        renderRoomMessages(forceScroll = true)
        setRoomLoadingState(loading = true, message = "Sending…")

        lifecycleScope.launch {
            antFarmClient.sendRoomMessage(room, apiKey, message)
                .onSuccess { refreshAntFarmRoom() }
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

    private fun sendLocalModelMessage(message: String) {
        autoScrollEnabled = true
        roomMessages += LocalMessage(author = "You", body = message, timestamp = nowTime(), isUser = true)
        binding.composerInput.text?.clear()
        renderRoomMessages(forceScroll = true)
        setRoomLoadingState(loading = true, message = "Calling local Qwen…")

        lifecycleScope.launch {
            localModelClient.chat(
                baseUrl = getLocalModelBaseUrl(),
                model = getLocalModelName(),
                transcript = roomMessages.toList(),
                systemPrompt = LOCAL_SYSTEM_PROMPT
            ).onSuccess { response ->
                roomMessages += LocalMessage(
                    author = response.model,
                    body = response.content,
                    timestamp = nowTime(),
                    isUser = false
                )
                binding.roomStatus.text = "Local reply • ${response.model}"
                binding.roomPresenceChip.text = "Local"
                binding.roomHint.text = "Direct 1:1 chat with the phone-hosted Qwen endpoint."
                renderRoomMessages()
                setRoomLoadingState(loading = false, message = "Local reply ready")
            }.onFailure { error ->
                roomMessages += LocalMessage(
                    author = "ClawWatch",
                    body = "Local Qwen failed: ${error.message ?: "unknown error"}",
                    timestamp = nowTime(),
                    isUser = false
                )
                binding.roomStatus.text = "Local Qwen unavailable"
                binding.roomPresenceChip.text = "Error"
                binding.roomHint.text = "Set the local endpoint in Watch, then make sure the phone-hosted model server is running."
                renderRoomMessages()
                setRoomLoadingState(loading = false, message = "Local Qwen failed")
            }
        }
    }

    private fun renderRoomMessages(forceScroll: Boolean = false) {
        messageAdapter.notifyDataSetChanged()
        binding.recyclerView.post {
            if (roomMessages.isNotEmpty() && (forceScroll || autoScrollEnabled)) {
                scrollToBottom(smooth = roomMessages.size > 1)
            } else {
                updateScrollToBottomFab()
            }
        }
    }

    private fun isNearBottom(threshold: Int = 2): Boolean {
        val total = messageAdapter.itemCount
        if (total == 0) return true
        val lastVisible = roomLayoutManager.findLastVisibleItemPosition()
        return lastVisible >= total - 1 - threshold
    }

    private fun updateScrollToBottomFab() {
        val show = messageAdapter.itemCount > 0 && !isNearBottom()
        binding.scrollToBottomFab.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun scrollToBottom(smooth: Boolean) {
        if (messageAdapter.itemCount == 0) {
            updateScrollToBottomFab()
            return
        }
        val last = messageAdapter.itemCount - 1
        if (smooth) {
            binding.recyclerView.smoothScrollToPosition(last)
        } else {
            binding.recyclerView.scrollToPosition(last)
        }
        updateScrollToBottomFab()
    }

    private fun setRoomLoadingState(loading: Boolean, message: String) {
        binding.refreshRoomButton.isEnabled = !loading
        binding.sendButton.isEnabled = !loading && isHumanReady()
        binding.loadRoomNowButton.isEnabled = !loading
        binding.openAntFarmRoomButton.isEnabled = !loading
        binding.openLocalQwenButton.isEnabled = !loading
        binding.roomStatus.text = message
        if (loading) {
            binding.roomPresenceChip.text = "Syncing"
        }
    }

    private fun hasRoomConfig(): Boolean = !getAntFarmKey().isNullOrBlank()

    private fun isSignedInWithGoogle(): Boolean = !getOwnerEmail().isNullOrBlank()

    private fun hasNickname(): Boolean = !getCurrentNickname().isNullOrBlank()

    private fun isHumanReady(): Boolean = isSignedInWithGoogle() && hasNickname()

    private fun getAntFarmKey(): String? =
        prefs.getString(PREF_ANTFARM_KEY, null)?.takeIf { it.isNotBlank() }

    private fun getConfiguredRoom(): String =
        (prefs.getString(PREF_ANTFARM_ROOMS, DEFAULT_ROOM) ?: DEFAULT_ROOM)
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: DEFAULT_ROOM

    private fun getLocalModelBaseUrl(): String =
        prefs.getString(PREF_LOCAL_MODEL_BASE_URL, DEFAULT_LOCAL_URL)
            ?.trim()
            ?.ifBlank { DEFAULT_LOCAL_URL }
            ?: DEFAULT_LOCAL_URL

    private fun getLocalModelName(): String =
        prefs.getString(PREF_LOCAL_MODEL_NAME, DEFAULT_LOCAL_MODEL)
            ?.trim()
            ?.ifBlank { DEFAULT_LOCAL_MODEL }
            ?: DEFAULT_LOCAL_MODEL

    private fun getOwnerEmail(): String? =
        prefs.getString(PREF_HUMAN_EMAIL, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun getOwnerDisplayName(): String? =
        prefs.getString(PREF_HUMAN_DISPLAY_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun getNicknameKey(email: String): String {
        val normalized = email.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return "human_nickname_$normalized"
    }

    private fun getCurrentNickname(): String? {
        val email = getOwnerEmail() ?: return null
        return prefs.getString(getNicknameKey(email), null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun persistSignedInUser(user: SignedInGoogleUser, updateUi: Boolean = true) {
        prefs.edit()
            .putString(PREF_HUMAN_EMAIL, user.email)
            .putString(PREF_HUMAN_DISPLAY_NAME, user.displayName)
            .putString(PREF_HUMAN_AVATAR_URL, user.avatarUrl)
            .apply()

        if (updateUi) {
            updateOwnerUi()
        }
    }

    private fun saveNickname() {
        val email = getOwnerEmail()
        if (email.isNullOrBlank()) {
            binding.roomStatus.text = "Sign in with Google first"
            binding.roomPresenceChip.text = "Setup"
            return
        }

        val nickname = binding.nicknameInput.text?.toString()?.trim().orEmpty()
        if (nickname.isBlank()) {
            binding.nicknameSummary.text = "Nickname cannot be blank."
            binding.nicknameInput.requestFocus()
            return
        }

        prefs.edit().putString(getNicknameKey(email), nickname).apply()
        binding.roomStatus.text = "Owner ready • $nickname"
        binding.roomPresenceChip.text = "Owner"
        binding.roomHint.text = "Signed in as $nickname. The room can now load through the linked ClawWatch agent."
        updateOwnerUi()

        if (hasRoomConfig()) {
            refreshActiveConversation()
        }
    }

    private fun signOutOwner() {
        googleSignInManager.signOut()
        prefs.edit()
            .remove(PREF_HUMAN_EMAIL)
            .remove(PREF_HUMAN_DISPLAY_NAME)
            .remove(PREF_HUMAN_AVATAR_URL)
            .apply()
        binding.nicknameInput.text?.clear()
        updateOwnerUi()
        binding.roomStatus.text = "Signed out"
        binding.roomPresenceChip.text = "Setup"
        binding.roomHint.text = "Sign in with Google in the Watch tab to use the room as the ClawWatch owner."
    }

    private fun updateOwnerUi() {
        val email = getOwnerEmail()
        val displayName = getOwnerDisplayName()
        val nickname = getCurrentNickname()
        val signedIn = !email.isNullOrBlank()

        binding.googleAuthEmail.text =
            if (signedIn) {
                listOfNotNull(displayName, email).distinct().joinToString("\n")
            } else {
                "Not signed in"
            }

        binding.googleAuthSummary.text =
            if (!signedIn) {
                "Sign in with Google to claim this ClawWatch companion and unlock your human profile setup."
            } else if (nickname.isNullOrBlank()) {
                "This Google account is connected. Pick the nickname ClawWatch should use for it."
            } else {
                "Signed in as $nickname. Room access is ready for this owner."
            }

        binding.googleSignInButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.googleSignOutButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.nicknameCard.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.nicknameInput.setText(nickname.orEmpty())
        binding.nicknameSummary.text =
            if (!signedIn) {
                "Sign in first to choose the nickname this account should use in ClawWatch."
            } else if (nickname.isNullOrBlank()) {
                "Set the nickname this account should use in ClawWatch."
            } else {
                "Current nickname: $nickname"
            }

        binding.sendButton.isEnabled = isHumanReady()
        binding.loadRoomNowButton.isEnabled = isHumanReady()
    }

    private fun nowTime(): String = localTimeFormat.format(Date())

    private fun formatTimestamp(value: String): String {
        if (value.isBlank()) return nowTime()
        return runCatching { isoTimeFormat.format(Instant.parse(value)) }.getOrElse { value.take(16) }
    }
}
