package com.thinkoff.clawwatch

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
import com.thinkoff.clawwatch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val roomMessages = mutableListOf<LocalMessage>()

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

        binding.tabRoom.setOnClickListener { showTab(Tab.ROOM) }
        binding.tabRooms.setOnClickListener { showTab(Tab.ROOMS) }
        binding.tabWatch.setOnClickListener { showTab(Tab.WATCH) }
        binding.sendButton.setOnClickListener { sendComposerMessage() }
        binding.composerInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

        binding.actionCheckWatch.setOnClickListener {
            runQuickAction("Check on watch")
        }
        binding.actionSummarizeFamily.setOnClickListener {
            runQuickAction("Summarize family")
        }
        binding.actionOpenScratchpad.setOnClickListener {
            runQuickAction("Open scratchpad")
        }
        binding.actionInviteSomeone.setOnClickListener {
            runQuickAction("Invite someone")
        }

        seedRoomMessages()
        renderRoomMessages()
        showTab(Tab.ROOM)
    }

    private fun showTab(tab: Tab) {
        binding.headerTitle.text = tab.title
        binding.headerSubtitle.text = tab.subtitle

        binding.roomPanel.alpha = if (tab == Tab.ROOM) 1f else 0f
        binding.roomsPanel.alpha = if (tab == Tab.ROOMS) 1f else 0f
        binding.watchPanel.alpha = if (tab == Tab.WATCH) 1f else 0f

        binding.roomPanel.visibility = if (tab == Tab.ROOM) android.view.View.VISIBLE else android.view.View.GONE
        binding.roomsPanel.visibility = if (tab == Tab.ROOMS) android.view.View.VISIBLE else android.view.View.GONE
        binding.watchPanel.visibility = if (tab == Tab.WATCH) android.view.View.VISIBLE else android.view.View.GONE

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

    private fun seedRoomMessages() {
        if (roomMessages.isNotEmpty()) return

        roomMessages += LocalMessage(
            author = "ClawWatch",
            body = "Your private room is the default home. Watch messages, family summaries, and agent coordination will all land here.",
            isUser = false
        )
        roomMessages += LocalMessage(
            author = "You",
            body = "Good. Keep the watch dashboard one tap away, but keep chat first.",
            isUser = true
        )
        roomMessages += LocalMessage(
            author = "ClawWatch",
            body = "That is the product shape now: Room first, then Rooms, then Watch.",
            isUser = false
        )
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

    private fun sendComposerMessage() {
        val message = binding.composerInput.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return

        roomMessages += LocalMessage(author = "You", body = message, isUser = true)
        roomMessages += LocalMessage(
            author = "ClawWatch",
            body = generateReply(message),
            isUser = false
        )
        binding.composerInput.text?.clear()
        renderRoomMessages()
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

    private fun generateReply(message: String): String {
        val normalized = message.lowercase()
        return when {
            "family" in normalized ->
                "The family view will summarize your configured Ant Farm rooms here first, then let you jump into the room or scratchpad."
            "watch" in normalized ->
                "The Watch tab is where pairing, sync, avatar, and diagnostics stay available without taking over the app."
            "scratchpad" in normalized ->
                "Scratchpad should open as a quick side sheet from the room, not a separate heavy workflow."
            "invite" in normalized ->
                "Invites belong in the room flow too, so people can join the ClawWatch room without leaving the companion rhythm."
            else ->
                "This Room tab is the first ThinkOff-style chat shell in ClawWatch Pro. Next step is replacing these seeded bubbles with the real streamed room timeline."
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
