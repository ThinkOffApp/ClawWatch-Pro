package com.thinkoff.clawwatch

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.thinkoff.clawwatch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        binding.tabRoom.setOnClickListener { showTab(Tab.ROOM) }
        binding.tabRooms.setOnClickListener { showTab(Tab.ROOMS) }
        binding.tabWatch.setOnClickListener { showTab(Tab.WATCH) }

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
}
