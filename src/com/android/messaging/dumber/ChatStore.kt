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
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported chat storage version")
    private fun key(): SecretKey {
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
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    }
    private fun open(bytes: ByteArray): ByteArray {
        val size = bytes[0].toInt()
        require(size in 12..16 && bytes.size > size + 1)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
            doFinal(bytes.copyOfRange(size + 1, bytes.size))
        }
    }
    @Synchronized
    private fun save(kind: String, id: String, write: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
        check(
            writableDatabase.insertWithOnConflict(
                "records",
                null,
                ContentValues().apply {
                    put("kind", kind)
                    put("id", digest(id))
                    put("payload", seal(bytes))
                },
                SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L
        )
    }
    @Synchronized
    private fun <T> records(kind: String, read: DataInputStream.() -> T): List<T> =
        readableDatabase.rawQuery("SELECT payload FROM records WHERE kind=?", arrayOf(kind)).use { c
            ->
            buildList {
                while (c.moveToNext()) add(
                    DataInputStream(ByteArrayInputStream(open(c.getBlob(0)))).use(read)
                )
            }
        }
    @Synchronized
    private fun remove(kind: String, id: String) {
        writableDatabase.delete("records", "kind=? AND id=?", arrayOf(kind, digest(id)))
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
