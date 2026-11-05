package com.android.messaging.dumber

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import chat.dumber.sdk.*
import java.util.UUID
import java.util.concurrent.Executors

/** Narrow Java-facing integration. Account/queue records remain typed and app-private. */
object DumberChat {
    const val CHANGED = "com.android.messaging.dumber.CHANGED"
    const val MARKER = "dumber.chat:"
    val workers = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gateway: CarrierGateway? = null
    @Volatile private var encrypted = emptySet<String>()
    private val sendLocks = Array(64) { Any() }
    private fun sendLock(id: String) = sendLocks[(id.hashCode() and Int.MAX_VALUE) % sendLocks.size]
    interface CarrierGateway {
        fun send(id: String, subscription: Int, recipient: String, body: String)
        fun refreshMessages(incoming: Boolean)
        fun blocked(number: String): Boolean
    }
    @JvmStatic
    fun install(context: Context, carrier: CarrierGateway) {
        gateway = carrier
        SimRemovalUi.install(context.applicationContext as android.app.Application)
        workers.execute {
            runCatching {
                rebuildTransport(context)
                recover(context)
                ChatSyncService.ensure(context)
                ChatSyncService.schedule(context)
            }
        }
    }
    @JvmStatic
    fun resume(context: Context) {
        workers.execute { runCatching { ChatSyncService.ensure(context) } }
    }
    /** Read encrypted account storage off the UI thread. */
    @JvmStatic
    fun loadActivation(context: Context, callback: java.util.function.Consumer<Boolean>) {
        val app = context.applicationContext
        workers.execute {
            val activated =
                runCatching { ChatStore.get(app).accounts().any { it.enabled } }.getOrDefault(false)
            main.post { callback.accept(activated) }
        }
    }
    @JvmStatic
    fun openSettings(context: Context) {
        context.startActivity(Intent(context, ChatSettingsActivity::class.java))
    }
    @JvmStatic
    fun openGroup(context: Context, key: String) {
        context.startActivity(Intent(context, ChatGroupActivity::class.java).putExtra("group", key))
    }
    @JvmStatic
    fun newGroup(context: Context) {
        context.startActivity(Intent(context, ChatGroupActivity::class.java))
    }
    internal fun changed(context: Context) {
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }
    internal fun <T> task(activity: Activity, work: () -> T, done: (T) -> Unit) {
        workers.execute {
            try {
                val result = work()
                main.post { if (!activity.isFinishing && !activity.isDestroyed) done(result) }
            } catch (e: Exception) {
                main.post {
                    if (!activity.isFinishing && !activity.isDestroyed)
                        android.widget.Toast.makeText(
                                activity,
                                e.message ?: "Chat operation failed",
                                android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                }
            }
        }
    }
    const val UNSUPPORTED_CONTENT =
        "Subjects and attachments aren't supported by encrypted messaging. Remove them to send encrypted."

    private data class DirectRoute(
        val account: ChatAccount,
        val sim: Sim,
        val number: String,
        val carrier: Boolean
    )

    /** Uses cached availability only. Unknown availability must not downgrade to MMS. */
    private fun directRoute(context: Context, recipient: String, subscription: Int): DirectRoute? {
        check(ChatAccounts.isDefault(context)) { "Messaging is no longer the default SMS app" }
        val store = ChatStore.get(context)
        val sim = ChatAccounts.selected(context, subscription)
        check(sim != null || store.accounts().none { it.enabled }) {
            "Select an available SIM before sending"
        }
        val account = ChatAccounts.forSim(context, sim) ?: return null
        val number = ChatAccounts.normalize(context, recipient, sim!!.subscription) ?: recipient
        val status =
            runCatching { ChatAccounts.client(context, account).recipientStatus(number) }
                .getOrNull()
        return DirectRoute(
            account,
            sim,
            number,
            ChatPolicy.allowed(context, number) && ChatRules.useCarrier(status)
        )
    }

    /** Composer preflight runs off the UI thread, before clearing any draft content. */
    @JvmStatic
    fun canSendCarrierContent(context: Context, recipient: String, subscription: Int): Boolean {
        val route = directRoute(context, recipient, subscription) ?: return true
        return route.carrier
    }

    /** Invoked for all direct messages before any carrier rows exist. */
    @JvmStatic
    fun intercept(
        context: Context,
        recipient: String,
        body: String,
        conversation: String,
        subscription: Int,
        carrierOnly: Boolean
    ): Boolean {
        val route = directRoute(context, recipient, subscription) ?: return false
        if (route.carrier) return false
        check(!carrierOnly) { UNSUPPORTED_CONTENT }
        val (account, sim, number) = route
        val store = ChatStore.get(context)
        val pending =
            PendingText(
                UUID.randomUUID().toString(),
                account.id,
                sim.subscription,
                number,
                body,
                conversation
            )
        store.save(pending)
        workers.execute { retry(context, pending.id) }
        changed(context)
        return true
    }
    internal fun retry(context: Context, id: String) =
        synchronized(sendLock(id)) {
            val store = ChatStore.get(context)
            var p = store.pending(id) ?: return@synchronized
            if (p.state == DeliveryState.CARRIER_QUEUED) return@synchronized
            try {
                check(ChatAccounts.isDefault(context)) { "Set Messaging as the default SMS app" }
                val account = store.account(p.account) ?: error("Original account unavailable")
                p =
                    ChatDelivery.transmit(
                        p,
                        account,
                        ChatAccounts.client(context, account),
                        store::save
                    ) {
                        ChatPolicy.allowed(context, it)
                    }
                val event =
                    ReceivedText(
                        p.account,
                        requireNotNull(p.conversation),
                        Message(
                            requireNotNull(p.eventId),
                            Contact(p.account, account.phone),
                            p.createdAt,
                            MessageContent.Text(p.body),
                            true,
                            p.id
                        )
                    )
                if (p.group) {
                    check(store.group(groupKey(p.account, event.conversation)) != null)
                    store.groupEvent(event)
                } else deliverDirect(context, event, p.recipient, p.subscription, false)
                store.pendingDone(p.id)
                ChatNotifications.cancel(context, p.id)
            } catch (e: Exception) {
                p = store.pending(id) ?: return@synchronized
                store.save(
                    p.copy(
                        error = e.message ?: "Unable to send",
                        recipientUnavailable =
                            (e as? ChatException)?.recipientUnavailable == true ||
                                p.recipientUnavailable
                    )
                )
                ChatNotifications.pending(context, p)
            } finally {
                changed(context)
            }
        }
    internal fun discard(context: Context, id: String) {
        synchronized(sendLock(id)) {
            val store = ChatStore.get(context)
            val p = store.pending(id) ?: return@synchronized
            check(p.state != DeliveryState.CONFIRMED && p.state != DeliveryState.CARRIER_QUEUED) {
                "This message has already been handed off"
            }
            store.pendingDone(id)
            ChatNotifications.cancel(context, id)
            changed(context)
        }
    }
    internal fun fallback(context: Context, id: String) {
        synchronized(sendLock(id)) {
            val store = ChatStore.get(context)
            val p = store.pending(id) ?: return@synchronized
            check(
                !p.group &&
                    p.state != DeliveryState.CONFIRMED &&
                    p.state != DeliveryState.CARRIER_QUEUED
            ) {
                "This message cannot use SMS"
            }
            check(
                ChatAccounts.forSim(context, ChatAccounts.selected(context, p.subscription))?.id ==
                    p.account
            ) {
                "The original SIM is unavailable or its account changed"
            }
            check(ChatPolicy.allowed(context, p.recipient)) { "Message blocked by policy" }
            val carrier = requireNotNull(gateway) { "Carrier messaging unavailable" }
            store.save(p.copy(state = DeliveryState.CARRIER_QUEUED))
            try {
                carrier.send(p.id, p.subscription, p.recipient, p.body)
            } catch (e: Exception) {
                store.save(p)
                throw e
            }
            changed(context)
        }
    }
    private fun recover(context: Context) {
        ChatStore.get(context).pending().forEach { p ->
            if (p.state == DeliveryState.CARRIER_QUEUED)
                gateway?.send(p.id, p.subscription, p.recipient, p.body)
            else if (p.state == DeliveryState.CONFIRMED) workers.execute { retry(context, p.id) }
        }
    }
    @JvmStatic
    @Synchronized
    fun carrierUri(context: Context, id: String, thread: Long, time: Long): Uri {
        val p = ChatStore.get(context).pending(id) ?: error("SMS fallback no longer queued")
        check(p.state == DeliveryState.CARRIER_QUEUED && ChatAccounts.isDefault(context))
        check(
            ChatAccounts.forSim(context, ChatAccounts.selected(context, p.subscription))?.id ==
                p.account
        ) {
            "The original SIM or account changed"
        }
        check(ChatPolicy.allowed(context, p.recipient)) { "Message blocked by policy" }
        val marker = "dumber-sms:$id"
        val existing =
            context.contentResolver
                .query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id"),
                    "service_center=?",
                    arrayOf(marker),
                    null
                )
                ?.use {
                    if (it.moveToFirst())
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0))
                    else null
                }
        return existing
            ?: context.contentResolver.insert(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put("address", p.recipient)
                    put("body", p.body)
                    put("sub_id", p.subscription)
                    put("thread_id", thread)
                    put("date", time)
                    put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put("read", 1)
                    put("seen", 1)
                    put("service_center", marker)
                    put("reply_path_present", 0)
                }
            )
                ?: error("Unable to queue SMS")
    }
    internal fun resumeCarrier(context: Context, id: String) {
        val p = ChatStore.get(context).pending(id) ?: return
        check(p.state == DeliveryState.CARRIER_QUEUED)
        requireNotNull(gateway).send(p.id, p.subscription, p.recipient, p.body)
    }
    @JvmStatic
    fun carrierFailed(context: Context, id: String, error: String) {
        val store = ChatStore.get(context)
        store.pending(id)?.let { store.save(it.copy(error = error)) }
        changed(context)
    }
    @JvmStatic
    fun sendFailed(context: Context, error: String) {
        main.post {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    @JvmStatic
    fun carrierPending(context: Context, id: String) =
        ChatStore.get(context).pending(id)?.state == DeliveryState.CARRIER_QUEUED
    @JvmStatic
    fun carrierDone(context: Context, id: String) {
        ChatStore.get(context).pendingDone(id)
        ChatNotifications.cancel(context, id)
        changed(context)
    }
    @JvmStatic fun encrypted(uri: String?) = uri != null && encrypted.contains(uri)
    @JvmStatic
    @Synchronized
    fun transportImported(context: Context, uri: String, marker: String?) {
        if (marker?.startsWith(MARKER) == true) {
            ChatStore.get(context).markTransport(uri)
            encrypted = encrypted + uri
        } else if (encrypted.contains(uri)) {
            ChatStore.get(context).clearTransport(uri)
            encrypted = encrypted - uri
        }
    }
    @Synchronized
    private fun rebuildTransport(context: Context) {
        val store = ChatStore.get(context)
        encrypted = store.encryptedUris()
        if (!ChatAccounts.isDefault(context)) return
        val present = mutableSetOf<String>()
        context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "service_center"),
                "service_center LIKE ?",
                arrayOf("$MARKER%"),
                null
            )
            ?.use { c ->
                while (c.moveToNext()) {
                    val uri =
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, c.getLong(0))
                            .toString()
                    present.add(uri)
                }
                store.resetTransport(present)
                encrypted = present.toSet()
            }
    }
    @Synchronized
    internal fun deliverDirect(
        context: Context,
        v: ReceivedText,
        phone: String,
        subscription: Int,
        incoming: Boolean
    ) {
        val store = ChatStore.get(context)
        if (store.handled(v)) return
        if (!ChatPolicy.allowed(context, phone)) {
            store.done(v)
            return
        }
        check(ChatAccounts.isDefault(context)) { "Messaging is no longer the default SMS app" }
        val marker = MARKER + v.account + "/" + v.conversation + "/" + v.message.eventId
        var uri =
            context.contentResolver
                .query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id"),
                    "service_center=?",
                    arrayOf(marker),
                    null
                )
                ?.use { c ->
                    if (c.moveToFirst())
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, c.getLong(0))
                    else null
                }
        if (uri == null) {
            val thread = Telephony.Threads.getOrCreateThreadId(context, phone)
            uri =
                context.contentResolver.insert(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put("address", phone)
                        put("body", (v.message.content as MessageContent.Text).body)
                        put("thread_id", thread)
                        put("date", v.message.timestampMs)
                        put("date_sent", v.message.timestampMs)
                        put("sub_id", subscription)
                        put(
                            "type",
                            if (incoming) Telephony.Sms.MESSAGE_TYPE_INBOX
                            else Telephony.Sms.MESSAGE_TYPE_SENT
                        )
                        put("read", if (incoming) 0 else 1)
                        put("seen", if (incoming) 0 else 1)
                        put("service_center", marker)
                        put("reply_path_present", 0)
                    }
                )
                    ?: error("Unable to save message")
        }
        store.markTransport(uri.toString())
        encrypted = encrypted + uri.toString()
        store.done(v)
        gateway?.refreshMessages(incoming && gateway?.blocked(phone) != true)
    }
    internal fun sync(context: Context, account: ChatAccount) {
        val store = ChatStore.get(context)
        val client = ChatAccounts.client(context, account)
        val update = client.sync()
        if (
            store.account(account.id)?.let {
                it.enabled && it.session.credentials == account.session.credentials
            } != true || !ChatAccounts.isDefault(context)
        )
            return
        ChatCalls.receive(context, account, update.calls)
        val conversations = client.conversations().associateBy { it.id }.toMutableMap()
        for (change in update.conversations) {
            val info = change.conversation
            conversations[info.id] = info
            if (info.kind == ConversationKind.GROUP) observe(context, account.id, info)
            if (
                info.kind == ConversationKind.DIRECT &&
                    info.membership == Membership.INVITED &&
                    info.participants.singleOrNull()?.phone?.let {
                        ChatPolicy.allowed(context, it)
                    } == true
            )
                client.join(info.id)
            change.messages
                .filter { it.timestampMs >= account.connectedAt }
                .forEach { store.enqueue(ReceivedText(account.id, info.id, it)) }
        }
        for (v in store.received().filter { it.account == account.id }) {
            if (v.message.content == MessageContent.UnableToDecrypt) continue
            if (v.message.content !is MessageContent.Text || !v.message.encrypted) {
                store.done(v)
                continue
            }
            val info = conversations[v.conversation] ?: continue
            if (info.kind == ConversationKind.GROUP) {
                observe(context, account.id, info)
                val sender =
                    info.members.find { it.contact.id == v.message.sender.id }?.contact
                        ?: v.message.sender
                if (sender.phone == null && !ChatPolicy.allowed(context, null)) continue
                if (!ChatPolicy.allowed(context, sender.phone)) {
                    store.done(v)
                    continue
                }
                val g = store.group(groupKey(account.id, info.id))!!
                if (
                    store.groupEvent(v) &&
                        sender.id != account.id &&
                        !g.muted &&
                        (sender.phone?.let { gateway?.blocked(it) } != true)
                )
                    ChatNotifications.group(context, g)
                acknowledge(context, v)
            } else if (info.kind == ConversationKind.DIRECT) {
                val incoming = v.message.sender.id != account.id
                val contact =
                    if (incoming)
                        info.members.find { it.contact.id == v.message.sender.id }?.contact
                            ?: v.message.sender
                    else info.participants.singleOrNull()
                val phone = contact?.phone ?: continue
                deliverDirect(context, v, phone, account.subscription, incoming)
                acknowledge(context, v)
            }
        }
        changed(context)
    }
    private fun acknowledge(context: Context, value: ReceivedText) {
        if (value.message.sender.id != value.account) return
        val id = value.message.transactionId ?: return
        synchronized(sendLock(id)) {
            val store = ChatStore.get(context)
            val p = store.pending(id) ?: return
            if (
                p.account == value.account &&
                    p.conversation == value.conversation &&
                    p.state != DeliveryState.CARRIER_QUEUED
            ) {
                store.pendingDone(id)
                ChatNotifications.cancel(context, id)
            }
        }
    }
    internal fun observe(context: Context, account: String, info: Conversation) {
        val store = ChatStore.get(context)
        store.atomic {
            val old = store.group(groupKey(account, info.id))
            store.save(
                old?.copy(conversation = info)
                    ?: GroupState(account, info, date = System.currentTimeMillis())
            )
        }
    }
    @JvmStatic
    fun receiveCode(context: Context, body: String, timestamp: Long, subscription: Int) {
        val app = context.applicationContext
        workers.execute {
            runCatching {
                val v = ChatStore.get(app).verification() ?: return@runCatching
                if (
                    v.subscription != subscription ||
                        timestamp < v.startedAt - 30_000 ||
                        System.currentTimeMillis() > v.expiresAt
                )
                    return@runCatching
                val code = ChatRules.verificationCode(body) ?: return@runCatching
                synchronized(ChatAccounts) {
                    if (ChatStore.get(app).verification()?.nonce == v.nonce)
                        ChatAccounts.verify(app, v.nonce, code)
                }
            }
        }
    }
}
