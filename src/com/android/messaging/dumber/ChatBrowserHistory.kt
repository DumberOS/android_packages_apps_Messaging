package com.android.messaging.dumber

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import chat.dumber.sdk.*

/** Uses existing account storage and the foreground sync worker; no new sync protocol. */
internal object ChatBrowserHistory {
    private val locks = Array(16) { Any() }
    private fun lock(id: String) = locks[(id.hashCode() and Int.MAX_VALUE) % locks.size]

    fun link(context: Context, account: ChatAccount, code: String, review: PairingReview) = synchronized(lock(account.id)) {
        val store = ChatStore.get(context)
        checkCurrent(store, account)
        check(review.expiresAt > System.currentTimeMillis()) { "Request expired" }
        check(store.browserHistory().none { it.account == account.id && !it.finished }) {
            "A browser copy is still pending. Retry or stop it first."
        }
        val api = ChatAccounts.client(context, account)
        var failure = if (review.historyPublicKey == null) "This browser does not support history copying." else ""
        val prepared = if (review.historyPublicKey == null) null else try {
            val snapshot = snapshot(context, account, api, store)
            api.preparePairingHistory(review, snapshot, contactNames(context, snapshot))
        } catch (e: Exception) {
            failure = "History was not copied: ${e.message ?: "Unable to read saved chats"}"
            null
        }
        val now = System.currentTimeMillis()
        store.atomic {
            checkCurrent(store, account)
            store.save(BrowserHistoryTransfer(account.id, ChatHistoryTransfer.sessionKey(account), code,
                review.expiresAt, now + 15 * 60_000, prepared, error = failure))
        }
        retry(context, account, true)
    }

    fun retry(context: Context, account: ChatAccount, force: Boolean = false) = synchronized(lock(account.id)) {
        val store = ChatStore.get(context)
        var job = store.browserHistory().find { it.account == account.id } ?: return@synchronized
        if (job.finished || (!force && job.nextAttemptAt > System.currentTimeMillis())) return@synchronized
        if (!current(store, account)) return@synchronized
        if (job.sessionKey != ChatHistoryTransfer.sessionKey(account)) {
            store.clearBrowserHistory(account.id)
            return@synchronized
        }
        job = store.browserHistory(true).find { it.account == account.id } ?: return@synchronized
        val save: (BrowserHistoryTransfer) -> Unit = { value -> store.atomic {
            checkCurrent(store, account)
            store.save(value)
        } }
        try {
            check(ChatAccounts.isDefault(context)) { "Messaging is no longer the default SMS app" }
            val api = ChatAccounts.client(context, account)
            if (!job.approved) job = ChatHistoryTransfer.advance(job, store.account(account.id), api, System.currentTimeMillis(), save)
            if (!job.finished) ChatHistoryTransfer.advance(job, store.account(account.id), api, System.currentTimeMillis(), save)
        } catch (e: Exception) {
            // An approval response may have committed before a later upload failure.
            store.atomic {
                if (current(store, account)) {
                    val latest = store.browserHistory(true).find { it.account == account.id } ?: return@atomic
                    val ended = e is ChatException && e.kind in listOf(ErrorKind.NOT_FOUND, ErrorKind.PERMISSION_DENIED, ErrorKind.UPGRADE_REQUIRED)
                    store.save(latest.copy(nextAttemptAt = System.currentTimeMillis() + 30_000,
                        finished = ended, prepared = if (ended) null else latest.prepared,
                        error = if (ended) "History copy unavailable. Link the browser again." else e.message ?: "Copy interrupted; retrying"))
                }
            }
            if (e is ChatException && e.kind == ErrorKind.AUTHENTICATION_REQUIRED) throw e
        } finally { DumberChat.changed(context) }
    }

    fun cancel(context: Context, account: String) = synchronized(lock(account)) {
        ChatStore.get(context).clearBrowserHistory(account)
        DumberChat.changed(context)
    }

    private fun current(store: ChatStore, account: ChatAccount) = store.account(account.id)?.let {
        it.enabled && it.session.credentials == account.session.credentials
    } == true
    private fun checkCurrent(store: ChatStore, account: ChatAccount) = check(current(store, account)) { "Account changed; review this browser again" }

    private fun snapshot(context: Context, account: ChatAccount, api: ChatApi, store: ChatStore): List<HistoryConversation> {
        val infos = api.conversations()
        val (groups, messages) = store.atomic {
            checkCurrent(store, account)
            val groups = store.groups().filter { it.account == account.id && !it.hidden }
            groups to groups.flatMap { store.messages(it.key) }
        }
        val prefix = "${DumberChat.MARKER}${account.id}/"
        return context.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf("service_center", "body", "date", "type"),
            "service_center LIKE ? AND (type=? OR type=?)",
            arrayOf("$prefix%", Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()), "date ASC")?.use { cursor ->
            val rows = sequence {
                while (cursor.moveToNext()) yield(HistoryProviderRow(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX))
            }
            ChatHistorySnapshot.collect(account, infos, groups, messages, rows)
        } ?: error("Unable to read saved conversations")
    }

    private fun contactNames(context: Context, history: List<HistoryConversation>): Map<String, String> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val byPhone = mutableMapOf<String, String?>()
        return buildMap {
            for (contact in ChatHistorySnapshot.contacts(history)) {
                val phone = contact.phone ?: continue
                val name = byPhone.getOrPut(phone) {
                    context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)),
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0)?.trim()?.takeIf(String::isNotEmpty) else null
                    }
                }
                if (name != null) put(contact.id, name)
            }
        }
    }
}
