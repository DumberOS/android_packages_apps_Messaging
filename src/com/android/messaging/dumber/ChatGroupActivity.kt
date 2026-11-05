package com.android.messaging.dumber

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.view.View
import android.os.Bundle
import android.widget.*
import chat.dumber.sdk.*
import java.util.UUID
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.messaging.R

class ChatGroupActivity : ChatActivity() {
    companion object {
        @Volatile internal var visibleKey: String? = null
        private const val PICK_PARTICIPANT = 41
    }
    private var key: String? = null
    private var rendered: Any? = null
    private var draft = ""
    private var entry: EditText? = null
    private var screen: GroupScreen? = null
    private var toolbar: Toolbar? = null
    private var timeline: RecyclerView? = null
    private var messages: ChatGroupMessages? = null
    private var actions: LinearLayout? = null
    private var composer: View? = null
    private var send: ImageButton? = null
    private var sending = false
    private var initialTimeline = true
    private var scrollToLatest = false
    private var savedScroll: Parcelable? = null
    private var loadGeneration = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        key = state?.getString("key") ?: intent.getStringExtra("group")
        draft = state?.getString("draft").orEmpty()
        savedScroll = state?.getParcelable("scroll")
        title = "Encrypted group"
        if (key != null) chatLayout()
    }
    private fun chatLayout() {
        if (toolbar != null) return
        actionBar?.hide()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.dumber_group_chat)
        toolbar = findViewById<Toolbar>(R.id.group_toolbar).apply {
            title = this@ChatGroupActivity.title
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.dumber_group_menu)
            menu.findItem(R.id.group_participants).isEnabled = false
            setOnMenuItemClickListener { item ->
                val current = screen
                if (current == null) false else when (item.itemId) {
                    R.id.group_details -> { details(current.group); true }
                    R.id.group_participants -> { manageParticipants(current.group); true }
                    else -> false
                }
            }
        }
        actions = findViewById(R.id.group_actions)
        composer = findViewById(R.id.group_composer)
        messages = ChatGroupMessages { pending -> showPending(pending) }
        timeline = findViewById<RecyclerView>(R.id.group_messages).apply {
            layoutManager = LinearLayoutManager(this@ChatGroupActivity).apply { stackFromEnd = true }
            adapter = messages
        }
        entry = findViewById<EditText>(R.id.group_message_entry).apply {
            setText(draft)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSendButton() }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        send = findViewById<ImageButton>(R.id.group_send).apply { setOnClickListener { sendMessage() } }
        updateSendButton()
    }
    private fun canSend(state: GroupScreen?) = state != null && state.connected &&
        state.group.conversation.membership == Membership.JOINED && state.group.conversation.capabilities.send

    private fun updateSendButton() {
        send?.isEnabled = canSend(screen) && !sending && !entry?.text.isNullOrBlank()
        send?.alpha = if (send?.isEnabled == true) 1f else .45f
    }
    private fun sendMessage() {
        val g = screen?.group ?: return
        val body = entry?.text?.toString().orEmpty()
        if (sending || !canSend(screen) || body.isBlank()) return
        sending = true
        updateSendButton()
        val app = applicationContext
        DumberChat.task(this, { runCatching {
            check(ChatAccounts.isDefault(app)) { "Set Messaging as the default SMS app" }
            val store = ChatStore.get(app)
            store.atomic {
                val account = store.account(g.account)?.takeIf { it.enabled } ?: error("Reconnect the account")
                val current = store.group(g.key) ?: error("Group no longer available")
                check(current.conversation.membership == Membership.JOINED && current.conversation.capabilities.send) {
                    "You cannot send messages to this group"
                }
                PendingText(UUID.randomUUID().toString(), g.account, account.subscription, "", body, "",
                    g.conversation.id, true).also { store.save(it) }
            }.also { pending ->
                // Delivery survives leaving this screen; the draft clears only after durable queuing.
                DumberChat.workers.execute { DumberChat.retry(app, pending.id) }
            }
        } }) { result ->
            sending = false
            result.fold(onSuccess = {
                if (entry?.text?.toString() == body) { entry?.text?.clear(); draft = "" }
                scrollToLatest = true
                DumberChat.changed(this)
                refresh()
            }, onFailure = {
                Toast.makeText(this, it.message ?: "Unable to send message", Toast.LENGTH_LONG).show()
            })
            updateSendButton()
        }
    }
    private fun showPending(pending: PendingText) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pendingControls(this, box, pending)
        AlertDialog.Builder(this).setTitle("Message options")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("key", key)
        state.putString("draft", entry?.text?.toString() ?: draft)
        state.putParcelable("scroll", timeline?.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(state)
    }
    override fun onResume() {
        visibleKey = key
        super.onResume()
        key?.let { ChatNotifications.cancel(this, it) }
    }
    override fun onPause() {
        visibleKey = null
        loadGeneration++
        super.onPause()
    }
    override fun refresh() {
        if (key == null) {
            if (rendered == null) createForm()
            return
        }
        val target = key!!
        val ticket = ++loadGeneration
        DumberChat.task(
            this,
            {
                val store = ChatStore.get(this)
                val g = store.group(target) ?: error("Group no longer available")
                store.atomic {
                    store.group(g.key)?.let { if (it.unread != 0) store.save(it.copy(unread = 0)) }
                }
                GroupScreen(
                    g.copy(unread = 0),
                    store.messages(g.key),
                    store.pending().filter {
                        it.group && groupKey(it.account, it.conversation.orEmpty()) == g.key
                    },
                    store.account(g.account)?.enabled == true
                )
            }
        ) { state ->
            if (ticket != loadGeneration || key != target) return@task
            if (state == rendered && !scrollToLatest) return@task
            chatLayout()
            val previous = screen
            screen = state
            rendered = state
            val g = state.group
            title = g.conversation.name?.takeIf { it.isNotBlank() } ?: "Encrypted group"
            toolbar?.title = title
            toolbar?.menu?.findItem(R.id.group_participants)?.isEnabled = state.connected &&
                g.conversation.membership == Membership.JOINED && g.conversation.capabilities.manage
            if (previous?.connected != state.connected || previous.group.conversation != g.conversation) {
                renderActions(state)
            }
            composer?.visibility = if (canSend(state)) View.VISIBLE else View.GONE
            updateSendButton()
            val list = timeline!!
            val manager = list.layoutManager as LinearLayoutManager
            val atBottom = !list.canScrollVertically(1)
            messages!!.update(g, state.messages, state.pending)
            val scroll = savedScroll
            if (scroll != null) {
                manager.onRestoreInstanceState(scroll)
                savedScroll = null
            } else if ((initialTimeline || atBottom || scrollToLatest) && messages!!.itemCount > 0) {
                list.scrollToPosition(messages!!.itemCount - 1)
            }
            initialTimeline = false
            scrollToLatest = false
        }
    }
    private fun renderActions(state: GroupScreen) {
        val box = actions ?: return
        box.removeAllViews()
        val g = state.group
        if (!state.connected) box.button("Reconnect account") { DumberChat.openSettings(this) }
        if (g.conversation.membership == Membership.INVITED && state.connected) {
            box.button("Join group") { membershipChange(g, true) }
            box.button("Decline invitation") { membershipChange(g, false) }
        } else if (state.connected && !canSend(state)) {
            box.label(if (g.conversation.membership == Membership.JOINED)
                "You cannot send messages to this group." else "You are no longer a participant in this group.")
        }
        box.visibility = if (box.childCount == 0) View.GONE else View.VISIBLE
    }
    private fun createForm() {
        rendered = "creating"
        DumberChat.task(this, {
            val store = ChatStore.get(this)
            val accounts = store.accounts().filter { it.enabled }
            accounts to accounts.associate { it.id to store.creation(it.id) }
        }) { (accounts, saved) ->
            content.removeAllViews()
            content.label("New encrypted group")
            if (accounts.isEmpty()) {
                content.button("Connect dumber.chat") { DumberChat.openSettings(this) }
                rendered = null
                return@task
            }
            val picker = Spinner(this).apply {
                adapter = ArrayAdapter(this@ChatGroupActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    accounts.map { "${it.phone} · ${it.simLabel}" })
                visibility = if (accounts.size > 1) View.VISIBLE else View.GONE
                content.addView(this)
            }
            val name = content.input("Group name")
            content.label("Start a group, then add participants from the chat.")
            val note = content.label("")
            val create = content.button("Create group") {
                val account = accounts[picker.selectedItemPosition]
                val title = name.text.toString().trim()
                operation {
                    try {
                        check(ChatAccounts.isDefault(this)) { "Set Messaging as the default SMS app" }
                        val store = ChatStore.get(this)
                        val op = ChatGroupActions.creation(account.id, title, store.creation(account.id))
                        store.save(op)
                        val info = ChatAccounts.client(this, account)
                            .createGroup(op.name, op.phones, op.operationId)
                        DumberChat.observe(this, account.id, info)
                        store.creationDone(account.id)
                        runOnUiThread {
                            key = groupKey(account.id, info.id)
                            visibleKey = key
                            chatLayout()
                        }
                        DumberChat.changed(this)
                    } finally {
                        runOnUiThread { rendered = null }
                    }
                }
            }
            val discard = content.button("Discard saved creation") {
                val account = accounts[picker.selectedItemPosition]
                confirm("Discard saved creation?",
                    "If the earlier request already created a group, it will remain in your chats. A new creation will make a separate group.") {
                    operation {
                        ChatStore.get(this).creationDone(account.id)
                        runOnUiThread { rendered = null }
                    }
                }
            }
            fun showSaved() {
                val op = saved[accounts[picker.selectedItemPosition].id]
                name.setText(op?.name.orEmpty())
                name.isEnabled = op == null
                create.text = if (op == null) "Create group" else "Retry creation"
                create.isEnabled = op == null || op.phones.isEmpty()
                discard.visibility = if (op == null) View.GONE else View.VISIBLE
                note.text = when {
                    op == null -> ""
                    op.phones.isNotEmpty() -> "Discard the previous request with participants to start a new group."
                    else -> "Retry will resume this saved group creation."
                }
            }
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showSaved()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            showSaved()
        }
    }

    private fun addParticipant(g: GroupState, initial: String = "") {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        box.label("Choose a contact or enter a phone number. They need dumber.chat activated to receive an invitation.")
        val number = box.input("Phone number", android.text.InputType.TYPE_CLASS_PHONE)
        number.setText(initial)
        val status = box.label("")
        val dialog = AlertDialog.Builder(this).setTitle("Add participants")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null).create()
        val contacts = box.button("Choose contact") {
            try {
                startActivityForResult(Intent(Intent.ACTION_PICK).apply {
                    type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                }, PICK_PARTICIPANT)
                dialog.dismiss()
            } catch (e: android.content.ActivityNotFoundException) {
                status.text = "No contact picker is available. Enter the phone number instead."
            }
        }
        val invite = box.button("Send invitation") {}
        invite.setOnClickListener {
            val raw = number.text.toString()
            invite.isEnabled = false
            contacts.isEnabled = false
            number.isEnabled = false
            status.text = "Sending invitation…"
            DumberChat.task(this, {
                runCatching {
                    check(ChatAccounts.isDefault(this)) { "Set Messaging as the default SMS app" }
                    val account = ChatStore.get(this).account(g.account)?.takeIf { it.enabled }
                        ?: error("Reconnect the account")
                    val phone = ChatAccounts.normalize(this, raw.trim(), account.subscription)
                        ?: error("Enter a valid phone number, including the country code (for example +46).")
                    check(ChatPolicy.allowed(this, phone)) { "Participant blocked by policy" }
                    val info = ChatGroupActions.invite(ChatAccounts.client(this, account), g.conversation.id, phone, account.phone)
                    DumberChat.observe(this, account.id, info)
                    DumberChat.changed(this)
                    phone
                }
            }) { result ->
                invite.isEnabled = true
                contacts.isEnabled = true
                number.isEnabled = true
                status.text = result.fold(
                    onSuccess = { number.text.clear(); "Invitation sent to $it. Add another participant or close this window." },
                    onFailure = { it.message ?: "Unable to invite this participant. Try again." })
            }
        }
        dialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PARTICIPANT || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val group = key ?: return
        DumberChat.task(this, {
            val phone = contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("This contact has no phone number. Choose another contact.")
            (ChatStore.get(this).group(group) ?: error("Group no longer available")) to phone
        }) { (g, phone) -> addParticipant(g, phone) }
    }
    private fun membershipChange(g: GroupState, join: Boolean) {
        operation {
            check(ChatAccounts.isDefault(this)) { "Set Messaging as the default SMS app" }
            val store = ChatStore.get(this)
            val account = store.account(g.account)?.takeIf { it.enabled } ?: error("Reconnect the account")
            val current = store.group(g.key) ?: error("Group no longer available")
            val client = ChatAccounts.client(this, account)
            val info = if (join) ChatGroupActions.joinInvitation(client, current.conversation, account.id) {
                ChatPolicy.allowed(this, it)
            } else ChatGroupActions.leave(client, current.conversation, account.id)
            DumberChat.observe(this, account.id, info)
            DumberChat.changed(this)
        }
    }
    private fun change(g: GroupState, action: (ChatApi, Conversation) -> Unit) {
        operation {
            check(ChatAccounts.isDefault(this)) { "Set Messaging as the default SMS app" }
            val a =
                ChatStore.get(this).account(g.account)?.takeIf { it.enabled }
                    ?: error("Reconnect the account")
            val client = ChatAccounts.client(this, a)
            val info = client.conversationInfo(g.conversation.id)
            action(client, info)
            DumberChat.observe(this, g.account, client.conversationInfo(info.id))
            DumberChat.changed(this)
        }
    }
    private fun manageParticipants(g: GroupState) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Manage participants")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null).create()
        box.label(g.conversation.members.joinToString("\n") {
            "${it.contact.phone ?: "Unknown participant"} · ${it.membership.name.lowercase()}${if (it.admin) " · Admin" else ""}"
        })
        if (screen?.connected == true && g.conversation.membership == Membership.JOINED &&
            g.conversation.capabilities.manage) {
            box.button("Add participants") {
                dialog.dismiss()
                addParticipant(g)
            }
            g.conversation.members
                .filter {
                    it.contact.id != g.account &&
                        it.membership in listOf(Membership.JOINED, Membership.INVITED)
                }
                .forEach { member ->
                    val who = member.contact.phone ?: "Unknown participant"
                    box.button("Remove $who") {
                        confirm("Remove participant?", who) {
                            change(g) { c, i ->
                                check(i.capabilities.manage)
                                c.removeMember(i.id, member.contact.id)
                            }
                        }
                        dialog.dismiss()
                    }
                    if (!member.admin)
                        box.button("Make $who an admin") {
                            confirm("Grant admin access?", who) {
                                change(g) { c, i ->
                                    check(i.capabilities.manage)
                                    c.grantAdmin(i.id, member.contact.id)
                                }
                            }
                            dialog.dismiss()
                        }
                }
        }
        dialog.show()
    }
    private fun details(g: GroupState) {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(g.conversation.name ?: "Group details")
                .setView(ScrollView(this).apply { addView(box) })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        fun local(work: (GroupState) -> GroupState) {
            operation {
                val s = ChatStore.get(this)
                s.atomic { s.group(g.key)?.let { s.save(work(it)) } }
                DumberChat.changed(this)
            }
            dialog.dismiss()
        }
        box.label("End-to-end encrypted · ${g.conversation.membership.name.lowercase()}")
        box.label(
            g.conversation.members.joinToString("\n") {
                "${it.contact.phone ?: "Unknown participant"} · ${it.membership.name.lowercase()}${if(it.admin) " · Admin" else ""}"
            }
        )
        box.button(if (g.archived) "Unarchive" else "Archive") {
            local { it.copy(archived = !it.archived) }
        }
        box.button(if (g.muted) "Unmute" else "Mute") { local { it.copy(muted = !it.muted) } }
        box.button(if (g.pinned) "Unpin" else "Pin") { local { it.copy(pinned = !it.pinned) } }
        if (screen?.connected == true && g.conversation.membership == Membership.JOINED &&
            g.conversation.capabilities.manage) {
            box.button("Rename group") {
                prompt("Group name", g.conversation.name.orEmpty()) { name ->
                    change(g) { c, i ->
                        check(i.capabilities.manage)
                        c.renameGroup(i.id, name)
                    }
                }
                dialog.dismiss()
            }
        }
        if (g.conversation.membership == Membership.JOINED)
            box.button("Leave group") {
                confirm("Leave group?", "You will stop receiving messages from this group.") {
                    membershipChange(g, false)
                }
                dialog.dismiss()
            }
        box.button("Delete local history") {
            confirm(
                "Delete local group history?",
                "This removes messages from this phone. It does not leave the group or delete messages on other devices."
            ) {
                operation {
                    ChatStore.get(this).deleteGroup(g.key)
                    DumberChat.changed(this)
                    runOnUiThread { finish() }
                }
            }
            dialog.dismiss()
        }
        dialog.show()
    }
    private data class GroupScreen(
        val group: GroupState,
        val messages: List<ReceivedText>,
        val pending: List<PendingText>,
        val connected: Boolean
    )
}
