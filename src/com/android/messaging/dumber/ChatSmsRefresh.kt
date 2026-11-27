package com.android.messaging.dumber

/** Coalesce provider notifications, but do not lose changes arriving during an export. */
internal class ChatSmsRefresh {
    private var generation = 1L
    private var completed = 0L
    private var lastSuccess = 0L
    @Synchronized fun changed() { generation++ }
    @Synchronized fun due(now: Long): Long? =
        if (generation != completed || now - lastSuccess >= 10_000) generation else null
    @Synchronized fun complete(ticket: Long, now: Long) { completed = ticket; lastSuccess = now }
}
