package com.thinkoff.clawwatch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.thinkoff.clawwatch.databinding.ItemChannelBinding

data class ChannelItem(
    val slug: String,
    val title: String,
    val preview: String
)

class ChannelAdapter(
    private val items: List<ChannelItem>,
    private val onChannelClicked: (ChannelItem) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    inner class ChannelViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ChannelViewHolder(ItemChannelBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = items[position]
        holder.binding.channelSource.text = channel.title
        holder.binding.channelLatestMessage.text = channel.preview
        holder.binding.root.setOnClickListener { onChannelClicked(channel) }
    }

    override fun getItemCount(): Int = items.size
}
