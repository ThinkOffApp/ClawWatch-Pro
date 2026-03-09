package com.thinkoff.clawwatch

import android.view.LayoutInflater
import android.view.ViewGroup
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

    class InVH(private val binding: ItemMessageInBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: LocalMessage) {
            binding.messageAuthor.text = message.author
            binding.messageText.text = message.body
            binding.messageTime.text = message.timestamp
        }
    }

    class OutVH(private val binding: ItemMessageOutBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: LocalMessage) {
            binding.messageAuthor.text = message.author
            binding.messageText.text = message.body
            binding.messageTime.text = message.timestamp
        }
    }
}
