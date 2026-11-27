package com.android.messaging.dumber

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import chat.dumber.sdk.*
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-owned storage. No JSON objects cross this repository boundary. */
internal class ChatStore private constructor(context: Context) :
    SQLiteOpenHelper(
        context,
        File(context.noBackupFilesDir, "dumber-chat.db").absolutePath,
        null,
        1
    ) {
    companion object {
        @Volatile private var instance: ChatStore? = null
        fun get(context: Context): ChatStore =
            instance
                ?: synchronized(this) {
                    instance ?: ChatStore(context.applicationContext).also { instance = it }
                }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(kind,id))"
        )
        db.execSQL("CREATE TABLE handled(id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE transport(uri TEXT PRIMARY KEY NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // A send-attempt commit must reach durable storage before SmsManager.
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported chat storage version")
    private val recordCache = ChatRecordCache()
    private val storageKey: SecretKey by lazy { loadKey() }
    private fun loadKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "dumber-messaging-storage"
        if (!store.containsAlias(alias))
            KeyGenerator.getInstance("AES", "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        return store.getKey(alias, null) as SecretKey
    }
    private fun seal(bytes: ByteArray): ByteArray {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, storageKey) }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    }
    private fun open(bytes: ByteArray): ByteArray {
        val size = bytes[0].toInt()
        require(size in 12..16 && bytes.size > size + 1)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, storageKey, GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
            doFinal(bytes.copyOfRange(size + 1, bytes.size))
        }
    }
    @Synchronized
    private fun save(kind: String, id: String, write: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
        val storedId = digest(id)
        val encrypted = seal(bytes)
        check(
            writableDatabase.insertWithOnConflict(
                "records",
                null,
                ContentValues().apply {
                    put("kind", kind)
                    put("id", storedId)
                    put("payload", encrypted)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L
        )
        recordCache.remember("$kind/$storedId", encrypted, bytes)
    }
    @Synchronized
    private fun <T> records(kind: String, read: DataInputStream.() -> T): List<T> =
        readableDatabase.rawQuery("SELECT id,payload FROM records WHERE kind=?", arrayOf(kind)).use { c
            ->
            buildList {
                while (c.moveToNext()) add(
                    DataInputStream(ByteArrayInputStream(recordCache.read("$kind/${c.getString(0)}", c.getBlob(1), ::open))).use(read)
                )
            }
        }
    @Synchronized
    private fun remove(kind: String, id: String) {
        val storedId = digest(id)
        writableDatabase.delete("records", "kind=? AND id=?", arrayOf(kind, storedId))
        recordCache.remove("$kind/$storedId")
    }
    @Synchronized
    fun <T> atomic(block: () -> T): T {
        writableDatabase.beginTransaction()
        try {
            val result = block()
            writableDatabase.setTransactionSuccessful()
            return result
        } finally {
            writableDatabase.endTransaction()
        }
    }
    @Synchronized
    fun accounts() =
        records("account") {
            ChatAccount(
                ChatStorage.decodeSession(text()),
                text(),
                readInt(),
                readInt(),
                optional(),
                text(),
                text(),
                text(),
                readLong(),
                AccountStatus.valueOf(text())
            )
        }
    @Synchronized fun account(id: String) = accounts().find { it.id == id }
    @Synchronized
    fun save(account: ChatAccount) =
        atomic {
        val previous = this.account(account.id)
        if (!account.enabled || previous?.session?.credentials != account.session.credentials) clearBrowserHistory(account.id)
        save("account", account.id) {
            text(ChatStorage.encodeSession(account.session))
            text(account.phone)
            writeInt(account.subscription)
            writeInt(account.cardId)
            optional(account.reportedNumber)
            text(account.simLabel)
            text(account.storeName)
            text(account.storePassword)
            writeLong(account.connectedAt)
            text(account.status.name)
        }
        }
    @Synchronized fun smsEnabled(account: ChatAccount): Boolean = records("sms-enabled") { text() to text() }
        .any { it.first == account.id && it.second == ChatHistoryTransfer.sessionKey(account) }
    @Synchronized fun enableSms(account: ChatAccount) = save("sms-enabled", account.id) { text(account.id); text(ChatHistoryTransfer.sessionKey(account)) }
    @Synchronized fun smsAttempts(): List<SmsAttempt> = records("sms-attempt") {
        val old = SmsAttempt(text(), text(), SmsRequest(text(), text(), text(), text(), text(), readLong(), readLong()), text(), text(), List(readInt()) { readInt() })
        val routed = if (available() == 0) old else old.copy(request = old.request.copy(simId = text(), transport = text()), matrixRoom = text(), matrixEvent = text(), matrixAccount = text())
        if (available() == 0) routed else routed.copy(queueVersion = readInt(), subscription = readInt(), pinnedSim = text(), localId = text())
    }
    @Synchronized fun save(v: SmsAttempt) = save("sms-attempt", v.account + "/" + v.sessionKey + "/" + v.request.requestId) {
        text(v.account);text(v.sessionKey);text(v.request.eventId);text(v.request.requestId);text(v.request.kind)
        text(v.request.address);text(v.request.body);writeLong(v.request.createdAtMs);writeLong(v.request.receivedAtMs)
        text(v.state);text(v.uri);writeInt(v.parts.size);v.parts.forEach { writeInt(it) }
        text(v.request.simId);text(v.request.transport);text(v.matrixRoom);text(v.matrixEvent);text(v.matrixAccount)
        writeInt(v.queueVersion);writeInt(v.subscription);text(v.pinnedSim);text(v.localId)
    }
    /** Commit the request identity before either local queueing or legacy submission. */
    @Synchronized fun claimSms(v: SmsAttempt): Boolean = atomic {
        val account = account(v.account)
        if (account?.enabled != true || ChatHistoryTransfer.sessionKey(account) != v.sessionKey || !smsEnabled(account)) return@atomic false
        val id = digest(v.account + "/" + v.sessionKey + "/" + v.request.requestId)
        if (writableDatabase.rawQuery("SELECT 1 FROM records WHERE kind='sms-attempt' AND id=?", arrayOf(id)).use { it.moveToFirst() }) return@atomic false
        save(v)
        true
    }
    @Synchronized fun smsExports(): List<SmsExport> = records("sms-export") { SmsExport(text(), text(), text(), text(), text()) }
    @Synchronized fun save(v: SmsExport) = save("sms-export", v.account + "/" + v.sessionKey + "/" + v.id) {
        text(v.account);text(v.sessionKey);text(v.id);text(v.address);text(v.fingerprint)
    }
    @Synchronized fun smsSnapshot(account: ChatAccount) = records("sms-snapshot") {
        SmsSnapshot(text(), text(), readLong(), List(readInt()) { text() }, readLong(), readBoolean())
    }.find { it.account == account.id && it.sessionKey == ChatHistoryTransfer.sessionKey(account) }
    @Synchronized fun save(v: SmsSnapshot) = save("sms-snapshot", v.account + "/" + v.sessionKey) {
        text(v.account); text(v.sessionKey); writeLong(v.generation)
        writeInt(v.requestIds.size); v.requestIds.forEach { text(it) }; writeLong(v.count); writeBoolean(v.finished)
    }
    @Synchronized fun startSmsSnapshot(account: ChatAccount, requestId: String? = null): SmsSnapshot {
        val old = smsSnapshot(account)
        val requests = (old?.requestIds.orEmpty() + listOfNotNull(requestId)).distinct().takeLast(32)
        return SmsSnapshot(account.id, ChatHistoryTransfer.sessionKey(account), (old?.generation ?: 0) + 1, requests).also { save(it) }
    }
    @Synchronized fun smsRouteExports(account: ChatAccount) = records("sms-route") { listOf(text(), text(), text(), text()) }
        .filter { it[0] == account.id && it[1] == ChatHistoryTransfer.sessionKey(account) }.associate { it[2] to it[3] }
    @Synchronized fun saveSmsRoute(account: ChatAccount, id: String, fingerprint: String) =
        save("sms-route", account.id + "/" + ChatHistoryTransfer.sessionKey(account) + "/" + id) {
            text(account.id);text(ChatHistoryTransfer.sessionKey(account));text(id);text(fingerprint)
        }
    @Synchronized fun clearSmsExports(account: ChatAccount) {
        for (id in smsRouteExports(account).keys) remove("sms-route", account.id + "/" + ChatHistoryTransfer.sessionKey(account) + "/" + id)
        for (v in smsExports().filter { it.account == account.id && it.sessionKey == ChatHistoryTransfer.sessionKey(account) })
            remove("sms-export", v.account + "/" + v.sessionKey + "/" + v.id)
    }
    private data class HistoryChunk(val account: String, val index: Int, val body: String)
    private fun historyChunks() = records("browser_history_chunk") { HistoryChunk(text(), readInt(), text()) }
    @Synchronized
    @JvmOverloads
    fun browserHistory(includePayload: Boolean = false): List<BrowserHistoryTransfer> {
        val chunks = if (includePayload) historyChunks().groupBy { it.account } else emptyMap()
        return records("browser_history") {
            val account = text()
            val sessionKey = text()
            val code = text()
            val codeExpiry = readLong()
            val expiry = readLong()
            val count = readInt()
            val prepared = if (count == 0 || !includePayload) null else {
                val parts = chunks[account].orEmpty().sortedBy { it.index }
                check(parts.size == count && parts.map { it.index } == (0 until count).toList()) { "History copy storage is incomplete" }
                ChatStorage.decodePreparedHistory(parts.joinToString("") { it.body })
            }
            BrowserHistoryTransfer(account, sessionKey, code, codeExpiry, expiry, prepared,
                readBoolean(), readBoolean(), readLong(), text())
        }
    }
    @Synchronized
    fun save(job: BrowserHistoryTransfer) = atomic {
        clearBrowserHistory(job.account)
        // Keep each encrypted SQLite row below Android's CursorWindow limit.
        val chunks = job.prepared?.let(ChatStorage::encodePreparedHistory)?.chunked(64 * 1024).orEmpty()
        chunks.forEachIndexed { index, body ->
            save("browser_history_chunk", "${job.account}\u0000$index") {
                text(job.account); writeInt(index); text(body)
            }
        }
        save("browser_history", job.account) {
            text(job.account); text(job.sessionKey); text(job.code)
            writeLong(job.codeExpiresAt); writeLong(job.expiresAt); writeInt(chunks.size)
            writeBoolean(job.approved); writeBoolean(job.finished); writeLong(job.nextAttemptAt); text(job.error)
        }
    }
    @Synchronized fun clearBrowserHistory(account: String) = atomic {
        historyChunks().filter { it.account == account }.forEach {
            remove("browser_history_chunk", "$account\u0000${it.index}")
        }
        remove("browser_history", account)
    }
    @Synchronized
    fun sessionNotices(): Set<String> = records("session_notice") { text() }.toSet()

    @Synchronized
    fun markSessionNotice(account: ChatAccount) {
        val key = ChatSessionAlerts.key(account)
        save("session_notice", account.id) { text(key) }
    }

    /** Re-registration can create a new account ID; retain the old history without its warning. */
    @Synchronized
    fun resolveRevocations(phone: String) {
        accounts().filter { it.phone == phone && it.status == AccountStatus.REVOKED }
            .forEach { save(it.copy(status = AccountStatus.LOGGED_OUT)) }
    }

    @Synchronized
    fun simChoices() = records("sim_choice") { SimRemovalChoice(text(), text(), readBoolean()) }
    @Synchronized
    fun save(choice: SimRemovalChoice) = save("sim_choice", choice.account) {
        text(choice.account)
        text(choice.nonce)
        writeBoolean(choice.keep)
    }
    @Synchronized fun clearSimChoice(account: String) = remove("sim_choice", account)

    @Synchronized
    fun verification() =
        records("verification") {
                Verification(text(), text(), readInt(), readInt(), readLong(), readLong())
            }
            .singleOrNull()
    @Synchronized
    fun save(v: Verification) =
        save("verification", "pending") {
            text(v.nonce)
            text(v.phone)
            writeInt(v.subscription)
            writeInt(v.cardId)
            writeLong(v.startedAt)
            writeLong(v.expiresAt)
        }
    @Synchronized fun clearVerification() = remove("verification", "pending")
    @Synchronized
    fun queueLogout(s: Session) =
        save("logout", s.credentials) { text(ChatStorage.encodeSession(s)) }
    @Synchronized fun logouts() = records("logout") { ChatStorage.decodeSession(text()) }
    @Synchronized fun logoutDone(s: Session) = remove("logout", s.credentials)
    @Synchronized
    fun pending() =
        records("pending") {
            PendingText(
                text(),
                text(),
                readInt(),
                text(),
                text(),
                text(),
                optional(),
                readBoolean(),
                DeliveryState.valueOf(text()),
                optional(),
                text(),
                readBoolean(),
                readLong(),
                optional()
            )
        }
    @Synchronized fun pending(id: String) = pending().find { it.id == id }
    @Synchronized
    fun save(p: PendingText) =
        save("pending", p.id) {
            text(p.id)
            text(p.account)
            writeInt(p.subscription)
            text(p.recipient)
            text(p.body)
            text(p.localConversation)
            optional(p.conversation)
            writeBoolean(p.group)
            text(p.state.name)
            optional(p.eventId)
            text(p.error)
            writeBoolean(p.recipientUnavailable)
            writeLong(p.createdAt)
            optional(p.deviceId)
        }
    @Synchronized fun pendingDone(id: String) = remove("pending", id)
    @Synchronized
    fun groups() =
        records("group") {
            GroupState(
                text(),
                ChatStorage.decodeConversation(text()),
                readLong(),
                text(),
                readInt(),
                readBoolean(),
                readBoolean(),
                readBoolean(),
                readBoolean()
            )
        }
    @Synchronized fun group(key: String) = groups().find { it.key == key }
    @Synchronized
    fun save(g: GroupState) =
        save("group", g.key) {
            text(g.account)
            text(ChatStorage.encodeConversation(g.conversation))
            writeLong(g.date)
            text(g.snippet)
            writeInt(g.unread)
            writeBoolean(g.archived)
            writeBoolean(g.muted)
            writeBoolean(g.pinned)
            writeBoolean(g.hidden)
        }
    @Synchronized
    fun creation(account: String) =
        records("creation") { GroupCreation(text(), text(), List(readInt()) { text() }, text()) }
            .find { it.account == account }
    @Synchronized
    fun save(c: GroupCreation) =
        save("creation", c.account) {
            text(c.account)
            text(c.name)
            writeInt(c.phones.size)
            c.phones.forEach { text(it) }
            text(c.operationId)
        }
    @Synchronized fun creationDone(account: String) = remove("creation", account)
    @Synchronized
    fun received() =
        records("received") { ReceivedText(text(), text(), ChatStorage.decodeMessage(text())) }
    @Synchronized
    fun enqueue(v: ReceivedText) {
        if (!handled(v))
            save("received", eventKey(v)) {
                text(v.account)
                text(v.conversation)
                text(ChatStorage.encodeMessage(v.message))
            }
    }
    private fun eventKey(v: ReceivedText) =
        "${v.account}\u0000${v.conversation}\u0000${v.message.eventId}"
    @Synchronized
    fun handled(v: ReceivedText) =
        readableDatabase
            .rawQuery("SELECT 1 FROM handled WHERE id=?", arrayOf(digest(eventKey(v))))
            .use { it.moveToFirst() }
    @Synchronized
    fun done(v: ReceivedText) {
        writableDatabase.insertWithOnConflict(
            "handled",
            null,
            ContentValues().apply { put("id", digest(eventKey(v))) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        remove("received", eventKey(v))
    }
    @Synchronized
    fun messages(key: String) =
        records("message") { ReceivedText(text(), text(), ChatStorage.decodeMessage(text())) }
            .filter { groupKey(it.account, it.conversation) == key }
            .sortedBy { it.message.timestampMs }
    @Synchronized
    fun groupEvent(v: ReceivedText): Boolean = atomic {
        if (handled(v)) return@atomic false
        val group = group(groupKey(v.account, v.conversation)) ?: return@atomic false
        save("message", eventKey(v)) {
            text(v.account)
            text(v.conversation)
            text(ChatStorage.encodeMessage(v.message))
        }
        val incoming = v.message.sender.id != v.account
        save(
            group.copy(
                date = maxOf(group.date, v.message.timestampMs),
                snippet =
                    if (v.message.timestampMs >= group.date)
                        (v.message.content as? MessageContent.Text)?.body ?: "Encrypted message"
                    else group.snippet,
                unread = group.unread + if (incoming) 1 else 0,
                hidden = false
            )
        )
        done(v)
        true
    }
    @Synchronized
    fun deleteGroup(key: String) = atomic {
        val g = group(key) ?: return@atomic
        (received() + messages(key))
            .filter { groupKey(it.account, it.conversation) == key }
            .forEach {
                done(it)
                remove("message", eventKey(it))
            }
        pending()
            .filter { it.group && groupKey(it.account, it.conversation.orEmpty()) == key }
            .forEach { pendingDone(it.id) }
        save(g.copy(hidden = true, snippet = "", unread = 0))
    }
    @Synchronized
    fun markTransport(uri: String) {
        writableDatabase.insertWithOnConflict(
            "transport",
            null,
            ContentValues().apply { put("uri", uri) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }
    @Synchronized
    fun clearTransport(uri: String) {
        writableDatabase.delete("transport", "uri=?", arrayOf(uri))
    }
    @Synchronized
    fun resetTransport(uris: Set<String>) = atomic {
        writableDatabase.delete("transport", null, null)
        uris.forEach(::markTransport)
    }
    @Synchronized
    fun encryptedUris() =
        readableDatabase.rawQuery("SELECT uri FROM transport", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
}

private fun DataOutputStream.text(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.text(): String {
    val n = readInt()
    require(n in 0..16_777_216)
    return ByteArray(n).also { readFully(it) }.toString(Charsets.UTF_8)
}

private fun DataOutputStream.optional(s: String?) {
    writeBoolean(s != null)
    if (s != null) text(s)
}

private fun DataInputStream.optional(): String? = if (readBoolean()) text() else null
