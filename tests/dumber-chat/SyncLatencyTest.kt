package com.android.messaging.dumber

fun main() {
    val cache = ChatRecordCache()
    var opens = 0
    val open: (ByteArray) -> ByteArray = { opens++; it.reversedArray() }
    val rows = (0 until 1000).map { "record:$it" to "ciphertext:$it".toByteArray() }
    repeat(10) {
        for ((id, bytes) in rows) check(cache.read(id, bytes.copyOf(), open).contentEquals(bytes.reversedArray()))
    }
    check(opens == 1000) { "Unchanged records were decrypted again: $opens" }
    val old = "old".toByteArray()
    val updated = "updated".toByteArray()
    cache.read("transaction", old, open)
    cache.remember("transaction", updated, updated.reversedArray())
    val before = opens
    cache.read("transaction", updated, open)
    check(opens == before)
    // A rolled-back SQLite write restores the old ciphertext. Never serve uncommitted bytes.
    check(cache.read("transaction", old, open).contentEquals(old.reversedArray()))
    check(opens == before + 1)
    cache.remove("transaction")
    cache.read("transaction", old, open)
    check(opens == before + 2)
    val small = ChatRecordCache(12)
    var evictions = 0
    val read: (ByteArray) -> ByteArray = { evictions++; it.copyOf() }
    small.read("a", old, read)
    small.read("b", old, read)
    small.read("a", old, read) // a is most recently used
    small.read("c", old, read)
    small.read("a", old, read)
    check(evictions == 3)
    small.read("b", old, read)
    check(evictions == 4)
    // Oversized records work without evicting the whole hot set or exceeding the bound.
    repeat(2) { small.read("big", ByteArray(100), read) }
    check(evictions == 6)

    val refresh = ChatSmsRefresh()
    val first = checkNotNull(refresh.due(0))
    refresh.complete(first, 100)
    check(refresh.due(101) == null)
    refresh.changed()
    val ticket = checkNotNull(refresh.due(102))
    refresh.changed() // a sent callback while the sending update is in flight
    refresh.complete(ticket, 200)
    val next = checkNotNull(refresh.due(201))
    check(next != ticket)
    // A failed export does not acknowledge the ticket; the next tick retries it.
    check(refresh.due(202) == next)
    refresh.complete(next, 300)
    check(refresh.due(10_299) == null)
    check(refresh.due(10_300) != null) // recovery if a provider omits a notification
    println("SMS latency: unchanged-record cache, rollback, eviction and refresh scheduling passed")
}
