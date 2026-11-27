package com.android.messaging.dumber

/** Bounded process-local cache; SQLite ciphertext remains the source of truth.
 * Comparing the stored bytes also handles transaction rollback without special hooks.
 */
internal class ChatRecordCache(private val limit: Int = 4 * 1024 * 1024) {
    private data class Entry(val encrypted: ByteArray, val plain: ByteArray) {
        val size get() = encrypted.size + plain.size
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized fun read(id: String, encrypted: ByteArray, open: (ByteArray) -> ByteArray): ByteArray {
        entries[id]?.takeIf { it.encrypted.contentEquals(encrypted) }?.let { return it.plain }
        val plain = open(encrypted)
        remember(id, encrypted, plain)
        return plain
    }

    @Synchronized fun remember(id: String, encrypted: ByteArray, plain: ByteArray) {
        remove(id)
        val entry = Entry(encrypted, plain)
        if (entry.size > limit) return
        entries[id] = entry
        bytes += entry.size
        val iterator = entries.entries.iterator()
        while (bytes > limit && iterator.hasNext()) {
            bytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    @Synchronized fun remove(id: String) { entries.remove(id)?.let { bytes -= it.size } }
}
