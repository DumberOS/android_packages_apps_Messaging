package com.android.messaging.dumber

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.android.messaging.R
import chat.dumber.sdk.MessageContent

/** Reuses Messaging's bubble shapes and colours while keeping group storage independent. */
internal class ChatGroupMessages(private val pendingClick: (PendingText) -> Unit) :
    RecyclerView.Adapter<ChatGroupMessages.Holder>() {
    private data class Row(val id: String, val outgoing: Boolean, val sender: String,
                           val body: String, val timestamp: Long, val status: String,
                           val pending: PendingText? = null)
    private var rows = emptyList<Row>()

    fun update(group: GroupState, messages: List<ReceivedText>, pending: List<PendingText>) {
        val confirmed = messages.filter { it.message.sender.id == group.account }
        val transactions = confirmed.mapNotNull { it.message.transactionId }.toSet()
        val events = confirmed.map { it.message.eventId }.toSet()
        val next = (messages.map { received ->
            val message = received.message
            val outgoing = message.sender.id == group.account
            val who = message.sender.phone ?: group.conversation.members.find {
                it.contact.id == message.sender.id
            }?.contact?.phone ?: "Unknown participant"
            Row(if (outgoing && message.transactionId != null) "txn:${message.transactionId}"
                else "event:${message.eventId}", outgoing, who,
                (message.content as? MessageContent.Text)?.body ?: "Waiting for encryption keys",
                message.timestampMs, if (message.encrypted) "Encrypted" else "")
        } + pending.filter { it.id !in transactions && it.eventId !in events }.map {
            val status = when {
                it.state == DeliveryState.CONFIRMED -> "Sent encrypted · Saving locally"
                it.state == DeliveryState.UNCERTAIN -> "Delivery uncertain · Tap for options"
                it.error.isNotBlank() -> "Not sent · Tap for options"
                else -> "Waiting to send · Tap for options"
            }
            Row("txn:${it.id}", true, "", it.body, it.createdAt, status, it)
        }).sortedBy { it.timestamp }
        if (rows == next) return
        val before = rows
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = before.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(old: Int, new: Int) = before[old].id == next[new].id
            override fun areContentsTheSame(old: Int, new: Int) = before[old] == next[new]
        })
        rows = next
        changes.dispatchUpdatesTo(this)
    }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val width = parent.width.takeIf { it > 0 } ?: parent.resources.displayMetrics.widthPixels
        return Holder(parent.context, (width * .82f).toInt())
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        holder.line.gravity = if (row.outgoing) Gravity.END else Gravity.START
        val colour = context.getColor(if (row.outgoing) R.color.message_text_color_outgoing
            else R.color.message_text_color_incoming)
        holder.bubble.background = context.getDrawable(if (row.outgoing)
            R.drawable.message_bubble_outgoing_no_arrow else R.drawable.message_bubble_incoming_no_arrow)!!.mutate().apply {
                setTint(context.getColor(if (row.outgoing) R.color.message_bubble_color_outgoing else R.color.lineage_accent))
            }
        holder.sender.text = row.sender
        holder.sender.visibility = if (row.outgoing) View.GONE else View.VISIBLE
        holder.sender.setTextColor(colour)
        holder.body.text = row.body
        holder.body.setTextColor(colour)
        holder.body.setTextIsSelectable(row.pending == null)
        val time = android.text.format.DateFormat.format("MMM d HH:mm", row.timestamp)
        holder.status.text = if (row.status.isBlank()) time else "$time · ${row.status}"
        holder.status.setTextColor(context.getColor(if (row.outgoing) R.color.timestamp_text_outgoing
            else R.color.timestamp_text_incoming))
        holder.bubble.setOnClickListener(row.pending?.let { p -> View.OnClickListener { pendingClick(p) } })
        holder.bubble.isClickable = row.pending != null
        holder.bubble.isFocusable = row.pending != null
    }
    class Holder(context: Context, maxWidth: Int) : RecyclerView.ViewHolder(LinearLayout(context)) {
        val line = itemView as LinearLayout
        val bubble = LinearLayout(context)
        val sender = TextView(context)
        val body = TextView(context)
        val status = TextView(context)
        init {
            fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
            line.orientation = LinearLayout.HORIZONTAL
            line.layoutParams = RecyclerView.LayoutParams(-1, -2)
            line.setPadding(dp(12), dp(3), dp(12), dp(3))
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(dp(16), dp(10), dp(16), dp(10))
            line.addView(bubble, LinearLayout.LayoutParams(-2, -2))
            listOf(sender, body, status).forEach { text ->
                text.maxWidth = maxWidth - dp(32)
                bubble.addView(text, LinearLayout.LayoutParams(-2, -2))
            }
            sender.textSize = 13f
            sender.setTypeface(null, Typeface.BOLD)
            sender.setPadding(0, 0, 0, dp(4))
            body.textSize = 16f
            status.textSize = 11f
            status.setPadding(0, dp(5), 0, 0)
        }
    }
}
