package com.android.messaging.dumber

import chat.dumber.sdk.*

/** Durable one-time work, scoped to the exact registration that reviewed the browser. */
internal data class BrowserHistoryTransfer(
    val account: String,
    val sessionKey: String,
    val code: String,
    val codeExpiresAt: Long,
    val expiresAt: Long,
    val prepared: PreparedHistory?,
    val approved: Boolean = false,
    val finished: Boolean = false,
    val nextAttemptAt: Long = 0,
    val error: String = ""
) {
    override fun toString() = "BrowserHistoryTransfer(account=$account, approved=$approved, finished=$finished)"
}

internal object ChatHistoryTransfer {
    fun sessionKey(account: ChatAccount) = digest(account.session.credentials)

    /** Persist approval separately; an upload failure must never undo linking. */
    fun advance(
        job: BrowserHistoryTransfer, account: ChatAccount?, api: ChatApi,
        now: Long, save: (BrowserHistoryTransfer) -> Unit
    ): BrowserHistoryTransfer {
        check(account?.enabled == true && account.id == job.account && sessionKey(account) == job.sessionKey) {
            "The account changed; link this browser again"
        }
        if (job.finished) return job
        if (now >= job.expiresAt || (!job.approved && now >= job.codeExpiresAt && job.prepared == null)) {
            return job.copy(prepared = null, finished = true, error = "History copy expired. Link the browser again.").also(save)
        }
        if (!job.approved && now >= job.codeExpiresAt) {
            // Approval may have succeeded before its response was lost. The
            // server permits this upload only for an already approved transfer.
            api.uploadPairingHistory(requireNotNull(job.prepared))
            return job.copy(approved = true, prepared = null, finished = true, error = "").also(save)
        }
        if (!job.approved) {
            api.approvePairing(job.code)
            return job.copy(approved = true, expiresAt = now + 15 * 60_000, nextAttemptAt = 0).also(save)
        }
        job.prepared?.let { api.uploadPairingHistory(it) }
        return job.copy(prepared = null, finished = true, nextAttemptAt = 0,
            error = if (job.prepared != null) "" else job.error).also(save)
    }
}

internal data class HistoryProviderRow(val marker: String?, val body: String?, val timestamp: Long, val incoming: Boolean)

/** Reads only confirmed encrypted direct rows and the app's surviving group history. */
internal object ChatHistorySnapshot {
    fun collect(
        account: ChatAccount, conversations: List<Conversation>, groups: List<GroupState>,
        groupMessages: List<ReceivedText>, direct: Sequence<HistoryProviderRow>
    ): List<HistoryConversation> {
        val infos = conversations.associateBy { it.id }.toMutableMap()
        val ownGroups = groups.filter { it.account == account.id && !it.hidden }
        ownGroups.forEach { infos.putIfAbsent(it.conversation.id, it.conversation) }
        val messages = linkedMapOf<String, LinkedHashMap<String, Message>>()
        val prefix = "dumber.chat:${account.id}/"
        for (row in direct) {
            val marker = row.marker?.takeIf { it.startsWith(prefix) } ?: continue
            val ids = marker.removePrefix(prefix).split('/', limit = 2)
            if (ids.size != 2 || ids.any { it.isEmpty() } || row.body == null) continue
            val info = infos[ids[0]] ?: error("A saved chat is missing from this device's encryption store")
            check(info.kind == ConversationKind.DIRECT) { "A saved direct chat has unsupported metadata" }
            val sender = if (!row.incoming) Contact(account.id, account.phone)
                else info.participants.singleOrNull()
                    ?: info.members.map { it.contact }.filter { it.id != account.id }.distinctBy { it.id }.singleOrNull()
                    ?: error("A saved chat participant could not be identified")
            messages.getOrPut(info.id) { linkedMapOf() }[ids[1]] = Message(
                ids[1], sender, row.timestamp, MessageContent.Text(row.body), true)
        }
        val groupIds = ownGroups.map { it.conversation.id }.toSet()
        for (v in groupMessages) {
            if (v.account != account.id || v.conversation !in groupIds || v.message.content !is MessageContent.Text) continue
            messages.getOrPut(v.conversation) { linkedMapOf() }[v.message.eventId] = v.message
        }
        return messages.map { (id, events) -> HistoryConversation(infos.getValue(id), events.values.sortedBy { it.timestampMs }) }
    }
    fun contacts(history: List<HistoryConversation>): List<Contact> = history.flatMap {
        it.conversation.participants + it.conversation.members.map { member -> member.contact } + it.messages.map { message -> message.sender }
    }.distinctBy { it.id }
}
