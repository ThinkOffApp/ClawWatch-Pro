package com.thinkoff.clawwatch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thinkoff.clawwatch.databinding.ItemChannelBinding
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color
import com.thinkoff.core.ui.ChatMessage
import com.thinkoff.core.ui.SharedChatScreen

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
        private val IDE_DIRECT_TARGETS = listOf(
            "@claudeMB",
            "@geminiMB",
            "@CodexMB",
            "@claudemm"
        )
        private const val CLAWWATCH_CHANNEL = "clawwatch"
    }

    private enum class Tab(
        val title: String,
        val subtitle: String
    ) {
        ROOM(
            title = "Conversation",
            subtitle = "Live channel discussion."
        ),
        ROOMS(
            title = "Channels",
            subtitle = "Choose a room or IDE channel."
        ),
        WATCH(
            title = "Account",
            subtitle = "Owner sign-in, nickname, and defaults."
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var googleSignInManager: GoogleSignInManager
    private val antFarmClient = AntFarmClient()
    private val roomMessages = mutableListOf<LocalMessage>()
    private val channelItems = mutableListOf<ChannelItem>()
    private val channelPreviewOverrides = mutableMapOf<String, String>()
    private val recentDirectChannels = linkedMapOf<String, ChannelItem>()
    private lateinit var messageAdapter: RoomMessageAdapter
    private lateinit var roomLayoutManager: LinearLayoutManager
    private lateinit var watchRelay: WatchRelay
    private lateinit var phoneAgent: PhoneAgent
    private var autoScrollEnabled = true
    private val PREF_CLAWWATCH_HISTORY = "clawwatch_chat_history"
    private var activeTab: Tab = Tab.ROOMS
    private var activeRoomSlug: String = DEFAULT_ROOM
    private var activeRoomName: String = "Family room"
    private var activeTargetKind: TargetKind = TargetKind.ROOM
    private val localTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isoTimeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (activeTab != Tab.ROOMS) {
                showTab(Tab.ROOMS)
            }
        }
    }
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            googleSignInManager.handleSignInResult(result.data)
                .onSuccess { user ->
                    persistSignedInUser(user)
                    binding.roomStatus.text = "Signed in as ${getCurrentNickname() ?: user.displayName ?: user.email}"
                    binding.roomPresenceChip.text = "Owner"
                    binding.roomHint.text = "Owner setup complete. ClawWatch can now load the room."
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

    private enum class TargetKind {
        ROOM,
        IDE,
        WATCH
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.headerEyebrow.text = android.text.Html.fromHtml(getString(R.string.clawwatch_html), android.text.Html.FROM_HTML_MODE_LEGACY)
        
        prefs = SecurePrefs.watch(this)
        googleSignInManager = GoogleSignInManager(this)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
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
        binding.headerBackButton.setOnClickListener { showTab(Tab.ROOMS) }
        binding.headerActionButton.setOnClickListener {
            if (activeTab == Tab.WATCH) {
                showTab(Tab.ROOMS)
            } else {
                showTab(Tab.WATCH)
            }
        }
        binding.sendButton.setOnClickListener { sendComposerMessage() }
        binding.sendWhatsAppButton.setOnClickListener {
            handoffComposerMessage(
                packageName = "com.whatsapp",
                appLabel = "WhatsApp"
            )
        }
        binding.sendTelegramButton.setOnClickListener {
            handoffComposerMessage(
                packageName = "org.telegram.messenger",
                appLabel = "Telegram"
            )
        }
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

        binding.composerInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // On-device Gemma agent (tier-1 handler)
        phoneAgent = PhoneAgent(this)
        lifecycleScope.launch {
            binding.gemmaStatusText.text = "Initializing Gemma..."
            binding.gemmaModelInfo.text = ""
            binding.gemmaStatusChip.text = "Gemma loading..."
            val success = phoneAgent.initialize()
            if (success) {
                binding.gemmaStatusText.text = "Gemma 4 E2B loaded and ready to respond"
                binding.gemmaModelInfo.text = phoneAgent.getModelInfo()
                binding.gemmaStatusText.setTextColor(0xFF4ADE80.toInt())
                // Channel list chip
                binding.gemmaStatusChip.text = "Gemma 4 E2B ready"
                binding.gemmaStatusChip.setTextColor(0xFF4ADE80.toInt())
                binding.gemmaStatusDot.setBackgroundColor(0xFF4ADE80.toInt())
            } else {
                binding.gemmaStatusText.text = "Gemma not available. Download Gemma 4 E2B in Google AI Edge Gallery, then restart."
                binding.gemmaModelInfo.text = "Model not found"
                binding.gemmaStatusText.setTextColor(0xFFEF4444.toInt())
                binding.gemmaStatusChip.text = "Gemma offline"
                binding.gemmaStatusChip.setTextColor(0xFF9CA3AF.toInt())
                binding.gemmaStatusDot.setBackgroundColor(0xFFEF4444.toInt())
            }
        }

        // Watch relay for ClawWatch agent channel
        watchRelay = WatchRelay(this)
        watchRelay.setPhoneAgent(phoneAgent)
        watchRelay.setResponseListener { response ->
            runOnUiThread {
                roomMessages += LocalMessage(
                    author = "ClawWatch",
                    body = response,
                    timestamp = nowTime(),
                    isUser = false
                )
                channelPreviewOverrides[CLAWWATCH_CHANNEL] = response.take(96)
                renderRoomMessages(forceScroll = true)
                setRoomLoadingState(loading = false, message = "Connected")
                setGemmaActivity("idle")
                if (activeTargetKind == TargetKind.WATCH) saveClawWatchHistory()
            }
        }
        watchRelay.setAvatarStateListener { state, mood ->
            runOnUiThread {
                updateAvatarState(state, mood)
            }
        }
        watchRelay.setHistoryListener { history ->
            runOnUiThread {
                if (activeTargetKind == TargetKind.WATCH) {
                    // Don't overwrite local history with empty watch response
                    if (history.isEmpty() && roomMessages.isNotEmpty()) return@runOnUiThread
                    // Merge watch history into phone display
                    val nickname = getCurrentNickname() ?: "You"
                    val existing = roomMessages.toList()
                    roomMessages.clear()
                    history.forEach { (role, content) ->
                        roomMessages += LocalMessage(
                            author = if (role == "user") nickname else "ClawWatch",
                            body = content,
                            timestamp = "",
                            isUser = role == "user"
                        )
                    }
                    // Append any optimistic messages not yet in history
                    existing.filter { it.isUser && roomMessages.none { h -> h.body == it.body } }
                        .forEach { roomMessages += it }
                    saveClawWatchHistory()
                    renderRoomMessages(forceScroll = true)
                }
            }
        }

        loadSavedSettings()
        seedInitialMessages()
        renderRoomMessages(forceScroll = true)
        showTab(Tab.ROOMS, animate = false)

        if (hasRoomConfig()) {
            refreshAntFarmRoom()
        }
    }

    override fun onResume() {
        super.onResume()
        watchRelay.start()
        refreshRecentDirectChannels()
    }

    override fun onPause() {
        super.onPause()
        watchRelay.stop()
    }

    private fun showTab(tab: Tab, animate: Boolean = true) {
        val previousTab = activeTab
        if (previousTab == Tab.ROOM && activeTargetKind == TargetKind.WATCH) {
            saveClawWatchHistory()
        }
        activeTab = tab
        applyHeader()
        updateHeaderActions()

        if (tab == Tab.ROOM) {
            updateComposeChatView()
        } else {
            binding.composeChatView.visibility = View.GONE
        }

        transitionPanel(
            panel = binding.roomPanel,
            show = tab == Tab.ROOM,
            enterFromRight = previousTab != Tab.ROOM && tab == Tab.ROOM,
            animate = animate
        )
        transitionPanel(
            panel = binding.roomsPanel,
            show = tab == Tab.ROOMS,
            enterFromRight = previousTab == Tab.WATCH && tab == Tab.ROOMS,
            animate = animate
        )
        transitionPanel(
            panel = binding.watchPanel,
            show = tab == Tab.WATCH,
            enterFromRight = true,
            animate = animate
        )

        styleTab(binding.tabRoom, active = tab == Tab.ROOM)
        styleTab(binding.tabRooms, active = tab == Tab.ROOMS)
        styleTab(binding.tabWatch, active = tab == Tab.WATCH)
        backPressedCallback.isEnabled = tab != Tab.ROOMS
    }

    private fun applyHeader() {
        when (activeTab) {
            Tab.ROOM -> {
                binding.headerTitle.text = activeRoomName
                binding.headerSubtitle.text = "Live channel discussion."
            }
            else -> {
                binding.headerTitle.text = activeTab.title
                binding.headerSubtitle.text = activeTab.subtitle
            }
        }
    }

    private fun updateHeaderActions() {
        binding.headerBackButton.visibility = if (activeTab == Tab.ROOMS) View.GONE else View.VISIBLE
        binding.headerActionButton.text = if (activeTab == Tab.WATCH) "Channels" else "Account"
    }

    private fun transitionPanel(
        panel: View,
        show: Boolean,
        enterFromRight: Boolean,
        animate: Boolean
    ) {
        if (!animate) {
            panel.animate().cancel()
            panel.visibility = if (show) View.VISIBLE else View.GONE
            panel.alpha = 1f
            panel.translationX = 0f
            return
        }

        val travel = panel.resources.displayMetrics.density * 28f
        val offset = if (enterFromRight) travel else -travel

        if (show) {
            if (panel.visibility == View.VISIBLE) return
            panel.animate().cancel()
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationX = offset
            panel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180L)
                .start()
        } else if (panel.visibility == View.VISIBLE) {
            panel.animate().cancel()
            panel.animate()
                .alpha(0f)
                .translationX(-offset / 2f)
                .setDuration(140L)
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    panel.translationX = 0f
                }
                .start()
        }
    }

    private fun styleTab(button: Button, active: Boolean) {
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
        refreshRecentDirectChannels()
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
        channelPreviewOverrides.remove(activeRoomSlug)
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
            author = "ClawWatch",
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
        // ClawWatch channel uses direct Bluetooth relay, not Ant Farm rooms
        if (classifyTarget(activeRoomSlug) == TargetKind.WATCH) return
        refreshAntFarmRoom()
    }

    private fun refreshAntFarmRoom() {
        val apiKey = getAntFarmKey()
        val target = activeRoomSlug.ifBlank { getConfiguredRoom() }
        val targetKind = classifyTarget(target)

        // Skip owner gates for read-only browsing. Sign-in only needed for sending.
        if (!isSignedInWithGoogle()) {
            binding.roomHint.text = "Sign in via Account to send messages."
            binding.roomPresenceChip.text = "Read only"
        }

        if (apiKey.isNullOrBlank()) {
            activeRoomName = "Family room"
            roomMessages.clear()
            roomMessages += LocalMessage(
                author = "ClawWatch",
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

        setRoomLoadingState(loading = true, message = "Syncing $target…")
        channelPreviewOverrides[target] = "Syncing conversation..."
        updateRoomsUi()
        refreshRecentDirectChannels(updateUi = false)
        lifecycleScope.launch {
            val googleIdToken = if (targetKind == TargetKind.IDE) {
                googleSignInManager.getFreshIdToken()
            } else {
                null
            }

            if (targetKind == TargetKind.IDE && googleIdToken.isNullOrBlank()) {
                activeRoomName = displayNameForRoom(target, fallback = target)
                roomMessages.clear()
                roomMessages += LocalMessage(
                    author = "ClawWatch",
                    body = "Direct IDE messaging needs a fresh Google sign-in on this phone. Sign out and sign in again in Account.",
                    timestamp = nowTime(),
                    isUser = false
                )
                binding.roomStatus.text = "Google reauth needed"
                binding.roomPresenceChip.text = "Setup"
                binding.roomHint.text = "Google ID token is missing for human IDE messaging."
                channelPreviewOverrides[target] = "Google reauth needed."
                applyHeader()
                updateRoomsUi()
                renderRoomMessages(forceScroll = true)
                setRoomLoadingState(loading = false, message = "Google reauth needed")
                return@launch
            }

            val result = when (targetKind) {
                TargetKind.ROOM -> antFarmClient.fetchRoomMessages(target, apiKey)
                    .map { feed ->
                        activeRoomSlug = feed.roomSlug
                        activeRoomName = displayNameForRoom(feed.roomSlug, fallback = feed.roomName)
                        activeTargetKind = TargetKind.ROOM
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
                                author = "ClawWatch",
                                body = "No messages yet in ${feed.roomSlug}. Send one to start the test.",
                                timestamp = nowTime(),
                                isUser = false
                            )
                        }
                        channelPreviewOverrides[feed.roomSlug] =
                            roomMessages.lastOrNull()?.body?.take(96) ?: "No messages yet."
                    }
                TargetKind.IDE -> antFarmClient.fetchDirectMessagesAsHuman(target, googleIdToken!!)
                    .map { feed ->
                        activeRoomSlug = feed.target
                        activeRoomName = displayNameForRoom(feed.target, fallback = feed.target)
                        activeTargetKind = TargetKind.IDE
                        binding.roomStatus.text = "Connected • ${feed.target} • ${feed.messages.size} messages"
                        binding.roomPresenceChip.text = "Live"
                        binding.roomHint.text =
                            "Signed in as ${getCurrentNickname()} • direct IDE messaging is active."

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
                                body = "No direct messages yet with ${feed.target}. Send one to start the test.",
                                timestamp = nowTime(),
                                isUser = false
                            )
                        }
                        channelPreviewOverrides[feed.target] =
                            roomMessages.lastOrNull()?.body?.take(96) ?: "No messages yet."
                    }
                TargetKind.WATCH -> Result.success(Unit) // Handled by WatchRelay
            }

            result
                .onSuccess {
                    applyHeader()
                    refreshRecentDirectChannels(updateUi = false)
                    updateRoomsUi()
                    renderRoomMessages(forceScroll = true)
                    setRoomLoadingState(
                        loading = false,
                        message = if (targetKind == TargetKind.ROOM) "Room live" else "IDE live"
                    )
                }
                .onFailure { error ->
                    activeRoomName = displayNameForRoom(target, fallback = target)
                    roomMessages.clear()
                    roomMessages += LocalMessage(
                        author = "ClawWatch",
                        body = "Room load failed: ${error.message ?: "unknown error"}",
                        timestamp = nowTime(),
                        isUser = false
                    )
                    binding.roomStatus.text = "Connection failed for $target"
                    binding.roomPresenceChip.text = "Error"
                    binding.roomHint.text = "Check the saved room slug and try again."
                    channelPreviewOverrides[target] = "Load failed."
                    applyHeader()
                    refreshRecentDirectChannels(updateUi = false)
                    updateRoomsUi()
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

    private fun handoffComposerMessage(
        packageName: String,
        appLabel: String
    ) {
        val message = binding.composerInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) {
            Toast.makeText(this, "Write a message first", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "$appLabel is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAntFarmMessage(message: String) {
        val apiKey = getAntFarmKey()
        val target = activeRoomSlug.ifBlank { getConfiguredRoom() }
        val targetKind = classifyTarget(target)

        // Watch channel bypasses room auth requirements
        if (targetKind == TargetKind.WATCH) {
            autoScrollEnabled = true
            roomMessages += LocalMessage(
                author = getCurrentNickname() ?: "You",
                body = message,
                timestamp = nowTime(),
                isUser = true
            )
            channelPreviewOverrides[target] = message.take(96)
            binding.composerInput.text?.clear()
            saveClawWatchHistory()
            updateRoomsUi()
            renderRoomMessages(forceScroll = true)
            setRoomLoadingState(loading = true, message = "Thinking...")
            updateAvatarState("THINKING", "NEUTRAL")
            setGemmaActivity("thinking")

            lifecycleScope.launch {
                // Always send to watch first -- watch is the brain, holds conversation context.
                // Gemma is ONLY the offline fallback, never a split-brain answerer.
                val sent = watchRelay.sendQuery(message)
                if (!sent) {
                    // Watch offline -- Gemma fallback (clearly labeled)
                    setRoomLoadingState(loading = true, message = "Watch offline, trying local...")
                    val fallback = runGemmaFallback(message)
                    if (fallback != null) {
                        roomMessages += LocalMessage(
                            author = "ClawWatch (offline)",
                            body = fallback,
                            timestamp = nowTime(),
                            isUser = false
                        )
                        setRoomLoadingState(loading = false, message = "Offline mode")
                    } else {
                        roomMessages += LocalMessage(
                            author = "ClawWatch",
                            body = "Watch not connected. Make sure your ClawWatch is nearby and awake.",
                            timestamp = nowTime(),
                            isUser = false
                        )
                        setRoomLoadingState(loading = false, message = "Offline")
                    }
                    updateAvatarState("IDLE", "NEUTRAL")
                    setGemmaActivity("idle")
                    renderRoomMessages(forceScroll = true)
                }
                // If sent, response arrives via watchRelay listener
            }
            return
        }

        if (!isHumanReady()) {
            roomMessages += LocalMessage(
                "ClawWatch",
                "Finish Google sign-in and nickname setup in the Account tab before sending room messages.",
                nowTime(),
                false
            )
            renderRoomMessages(forceScroll = true)
            return
        }
        if (apiKey.isNullOrBlank()) {
            roomMessages += LocalMessage("ClawWatch", "The internal room transport is missing. Reload the app and try again.", nowTime(), false)
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
        channelPreviewOverrides[target] = message.take(96)
        binding.composerInput.text?.clear()
        updateRoomsUi()
        renderRoomMessages(forceScroll = true)
        setRoomLoadingState(loading = true, message = "Sending...")

        lifecycleScope.launch {
            val googleIdToken = if (targetKind == TargetKind.IDE) {
                googleSignInManager.getFreshIdToken()
            } else {
                null
            }

            if (targetKind == TargetKind.IDE && googleIdToken.isNullOrBlank()) {
                roomMessages += LocalMessage(
                    author = "ClawWatch",
                    body = "Direct IDE messaging needs a fresh Google sign-in on this phone. Sign out and sign in again in Account.",
                    timestamp = nowTime(),
                    isUser = false
                )
                setRoomLoadingState(loading = false, message = "Google reauth needed")
                renderRoomMessages()
                return@launch
            }

            val result = when (targetKind) {
                TargetKind.ROOM -> antFarmClient.sendRoomMessage(target, apiKey, message)
                TargetKind.IDE -> antFarmClient.sendDirectMessageAsHuman(target, googleIdToken!!, message)
                TargetKind.WATCH -> return@launch // Handled by WatchRelay path above
            }
            result
                .onSuccess { 
                    setRoomLoadingState(loading = false, message = "Sent")
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

    private fun updateComposeChatView() {
        // Use native RecyclerView with RoomMessageAdapter (shows usernames)
        binding.composeChatView.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
    }

    private fun renderRoomMessages(forceScroll: Boolean = false) {
        messageAdapter.notifyDataSetChanged()
        if (forceScroll || autoScrollEnabled) {
            scrollToBottom(smooth = !forceScroll)
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
        binding.sendWhatsAppButton.isEnabled = !loading
        binding.sendTelegramButton.isEnabled = !loading
        binding.loadRoomNowButton.isEnabled = !loading
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

    private fun classifyTarget(target: String): TargetKind {
        if (target == CLAWWATCH_CHANNEL) return TargetKind.WATCH
        val normalized = target.trim().removePrefix("@")
        return if (
            target.trim().startsWith("@") ||
            IDE_DIRECT_TARGETS.any { it.removePrefix("@").equals(normalized, ignoreCase = true) }
        ) {
            TargetKind.IDE
        } else {
            TargetKind.ROOM
        }
    }

    private fun openRoomTarget(roomSlug: String, roomName: String) {
        activeRoomSlug = roomSlug
        activeRoomName = roomName
        activeTargetKind = classifyTarget(roomSlug)
        applyHeader()
        updateAvatarVisibility()
        channelPreviewOverrides[roomSlug] = channelPreviewOverrides[roomSlug] ?: "Opening conversation..."
        updateRoomsUi()

        if (activeTargetKind == TargetKind.WATCH) {
            // Load persisted chat history
            val saved = loadClawWatchHistory()
            roomMessages.clear()
            if (saved.isNotEmpty()) {
                roomMessages.addAll(saved)
            } else {
                roomMessages += LocalMessage(
                    author = "ClawWatch",
                    body = "Hi! I'm your ClawWatch assistant. Ask me anything, or say \"how is my health\" to check your vitals.",
                    timestamp = nowTime(),
                    isUser = false
                )
            }
            renderRoomMessages(forceScroll = true)
            // Also request latest from watch to sync any new turns
            lifecycleScope.launch { watchRelay.requestHistory() }
            showTab(Tab.ROOM)
        } else {
            refreshActiveConversation(switchToRoom = true)
        }
    }

    private fun updateAvatarVisibility() {
        binding.avatarContainer.visibility =
            if (activeTargetKind == TargetKind.WATCH) View.VISIBLE else View.GONE
    }

    private fun updateAvatarState(state: String, mood: String) {
        try {
            if (binding.avatarContainer.visibility != View.VISIBLE) return

            binding.avatarStatusText.text = when (state) {
                "THINKING" -> "Thinking..."
                "SPEAKING" -> "Speaking..."
                "LISTENING" -> "Listening..."
                "ERROR" -> "Something went wrong"
                else -> "Ready"
            }

            val avdName = when (state.lowercase()) {
                "thinking" -> "avd_avatar_lobster_thinking"
                "speaking" -> "avd_avatar_lobster_speaking"
                "listening" -> "avd_avatar_lobster_listening"
                else -> "avd_avatar_lobster_idle"
            }
            val resId = resources.getIdentifier(avdName, "drawable", packageName)
            if (resId != 0) {
                val avd = androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
                    .create(this, resId)
                binding.avatarView.setImageDrawable(avd)
                avd?.start()
            }
        } catch (e: Exception) {
            android.util.Log.e("ClawWatch", "Avatar state update failed: ${e.message}")
        }
    }

    private fun refreshRecentDirectChannels(updateUi: Boolean = true) {
        val currentGoogleUser = googleSignInManager.getCurrentUser()
        if (currentGoogleUser == null) {
            recentDirectChannels.clear()
            if (updateUi) updateRoomsUi()
            return
        }

        if (!isSignedInWithGoogle()) {
            persistSignedInUser(currentGoogleUser, updateUi = false)
        }

        lifecycleScope.launch {
            val googleIdToken = googleSignInManager.getFreshIdToken()
            if (googleIdToken.isNullOrBlank()) {
                return@launch
            }

            antFarmClient.fetchRecentDirectThreadsAsHuman(googleIdToken)
                .onSuccess { threads ->
                    recentDirectChannels.clear()
                    threads.forEach { thread ->
                        recentDirectChannels[thread.target] = ChannelItem(
                            slug = thread.target,
                            title = thread.target,
                            preview = thread.preview
                        )
                    }
                    if (updateUi) {
                        updateRoomsUi()
                    }
                }
        }
    }

    private fun updateRoomsUi() {
        binding.familyRoomSummary.text = "Family room: ${getConfiguredRoom()} · plus recent DMs and IDE channels"
        channelItems.clear()
        channelItems.addAll(buildChannelItems())
        binding.channelsListContainer.removeAllViews()
        val inflater = layoutInflater
        channelItems.forEach { channel ->
            val itemBinding = ItemChannelBinding.inflate(inflater, binding.channelsListContainer, false)
            itemBinding.channelSource.text = channel.title
            itemBinding.channelLatestMessage.text = channel.preview
            itemBinding.root.setOnClickListener {
                openRoomTarget(channel.slug, channel.title)
            }
            binding.channelsListContainer.addView(itemBinding.root)
        }
    }

    private fun buildChannelItems(): List<ChannelItem> {
        val configuredRoom = getConfiguredRoom()
        val items = linkedMapOf<String, ChannelItem>()

        // ClawWatch agent channel always first
        items[CLAWWATCH_CHANNEL] = ChannelItem(
            slug = CLAWWATCH_CHANNEL,
            title = "ClawWatch",
            preview = channelPreviewFor(CLAWWATCH_CHANNEL)
        )

        items[configuredRoom] = ChannelItem(
            slug = configuredRoom,
            title = "Family room",
            preview = channelPreviewFor(configuredRoom)
        )

        listOf(IDE_ROOM_THINKOFF_DEVELOPMENT, IDE_ROOM_ANT_FARM_MANAGEMENT)
            .filter { it != configuredRoom }
            .forEach { slug ->
                items[slug] = ChannelItem(
                    slug = slug,
                    title = slug,
                    preview = channelPreviewFor(slug)
                )
            }

        recentDirectChannels.values.forEach { channel ->
            items.putIfAbsent(
                channel.slug,
                ChannelItem(
                    slug = channel.slug,
                    title = channel.title,
                    preview = channelPreviewFor(channel.slug)
                )
            )
        }

        IDE_DIRECT_TARGETS.forEach { slug ->
            items.putIfAbsent(
                slug,
                ChannelItem(
                    slug = slug,
                    title = slug,
                    preview = channelPreviewFor(slug)
                )
            )
        }

        return items.values.map { item ->
            ChannelItem(
                slug = item.slug,
                title = item.title,
                preview = item.preview
            )
        }
    }

    private fun channelPreviewFor(slug: String): String {
        channelPreviewOverrides[slug]?.takeIf { it.isNotBlank() }?.let { return it }
        recentDirectChannels[slug]?.preview?.takeIf { it.isNotBlank() }?.let { return it }
        if (slug == activeRoomSlug) {
            roomMessages.lastOrNull()?.body?.takeIf { it.isNotBlank() }?.let { return it.take(96) }
        }
        return when (slug) {
            CLAWWATCH_CHANNEL -> "Talk with your ClawWatch AI."
            getConfiguredRoom() -> "Your ClawWatch family conversation."
            IDE_ROOM_THINKOFF_DEVELOPMENT -> "IDE channel · team development."
            IDE_ROOM_ANT_FARM_MANAGEMENT -> "IDE channel · ops and management."
            "@claudeMB" -> "Direct IDE chat · ClaudeMB."
            "@geminiMB" -> "Direct IDE chat · geminiMB."
            "@CodexMB" -> "Direct IDE chat · CodexMB."
            "@claudemm" -> "Direct IDE chat · ClaudeMM."
            else -> "Open channel."
        }
    }

    private fun displayNameForRoom(roomSlug: String, fallback: String = roomSlug): String =
        when {
            roomSlug == activeRoomSlug && activeRoomName.isNotBlank() -> activeRoomName
            roomSlug == IDE_ROOM_THINKOFF_DEVELOPMENT -> "thinkoff-development"
            roomSlug == IDE_ROOM_ANT_FARM_MANAGEMENT -> "ant-farm-management"
            roomSlug == getConfiguredRoom() -> "Family room"
            recentDirectChannels.containsKey(roomSlug) -> recentDirectChannels[roomSlug]?.title ?: roomSlug
            IDE_DIRECT_TARGETS.any { it.equals(roomSlug, ignoreCase = true) } -> roomSlug
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
            refreshRecentDirectChannels()
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
        binding.roomHint.text = "Signed in as $nickname. The room can now load through ClawWatch."
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
        recentDirectChannels.clear()
        binding.nicknameInput.text?.clear()
        updateOwnerUi()
        updateRoomsUi()
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
                "Sign in with Google to use this ClawWatch companion and unlock profile setup."
            } else if (localOwnerMode) {
                "Google OAuth is not configured for this debug build, so this phone is using local owner mode."
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
            } else if (localOwnerMode && nickname.isNullOrBlank()) {
                "Set the nickname this device should use while local owner mode is active."
            } else if (nickname.isNullOrBlank()) {
                "Set the nickname this account should use in ClawWatch."
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

    /**
     * Last-resort Gemma fallback for when watch is offline.
     * Bypasses the classifier and runs Gemma directly.
     */
    private suspend fun runGemmaFallback(prompt: String): String? {
        if (!phoneAgent.isAvailable()) {
            phoneAgent.initialize()
        }
        if (!phoneAgent.isAvailable()) return null
        val result = phoneAgent.query(prompt)
        return when (result) {
            is PhoneAgent.RouterResult.Answer -> result.text
            is PhoneAgent.RouterResult.Escalate -> {
                // Even escalated queries get Gemma as last resort when offline
                try {
                    val directResult = phoneAgent.query("Please answer briefly: $prompt")
                    (directResult as? PhoneAgent.RouterResult.Answer)?.text
                } catch (_: Exception) { null }
            }
        }
    }

    private fun saveClawWatchHistory() {
        try {
            val json = org.json.JSONArray()
            roomMessages.forEach { msg ->
                json.put(org.json.JSONObject().apply {
                    put("author", msg.author)
                    put("body", msg.body)
                    put("timestamp", msg.timestamp)
                    put("isUser", msg.isUser)
                })
            }
            prefs.edit().putString(PREF_CLAWWATCH_HISTORY, json.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun loadClawWatchHistory(): List<LocalMessage> {
        val stored = prefs.getString(PREF_CLAWWATCH_HISTORY, null) ?: return emptyList()
        return try {
            val json = org.json.JSONArray(stored)
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                LocalMessage(
                    author = obj.getString("author"),
                    body = obj.getString("body"),
                    timestamp = obj.optString("timestamp", ""),
                    isUser = obj.getBoolean("isUser")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private var gemmaActivityAnimator: android.animation.ValueAnimator? = null

    private fun setGemmaActivity(state: String) {
        gemmaActivityAnimator?.cancel()
        when (state) {
            "thinking" -> {
                binding.gemmaStatusChip.text = "Gemma thinking..."
                binding.gemmaStatusChip.setTextColor(0xFFFFB020.toInt()) // amber
                binding.gemmaStatusDot.setBackgroundColor(0xFFFFB020.toInt())
                // Pulse animation on the dot
                gemmaActivityAnimator = android.animation.ValueAnimator.ofFloat(0.3f, 1.0f).apply {
                    duration = 600L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    addUpdateListener { binding.gemmaStatusDot.alpha = it.animatedValue as Float }
                    start()
                }
            }
            "streaming" -> {
                binding.gemmaStatusChip.text = "Gemma responding..."
                binding.gemmaStatusChip.setTextColor(0xFF60A5FA.toInt())
                binding.gemmaStatusDot.setBackgroundColor(0xFF60A5FA.toInt())
            }
            else -> {
                if (phoneAgent.isAvailable()) {
                    binding.gemmaStatusChip.text = "Gemma 4 E2B ready"
                    binding.gemmaStatusChip.setTextColor(0xFF4ADE80.toInt())
                    binding.gemmaStatusDot.setBackgroundColor(0xFF4ADE80.toInt())
                } else {
                    binding.gemmaStatusChip.text = "Gemma offline"
                    binding.gemmaStatusChip.setTextColor(0xFF9CA3AF.toInt())
                    binding.gemmaStatusDot.setBackgroundColor(0xFFEF4444.toInt())
                }
                binding.gemmaStatusDot.alpha = 1.0f
            }
        }
    }

    private fun nowTime(): String = localTimeFormat.format(Date())

    private fun formatTimestamp(value: String): String {
        if (value.isBlank()) return nowTime()
        return runCatching { isoTimeFormat.format(Instant.parse(value)) }.getOrElse { value.take(16) }
    }
}
