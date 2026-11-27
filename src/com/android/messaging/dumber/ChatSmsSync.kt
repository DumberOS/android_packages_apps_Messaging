package com.android.messaging.dumber

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import chat.dumber.sdk.*
import java.util.UUID

/** Matrix requests are deduplicated; new carrier SMS use Bugle's normal queue. */
internal object ChatSmsSync {
    private val locks = Array(16) { Any() }
    private val publishLocks = Array(16) { Any() }
    private fun publishLock(id: String) = publishLocks[(id.hashCode() and Int.MAX_VALUE) % publishLocks.size]
    private fun lock(id: String) = locks[(id.hashCode() and Int.MAX_VALUE) % locks.size]
    fun configure(context: Context, account: ChatAccount, pairingId: String, enabled: Boolean) {
        synchronized(publishLock(account.id)) { synchronized(lock(account.id)) {
            check(ChatAccounts.isDefault(context)) { "Messaging must be the default SMS app" }
            val store = ChatStore.get(context)
            check(current(store, account)) { "Account changed" }
            val link = ChatAccounts.client(context, account).configureBrowserSms(pairingId, enabled)
            applyLink(store,account,link)
        } }
        if (enabled) publish(context,account)
    }
    private fun applyLink(store: ChatStore, account: ChatAccount, link: BrowserLink?) = store.atomic {
        check(current(store,account)) { "Account changed" }
        val id = link?.takeIf { it.smsEnabled && it.smsRoom != null }?.pairingId
        if (id == null) store.disableSms(account)
        else if (store.smsLink(account) != id) {
            store.disableSms(account)
            store.enableSms(account,id)
            store.startSmsSnapshot(account)
        }
    }
    private fun refreshLink(context: Context, account: ChatAccount): BrowserLink? {
        val link = ChatAccounts.client(context,account).sessionStatus().browser
        applyLink(ChatStore.get(context),account,link)
        return link
    }
    private fun current(store: ChatStore, account: ChatAccount) = store.account(account.id)?.let {
        it.enabled && it.session.credentials == account.session.credentials
    } == true
    private fun attempts(store: ChatStore, account: ChatAccount) = store.smsAttempts().filter {
        it.account == account.id && it.sessionKey == ChatHistoryTransfer.sessionKey(account)
    }

    fun receive(context: Context, account: ChatAccount, requests: List<SmsRequest>) = synchronized(lock(account.id)) {
        val store = ChatStore.get(context)
        if (!current(store, account) || !store.smsEnabled(account) || !ChatAccounts.isDefault(context)) return@synchronized
        val api = ChatAccounts.client(context, account)
        for (request in requests) {
            if (request.pairingId.isEmpty() || request.pairingId != store.smsLink(account)) { api.acknowledgeSms(listOf(request.eventId)); continue }
            if (request.kind == "snapshot_request") {
                // Also deduplicate snapshot requests; never clear the send-attempt ledger.
                val marker = SmsAttempt(account.id, ChatHistoryTransfer.sessionKey(account), request, "snapshot")
                if (ChatSmsPolicy.fresh(request, System.currentTimeMillis())) store.atomic {
                    if (store.claimSms(marker)) {
                        store.clearSmsExports(account)
                        store.startSmsSnapshot(account, request.requestId)
                    }
                }
            } else if (request.kind == "recipient_request") {
                val marker = SmsAttempt(account.id, ChatHistoryTransfer.sessionKey(account), request, "lookup")
                if (ChatSmsPolicy.fresh(request, System.currentTimeMillis()) && store.claimSms(marker) && ChatPolicy.allowed(context, request.address)) {
                    for (sim in ChatAccounts.sims(context).orEmpty()) {
                        val linked = ChatAccounts.forSim(context, sim) ?: continue
                        runCatching { ChatAccounts.client(context, linked).checkRecipient(request.address) }
                    }
                }
            } else {
                val attempt = SmsAttempt(account.id, ChatHistoryTransfer.sessionKey(account), request,
                    ChatSmsPolicy.initial(request, System.currentTimeMillis()),
                    queueVersion = if (request.kind == "send" || request.kind == "send_v2" && request.transport == "sms") 1 else 0)
                if (store.claimSms(attempt) && attempt.state == "unknown") {
                    if (attempt.queueVersion == 1) prepareQueue(context,account,attempt)
                    else submit(context, account, attempt)
                }
            }
            api.acknowledgeSms(listOf(request.eventId))
        }
    }

    fun key(attempt: SmsAttempt) = digest(attempt.account + "/" + attempt.sessionKey + "/" + attempt.request.requestId)
    private fun queued(context: Context, key: String) = ChatStore.get(context).smsAttempts().find { it.queueVersion == 1 && key(it) == key }

    private fun prepareQueue(context: Context, account: ChatAccount, original: SmsAttempt) {
        val store = ChatStore.get(context)
        try {
            var attempt = queued(context,key(original)) ?: return
            if (attempt.localId.isNotEmpty() || attempt.state !in listOf("unknown","sending")) return
            // Replays after committing the local transaction only restore the ledger link.
            if (BrowserSmsQueue.replay(context,key(attempt))) return
            if (attempt.pinnedSim.isEmpty()) {
                check(current(store,account) && store.smsEnabled(account) && ChatAccounts.isDefault(context))
                val request = attempt.request
                val sim = if (request.kind == "send_v2") ChatAccounts.sims(context).orEmpty().find {
                    ChatSmsPolicy.simId(it,ChatAccounts.forSim(context,it)) == request.simId
                } else ChatAccounts.selected(context,account.subscription)?.takeIf { it.cardId == account.cardId }
                check(sim != null && ChatPolicy.allowed(context,request.address))
                val linked = ChatAccounts.forSim(context,sim)
                if (request.kind == "send_v2") {
                    val availability = linked?.let { ChatAccounts.client(context,it).checkRecipient(request.address).availability }
                    check(ChatSmsPolicy.matchesRoute(request,ChatSmsPolicy.simId(sim,linked),ChatSmsPolicy.transport(linked != null,availability)))
                }
                attempt = attempt.copy(state="sending",subscription=sim.subscription,pinnedSim=ChatSmsPolicy.simId(sim,linked))
                store.save(attempt)
            }
            val stop = queuePermission(context,key(attempt),false)
            if (stop.isNotEmpty()) { store.save(attempt.copy(state=stop));return }
            com.android.messaging.datamodel.action.InsertNewMessageAction.insertBrowserSms(
                key(attempt),attempt.subscription,attempt.request.address,attempt.request.body)
        } catch (_: Exception) {
            queued(context,key(original))?.let { if (it.localId.isEmpty()) store.save(it.copy(state="failed")) }
        }
    }
    @JvmStatic fun queuePermission(context: Context, key: String, manual: Boolean): String {
        val attempt = queued(context,key) ?: return "failed"
        if (!manual && !ChatSmsPolicy.fresh(attempt.request,System.currentTimeMillis())) return "expired"
        val store = ChatStore.get(context)
        val account = store.account(attempt.account) ?: return "failed"
        if (!current(store,account) || ChatHistoryTransfer.sessionKey(account) != attempt.sessionKey ||
                !store.smsEnabled(account) || !ChatAccounts.isDefault(context) || !ChatPolicy.allowed(context,attempt.request.address)) return "failed"
        // A durable request belongs to one browser link. Re-linking never revives it.
        val link = runCatching { ChatAccounts.client(context,account).sessionStatus().browser }.getOrNull()
        if (link?.smsEnabled != true || link.smsRoom == null || link.pairingId != attempt.request.pairingId) return "failed"
        val sim = ChatAccounts.selected(context,attempt.subscription) ?: return "failed"
        return if (ChatSmsPolicy.simId(sim,ChatAccounts.forSim(context,sim)) == attempt.pinnedSim) "" else "failed"
    }
    @JvmStatic fun queueUri(context: Context, key: String, thread: Long, time: Long): Uri? {
        val store = ChatStore.get(context)
        val attempt = queued(context,key) ?: return null
        val stop = queuePermission(context,key,false)
        if (stop.isNotEmpty()) { store.save(attempt.copy(state=stop));return null }
        val marker = "dumber-browser-sms:$key"
        val existing = context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id"),"service_center=?",arrayOf(marker),null)?.use {
            if(it.moveToFirst()) ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,it.getLong(0)) else null
        }
        val uri = existing ?: context.contentResolver.insert(Telephony.Sms.CONTENT_URI,ContentValues().apply {
            put("address",attempt.request.address);put("body",attempt.request.body);put("sub_id",attempt.subscription)
            put("thread_id",thread);put("date",time);put("type",Telephony.Sms.MESSAGE_TYPE_SENT)
            put("status",Telephony.Sms.STATUS_NONE);put("read",1);put("seen",1);put("service_center",marker);put("reply_path_present",0)
        }) ?: error("Cannot create outgoing SMS")
        store.save(attempt.copy(uri=uri.toString()))
        return uri
    }
    @JvmStatic fun linked(context: Context, key: String, localId: String, uri: String) {
        val store = ChatStore.get(context)
        store.atomic { queued(context,key)?.let { store.save(it.copy(localId=localId,uri=uri,state="sending")) } }
    }
    @JvmStatic fun queueFailed(context: Context, key: String) {
        val store = ChatStore.get(context)
        store.atomic { queued(context,key)?.let { if(it.localId.isEmpty()) store.save(it.copy(state="failed")) } }
    }
    @JvmStatic fun legacySentUris(context: Context): Set<String> = ChatStore.get(context).smsAttempts()
        .filter { it.queueVersion == 0 && it.state == "sent" && it.uri.isNotEmpty() && it.request.transport != "encrypted" }.map { it.uri }.toSet()

    private fun submit(context: Context, account: ChatAccount, attempt: SmsAttempt) {
        val store = ChatStore.get(context)
        var saved = attempt
        var submitted = false
        try {
            check(current(store, account) && store.smsEnabled(account) && ChatAccounts.isDefault(context)) { "Registration ended" }
            val request = attempt.request
            val sim = if (request.kind == "send_v2") ChatAccounts.sims(context).orEmpty().find {
                ChatSmsPolicy.simId(it, ChatAccounts.forSim(context, it)) == request.simId
            } else ChatAccounts.selected(context, account.subscription)?.takeIf { it.cardId == account.cardId }
            check(sim != null) { "Selected SIM changed or was removed" }
            check(ChatPolicy.allowed(context, request.address)) { "Recipient blocked by phone policy" }
            val linked = ChatAccounts.forSim(context, sim)
            if (request.kind == "send_v2") {
                val status = linked?.let { ChatAccounts.client(context, it).checkRecipient(request.address) }
                check(ChatSmsPolicy.matchesRoute(request, ChatSmsPolicy.simId(sim, linked), ChatSmsPolicy.transport(linked != null, status?.availability))) { "Send method changed; review the SIM selection" }
            }
            fun readyToSend(): Boolean {
                if (!ChatSmsPolicy.fresh(request, System.currentTimeMillis())) {
                    store.save(saved.copy(state = "expired")); return false
                }
                check(current(store, account) && store.smsEnabled(account) && ChatAccounts.isDefault(context))
                val selected = ChatAccounts.selected(context, sim.subscription)
                check(selected != null && ChatSmsPolicy.simId(selected, ChatAccounts.forSim(context, selected)) == ChatSmsPolicy.simId(sim, linked)) { "SIM changed" }
                return true
            }
            if (request.kind == "send_v2" && request.transport == "encrypted") {
                val sender = requireNotNull(linked)
                val client = ChatAccounts.client(context, sender)
                val room = client.openChat(request.address, true)
                saved = saved.copy(matrixRoom = room.id, matrixAccount = sender.id)
                store.save(saved)
                if (!readyToSend()) return
                submitted = true
                val sent = client.sendText(room.id, request.body, request.requestId)
                check(sent.encrypted) { "Encryption confirmation missing" }
                saved = saved.copy(state = "sent", matrixEvent = sent.eventId)
                store.save(saved)
                DumberChat.deliverDirect(context, ReceivedText(sender.id, room.id,
                    Message(sent.eventId, Contact(sender.id, sender.phone), request.createdAtMs, MessageContent.Text(request.body), true, request.requestId)),
                    request.address, sim.subscription, false)
                val marker = DumberChat.MARKER + sender.id + "/" + room.id + "/" + sent.eventId
                context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "service_center=?", arrayOf(marker), null)?.use {
                    if (it.moveToFirst()) store.save(saved.copy(uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0)).toString()))
                }
                return
            }
            error("Carrier requests must use the local send queue")
        } catch (_: Exception) {
            // A lost response may follow successful submission. Never retry a claimed request.
            if (saved.state != "sent") store.save(saved.copy(state = if (submitted) "unknown" else "failed"))
        } finally { DumberChat.refreshSms(context) }
    }

    fun sent(context: Context, intent: Intent, result: Int) = synchronized(lock(intent.getStringExtra("account") ?: "")) {
        val store = ChatStore.get(context)
        store.atomic {
            val attempt = store.smsAttempts().find { it.account == intent.getStringExtra("account") &&
                it.sessionKey == intent.getStringExtra("session") && it.request.requestId == intent.getStringExtra("request") } ?: return@atomic
            val part = intent.getIntExtra("part", -1)
            if (part !in attempt.parts.indices || attempt.parts[part] != -1) return@atomic
            // Activity.RESULT_OK is -1 too, so store 1/0 rather than the Android result code.
            val parts = attempt.parts.toMutableList().apply { this[part] = if (result == Activity.RESULT_OK) 1 else 0 }
            val status = if (parts.any { it == -1 }) "unknown" else if (parts.all { it == 1 }) "sent" else "failed"
            store.save(attempt.copy(state = status, parts = parts))
            if (status == "sent" && attempt.uri.isNotEmpty()) context.contentResolver.update(Uri.parse(attempt.uri), ContentValues().apply {
                put("type", Telephony.Sms.MESSAGE_TYPE_SENT); put("date_sent", System.currentTimeMillis())
            }, null, null)
        }
        DumberChat.refreshSms(context)
    }

    private fun publishRoutes(context: Context, account: ChatAccount) {
        val store = ChatStore.get(context)
        val routes = ChatAccounts.sims(context).orEmpty().map { sim -> sim to ChatAccounts.forSim(context, sim) }
        val sims = routes.map { (sim, linked) -> SmsSim(ChatSmsPolicy.simId(sim, linked), sim.name.take(128),
            (linked?.phone ?: sim.displayNumber).orEmpty().take(64), linked?.id.orEmpty()) }
        val recipients = routes.flatMap { (sim, linked) ->
            if (linked == null) emptyList() else ChatAccounts.client(context, linked).smsRecipientRoutes().map {
                it.copy(simId = ChatSmsPolicy.simId(sim, linked))
            }
        }.sortedWith(compareBy({ it.simId }, { it.address }))
        val old = store.smsRouteExports(account)
        val fingerprint = digest(sims.toString())
        val changes = recipients.filter { old[it.simId + ":" + it.address] != it.transport }.take(1000)
        if (old["sims"] == fingerprint && changes.isEmpty()) return
        val batches = changes.chunked(100).ifEmpty { listOf(emptyList()) }
        for (batch in batches) {
            if (!current(store, account) || !store.smsEnabled(account)) return
            ChatAccounts.client(context, account).publishSmsRoutes(sims, batch, UUID.randomUUID().toString())
            store.atomic {
                if (current(store, account)) {
                    store.saveSmsRoute(account, "sims", fingerprint)
                    batch.forEach { store.saveSmsRoute(account, it.simId + ":" + it.address, it.transport) }
                }
            }
        }
    }

    fun publish(context: Context, account: ChatAccount) = synchronized(publishLock(account.id)) publishing@ {
        val store = ChatStore.get(context)
        if (!current(store, account)) return@publishing
        synchronized(lock(account.id)) { refreshLink(context,account) }
        if (!store.smsEnabled(account)) return@publishing
        val (snapshot, exports) = synchronized(lock(account.id)) {
            attempts(store,account).filter { it.queueVersion == 1 && it.request.pairingId == store.smsLink(account) && it.localId.isEmpty() && it.state in listOf("unknown","sending") }
                .forEach { prepareQueue(context,account,it) }
            val snapshot = store.smsSnapshot(account) ?: store.startSmsSnapshot(account)
            snapshot to store.smsExports().filter { it.account == account.id && it.sessionKey == ChatHistoryTransfer.sessionKey(account) }.associateBy { it.id }
        }
        publishRoutes(context, account)
        val accounts = store.accounts().filter { it.enabled }
        val sims = ChatAccounts.sims(context).orEmpty().associate { sim ->
            sim.subscription to ChatSmsPolicy.simId(sim, accounts.singleOrNull { ChatRules.matches(it, sim) })
        }
        val key = ChatHistoryTransfer.sessionKey(account)
        val jobs = attempts(store, account).filter { it.request.kind in listOf("send", "send_v2") }
        val byEvent = jobs.filter { it.matrixEvent.isNotEmpty() }.associateBy { it.matrixEvent }
        val byMarker = jobs.filter { it.queueVersion == 1 }.associateBy { "dumber-browser-sms:${key(it)}" }
        val statuses = jobs.associate { job -> job.request.requestId to
            if (job.queueVersion == 1) BrowserSmsQueue.status(key(job)) ?: if(job.state == "unknown") "sending" else job.state else job.state }
        fun status(job: SmsAttempt) = statuses.getValue(job.request.requestId)
        val byUri = jobs.filter { it.uri.isNotEmpty() }.associateBy { it.uri }
        val names = mutableMapOf<String, String>()
        fun name(address: String) = names.getOrPut(address) {
            runCatching { context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" } ?: "" }.getOrDefault("").take(256)
        }
        val messages = linkedMapOf<String, SmsMessage>()
        val normalizedAddresses = mutableMapOf<Pair<String, Int>, String>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "sub_id", "service_center"),
            "type IN (1,2,4,5,6)",
            null, "date DESC")?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                val body = c.getString(2) ?: continue
                val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, c.getLong(0)).toString()
                val marker = c.getString(6).orEmpty()
                val matrix = if (marker.startsWith(DumberChat.MARKER)) marker.removePrefix(DumberChat.MARKER).split("/", limit = 3) else emptyList()
                if (marker.startsWith(DumberChat.MARKER) && (matrix.size != 3 || !matrix[2].startsWith("$"))) continue
                val job = byMarker[marker] ?: byUri[uri] ?: matrix.getOrNull(2)?.let(byEvent::get)
                if (job != null && status(job) == "deleted") continue
                val simId = sims[c.getInt(5)].orEmpty()
                val id = job?.let { "request:${it.request.requestId}" } ?: "sms:${c.getLong(0)}"
                val type = c.getInt(4)
                // History belongs to the phone, including old/removed SIMs. Use each
                // row's SIM region when available.
                val normalized = normalizedAddresses.getOrPut(address to c.getInt(5)) {
                    ChatAccounts.normalize(context, address, c.getInt(5))
                        ?: ChatAccounts.normalize(context, address, account.subscription) ?: address
                }
                messages[id] = SmsMessage(id, normalized, body.take(10000), c.getLong(3), type == 1,
                    job?.let(::status) ?: when (type) { 1 -> "received"; 2 -> "sent"; 5 -> "failed"; else -> "sending" }, name(address), simId = job?.request?.simId?.ifEmpty { simId } ?: simId,
                    transport = if (matrix.isEmpty()) "sms" else "encrypted", matrixEventId = matrix.getOrNull(2).orEmpty(),
                    matrixRoomId = matrix.getOrNull(1).orEmpty(), matrixAccount = matrix.getOrNull(0).orEmpty())
            }
        } ?: error("Cannot read SMS history")
        for (job in jobs) if (job.request.pairingId == store.smsLink(account) && "request:${job.request.requestId}" !in messages && job.uri.isEmpty()) {
            val r = job.request
            messages["request:${r.requestId}"] = SmsMessage("request:${r.requestId}", r.address, r.body, r.createdAtMs, false, status(job), name(r.address), simId = r.simId, transport = r.transport.ifEmpty { "sms" },
                matrixEventId = job.matrixEvent, matrixRoomId = job.matrixRoom, matrixAccount = job.matrixAccount)
        }
        for ((id, old) in exports) if (id !in messages) messages[id] = SmsMessage(id, old.address, "", 0, false, "deleted", deleted = true)
        // Include names in the fingerprint, so contact edits are synchronized too.
        val changed = messages.values.map { it to digest(it.toString()) }.filter { exports[it.first.id]?.fingerprint != it.second }
        if (changed.isEmpty() && snapshot.finished) return@publishing
        val api = ChatAccounts.client(context, account)
        var offset = 0
        // Bound each sync turn so incoming send requests and chat sync stay responsive.
        repeat(10) {
            if (!current(store, account) || !store.smsEnabled(account) || store.smsSnapshot(account)?.generation != snapshot.generation) return@publishing
            var bytes = 0
            val batch = changed.drop(offset).take(20).takeWhile { (m, _) ->
                bytes += m.body.toByteArray(Charsets.UTF_8).size + m.name.toByteArray(Charsets.UTF_8).size + m.address.length + 600
                bytes < 46 * 1024
            }
            check(batch.isNotEmpty() || changed.isEmpty()) { "SMS history message exceeds transfer limit" }
            api.publishSms(batch.map { it.first }, snapshot.progress(offset, batch.size, changed.size), UUID.randomUUID().toString())
            store.atomic {
                // A browser can request a new snapshot while the batch is in flight.
                // Never commit old fingerprints or counters into that new generation.
                if (current(store, account) && store.smsSnapshot(account)?.generation == snapshot.generation) {
                    for ((m, fingerprint) in batch) store.save(SmsExport(account.id, key, m.id, m.address, fingerprint))
                    // Commit counters with fingerprints so a lost response/restart
                    // repeats the same offset instead of creating a missing range.
                    store.save(snapshot.copy(count = snapshot.count + offset + batch.size, finished = offset + batch.size == changed.size))
                }
            }
            offset += batch.size
            if (offset == changed.size) return@publishing
        }
    }
}

class ChatSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() detaches the result from this receiver; read it from the token.
        val pending = goAsync(); val result = pending.resultCode
        DumberChat.workers.execute { try { ChatSmsSync.sent(context, intent, result) } finally { pending.finish() } }
    }
}
