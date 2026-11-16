package com.varadha.nova

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.varadha.nova.databinding.ItemActionBinding
import com.varadha.nova.databinding.ItemNovaBinding
import com.varadha.nova.databinding.ItemTypingBinding
import com.varadha.nova.databinding.ItemUserBinding

/** One row in the conversation. */
sealed class Msg {
    data class User(val text: String) : Msg()
    data class Nova(val text: String) : Msg()
    class Action(val card: ActionCard) : Msg() {
        enum class State { WAITING, DONE, CANCELLED }
        var state = if (card.countdown > 0) State.WAITING else State.DONE
        var progress = 0 // 0..1000 of the countdown
    }
    object Typing : Msg()
}

class ChatAdapter(
    private val onCancel: (Msg.Action) -> Unit,
    private val onNow: (Msg.Action) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val items = mutableListOf<Msg>()

    fun add(m: Msg) {
        items += m
        notifyItemInserted(items.size - 1)
    }

    fun removeTyping() {
        val i = items.indexOf(Msg.Typing)
        if (i >= 0) {
            items.removeAt(i)
            notifyItemRemoved(i)
        }
    }

    fun changed(m: Msg) {
        val i = items.indexOf(m)
        if (i >= 0) notifyItemChanged(i, PAYLOAD_PROGRESS)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Msg.User -> 0
        is Msg.Nova -> 1
        is Msg.Action -> 2
        Msg.Typing -> 3
    }

    class UserVH(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)
    class NovaVH(val b: ItemNovaBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemActionBinding) : RecyclerView.ViewHolder(b.root)
    class TypingVH(val b: ItemTypingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> UserVH(ItemUserBinding.inflate(inf, parent, false))
            1 -> NovaVH(ItemNovaBinding.inflate(inf, parent, false))
            2 -> ActionVH(ItemActionBinding.inflate(inf, parent, false))
            else -> TypingVH(ItemTypingBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS) && holder is ActionVH) {
            bindAction(holder, items[position] as Msg.Action)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val m = items[position]) {
            is Msg.User -> (holder as UserVH).b.text.text = m.text
            is Msg.Nova -> (holder as NovaVH).b.text.text = m.text
            is Msg.Action -> bindAction(holder as ActionVH, m)
            Msg.Typing -> (holder as TypingVH).b.orb.active = true
        }
    }

    private fun bindAction(h: ActionVH, m: Msg.Action) {
        val b = h.b
        b.icon.setImageResource(m.card.icon)
        b.title.text = m.card.title
        b.subtitle.text = when (m.state) {
            Msg.Action.State.CANCELLED -> "Cancelled"
            else -> m.card.subtitle
        }
        b.subtitle.visibility = if (b.subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        b.done.visibility = if (m.state == Msg.Action.State.DONE) View.VISIBLE else View.GONE
        val waiting = m.state == Msg.Action.State.WAITING
        b.countdownBox.visibility = if (waiting) View.VISIBLE else View.GONE
        b.countdown.progress = m.progress
        b.btnCancel.setOnClickListener { onCancel(m) }
        b.btnNow.setOnClickListener { onNow(m) }
        b.root.alpha = if (m.state == Msg.Action.State.CANCELLED) 0.55f else 1f
    }

    override fun getItemCount() = items.size

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
