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
        private const val PREF_HUMAN_EMAIL = "human_email"
        private const val PREF_HUMAN_DISPLAY_NAME = "human_display_name"
        private const val PREF_HUMAN_AVATAR_URL = "human_avatar_url"
        private const val PREF_LOCAL_OWNER_MODE = "local_owner_mode"
        private const val DEFAULT_ROOM = "ant-farm-management"
        private const val DEFAULT_TEST_ANTFARM_KEY = "antfarm_67efac6733223e873ab305e1e48e9c6a3f573eab38d07b00c8eabffd718d0b2b"
        private const val LOCAL_OWNER_EMAIL = "local-owner@device.local"
        private const val LOCAL_OWNER_NAME = "Local owner"
        private const val IDE_ROOM_THINKOFF_DEVELOPMENT = "thinkoff-development"
        private const val IDE_ROOM_ANT_FARM_MANAGEMENT = "ant-farm-management"
    }

    private enum class Tab(
        val title: String,
        val subtitle: String
    ) {
        ROOM(
            title = "Room",
            subtitle = "Chat with your live room."
        ),
        ROOMS(
            title = "Rooms",
            subtitle = "Choose the room target for chat."
        ),
        WATCH(
            title = "Account",
            subtitle = "Google sign-in and room setup."
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var googleSignInManager: GoogleSignInManager
    private val antFarmClient = AntFarmClient()
    private val roomMessages = mutableListOf<LocalMessage>()
    private lateinit var messageAdapter: RoomMessageAdapter
    private lateinit var roomLayoutManager: LinearLayoutManager
    private var autoScrollEnabled = true
    private var activeTab: Tab = Tab.ROOM
    private var activeRoomSlug: String = DEFAULT_ROOM
    private var activeRoomName: String = "Family room"
    private val localTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isoTimeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            googleSignInManager.handleSignInResult(result.data)
                .onSuccess { user ->
                    persistSignedInUser(user)
                    binding.roomStatus.text = "Signed in as ${getCurrentNickname() ?: user.displayName ?: user.email}"
                    binding.roomPresenceChip.text = "Owner"
                    binding.roomHint.text = "Owner setup complete. GroupMind can now load the room."
                    if (isHumanReady()) {
                        refreshActiveConversation()
                    }
                }
                .onFailure { error ->
                    val errorText = error.message?.trim().orEmpty().ifBlank { "Google sign-in did not complete." }
                    if (isDeveloperError(errorText)) {
                        persistLocalOwner(updateUi = false)
                        binding.roomStatus.text = "Local owner mode enabled"
                        binding.roomPresenceChip.text = "Local"
                        binding.roomHint.text = "Google OAuth for this debug build is not configured yet, so local owner mode is active on this device."
                        binding.googleAuthSummary.text = "Google OAuth for this debug build is not configured yet. Using local owner mode so you can test the companion app now."
                        updateOwnerUi()
                        return@registerForActivityResult
                    }
                    binding.roomStatus.text = "Google sign-in failed"
                    binding.roomPresenceChip.text = "Error"
                    binding.roomHint.text = errorText
                    updateOwnerUi()
                    binding.googleAuthSummary.text = errorText
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.headerEyebrow.text = android.text.Html.fromHtml(getString(R.string.groupmind_html), android.text.Html.FROM_HTML_MODE_LEGACY)
        
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
            refreshActiveConversation(switchToRoom = true)
        }
        binding.openAntFarmRoomButton.setOnClickListener {
            openRoomTarget(
                roomSlug = getConfiguredRoom(),
                roomName = "Family room"
            )
        }
        binding.openThinkoffDevelopmentButton.setOnClickListener {
            openRoomTarget(
                roomSlug = IDE_ROOM_THINKOFF_DEVELOPMENT,
                roomName = "thinkoff-development"
            )
        }
        binding.openAntFarmManagementButton.setOnClickListener {
            openRoomTarget(
                roomSlug = IDE_ROOM_ANT_FARM_MANAGEMENT,
                roomName = "ant-farm-management"
            )
        }

        binding.composerInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

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
        styleModeButton(binding.openAntFarmRoomButton, active = true)
    }

    private fun applyHeader() {
        when (activeTab) {
            Tab.ROOM -> {
                binding.headerTitle.text = activeRoomName
                binding.headerSubtitle.text = "Live Ant Farm room timeline and messaging."
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

    private fun styleModeButton(button: Button, active: Boolean) {
        val background = if (active) R.color.tab_active else R.color.tab_inactive
        val foreground = if (active) R.color.tab_active_text else R.color.tab_inactive_text
        button.backgroundTintList = ContextCompat.getColorStateList(this, background)
        button.setTextColor(ContextCompat.getColor(this, foreground))
    }

    private fun loadSavedSettings() {
        ensureInternalTestDefaults()
        binding.antFarmRoomInput.setText(getConfiguredRoom())
        activeRoomSlug = getConfiguredRoom()
        updateRoomsUi()
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
            .apply()

        activeRoomSlug = getConfiguredRoom()
        activeRoomName = "Family room"
        updateRoomsUi()
        binding.roomStatus.text = if (isHumanReady()) "Settings saved" else "Finish owner setup first"
        binding.roomPresenceChip.text = "Saved"
        binding.roomHint.text =
            if (isHumanReady()) {
                "Owner setup complete. The room target is ready to load from the Rooms tab."
            } else {
                "Save the Google owner account and nickname before loading rooms."
            }
        updateOwnerUi()
    }

    private fun seedInitialMessages() {
        if (roomMessages.isNotEmpty()) return

        roomMessages += LocalMessage(
            author = "GroupMind",
            body = "Open Rooms to load your family room, then chat live.",
            timestamp = nowTime(),
            isUser = false
        )
        binding.roomHint.text = "Connected room state and message timeline appear here."
        binding.roomStatus.text = "Choose a room target to begin"
        binding.roomPresenceChip.text = "Live room"
    }

    private fun refreshActiveConversation(switchToRoom: Boolean = false) {
        if (switchToRoom) showTab(Tab.ROOM)
        refreshAntFarmRoom()
    }

    private fun refreshAntFarmRoom() {
        val apiKey = getAntFarmKey()
        val room = activeRoomSlug.ifBlank { getConfiguredRoom() }

        if (!isSignedInWithGoogle()) {
            activeRoomName = "Family room"
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "GroupMind",
                body = "Sign in with Google in the Account tab before loading your room.",
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
            activeRoomName = "Family room"
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "GroupMind",
                body = "Set your nickname in the Account tab before loading the family room.",
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
            activeRoomName = "Family room"
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "GroupMind",
                body = "The internal room transport is missing. Reload the app and try again.",
                timestamp = nowTime(),
                isUser = false
            )
            binding.roomHint.text = "The hidden room transport is not configured."
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
                    activeRoomName = displayNameForRoom(feed.roomSlug, fallback = feed.roomName)
                    binding.roomStatus.text = "Connected • ${feed.roomSlug} • ${feed.messages.size} messages"
                    binding.roomPresenceChip.text = "Live"
                    binding.roomHint.text =
                        "Signed in as ${getCurrentNickname()} • live room traffic is active."

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
                            author = "GroupMind",
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
                    activeRoomName = displayNameForRoom(room, fallback = room)
                    roomMessages.clear()
                    roomMessages += LocalMessage(
                        author = "GroupMind",
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

    private fun sendComposerMessage() {
        val message = binding.composerInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return
        sendAntFarmMessage(message)
    }

    private fun sendAntFarmMessage(message: String) {
        val apiKey = getAntFarmKey()
        val room = activeRoomSlug.ifBlank { getConfiguredRoom() }
        if (!isHumanReady()) {
            roomMessages += LocalMessage(
                "GroupMind",
                "Finish Google sign-in and nickname setup in the Account tab before sending room messages.",
                nowTime(),
                false
            )
            renderRoomMessages(forceScroll = true)
            return
        }
        if (apiKey.isNullOrBlank()) {
            roomMessages += LocalMessage("GroupMind", "The internal room transport is missing. Reload the app and try again.", nowTime(), false)
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
                        author = "GroupMind",
                        body = "Send failed: ${error.message ?: "unknown error"}",
                        timestamp = nowTime(),
                        isUser = false
                    )
                    setRoomLoadingState(loading = false, message = "Send failed")
                    renderRoomMessages()
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
        getConfiguredRooms().firstOrNull() ?: DEFAULT_ROOM

    private fun getConfiguredRooms(): List<String> =
        (prefs.getString(PREF_ANTFARM_ROOMS, DEFAULT_ROOM) ?: DEFAULT_ROOM)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(DEFAULT_ROOM) }

    private fun openRoomTarget(roomSlug: String, roomName: String) {
        activeRoomSlug = roomSlug
        activeRoomName = roomName
        applyHeader()
        refreshActiveConversation(switchToRoom = true)
    }

    private fun updateRoomsUi() {
        binding.familyRoomSummary.text = "Saved room slug: ${getConfiguredRoom()}"
    }

    private fun displayNameForRoom(roomSlug: String, fallback: String = roomSlug): String =
        when {
            roomSlug == activeRoomSlug && activeRoomName.isNotBlank() -> activeRoomName
            roomSlug == IDE_ROOM_THINKOFF_DEVELOPMENT -> "thinkoff-development"
            roomSlug == IDE_ROOM_ANT_FARM_MANAGEMENT -> "ant-farm-management"
            roomSlug == getConfiguredRoom() -> "Family room"
            else -> fallback
        }

    private fun getOwnerEmail(): String? =
        prefs.getString(PREF_HUMAN_EMAIL, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun getOwnerDisplayName(): String? =
        prefs.getString(PREF_HUMAN_DISPLAY_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun isLocalOwnerMode(): Boolean =
        prefs.getBoolean(PREF_LOCAL_OWNER_MODE, false)

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
            .putBoolean(PREF_LOCAL_OWNER_MODE, false)
            .apply()

        if (updateUi) {
            updateOwnerUi()
        }
    }

    private fun persistLocalOwner(updateUi: Boolean = true) {
        prefs.edit()
            .putString(PREF_HUMAN_EMAIL, LOCAL_OWNER_EMAIL)
            .putString(PREF_HUMAN_DISPLAY_NAME, LOCAL_OWNER_NAME)
            .remove(PREF_HUMAN_AVATAR_URL)
            .putBoolean(PREF_LOCAL_OWNER_MODE, true)
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
        binding.roomHint.text = "Signed in as $nickname. The room can now load through GroupMind."
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
            .remove(PREF_LOCAL_OWNER_MODE)
            .apply()
        binding.nicknameInput.text?.clear()
        updateOwnerUi()
        binding.roomStatus.text = "Signed out"
        binding.roomPresenceChip.text = "Setup"
        binding.roomHint.text = "Sign in with Google in the Account tab to use the room."
    }

    private fun updateOwnerUi() {
        val email = getOwnerEmail()
        val displayName = getOwnerDisplayName()
        val nickname = getCurrentNickname()
        val signedIn = !email.isNullOrBlank()
        val localOwnerMode = isLocalOwnerMode()

        binding.googleAuthEmail.text =
            if (signedIn) {
                listOfNotNull(displayName, email).distinct().joinToString("\n")
            } else {
                "Not signed in"
            }

        binding.googleAuthSummary.text =
            if (!signedIn) {
                "Sign in with Google to use this GroupMind companion and unlock profile setup."
            } else if (localOwnerMode) {
                "Google OAuth is not configured for this debug build, so this phone is using local owner mode."
            } else if (nickname.isNullOrBlank()) {
                "This Google account is connected. Pick the nickname GroupMind should use for it."
            } else {
                "Signed in as $nickname. Room access is ready for this owner."
            }

        binding.googleSignInButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.googleSignOutButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.nicknameCard.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.nicknameInput.setText(nickname.orEmpty())
        binding.nicknameSummary.text =
            if (!signedIn) {
                "Sign in first to choose the nickname this account should use in GroupMind."
            } else if (localOwnerMode && nickname.isNullOrBlank()) {
                "Set the nickname this device should use while local owner mode is active."
            } else if (nickname.isNullOrBlank()) {
                "Set the nickname this account should use in GroupMind."
            } else {
                "Current nickname: $nickname"
            }

        binding.sendButton.isEnabled = isHumanReady()
        binding.loadRoomNowButton.isEnabled = isHumanReady()
    }

    private fun isDeveloperError(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("developer_error") || normalized.contains("code 10")
    }

    private fun nowTime(): String = localTimeFormat.format(Date())

    private fun formatTimestamp(value: String): String {
        if (value.isBlank()) return nowTime()
        return runCatching { isoTimeFormat.format(Instant.parse(value)) }.getOrElse { value.take(16) }
    }
}
