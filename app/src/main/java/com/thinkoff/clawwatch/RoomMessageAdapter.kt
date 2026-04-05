package com.thinkoff.clawwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.thinkoff.clawwatch.databinding.ItemMessageInBinding
import com.thinkoff.clawwatch.databinding.ItemMessageOutBinding

private const val VIEW_IN = 1
private const val VIEW_OUT = 2

class RoomMessageAdapter(
    private val items: List<LocalMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int =
        if (items[position].isUser) VIEW_OUT else VIEW_IN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_OUT) {
            OutVH(ItemMessageOutBinding.inflate(inflater, parent, false))
        } else {
            InVH(ItemMessageInBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = items[position]
        when (holder) {
            is InVH -> holder.bind(message)
            is OutVH -> holder.bind(message)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun showMessageMenu(context: Context, anchor: android.view.View, message: LocalMessage) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Copy")
        popup.menu.add(0, 2, 1, "Share")
        popup.setOnMenuItemClickListener { item ->
            val fullText = "${message.author}: ${message.body}"
            when (item.itemId) {
                1 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", fullText))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fullText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share message"))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    inner class InVH(private val binding: ItemMessageInBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: LocalMessage) {
            binding.messageAuthor.text = message.author
            binding.messageText.text = message.body
            binding.messageTime.text = message.timestamp
            binding.root.setOnLongClickListener {
                showMessageMenu(it.context, it, message)
                true
            }
        }
    }

    inner class OutVH(private val binding: ItemMessageOutBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: LocalMessage) {
            binding.messageAuthor.text = message.author
            binding.messageText.text = message.body
            binding.messageTime.text = message.timestamp
            binding.root.setOnLongClickListener {
                showMessageMenu(it.context, it, message)
                true
            }
        }
    }
}
