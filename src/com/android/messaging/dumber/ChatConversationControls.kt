package com.android.messaging.dumber

import android.app.Activity
import android.content.*
import android.telephony.SubscriptionManager
import android.widget.*
import android.view.View
import com.android.messaging.R
import chat.dumber.sdk.*

/** Owns discovery and transport actions without adding a row above the composer. */
class ChatConversationControls(private val activity: Activity, private val listener: Listener) {
    fun interface Listener {
        fun onChanged(selected: ChatComposeState, sims: Map<Int, ChatComposeState>, queued: Int)
    }
    private data class Target(val thread: String, val phone: String?, val subscription: Int,
                              val carrierOnly: Boolean, val mms: Boolean)
    private data class Route(val account: ChatAccount?, val phone: String, val status: RecipientStatus?)
    private data class State(val routes: Map<Int, Route>, val selected: Int, val pending: List<PendingText>)
    private var target: Target? = null
    private var generation = 0
    private var discovering = -1
    private var started = false
    private var state: State? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh(false) }
    }
    fun start() {
        if (started) return
        started = true
        activity.registerReceiver(receiver, IntentFilter(DumberChat.CHANGED), Context.RECEIVER_NOT_EXPORTED)
        refresh(true)
    }
    fun stop() {
        generation++
        if (started) activity.unregisterReceiver(receiver)
        started = false
    }
    fun update(thread: String?, phone: String?, subscription: Int, carrierOnly: Boolean, mms: Boolean) {
        val next = thread?.let { Target(it, phone, subscription, carrierOnly, mms) }
        if (target == next) return
        target = next
        generation++
        state = null
        // Never retain another SIM's lock while resolving a changed selection.
        listener.onChanged(if (phone == null) carrierState(mms) else ChatComposeState.CHECKING,
            emptyMap(), 0)
        refresh(true)
    }
    private fun carrierState(carrierOnly: Boolean) = if (carrierOnly) ChatComposeState.MMS else ChatComposeState.SMS
    private fun presentation(t: Target, route: Route?) = if (t.phone == null) carrierState(t.mms)
        else if (route == null) ChatComposeState.CHECKING
        else ChatComposeState.forRecipient(route.account != null, route.status?.availability, t.carrierOnly).let {
            if (it == ChatComposeState.SMS && t.mms) ChatComposeState.MMS else it
        }

    private fun refresh(discover: Boolean, force: Boolean = false) {
        val t = target ?: return
        if (!started || (!discover && discovering == generation)) return
        val ticket = ++generation
        if (discover) discovering = ticket
        DumberChat.task(activity, { runCatching {
            val sims = ChatAccounts.sims(activity).orEmpty()
            val selected = if (t.subscription >= 0) t.subscription else SubscriptionManager.getDefaultSmsSubscriptionId()
            val routes = sims.associate { sim ->
                val account = if (t.phone == null) null else ChatAccounts.forSim(activity, sim)
                val phone = t.phone?.let { ChatAccounts.normalize(activity, it, sim.subscription) ?: it }.orEmpty()
                val status = if (account == null) null else runCatching {
                    val client = ChatAccounts.client(activity, account)
                    if (discover && ChatPolicy.allowed(activity, phone)) client.checkRecipient(phone, force)
                    else client.recipientStatus(phone)
                }.getOrNull()
                sim.subscription to Route(account, phone, status)
            }
            State(routes, selected, ChatStore.get(activity).pending().filter {
                !it.group && it.localConversation == t.thread
            })
        } }) { loaded ->
            if (!started || ticket != generation || target != t) return@task
            discovering = -1
            val result = loaded.getOrNull() ?: run {
                state = null
                listener.onChanged(ChatComposeState.CHECKING, emptyMap(), 0)
                return@task
            }
            state = result
            listener.onChanged(presentation(t, result.routes[result.selected]),
                result.routes.mapValues { presentation(t, it.value) }, result.pending.size)
        }
    }
    fun subscriptionsChanged() { refresh(true) }

    fun showDetails() {
        val t = target ?: return
        val current = state
        val route = current?.routes?.get(current.selected)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Message transport")
            .setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null).create()
        val title = when (presentation(t, route)) {
            ChatComposeState.ENCRYPTED -> "Encrypted · ${route?.account?.phone}"
            ChatComposeState.CHECKING -> "Encrypted availability unknown · Sends will wait"
            ChatComposeState.UNSUPPORTED -> DumberChat.UNSUPPORTED_CONTENT
            ChatComposeState.MMS -> "MMS · Carrier attachments / group"
            ChatComposeState.SMS -> "SMS"
        }
        box.label(title)
        if (t.phone != null) {
            if (route?.account == null) box.button("Connect dumber.chat") {
                dialog.dismiss()
                DumberChat.openSettings(activity)
            }
            box.button("Check encrypted availability") {
                dialog.dismiss()
                refresh(true, true)
            }
            if (route?.status?.availability == Availability.AVAILABLE && route.account != null)
                box.button("Verify recipient devices") {
                    dialog.dismiss()
                    devices(activity, route.account.id, route.phone)
                }
        }
        current?.pending?.forEach { pendingControls(activity, box, it) }
        dialog.show()
    }
}

/** Immutable Java-facing row snapshot; no provider conversation IDs are fabricated. */
data class ChatGroupRow(
    val key: String,
    val title: String,
    val snippet: String,
    val timestamp: Long,
    val unread: Int,
    val pinned: Boolean,
    val invited: Boolean,
    val participantCount: Int
)

object ChatGroups {
    @JvmStatic
    fun load(
        context: Context,
        archived: Boolean,
        callback: java.util.function.Consumer<List<ChatGroupRow>>
    ) {
        DumberChat.workers.execute {
            val rows =
                runCatching {
                        ChatStore.get(context)
                            .groups()
                            .filter { !it.hidden && it.archived == archived }
                            .map {
                                ChatGroupRow(
                                    it.key,
                                    it.conversation.name ?: "Encrypted group",
                                    it.snippet,
                                    it.date,
                                    it.unread,
                                    it.pinned,
                                    it.conversation.membership == Membership.INVITED,
                                    it.conversation.members
                                        .filter { member -> member.membership == Membership.JOINED }
                                        .distinctBy { member -> member.contact.id }
                                        .size
                                )
                            }
                    }
                    .getOrDefault(emptyList())
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback.accept(rows) }
        }
    }
    @JvmStatic
    fun bind(view: View, row: ChatGroupRow) {
        val title = view.findViewById<TextView>(R.id.dumber_group_title)
        val snippet = view.findViewById<TextView>(R.id.dumber_group_snippet)
        val count = view.findViewById<TextView>(R.id.dumber_group_count)
        title.text = "${if(row.pinned) "★ " else ""}${row.title}${if(row.unread>0) " (${row.unread})" else ""}"
        snippet.text = if (row.invited) "Encrypted group invitation"
            else "Encrypted group · ${row.snippet}"
        count.text = java.text.NumberFormat.getIntegerInstance().format(row.participantCount)
        val typeface = if (row.unread > 0) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
        title.setTypeface(null, typeface)
        snippet.setTypeface(null, typeface)
        val participants = view.resources.getQuantityString(
            R.plurals.dumber_group_participant_count, row.participantCount, row.participantCount)
        view.contentDescription = "${title.text}, $participants, ${snippet.text}"
        view.setOnClickListener { DumberChat.openGroup(view.context, row.key) }
        view.setOnLongClickListener {
            DumberChat.openGroup(view.context, row.key)
            true
        }
    }
}
