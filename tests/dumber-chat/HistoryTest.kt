package com.android.messaging.dumber

import chat.dumber.sdk.*
import java.lang.reflect.Proxy

fun main() {
    var count = 0
    fun test(name: String, block: () -> Unit) { block(); count++; println("PASS $name") }
    val account = ChatAccount(Session("credentials", "self", "device", null), "+15555550101", 1, 1, null, "SIM", "crypto", "password", 0)
    val peer = Contact("peer", "+15555550102")
    val direct = Conversation("direct", ConversationKind.DIRECT, participants = listOf(peer), membership = Membership.JOINED)
    val group = Conversation("group", ConversationKind.GROUP, "Family", membership = Membership.LEFT)
    val savedGroup = GroupState(account.id, group)
    fun message(id: String, body: String) = Message(id, peer, 50, MessageContent.Text(body), true)
    test("export selects surviving encrypted rows of the chosen account and excludes carrier text") {
        val rows = sequenceOf(
            HistoryProviderRow(null, "SMS", 1, true),
            HistoryProviderRow("dumber-sms:queue", "Fallback SMS", 2, false),
            HistoryProviderRow("dumber.chat:other/direct/\$wrong", "Other SIM", 3, true),
            HistoryProviderRow("dumber.chat:self/direct/\$incoming", "Hello", 4, true),
            HistoryProviderRow("dumber.chat:self/direct/\$outgoing", "Reply", 5, false))
        val copied = ChatHistorySnapshot.collect(account, listOf(direct), listOf(savedGroup),
            listOf(ReceivedText("self", "group", message("\$group", "Kept")), ReceivedText("other", "group", message("\$other", "Other account"))), rows)
        check(copied.size == 2)
        check(copied[0].messages.map { it.eventId } == listOf("\$incoming", "\$outgoing"))
        check(copied[0].messages[0].sender == peer)
        check(copied[0].messages[1].sender.id == "self")
        check(copied[1].conversation.membership == Membership.LEFT)
        check(copied[1].messages.single().eventId == "\$group")
        check(ChatHistorySnapshot.contacts(copied).map { it.id }.toSet() == setOf("peer", "self"))
    }
    test("deleted groups, other accounts, and missing-key placeholders are not copied") {
        val copied = ChatHistorySnapshot.collect(account, listOf(direct), listOf(savedGroup.copy(hidden = true)),
            listOf(ReceivedText("self", "group", message("\$old", "Deleted"))), emptySequence())
        check(copied.isEmpty())
        val missing = ReceivedText("self", "group", message("\$missing", "").copy(content = MessageContent.UnableToDecrypt))
        check(ChatHistorySnapshot.collect(account, emptyList(), listOf(savedGroup), listOf(missing), emptySequence()).isEmpty())
    }
    test("missing SDK room metadata fails explicitly instead of silently dropping direct history") {
        check(runCatching { ChatHistorySnapshot.collect(account, emptyList(), emptyList(), emptyList(),
            sequenceOf(HistoryProviderRow("dumber.chat:self/missing/\$event", "Hello", 1, true))) }.isFailure)
    }
    val prepared = PreparedHistory("pair", "same encrypted envelope")
    val job = BrowserHistoryTransfer("self", ChatHistoryTransfer.sessionKey(account), "12345678", 1000, 10000, prepared)
    var failUpload = true
    val calls = mutableListOf<String>()
    val api = Proxy.newProxyInstance(ChatApi::class.java.classLoader, arrayOf(ChatApi::class.java)) { _, method, args ->
        calls += method.name
        when (method.name) {
            "approvePairing" -> PairingReview("Browser", 1000, true)
            "uploadPairingHistory" -> { check(args[0] == prepared); if (failUpload) throw ChatException("offline"); null }
            else -> error("Unexpected API operation")
        }
    } as ChatApi
    test("approval is durable before upload and retry reuses ciphertext without approving again") {
        calls.clear()
        var stored = job
        val approved = ChatHistoryTransfer.advance(job, account, api, 10) { stored = it }
        check(approved.approved && stored == approved)
        check(runCatching { ChatHistoryTransfer.advance(stored, account, api, 20) { stored = it } }.isFailure)
        check(stored.prepared == prepared && stored.approved)
        failUpload = false
        stored = stored.copy(error = "offline")
        val done = ChatHistoryTransfer.advance(stored, account, api, 30) { stored = it }
        check(done.finished && done.prepared == null && done.error.isEmpty())
        check(calls == listOf("approvePairing", "uploadPairingHistory", "uploadPairingHistory"))
    }
    test("lost approval can recover after code expiry while its transfer is still valid") {
        calls.clear()
        val done = ChatHistoryTransfer.advance(job, account, api, 2000) {}
        check(done.finished && done.approved && calls == listOf("uploadPairingHistory"))
    }
    test("account replacement and logout cannot resume old transfer work") {
        calls.clear()
        for (changed in listOf(null, account.copy(status = AccountStatus.LOGGED_OUT), account.copy(session = account.session.copy(credentials = "new")))) {
            check(runCatching { ChatHistoryTransfer.advance(job, changed, api, 10) {} }.isFailure)
        }
        check(calls.isEmpty())
    }
    test("expired snapshots are released without network calls") {
        calls.clear()
        val expired = ChatHistoryTransfer.advance(job, account, api, 10001) {}
        check(expired.finished && expired.prepared == null && expired.error.contains("expired"))
        check(calls.isEmpty())
    }
    test("export failure does not prevent approval and remains visible afterwards") {
        calls.clear()
        val failed = job.copy(prepared = null, error = "History exceeds 8 MiB")
        val approved = ChatHistoryTransfer.advance(failed, account, api, 10) {}
        val done = ChatHistoryTransfer.advance(approved, account, api, 20) {}
        check(done.finished && done.approved && done.error.contains("8 MiB"))
        check(calls == listOf("approvePairing"))
    }
    println("$count history tests passed")
}
